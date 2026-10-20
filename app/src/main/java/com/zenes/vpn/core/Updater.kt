package com.zenes.vpn.core

import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.zenes.vpn.BuildConfig
import com.zenes.vpn.R
import com.zenes.vpn.bg.CrashLogger
import com.zenes.vpn.data.Store
import com.zenes.vpn.util.NetPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection

/**
 * Self-updater for builds distributed via GitHub Releases.
 *
 * Flow: check api.github.com/repos/ZenesDK/ZenesVPN/releases/latest →
 * if a newer tag exists, [State.Available] is posted and the UI shows a popup →
 * download into cacheDir with progress → [State.Ready] → hand the APK to the
 * system package installer.
 *
 * Note: a truly silent install is impossible for a regular (non-store,
 * non-device-owner) app — Android always shows the system confirmation dialog
 * for a signature-matched update. "Download & install" therefore means:
 * the app downloads everything and only the final OS confirm button remains.
 *
 * Networking: path order follows the live VPN state (see [NetPath]).
 * The check is a small API call — through the tunnel first when it is up
 * (on carrier/censored networks direct GitHub is silently dropped), with a
 * direct fallback. The ~60 MB APK download goes direct-first (a flaky proxy
 * must not stall it) and falls back to the tunnel. When the VPN is down
 * there is a single direct attempt — plus a clear hint in the error:
 * connect the VPN and retry, the check will ride the tunnel (and indeed
 * retries once automatically, see [retryOnConnect]).
 */
object Updater {

    private const val LATEST_URL =
        "https://api.github.com/repos/ZenesDK/ZenesVPN/releases/latest"
    private const val CONNECT_TIMEOUT = 8_000
    private const val READ_TIMEOUT = 12_000
    /** Auto-check (app start) is throttled to once per 4 hours — polite to api.github.com. */
    private const val AUTO_CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000L

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data object UpToDate : State()
        data class Available(
            val version: String,
            val sizeBytes: Long,
            val url: String,
            /** Raw release body (markdown changelog section) from GitHub. */
            val notes: String = "",
        ) : State()

        data class Downloading(val percent: Int) : State()
        data class Ready(val version: String, val file: File) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Set when the latest check failed while the tunnel was down (the typical
     * carrier/censored-network case: direct GitHub is dropped). ZenesApp
     * watches it and runs one forced re-check as soon as the tunnel connects.
     * Cleared by any successful check.
     */
    @Volatile
    var retryOnConnect: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * @param auto  true = silent check on app start (throttled via Store,
     *              failures stay silent — the Settings row reports them on demand);
     *              false = explicit check from Settings (failures become visible state).
     * @param force skip the 4 h throttle (used by the tunnel-up retry);
     *              does not advance the throttle timestamp either.
     */
    fun checkAsync(context: Context, auto: Boolean, force: Boolean = false) {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        val appContext = context.applicationContext
        if (auto && !force) {
            val store = Store(appContext)
            val now = System.currentTimeMillis()
            if (now - store.lastUpdateCheck < AUTO_CHECK_INTERVAL_MS) return
            store.lastUpdateCheck = now
        }
        scope.launch {
            _state.value = State.Checking
            try {
                val info = fetchLatest(appContext)
                retryOnConnect = false
                _state.value = if (info == null || !isNewer(BuildConfig.VERSION_NAME, info.version)) {
                    State.UpToDate
                } else {
                    CrashLogger.breadcrumb("update available: ${info.version}")
                    info
                }
            } catch (t: Throwable) {
                CrashLogger.breadcrumb("update check failed: ${t.message}")
                retryOnConnect = true
                val message = buildString {
                    append(t.message ?: "network error")
                    if (!NetPath.tunnelUp()) {
                        append(" · ")
                        append(appContext.getString(R.string.update_blocked_hint))
                    }
                }
                _state.value = State.Failed(message)
            }
        }
    }

