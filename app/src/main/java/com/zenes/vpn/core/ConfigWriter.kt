package com.zenes.vpn.core

import android.content.Context
import com.zenes.vpn.R
import com.zenes.vpn.bg.CrashLogger
import com.zenes.vpn.data.Store
import java.io.File

/**
 * Single place that builds, validates and writes config.json. Both the app
 * (power button) and the Quick Settings tile use it, so per-app routing lists
 * always travel with the config: SbConfig embeds them into the tun inbound
 * ("include_package"/"exclude_package"), which is the same path sing-box for
 * Android uses for its profiles.
 */
object ConfigWriter {

    /** Returns null on success or a user-presentable error message. */
    fun write(context: Context): String? {
        return try {
            val store = Store(context)
            val server = store.selectedServer()
                ?: return context.getString(R.string.no_server)
            val include: List<String>
            val exclude: List<String>
            when (store.perAppMode) {
                Store.PER_APP_INCLUDE -> {
                    include = store.perAppInclude.filter { it.isNotBlank() }
                    exclude = emptyList()
                }
                Store.PER_APP_EXCLUDE -> {
                    include = emptyList()
                    exclude = store.perAppExclude.filter { it.isNotBlank() }
                }
                else -> {
                    include = emptyList()
                    exclude = emptyList()
                }
            }
            val config = SbConfig.build(server, include, exclude)
            File(context.filesDir, "config.json").writeText(config)
            io.nekohasekai.libbox.Libbox.checkConfig(config)
            CrashLogger.breadcrumb(
                "config written: ${config.length} bytes, mode=${store.perAppMode}" +
                    " include=${include.size} exclude=${exclude.size}",
            )
            null
        } catch (t: Throwable) {
            CrashLogger.log("config build failed", t)
            (t.message ?: t.javaClass.simpleName).take(300)
        }
    }
}
