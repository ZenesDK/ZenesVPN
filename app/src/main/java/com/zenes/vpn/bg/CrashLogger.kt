package com.zenes.vpn.bg

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent error log.
 *
 * Two kinds of failures are captured:
 * 1. Java-side exceptions and breadcrumbs -> filesDir/crash.log
 * 2. Go runtime / native crashes inside libbox -> the engine writes
 *    debug.SetCrashOutput to cacheDir/CrashReport-<source>.log and archives
 *    old reports in cacheDir/crash_reports/. These are merged into the
 *    report shown in Settings -> Diagnostics.
 */
object CrashLogger {

    private const val TAG = "ZenesVPN"
    private const val MAX_SIZE = 256 * 1024L
    private val LOCK = Any()

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun javaLogFile(): File? =
        appContext?.let { File(it.filesDir, "crash.log") }

    private fun nativeReportFile(): File? =
        appContext?.let { File(it.cacheDir, "CrashReport-app.log") }

    private fun nativeReportsDir(): File? =
        appContext?.let { File(it.cacheDir, "crash_reports") }

    fun log(message: String, t: Throwable? = null) {
        Log.e(TAG, message, t)
        val sb = StringBuilder()
        sb.append("=== ").append(timestamp()).append(" ===\n")
        sb.append(message).append('\n')
        if (t != null) appendThrowable(sb, t, depth = 0)
        appendLog(sb.toString())
    }

    /** Lightweight step marker, used to trace how far startup got. */
    fun breadcrumb(message: String) {
        Log.d(TAG, "· $message")
        appendLog("··· ${timestamp()} $message\n")
    }

    private fun appendThrowable(sb: StringBuilder, t: Throwable, depth: Int) {
        sb.append(t.javaClass.name).append(": ").append(t.message).append('\n')
        for (f in t.stackTrace.take(25)) sb.append("  at ").append(f.toString()).append('\n')
        val cause = t.cause
        if (cause != null && depth < 3) {
            sb.append("Caused by: ")
            appendThrowable(sb, cause, depth + 1)
        }
    }

    private fun appendLog(text: String) {
        synchronized(LOCK) {
            try {
                val file = javaLogFile() ?: return
                if (file.length() > MAX_SIZE) {
                    file.writeText("--- log truncated ---\n")
                }
                file.appendText(text)
            } catch (_: Throwable) {
                // never throw from the logger
            }
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

    // ------------------------------------------------------------ report

    /**
     * At app start: if the Go runtime left a crash report from a previous
     * run, copy its head into the Java log so it is visible in one place.
     *
     * NOTE: the panic message, the signal info and the CRASHING goroutine are
     * at the TOP of a Go crash dump — never cut the head off, cut the tail
     * instead (idle goroutine stacks at the end are noise).
     */
    fun captureNativeReportIfAny() {
        val native = nativeReportFile() ?: return
        try {
            if (native.exists() && native.length() > 0) {
                val text = native.readText()
                val excerpt = if (text.length <= 14000) {
                    text
                } else {
                    text.take(12000) + "\n…[dump tail cut, " + (text.length - 14000) + " chars]…\n" + text.takeLast(2000)
                }
                appendLog("=== NATIVE ENGINE CRASH (previous run) ===\n$excerpt\n=== END NATIVE CRASH ===\n")
            }
        } catch (_: Throwable) {
        }
    }

    /** Combined text for the diagnostics screen. */
    fun fullReport(): String {
        val sb = StringBuilder()
        synchronized(LOCK) {
            try {
                javaLogFile()?.let { f ->
                    if (f.exists() && f.length() > 0) {
                        sb.append("---- APPLICATION LOG ").append("----\n")
                        sb.append(f.readText())
                        sb.append('\n')
                    }
                }
                nativeReportFile()?.let { f ->
                    if (f.exists() && f.length() > 0) {
                        sb.append("---- ENGINE CRASH REPORT (native) ----\n")
                        // The panic header and the crashing goroutine live at the
                        // TOP of a Go dump — show the head, not the tail.
                        sb.append(f.readText().take(12000))
                        sb.append('\n')
                    }
                }
                nativeReportsDir()?.let { dir ->
                    val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
                    for (f in files.take(2)) {
                        try {
                            val text = f.readText()
                            if (text.isNotBlank()) {
                                sb.append("---- ENGINE ARCHIVE: ").append(f.name).append(" ----\n")
                                sb.append(text.takeLast(4000))
                                sb.append('\n')
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return sb.toString().trim()
    }

    fun hasAnything(): Boolean = fullReport().isNotEmpty()

    fun clearAll() {
        synchronized(LOCK) {
            try {
                javaLogFile()?.delete()
                nativeReportFile()?.delete()
                nativeReportsDir()?.listFiles()?.forEach { it.delete() }
            } catch (_: Throwable) {
            }
        }
    }
}
