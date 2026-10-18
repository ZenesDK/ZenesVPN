package com.zenes.vpn

import android.app.Application
import com.zenes.vpn.bg.CrashLogger
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)
        CrashLogger.captureNativeReportIfAny()
        CrashLogger.breadcrumb("app start v${BuildConfig.VERSION_NAME}")

        // Catch any uncaught exception into the in-app error log
        // before delegating to the system handler.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashLogger.log("Uncaught exception on thread \"${thread.name}\"", throwable)
            previous?.uncaughtException(thread, throwable)
        }

        try {
            val tempDir = File(cacheDir, "temp").apply { mkdirs() }
            val opts = SetupOptions()
            opts.basePath = filesDir.absolutePath
            opts.workingPath = cacheDir.absolutePath
            opts.tempPath = tempDir.absolutePath
            opts.fixAndroidStack = true
            opts.logMaxLines = 500L
            opts.debug = false
            opts.crashReportSource = "app"
            opts.appVersion = BuildConfig.VERSION_NAME
            opts.appMarketingVersion = BuildConfig.VERSION_NAME
            opts.oomKillerDisabled = true
            Libbox.setup(opts)
        } catch (t: Throwable) {
            CrashLogger.log("Libbox setup failed", t)
        }
    }
}
