package com.zenes.vpn.parser

import android.net.Uri
import android.util.Base64
import com.zenes.vpn.data.ServerProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object LinkParser {

    private val FLAG_REGEX = Regex("[\uD83C][\uDDE6-\uDDFF][\uD83C][\uDDE6-\uDDFF]")

    /** Extracts every parseable link from arbitrary text (clipboard body, subscription body). */
    fun extractLinks(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val result = mutableListOf<String>()

        // try whole text as a base64 subscription body
        if (!text.contains("://")) {
            val decoded = tryDecodeBase64(text.trim())
            if (decoded != null) return extractLinks(decoded)
            return emptyList()
        }

        for (rawLine in text.split('\n', '\r', ' ')) {
            var line = rawLine.trim()
            if (line.isEmpty()) continue
            if (!line.contains("://")) {
                val decoded = tryDecodeBase64(line)
                if (decoded != null) {
                    result += extractLinks(decoded)
                    continue
                }
            }
            if (line.startsWith("vless://") || line.startsWith("trojan://") ||
                line.startsWith("hysteria2://") || line.startsWith("hy2://") ||
                line.startsWith("tuic://") || line.startsWith("ss://") || line.startsWith("vmess://")
            ) {
                // strip trailing garbage (e.g. commas)
                line = line.trimEnd(',', ';')
                result.add(line)
            }
        }
        return result
    }

    fun parseLink(link: String): ServerProfile? {
        return try {
            when {
                link.startsWith("vless://") -> parseVless(link)
                link.startsWith("vmess://") -> parseVmess(link)
                link.startsWith("trojan://") -> parseTrojan(link)
                link.startsWith("ss://") -> parseShadowsocks(link)
                link.startsWith("hysteria2://") || link.startsWith("hy2://") -> parseHysteria2(link)
                link.startsWith("tuic://") -> parseTuic(link)
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun tryDecodeBase64(s: String): String? {
        val trimmed = s.trim().replace("\n", "").replace("\r", "")
        if (trimmed.isEmpty() || trimmed.length < 8) return null
        for (mode in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_WRAP)) {
            try {
                val bytes = Base64.decode(trimmed, mode)
                val out = String(bytes, Charsets.UTF_8)
                if (out.contains("://") || out.contains("{")) return out
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun base64UrlDecode(s: String): String {
        val normalized = s.replace('-', '+').replace('_', '.')
            .let { if (it.length % 4 == 0) it else it + "=".repeat(4 - it.length % 4) }
        return String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun extractFlag(name: String): String {
        val m = FLAG_REGEX.find(name)
        return m?.value ?: ""
    }

    private fun boolParam(params: Map<String, String>, vararg keys: String): Boolean {
        for (k in keys) {
            when (params[k]?.lowercase()) {
                "1", "true", "yes" -> return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------ vless

    private fun parseVless(link: String): ServerProfile? {
        val body = link.removePrefix("vless://")
        val hashIdx = body.indexOf('#')
        val name = if (hashIdx >= 0) Uri.decode(body.substring(hashIdx + 1)) else ""
        val main = if (hashIdx >= 0) body.substring(0, hashIdx) else body
        val qIdx = main.indexOf('?')
        val authority = if (qIdx >= 0) main.substring(0, qIdx) else main
        val query = if (qIdx >= 0) main.substring(qIdx + 1) else ""

        val atIdx = authority.lastIndexOf('@')
        if (atIdx < 0) return null
        val uuid = authority.substring(0, atIdx)
        val hostPort = authority.substring(atIdx + 1)
        val (host, port) = splitHostPort(hostPort) ?: return null
        val params = parseQuery(query)

        val outbound = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)
        if (params["flow"]?.isNotEmpty() == true) outbound.put("flow", params["flow"])

        val tls = buildTls(params, defaultSecurity = "none", host = host)
        if (tls != null) outbound.put("tls", tls)
        val transport = buildTransport(params)
        if (transport != null) outbound.put("transport", transport)
        if (params["packet_encoding"]?.isNotEmpty() == true) {
            outbound.put("packet_encoding", params["packet_encoding"])
        }

        return ServerProfile(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "$host:$port" },
            flag = extractFlag(name),
            proto = "vless",
            host = host,
            port = port,
            link = link,
            outbound = outbound.toString(),
        )
    }

    // ------------------------------------------------------------------ vmess

    private fun parseVmess(link: String): ServerProfile? {
        val payload = link.removePrefix("vmess://").trim()
        val json = JSONObject(base64UrlDecode(payload))

        fun jstr(k: String): String = try { json.optString(k, "") } catch (_: Throwable) { "" }
        fun jint(k: String): Int = try {
            when (val v = json.opt(k)) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull() ?: 0
                else -> 0
            }
        } catch (_: Throwable) {
            0
        }

        val host = jstr("add")
        val port = jint("port")
        if (host.isEmpty() || port <= 0) return null
        val uuid = jstr("id")
        val name = jstr("ps")
        val net = jstr("net").ifBlank { "tcp" }
        val tlsEnabled = jstr("tls").equals("tls", true)
        val sni = jstr("sni").ifBlank { jstr("host") }
        val fp = jstr("fp").ifBlank { "chrome" }
        val alpn = jstr("alpn")

        val params = mutableMapOf<String, String>()
        params["type"] = net
        if (jstr("path").isNotEmpty()) params["path"] = jstr("path")
        if (jstr("host").isNotEmpty()) params["host"] = jstr("host")
        if (jstr("serviceName").isNotEmpty()) params["serviceName"] = jstr("serviceName")

        val outbound = JSONObject()
            .put("type", "vmess")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)
            .put("security", jstr("scy").ifBlank { "auto" })
            .put("alter_id", jint("aid"))
        if (tlsEnabled) {
            val tls = JSONObject().put("enabled", true)
            if (sni.isNotEmpty()) tls.put("server_name", sni)
            tls.put("insecure", false)
            if (fp.isNotEmpty()) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
            if (alpn.isNotEmpty()) tls.put("alpn", JSONArray(listOf(*alpn.split(',').map { it.trim() }.toTypedArray())))
            outbound.put("tls", tls)
        }
        val transport = buildTransport(params)
        if (transport != null) outbound.put("transport", transport)

        return ServerProfile(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "$host:$port" },
            flag = extractFlag(name),
            proto = "vmess",
            host = host,
            port = port,
            link = link,
            outbound = outbound.toString(),
        )
    }

    // ------------------------------------------------------------------ trojan

    private fun parseTrojan(link: String): ServerProfile? {
        val body = link.removePrefix("trojan://")
        val hashIdx = body.indexOf('#')
        val name = if (hashIdx >= 0) Uri.decode(body.substring(hashIdx + 1)) else ""
        val main = if (hashIdx >= 0) body.substring(0, hashIdx) else body
        val qIdx = main.indexOf('?')
        val authority = if (qIdx >= 0) main.substring(0, qIdx) else main
        val query = if (qIdx >= 0) main.substring(qIdx + 1) else ""

        val atIdx = authority.lastIndexOf('@')
        if (atIdx < 0) return null
        val password = Uri.decode(authority.substring(0, atIdx))
        val (host, port) = splitHostPort(authority.substring(atIdx + 1)) ?: return null
        val params = parseQuery(query)

        val outbound = JSONObject()
            .put("type", "trojan")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("password", password)

        val params2 = params.toMutableMap()
        if (!params2.containsKey("security")) params2["security"] = "tls"
        val tls = buildTls(params2, defaultSecurity = "tls", host = host)
        if (tls != null) outbound.put("tls", tls)
        val transport = buildTransport(params)
        if (transport != null) outbound.put("transport", transport)

        return ServerProfile(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "$host:$port" },
            flag = extractFlag(name),
            proto = "trojan",
            host = host,
            port = port,
            link = link,
            outbound = outbound.toString(),
        )
    }

    // ------------------------------------------------------------------ shadowsocks

    private fun parseShadowsocks(link: String): ServerProfile? {
        val body = link.removePrefix("ss://")
        val hashIdx = body.indexOf('#')
        val name = if (hashIdx >= 0) Uri.decode(body.substring(hashIdx + 1)) else ""
        var main = if (hashIdx >= 0) body.substring(0, hashIdx) else body
        var method: String
        var password: String
        var hostPort: String

        val qIdx = main.indexOf('?')
        val query = if (qIdx >= 0) main.substring(qIdx + 1) else ""
        if (qIdx >= 0) main = main.substring(0, qIdx)

        if (main.contains('@')) {
            // SIP002: base64(method:password)@host:port
            val atIdx = main.lastIndexOf('@')
            val userInfo = main.substring(0, atIdx)
            hostPort = main.substring(atIdx + 1)
            val decoded = try {
                base64UrlDecode(userInfo)
            } catch (_: Throwable) {
                Uri.decode(userInfo)
            }
            val sep = decoded.indexOf(':')
            if (sep < 0) return null
            method = decoded.substring(0, sep)
            password = Uri.decode(decoded.substring(sep + 1))
        } else {
            // legacy: base64(method:password@host:port)
            val decoded = try {
                base64UrlDecode(main)
            } catch (_: Throwable) {
                return null
            }
            val atIdx = decoded.lastIndexOf('@')
            if (atIdx < 0) return null
            val userInfo = decoded.substring(0, atIdx)
            hostPort = decoded.substring(atIdx + 1)
            val sep = userInfo.indexOf(':')
            if (sep < 0) return null
            method = userInfo.substring(0, sep)
            password = userInfo.substring(sep + 1)
        }

        val (host, port) = splitHostPort(hostPort) ?: return null
        val params = parseQuery(query)

        val outbound = JSONObject()
            .put("type", "shadowsocks")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("method", method)
            .put("password", password)

        return ServerProfile(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "$host:$port" },
            flag = extractFlag(name),
            proto = "shadowsocks",
            host = host,
            port = port,
            link = link,
            outbound = outbound.toString(),
        )
    }

    // ------------------------------------------------------------------ hysteria2

    private fun parseHysteria2(link: String): ServerProfile? {
        val body = link.removePrefix("hysteria2://").removePrefix("hy2://")
        val hashIdx = body.indexOf('#')
        val name = if (hashIdx >= 0) Uri.decode(body.substring(hashIdx + 1)) else ""
        val main = if (hashIdx >= 0) body.substring(0, hashIdx) else body
        val qIdx = main.indexOf('?')
        val authority = if (qIdx >= 0) main.substring(0, qIdx) else main
        val query = if (qIdx >= 0) main.substring(qIdx + 1) else ""

        val atIdx = authority.lastIndexOf('@')
        val password = if (atIdx >= 0) Uri.decode(authority.substring(0, atIdx)) else ""
        val (host, port) = splitHostPort(if (atIdx >= 0) authority.substring(atIdx + 1) else authority)
            ?: return null
        val params = parseQuery(query)

        val outbound = JSONObject()
            .put("type", "hysteria2")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
        if (password.isNotEmpty()) outbound.put("password", password)
        if (params["obfs"]?.isNotEmpty() == true) {
            outbound.put("obfs", JSONObject().put("type", params["obfs"]!!).put("password", params["obfs-password"] ?: ""))
        }
        params["up"]?.let { up ->
            val v = up.replace(Regex("[^0-9]"), "")
            if (v.isNotEmpty()) outbound.put("up_mbps", v.toInt())
        }
        params["down"]?.let { down ->
            val v = down.replace(Regex("[^0-9]"), "")
            if (v.isNotEmpty()) outbound.put("down_mbps", v.toInt())
        }

        val tls = JSONObject().put("enabled", true)
        params["sni"]?.let { if (it.isNotEmpty()) tls.put("server_name", it) }
        if (boolParam(params, "insecure", "allow_insecure") || params["insecure"] == "1") {
            tls.put("insecure", true)
        }
        params["alpn"]?.let { if (it.isNotEmpty()) tls.put("alpn", JSONArray(listOf(*it.split(',').map { s -> s.trim() }.toTypedArray()))) }
        outbound.put("tls", tls)

        return ServerProfile(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "$host:$port" },
            flag = extractFlag(name),
            proto = "hysteria2",
            host = host,
            port = port,
            link = link,
            outbound = outbound.toString(),
        )
    }

    // ------------------------------------------------------------------ tuic

    private fun parseTuic(link: String): ServerProfile? {
        val body = link.removePrefix("tuic://")
        val hashIdx = body.indexOf('#')
        val name = if (hashIdx >= 0) Uri.decode(body.substring(hashIdx + 1)) else ""
        val main = if (hashIdx >= 0) body.substring(0, hashIdx) else body
        val qIdx = main.indexOf('?')
        val authority = if (qIdx >= 0) main.substring(0, qIdx) else main
        val query = if (qIdx >= 0) main.substring(qIdx + 1) else ""

        val atIdx = authority.lastIndexOf('@')
        if (atIdx < 0) return null
        val userInfo = authority.substring(0, atIdx)
        val (uuid, password) = if (userInfo.contains(':')) {
            userInfo.substring(0, userInfo.indexOf(':')) to Uri.decode(userInfo.substring(userInfo.indexOf(':') + 1))
        } else {
            userInfo to ""
        }
        val (host, port) = splitHostPort(authority.substring(atIdx + 1)) ?: return null
        val params = parseQuery(query)

        val outbound = JSONObject()
            .put("type", "tuic")
            .put("tag", "proxy")
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)
        if (password.isNotEmpty()) outbound.put("password", password)
        params["congestion_control"]?.let { if (it.isNotEmpty()) outbound.put("congestion_control", it) }
        params["udp_relay_mode"]?.let { if (it.isNotEmpty()) outbound.put("udp_relay_mode", it) }

        val tls = JSONObject().put("enabled", true)
        params["sni"]?.let { if (it.isNotEmpty()) tls.put("server_name", it) }
        if (boolParam(params, "allow_insecure", "insecure")) tls.put("insecure", true)
        params["alpn"]?.let {
            if (it.isNotEmpty()) tls.put(
                "alpn",
                JSONArray(listOf(*it.split(',').map { s -> s.trim() }.toTypedArray())),
            )
        }
        outbound.put("tls", tls)

        return ServerProfile(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "$host:$port" },
            flag = extractFlag(name),
            proto = "tuic",
            host = host,
            port = port,
            link = link,
            outbound = outbound.toString(),
        )
    }

    // ------------------------------------------------------------------ shared builders

    private fun splitHostPort(s: String): Pair<String, Int>? {
        var host = s
        var port = 443
        val lastColon = s.lastIndexOf(':')
        val lastBracket = s.lastIndexOf(']')
        if (lastColon > lastBracket && lastColon > 0) {
            host = s.substring(0, lastColon).trim('[', ']')
            port = s.substring(lastColon + 1).trim('[').toIntOrNull() ?: return null
        } else if (s.startsWith('[')) {
            host = s.substring(1, lastBracket)
        }
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq >= 0) {
                map[Uri.decode(pair.substring(0, eq)).lowercase()] = Uri.decode(pair.substring(eq + 1))
            } else {
                map[Uri.decode(pair).lowercase()] = ""
            }
        }
        return map
    }

    private fun buildTls(params: Map<String, String>, defaultSecurity: String, host: String): JSONObject? {
        val security = params["security"] ?: defaultSecurity
        if (security == "none" || security.isEmpty()) return null
        val tls = JSONObject().put("enabled", true)
        val sni = params["sni"] ?: params["peer"] ?: params["host"] ?: ""
        if (sni.isNotEmpty()) tls.put("server_name", sni) else tls.put("server_name", host)
        if (boolParam(params, "allow_insecure", "insecure")) tls.put("insecure", true)
        params["alpn"]?.let {
            if (it.isNotEmpty()) tls.put(
                "alpn",
                JSONArray(listOf(*it.split(',').map { s -> s.trim() }.toTypedArray())),
            )
        }
        params["fp"]?.let { fp ->
            if (fp.isNotEmpty()) {
                tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
            }
        }
        if (security == "reality") {
            val reality = JSONObject().put("enabled", true)
            params["pbk"]?.let { if (it.isNotEmpty()) reality.put("public_key", it) }
            params["sid"]?.let { if (it.isNotEmpty()) reality.put("short_id", it) }
            tls.put("reality", reality)
        }
        return tls
    }

    private fun buildTransport(params: Map<String, String>): JSONObject? {
        val type = (params["type"] ?: "tcp").lowercase()
        return when (type) {
            "ws" -> {
                val t = JSONObject().put("type", "ws")
                val path = params["path"] ?: "/"
                t.put("path", path)
                val hostHeader = params["host"]
                if (hostHeader?.isNotEmpty() == true) {
                    t.put("headers", JSONObject().put("Host", hostHeader))
                }
                t
            }
            "http", "h2" -> {
                val t = JSONObject().put("type", "http")
                val path = params["path"]
                if (path?.isNotEmpty() == true) t.put("path", path)
                val hostHeader = params["host"]
                if (hostHeader?.isNotEmpty() == true) {
                    t.put("host", JSONArray(listOf(*hostHeader.split(',').map { it.trim() }.toTypedArray())))
                }
                t
            }
            "grpc" -> {
                val t = JSONObject().put("type", "grpc")
                params["serviceName"]?.let { if (it.isNotEmpty()) t.put("service_name", it) }
                t
            }
            else -> null
        }
    }
}
