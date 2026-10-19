package com.zenes.vpn.core

import com.zenes.vpn.data.ServerProfile
import com.zenes.vpn.data.Store
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a sing-box v1.14 configuration for the given server profile.
 * Topology: tun inbound <-> [proxy] / [direct] outbounds, DNS split,
 * sniff + dns-hijack route rules.
 *
 * Per-app routing (split tunneling) is embedded directly into the tun inbound
 * ("include_package" / "exclude_package") exactly like SFA does for its
 * profiles: sing-box carries those lists through the tun options to
 * TunOptions, and the VPN service applies them to VpnService.Builder in
 * openTun(). Android applies include and exclude exclusively, include wins.
 *
 * Smart RU routing (Store.ROUTE_RU) appends three bundled local rule-sets
 * (sing-geosite/sing-geoip "rule-set" branches, binary .srs, copied from
 * assets): geosite category-ru + category-gov-ru domains and geoip ru
 * addresses go to "direct", everything else stays on "proxy". RU domains
 * are also resolved via dns-direct so apps get RU CDN answers.
 */
object SbConfig {

    fun build(
        profile: ServerProfile,
        includePackages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
        routingMode: String = Store.ROUTE_ALL,
        ruleSetDir: String? = null,
    ): String {
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
        val smart = routingMode == Store.ROUTE_RU && !ruleSetDir.isNullOrBlank()
        if (smart) {
            // Russian domains are resolved over the direct path, so RU CDNs
            // answer with RU addresses and the ISP sees nothing odd.
            dns.put(
                "rules",
                JSONArray().put(
                    JSONObject()
                        .put("rule_set", JSONArray().put("geosite-ru").put("geosite-gov-ru"))
                        .put("server", "dns-direct"),
                ),
            )
        }
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
        if (includePackages.isNotEmpty()) {
            tun.put("include_package", JSONArray(includePackages))
        } else if (excludePackages.isNotEmpty()) {
            tun.put("exclude_package", JSONArray(excludePackages))
        }
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
        val rules = JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
            .put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        if (smart) {
            route.put(
                "rule_set",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "local")
                            .put("tag", "geosite-ru")
                            .put("format", "binary")
                            .put("path", "$ruleSetDir/category-ru.srs"),
                    )
                    .put(
                        JSONObject()
                            .put("type", "local")
                            .put("tag", "geosite-gov-ru")
                            .put("format", "binary")
                            .put("path", "$ruleSetDir/category-gov-ru.srs"),
                    )
                    .put(
                        JSONObject()
                            .put("type", "local")
                            .put("tag", "geoip-ru")
                            .put("format", "binary")
                            .put("path", "$ruleSetDir/geoip-ru.srs"),
                    ),
            )
            rules
                .put(
                    JSONObject()
                        .put("rule_set", JSONArray().put("geosite-ru").put("geosite-gov-ru"))
                        .put("outbound", "direct"),
                )
                .put(
                    JSONObject()
                        .put("rule_set", JSONArray().put("geoip-ru"))
                        .put("outbound", "direct"),
                )
        }
        route.put("rules", rules)
        route.put("final", "proxy")
        root.put("route", route)

        return root.toString()
    }
}
