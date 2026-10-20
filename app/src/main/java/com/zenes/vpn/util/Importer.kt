package com.zenes.vpn.util

import android.content.Context
import android.net.Network
import android.widget.Toast
import com.zenes.vpn.data.ServerProfile
import com.zenes.vpn.data.Store
import com.zenes.vpn.parser.LinkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TCP connect latency in ms; null when unreachable.
 *
 * Measures direct (physical-network) reachability first; when the tunnel is
 * up and the direct path fails, falls back through the tunnel (see
 * [NetPath.tcpConnectMs]) — on censored networks servers are often blocked
 * directly but perfectly alive behind the proxy.
 */
suspend fun tcpPing(
    context: Context? = null,
    host: String,
    port: Int,
    timeoutMs: Int = 3000,
): Int? = withContext(Dispatchers.IO) {
    NetPath.tcpConnectMs(context, host, port, timeoutMs)
}

fun toast(context: Context, message: String) {
    try {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    } catch (_: Throwable) {
    }
}

/** Result of a subscription fetch: body + standard subscription headers. */
class SubFetch(
    val body: String,
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0, // unix seconds
    val title: String? = null,
    val updateIntervalHours: Int = 0,
)

object Importer {

    /** Parses arbitrary text (links, base64 subscription body) and stores profiles. Returns added count. */
    suspend fun importText(context: Context, text: String, subId: String? = null): Int =
        withContext(Dispatchers.IO) {
            val links = LinkParser.extractLinks(text)
            val profiles = mutableListOf<ServerProfile>()
            for (link in links) {
                val profile = LinkParser.parseLink(link)
                if (profile != null) {
                    profiles.add(profile.copy(subId = subId))
                }
            }
            if (profiles.isEmpty()) return@withContext 0
            Store(context).addServers(profiles)
        }

    /**
     * Downloads a subscription URL and REPLACES that subscription's server set
     * with the fetched list (providers rotate servers, so a refresh must not
     * accumulate dead entries). Links that survive keep their id and measured
     * ping, so the selected server survives rotation whenever its link is
     * still offered. A body with no usable links is reported as failure and
     * leaves the previous server set untouched — a flaky fetch can never wipe
     * the user's servers. Stores metadata (traffic/expire/title) as before.
     */
    suspend fun importSubscription(context: Context, url: String): kotlin.Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val fetched = fetchSubscription(context, url)
                val store = Store(context)
                val sub = store.subs().firstOrNull { it.url == url } ?: store.addSub("", url)
                val profiles = mutableListOf<ServerProfile>()
                for (link in LinkParser.extractLinks(fetched.body)) {
                    val profile = LinkParser.parseLink(link) ?: continue
                    profiles.add(profile.copy(subId = sub.id))
                }
                if (profiles.isEmpty()) {
                    return@withContext kotlin.Result.failure(
                        RuntimeException("subscription body has no usable links"),
                    )
                }
                // If the name is still the auto-generated one, prefer the server-provided title.
                val autoNamed = sub.name.isBlank() || sub.name == url.substringBefore("/")
                val named = sub.copy(
                    name = if (autoNamed && !fetched.title.isNullOrBlank()) fetched.title!! else sub.name,
                    upload = fetched.upload,
                    download = fetched.download,
                    total = fetched.total,
                    expire = fetched.expire,
                    updateIntervalHours = fetched.updateIntervalHours,
                )
                val total = store.replaceServersOfSub(sub.id, profiles)
                store.updateSub(named.copy(lastUpdate = System.currentTimeMillis()))
                kotlin.Result.success(total)
            } catch (t: Throwable) {
                kotlin.Result.failure(t)
            }
        }

    /** Fetches a subscription URL and parses the standard headers next to the body. */
    fun fetchSubscription(context: Context, url: String): SubFetch =
        NetPath.withSmartPath(context, "subscription fetch", tunnelFirst = true) { network ->
            fetchVia(url, network)
        }

    private fun fetchVia(url: String, network: Network?): SubFetch {
        val connection = NetPath.open(url, network)
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "ZenesVPN/1.4")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

            // subscription-userinfo: upload=123; download=456; total=789; expire=1700000000
            var upload = 0L
            var download = 0L
            var total = 0L
            var expire = 0L
            connection.getHeaderField("subscription-userinfo")?.let { info ->
                for (part in info.split(";")) {
                    val kv = part.trim().split("=", limit = 2)
                    if (kv.size != 2) continue
                    val value = kv[1].trim().toLongOrNull() ?: continue
                    when (kv[0].trim().lowercase()) {
                        "upload" -> upload = value
                        "download" -> download = value
                        "total" -> total = value
                        "expire" -> expire = value
                    }
                }
            }

            // profile-title: plain or "base64:<b64>"
            var title: String? = connection.getHeaderField("profile-title")?.trim()
            if (title != null && title.startsWith("base64:")) {
                title = try {
                    String(android.util.Base64.decode(title.removePrefix("base64:"), android.util.Base64.DEFAULT), Charsets.UTF_8)
                } catch (_: Throwable) {
                    null
                }
            }
            if (title.isNullOrBlank()) title = null

            // profile-update-interval: hours
            val interval = connection.getHeaderField("profile-update-interval")
                ?.trim()?.toIntOrNull() ?: 0

            return SubFetch(body, upload, download, total, expire, title, interval)
        } finally {
            connection.disconnect()
        }
    }
}
