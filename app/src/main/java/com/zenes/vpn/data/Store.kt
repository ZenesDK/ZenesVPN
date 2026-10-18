package com.zenes.vpn.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ServerProfile(
    val id: String,
    val name: String,
    val flag: String,
    val proto: String,
    val host: String,
    val port: Int,
    val link: String,
    val outbound: String,
    val pingMs: Int = -1,
    val subId: String? = null,
)

data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val lastUpdate: Long = 0,
    // Standard subscription headers (subscription-userinfo), bytes / unix-seconds.
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
) {
    val used: Long get() = upload + download
}

class Store(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zenes_store", Context.MODE_PRIVATE)

    // ---------- servers ----------

    fun servers(): List<ServerProfile> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ServerProfile(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    flag = o.optString("flag"),
                    proto = o.optString("proto"),
                    host = o.optString("host"),
                    port = o.optInt("port"),
                    link = o.optString("link"),
                    outbound = o.optString("outbound"),
                    pingMs = o.optInt("ping", -1),
                    subId = if (o.has("subId") && !o.isNull("subId")) o.optString("subId") else null,
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun saveServers(list: List<ServerProfile>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
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
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    fun selectedId(): String? = prefs.getString(KEY_SELECTED, null)

    fun select(id: String) {
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    fun selectedServer(): ServerProfile? {
        val id = selectedId() ?: return null
        return servers().firstOrNull { it.id == id }
    }

    /** Adds profiles, replacing existing entries with the same link. Returns how many were added. */
    fun addServers(list: List<ServerProfile>): Int {
        if (list.isEmpty()) return 0
        val existing = servers().toMutableList()
        var added = 0
        for (p in list) {
            val idx = existing.indexOfFirst { it.link == p.link }
            if (idx >= 0) {
                existing[idx] = p.copy(id = existing[idx].id, pingMs = existing[idx].pingMs)
            } else {
                existing.add(p)
                added++
            }
        }
        saveServers(existing)
        if (selectedId() == null) select(existing.first().id)
        return added
    }

    fun updatePing(id: String, pingMs: Int) {
        val list = servers().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(pingMs = pingMs)
            saveServers(list)
        }
    }

    fun deleteServer(id: String) {
        saveServers(servers().filterNot { it.id == id })
        if (selectedId() == id) {
            val first = servers().firstOrNull()
            if (first != null) select(first.id) else prefs.edit().remove(KEY_SELECTED).apply()
        }
    }

    fun removeServersOfSub(subId: String) {
        saveServers(servers().filterNot { it.subId == subId })
    }

    // ---------- subscriptions ----------

    fun subs(): List<Subscription> {
        val raw = prefs.getString(KEY_SUBS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Subscription(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    url = o.optString("url"),
                    lastUpdate = o.optLong("lastUpdate"),
                    upload = o.optLong("upload"),
                    download = o.optLong("download"),
                    total = o.optLong("total"),
                    expire = o.optLong("expire"),
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun saveSubs(list: List<Subscription>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("url", s.url)
                    .put("lastUpdate", s.lastUpdate)
                    .put("upload", s.upload)
                    .put("download", s.download)
                    .put("total", s.total)
                    .put("expire", s.expire),
            )
        }
        prefs.edit().putString(KEY_SUBS, arr.toString()).apply()
    }

    fun addSub(name: String, url: String): Subscription {
        val sub = Subscription(UUID.randomUUID().toString(), name.ifBlank { url.substringBefore("/") }, url)
        saveSubs(subs() + sub)
        return sub
    }

    fun updateSub(sub: Subscription) {
        saveSubs(subs().map { if (it.id == sub.id) sub else it })
    }

    fun deleteSub(id: String) {
        saveSubs(subs().filterNot { it.id == id })
        removeServersOfSub(id)
    }

    // ---------- settings ----------

    var autostart: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()

    /** Remembers that the user was guided to the system Always-on VPN screen. */
    var killSwitchHint: Boolean
        get() = prefs.getBoolean(KEY_KILL_SWITCH, false)
        set(value) = prefs.edit().putBoolean(KEY_KILL_SWITCH, value).apply()

    /** Unix-ms of the last automatic update check (throttles Updater auto-check). */
    var lastUpdateCheck: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    // ---------- per-app routing ----------

    /** PER_APP_ALL / PER_APP_INCLUDE / PER_APP_EXCLUDE */
    var perAppMode: String
        get() = prefs.getString(KEY_PER_APP_MODE, PER_APP_ALL) ?: PER_APP_ALL
        set(value) = prefs.edit().putString(KEY_PER_APP_MODE, value).apply()

    /** Packages routed through the VPN when mode is PER_APP_INCLUDE. */
    var perAppInclude: Set<String>
        get() = prefs.getStringSet(KEY_PER_APP_INCLUDE, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_PER_APP_INCLUDE, value).apply()

    /** Packages excluded from the VPN when mode is PER_APP_EXCLUDE. */
    var perAppExclude: Set<String>
        get() = prefs.getStringSet(KEY_PER_APP_EXCLUDE, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_PER_APP_EXCLUDE, value).apply()

    companion object {
        private const val KEY_SERVERS = "servers"
        private const val KEY_SUBS = "subs"
        private const val KEY_SELECTED = "selected"
        private const val KEY_AUTOSTART = "autostart"
        private const val KEY_KILL_SWITCH = "kill_switch_hint"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"

        const val PER_APP_ALL = "all"
        const val PER_APP_INCLUDE = "include"
        const val PER_APP_EXCLUDE = "exclude"
        private const val KEY_PER_APP_MODE = "per_app_mode"
        private const val KEY_PER_APP_INCLUDE = "per_app_include"
        private const val KEY_PER_APP_EXCLUDE = "per_app_exclude"
    }
}
