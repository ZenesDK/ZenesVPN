package com.zenes.vpn.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.zenes.vpn.bg.CrashLogger
import com.zenes.vpn.bg.VpnStateBus
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.CancellationException

/**
 * Network-path selection for the app's own auxiliary requests (update check,
 * subscription refresh, pings).
 *
 * While OUR tunnel is up, default routing sends this app's sockets through
 * the tunnel — which is the only reliable path on networks that block
 * GitHub, subscription hosts or foreign server IPs directly (mobile
 * carriers and censored ISPs; DNS still resolves real IPs, but the TCP
 * handshake is silently dropped). On open networks the direct path works
 * and keeps traffic off the proxy. The helpers below therefore pick the
 * order of attempts from the live VPN state:
 *
 *   tunnel up   → preferred path first, the other one as fallback
 *   tunnel down → a single direct attempt (a second one would be identical)
 *
 * "Physical bypass" binds sockets to an underlying non-VPN network — the
 * app-level equivalent of VpnService.protect() (introduced in v1.10.1 for
 * the updater).
 */
object NetPath {

    /** True while our own tunnel is up: default routing goes through it. */
    fun tunnelUp(): Boolean =
        VpnStateBus.state.value == VpnStateBus.State.CONNECTED

    /**
     * Best underlying (non-VPN) network, or null when there is none
     * (VPN off / no internet-capable physical network).
     */
    fun bypassNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        var candidate: Network? = null
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return net
            if (candidate == null) candidate = net
        }
        return candidate
    }

    /** Opens the URL on the given network (null = default routing); DNS resolves via the same network. */
    fun open(url: String, network: Network?): HttpURLConnection =
        if (network != null) network.openConnection(URL(url)) as HttpURLConnection
        else URL(url).openConnection() as HttpURLConnection

    /**
     * Runs [block] on the best available network path, in the order that
     * matches the live VPN state (see the class doc). Returns the first
     * success; rethrows the last failure when every path fails.
     *
     * @param tunnelFirst true = try through the tunnel first (small API
     * calls: check, subscription refresh — on censored networks direct is
     * blocked and a direct-first order would always burn a full timeout);
     * false = direct first, tunnel fallback (big downloads: keep them off
     * a flaky proxy when possible).
     */
    inline fun <T> withSmartPath(
        context: Context,
        tag: String,
        tunnelFirst: Boolean = true,
        block: (Network?) -> T,
    ): T {
        if (!tunnelUp()) {
            // VPN down: default routing IS the direct path — one attempt,
            // fail fast (a bypass-network retry would just duplicate it).
            return block(null)
        }
        val bypass = bypassNetwork(context)
        if (bypass == null) {
            CrashLogger.breadcrumb("$tag: tunnel up, no bypass network — default routing")
            return block(null)
        }
        val order = if (tunnelFirst) {
            listOf<Network?>(null, bypass)
        } else {
            listOf<Network?>(bypass, null)
        }
        var last: Throwable? = null
        for (net in order) {
            try {
                return block(net)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                last = t
                CrashLogger.breadcrumb(
                    "$tag via ${if (net == null) "tunnel" else "direct"} failed: ${t.message}",
                )
            }
        }
        throw last ?: IllegalStateException("$tag failed on all paths")
    }

    /**
     * TCP-connect latency in ms, or null when unreachable.
     *
     * Measures DIRECT reachability first (bound to the physical network —
     * that is what decides whether a server is connectable at all, and it
     * keeps per-server numbers meaningful while the tunnel is up). When the
     * direct path fails and the tunnel is up, falls back through the tunnel
     * so the user still sees liveness on censored networks instead of a
     * wall of dashes.
     */
    fun tcpConnectMs(context: Context?, host: String, port: Int, timeoutMs: Int): Int? {
        if (context != null) {
            val net = bypassNetwork(context)
            if (net != null) {
                val ms = connectMs(net, host, port, timeoutMs)
                if (ms != null) return ms
                if (!tunnelUp()) return null
            }
        }
        return connectMs(null, host, port, timeoutMs)
    }

    /** One TCP connect on the given network (null = default routing); null on any failure. */
    private fun connectMs(network: Network?, host: String, port: Int, timeoutMs: Int): Int? {
        return try {
            val started = SystemClock.elapsedRealtime()
            val socket = if (network != null) network.socketFactory.createSocket() else Socket()
            val address = if (network != null) {
                // Resolve via the bound network so DNS and TCP take the same path.
                InetSocketAddress(network.getAllByName(host).firstOrNull() ?: return null, port)
            } else {
                InetSocketAddress(host, port)
            }
            socket.connect(address, timeoutMs)
            val elapsed = (SystemClock.elapsedRealtime() - started).toInt()
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            elapsed
        } catch (_: Throwable) {
            null
        }
    }
}