    fun downloadAsync(context: Context) {
        val cur = _state.value as? State.Available ?: return
        val appContext = context.applicationContext
        scope.launch {
            _state.value = State.Downloading(0)
            try {
                val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val out = File(dir, "zenes-update-${cur.version}.apk")
                out.delete()
                download(appContext, cur.url, out)
                if (out.length() < 1_000_000L) throw Exception("incomplete download")
                CrashLogger.breadcrumb("update downloaded: ${out.name} (${out.length()} B)")
                _state.value = State.Ready(cur.version, out)
            } catch (t: Throwable) {
                CrashLogger.breadcrumb("update download failed: ${t.message}")
                _state.value = State.Failed(t.message ?: "download failed")
            }
        }
    }

    /**
     * Starts the system installer for the downloaded APK.
     * Returns false if the "install unknown apps" permission is missing —
     * in that case the system settings screen is opened and the user should
     * tap Install again after granting it.
     */
    fun startInstall(context: Context): Boolean {
        val cur = _state.value as? State.Ready ?: return false
        if (Build.VERSION.SDK_INT >= 26 &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (t: Throwable) {
                CrashLogger.breadcrumb("unknown-app-sources screen failed: ${t.message}")
            }
            return false
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            cur.file,
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            CrashLogger.breadcrumb("installer start failed: ${t.message}")
            false
        }
    }

    /** Back to Idle so the popup does not re-appear until the next check. */
    fun dismiss() {
        if (_state.value !is State.Checking && _state.value !is State.Downloading) {
            _state.value = State.Idle
        }
    }

    // ------------------------------------------------------------ internals

    private fun fetchLatest(context: Context): State.Available? =
        NetPath.withSmartPath(context, "update check", tunnelFirst = true) { network ->
            fetchVia(network)
        }

    private fun fetchVia(network: Network?): State.Available? {
        val conn = NetPath.open(LATEST_URL, network)
        try {
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "ZenesVPN/${BuildConfig.VERSION_NAME}")
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v")
            var url: String? = null
            var size = 0L
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").endsWith(".apk")) {
                        url = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
            }
            if (url.isNullOrBlank()) throw Exception("release has no apk asset")
            return State.Available(tag, size, url, json.optString("body"))
        } finally {
            conn.disconnect()
        }
    }

    private fun download(context: Context, url: String, out: File) =
        // Direct first: the ~60 MB APK must not depend on a flaky proxy.
        NetPath.withSmartPath(context, "update download", tunnelFirst = false) { network ->
            downloadVia(url, out, network)
        }

    private fun downloadVia(url: String, out: File, network: Network?) {
        val conn = NetPath.open(url, network)
        try {
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "ZenesVPN/${BuildConfig.VERSION_NAME}")
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        copied += n
                        if (total > 0) {
                            val pct = (copied * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.value = State.Downloading(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ helpers

    /** Semantic-ish comparison: "1.10.0" > "1.9.9" > "1.9.0". Ignores a leading "v". */
    fun isNewer(current: String, candidate: String): Boolean {
        val a = parseVersion(current)
        val b = parseVersion(candidate)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (y != x) return y > x
        }
        return false
    }

    /**
     * Turns the markdown changelog section (the release body) into readable
     * plain text for the expandable "What's new" block:
     * the "## [x.y.z] — date" header is dropped, "### Section" becomes a
     * standalone label, "- item" becomes a bullet (nested items indent),
     * and lines wrapped in the markdown source are rejoined to their bullet.
     */
    fun cleanReleaseNotes(raw: String): String {
        if (raw.isBlank()) return ""
        val out = StringBuilder()
        for (rawLine in raw.lines()) {
            val line = rawLine.trimEnd()
            val t = line.trim()
            val indent = line.length - line.trimStart().length
            when {
                t.isEmpty() -> out.append('\n')
                t.startsWith("## ") -> Unit // release header (title + date) — skip
                t.startsWith("### ") -> {
                    if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append('\n')
                    out.append(t.removePrefix("### ")).append('\n')
                }
                t.startsWith("- ") -> {
                    val bullet = if (indent >= 2) "   • " else "• "
                    out.append(bullet).append(t.removePrefix("- ")).append('\n')
                }
                out.isEmpty() || out.endsWith("\n") -> out.append(t).append('\n')
                else -> out.insert(out.length - 1, " $t") // wrapped continuation
            }
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun parseVersion(v: String): List<Int> =
        v.trim().removePrefix("v")
            .substringBefore('-')
            .split('.')
            .map { part -> part.filter(Char::isDigit).toIntOrNull() ?: 0 }
}
