package com.zenes.vpn.core

import com.zenes.vpn.data.ServerProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a sing-box v1.14 configuration for the given server profile.
 * Topology: tun inbound <-> [proxy] / [direct] outbounds, DNS split,
 * sniff + dns-hijack route rules.
 */
object SbConfig {

    fun build(profile: ServerProfile): String {
        val root = JSONObject()

        root.put("log", JSONObject().put("level", "warn").put("timestamp", true))

        // ---------------- DNS ----------------
        val dns = JSONObject()
        val dnsServers = JSONArray()
        dnsServers.put(
            JSONObject()
                .put("tag", "dns-remote")
                .put("type", "udp")
                .put("server", "1.1.1.1")
                .put("detour", "proxy"),
        )
        dnsServers.put(
            JSONObject()
                .put("tag", "dns-direct")
                .put("type", "udp")
                .put("server", "8.8.8.8"),
        )
        dns.put("servers", dnsServers)
        dns.put("final", "dns-remote")
        dns.put("strategy", "prefer_ipv4")
        root.put("dns", dns)

        // ---------------- inbound ----------------
        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put(
                "address",
                JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"),
            )
            .put("mtu", 9000)
            .put("auto_route", true)
            .put("strict_route", true)
            // "system" = TCP handled by the Android kernel TCP/IP, UDP by sing-tun's
            // native UDP NAT. This avoids the gVisor userspace netstack entirely:
            // our gVisor build (sing-box-mod fork + Go 1.25) crashed in the TCP
            // dispatcher while processing real traffic (SIGSEGV in sleeper/park path).
            .put("stack", "system")
        root.put("inbounds", JSONArray().put(tun))

        // ---------------- outbounds ----------------
        val proxy = JSONObject(profile.outbound)
        val outbounds = JSONArray()
        outbounds.put(proxy)
        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))
        root.put("outbounds", outbounds)

        // ---------------- route ----------------
        val route = JSONObject()
        route.put("auto_detect_interface", true)
        route.put("default_domain_resolver", "dns-direct")
        route.put(
            "rules",
            JSONArray()
                .put(JSONObject().put("action", "sniff"))
                .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                .put(JSONObject().put("ip_is_private", true).put("outbound", "direct")),
        )
        route.put("final", "proxy")
        root.put("route", route)

        return root.toString()
    }
}
