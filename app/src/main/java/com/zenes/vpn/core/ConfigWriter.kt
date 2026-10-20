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
            val config = SbConfig.build(
                server,
                include,
                exclude,
                routingMode = store.routingMode,
                ruleSetDir = ensureRuleSets(context, store.routingMode, store.blockAds),
                blockAds = store.blockAds,
            )
            File(context.filesDir, "config.json").writeText(config)
            io.nekohasekai.libbox.Libbox.checkConfig(config)
            CrashLogger.breadcrumb(
                "server: ${server.name.take(60)} (${server.proto}) ${server.host}:${server.port}",
            )
            CrashLogger.breadcrumb(
                "config written: ${config.length} bytes, mode=${store.perAppMode}" +
                    " include=${include.size} exclude=${exclude.size} route=${store.routingMode}" +
                    " ads=${store.blockAds}",
            )
            null
        } catch (t: Throwable) {
            CrashLogger.log("config build failed", t)
            (t.message ?: t.javaClass.simpleName).take(300)
        }
    }

    /**
     * Copies the bundled .srs rule-sets (sing-geosite / sing-geoip, "rule-set"
     * branches) from assets into filesDir/rule-sets. Files are refreshed on
     * every write so an app update ships updated lists; done before the config
     * is validated (sing-box loads local rule-sets at start, a missing file is
     * fatal). Returns the directory that must be referenced by the config, or
     * null when neither smart routing nor ad blocking needs rule-sets.
     */
    private fun ensureRuleSets(context: Context, mode: String, blockAds: Boolean): String? {
        val needRu = mode == Store.ROUTE_RU
        if (!needRu && !blockAds) return null
        val dir = File(context.filesDir, "rule-sets")
        dir.mkdirs()
        if (needRu) {
            for (name in RULE_SET_FILES) {
                context.assets.open("rulesets/$name").use { input ->
                    File(dir, name).writeBytes(input.readBytes())
                }
            }
        }
        if (blockAds) {
            context.assets.open("rulesets/${ADS_RULE_SET}").use { input ->
                File(dir, ADS_RULE_SET).writeBytes(input.readBytes())
            }
        }
        return dir.absolutePath
    }

    private val RULE_SET_FILES = listOf(
        "category-ru.srs",
        "category-gov-ru.srs",
        "geoip-ru.srs",
    )

    private const val ADS_RULE_SET = "category-ads-all.srs"
}
