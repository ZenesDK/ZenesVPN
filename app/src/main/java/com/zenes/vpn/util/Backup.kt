package com.zenes.vpn.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.zenes.vpn.data.ServerProfile
import com.zenes.vpn.data.Store
import com.zenes.vpn.data.Subscription
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full backup of servers, subscriptions and settings. Two transports:
 * a plain JSON file (SAF) and a QR code payload ("zenes1:" + gzip + base64,
 * so a QR survives even when raw JSON exceeds its capacity). Restoring
 * replaces everything, the caller is responsible for asking the user first.
 */
object Backup {

    private const val MAGIC = "zenes-backup"
    private const val VERSION = 1
    private const val QR_PREFIX = "zenes1:"

    /** Byte-mode QR capacity (version 40, EC level L) minus safety margin. */
    const val QR_MAX_CHARS = 2900

    data class Summary(val servers: Int, val subs: Int)

    // ---------------- export ----------------

    fun buildJson(context: Context): String {
        val store = Store(context)
        val root = JSONObject()
            .put("magic", MAGIC)
            .put("v", VERSION)
            .put("created", System.currentTimeMillis())

        val servers = JSONArray()
        for (s in store.servers()) {
            servers.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("flag", s.flag)
                    .put("proto", s.proto)
                    .put("host", s.host)
                    .put("port", s.port)
                    .put("link", s.link)
                    .put("outbound", s.outbound)
                    .put("ping", s.pingMs)
                    .put("subId", s.subId ?: JSONObject.NULL),
            )
        }
        root.put("servers", servers)

        val subs = JSONArray()
        for (s in store.subs()) {
            subs.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("url", s.url)
                    .put("lastUpdate", s.lastUpdate)
                    .put("upload", s.upload)
                    .put("download", s.download)
                    .put("total", s.total)
                    .put("expire", s.expire)
                    .put("updateIntervalHours", s.updateIntervalHours),
            )
        }
        root.put("subs", subs)

        store.selectedId()?.let { root.put("selected", it) }
        root.put(
            "settings",
            JSONObject()
                .put("autostart", store.autostart)
                .put("killSwitchHint", store.killSwitchHint)
                .put("perAppMode", store.perAppMode)
                .put("perAppInclude", JSONArray(store.perAppInclude.toList()))
                .put("perAppExclude", JSONArray(store.perAppExclude.toList()))
                .put("subAutoUpdate", store.subAutoUpdate)
                .put("routingMode", store.routingMode),
        )
        return root.toString()
    }

    // ---------------- import ----------------

    /** Validates the payload without touching the store. Null = not a backup. */
    fun parse(text: String?): Summary? {
        if (text.isNullOrBlank()) return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optString("magic") != MAGIC) return null
        val servers = root.optJSONArray("servers")?.length() ?: 0
        val subs = root.optJSONArray("subs")?.length() ?: 0
        return Summary(servers, subs)
    }

    /** Applies a validated backup. Returns the same summary, or null if invalid. */
    fun restore(context: Context, text: String): Summary? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optString("magic") != MAGIC) return null

        val servers = ArrayList<ServerProfile>()
        val serverArr = root.optJSONArray("servers") ?: JSONArray()
        for (i in 0 until serverArr.length()) {
            val o = serverArr.optJSONObject(i) ?: continue
            val link = o.optString("link")
            if (link.isBlank()) continue
            servers.add(
                ServerProfile(
                    id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    name = o.optString("name"),
                    flag = o.optString("flag"),
                    proto = o.optString("proto"),
                    host = o.optString("host"),
                    port = o.optInt("port"),
                    link = link,
                    outbound = o.optString("outbound"),
                    pingMs = o.optInt("ping", -1),
                    subId = if (o.has("subId") && !o.isNull("subId")) o.optString("subId") else null,
                ),
            )
        }

        val subs = ArrayList<Subscription>()
        val subArr = root.optJSONArray("subs") ?: JSONArray()
        for (i in 0 until subArr.length()) {
            val o = subArr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue
            subs.add(
                Subscription(
                    id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                    name = o.optString("name"),
                    url = url,
                    lastUpdate = o.optLong("lastUpdate"),
                    upload = o.optLong("upload"),
                    download = o.optLong("download"),
                    total = o.optLong("total"),
                    expire = o.optLong("expire"),
                    updateIntervalHours = o.optInt("updateIntervalHours"),
                ),
            )
        }

        val store = Store(context)
        store.saveServers(servers)
        store.saveSubs(subs)
        val selected = root.optString("selected")
        when {
            selected.isNotBlank() && servers.any { it.id == selected } -> store.select(selected)
            servers.isNotEmpty() -> store.select(servers.first().id)
            else -> store.saveServers(emptyList()) // selection key cleared by saveServers' caller below
        }
        if (servers.isEmpty()) {
            // drop a dangling selected id (saveServers does not touch it)
            context.getSharedPreferences("zenes_store", Context.MODE_PRIVATE)
                .edit().remove("selected").apply()
        }

        val settings = root.optJSONObject("settings")
        if (settings != null) {
            if (settings.has("autostart")) store.autostart = settings.optBoolean("autostart")
            if (settings.has("killSwitchHint")) store.killSwitchHint = settings.optBoolean("killSwitchHint")
            if (settings.has("perAppMode")) store.perAppMode = settings.optString("perAppMode")
            if (settings.has("perAppInclude")) {
                store.perAppInclude = stringSet(settings.optJSONArray("perAppInclude"))
            }
            if (settings.has("perAppExclude")) {
                store.perAppExclude = stringSet(settings.optJSONArray("perAppExclude"))
            }
            if (settings.has("subAutoUpdate")) store.subAutoUpdate = settings.optString("subAutoUpdate")
            if (settings.has("routingMode")) store.routingMode = settings.optString("routingMode")
        }
        return Summary(servers.size, subs.size)
    }

    private fun stringSet(arr: JSONArray?): Set<String> {
        val out = LinkedHashSet<String>()
        if (arr != null) for (i in 0 until arr.length()) {
            val v = arr.optString(i)
            if (v.isNotBlank()) out.add(v)
        }
        return out
    }

    // ---------------- QR transport ----------------

    /** "zenes1:" + gzip(json) + base64. Throws when the payload cannot fit a QR. */
    fun qrPayload(context: Context): String {
        val gz = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(buildJson(context).toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val payload = QR_PREFIX + Base64.encodeToString(gz, Base64.NO_WRAP)
        if (payload.length > QR_MAX_CHARS) {
            throw IllegalStateException("QR payload too large: ${payload.length}")
        }
        return payload
    }

    /** Inverse of [qrPayload]; null when the scanned text is not our payload. */
    fun fromQrPayload(contents: String?): String? {
        if (contents == null || !contents.startsWith(QR_PREFIX)) return null
        return runCatching {
            val gz = Base64.decode(contents.removePrefix(QR_PREFIX), Base64.NO_WRAP)
            GZIPInputStream(ByteArrayInputStream(gz)).readBytes().toString(Charsets.UTF_8)
        }.getOrNull()
    }

    fun qrBitmap(content: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                pixels[y * sizePx + x] =
                    if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }
}
