package com.zenes.vpn.data

import android.content.Context
import com.zenes.vpn.bg.CrashLogger
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Traffic accounting keyed by day and server.
 *
 * Fed once per second from the libbox status stream (BoxClient.writeStatus):
 * the stream carries per-session totals, so the store keeps the previous
 * values and persists the deltas. A decreasing total means the VPN box was
 * restarted (reconnect) and the new total starts from zero again.
 *
 * Persistence: filesDir/stats.json, flushed at most once per 30 s while the
 * stream is running (a crash can lose at most half a minute of accounting).
 * Days older than [MAX_DAYS] are pruned on load.
 */
object StatsStore {

    private const val FILE = "stats.json"
    private const val MAX_DAYS = 90

    private var appContext: Context? = null
    private val lock = Any()
    private var prevUp = 0L
    private var prevDown = 0L
    private var loaded = false
    private var lastFlushAt = 0L
    private val dayKeys = LinkedHashMap<String, MutableDay>() // asc by key
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private class MutableServer(var name: String, var up: Long = 0, var down: Long = 0)
    private class MutableDay(var up: Long = 0, var down: Long = 0) {
        val servers = LinkedHashMap<String, MutableServer>()
    }

    /** Immutable view for the UI. */
    data class Server(val id: String, val name: String, val up: Long, val down: Long) {
        val total: Long get() = up + down
    }

    data class Day(val key: String, val up: Long, val down: Long, val servers: List<Server>)

    fun init(context: Context) {
        appContext = context.applicationContext
        load()
    }

    /**
     * Consumes the next status message. [upTotal]/[downTotal] are per-session
     * byte totals; deltas are attributed to the currently selected server.
     */
    fun accumulate(upTotal: Long, downTotal: Long) {
        val ctx = appContext ?: return
        synchronized(lock) {
            val dUp = if (upTotal >= prevUp) upTotal - prevUp else upTotal
            val dDown = if (downTotal >= prevDown) downTotal - prevDown else downTotal
            prevUp = upTotal
            prevDown = downTotal
            if (dUp <= 0 && dDown <= 0) return
            val key = todayKey()
            val day = dayKeys.getOrPut(key) { MutableDay() }
            day.up += dUp
            day.down += dDown
            try {
                val store = Store(ctx)
                val id = store.selectedId() ?: "no-server"
                val profile = store.servers().firstOrNull { it.id == id }
                val name = profile?.let { (it.flag + " ").plus(it.name) } ?: id
                val s = day.servers.getOrPut(id) { MutableServer(name) }
                s.name = name // keep the freshest label for deleted servers
                s.up += dUp
                s.down += dDown
            } catch (_: Throwable) {
                // attribution is best-effort, never break the stats stream
            }
            val now = System.currentTimeMillis()
            if (now - lastFlushAt > 30_000) flushLocked()
        }
    }

    /** Last [n] days (oldest first), gaps filled with empty days. */
    fun snapshot(n: Int): List<Day> {
        synchronized(lock) {
            val out = ArrayList<Day>(n)
            val cal = Calendar.getInstance()
            repeat(n) { i ->
                val key = dayFormat.format(cal.time)
                val d = dayKeys[key]
                out.add(
                    Day(
                        key = key,
                        up = d?.up ?: 0L,
                        down = d?.down ?: 0L,
                        servers = d?.servers?.map { (id, s) -> Server(id, s.name, s.up, s.down) } ?: emptyList(),
                    ),
                )
                cal.add(Calendar.DAY_OF_YEAR, -1)
            }
            return out.reversed()
        }
    }

    /** Aggregated per-server totals across the last [n] days, biggest first. */
    fun topServers(n: Int, limit: Int = 8): List<Server> {
        synchronized(lock) {
            val agg = LinkedHashMap<String, MutableServer>()
            var cal = Calendar.getInstance()
            repeat(n) { i ->
                val key = dayFormat.format(cal.time)
                dayKeys[key]?.servers?.forEach { (id, s) ->
                    val a = agg.getOrPut(id) { MutableServer(s.name) }
                    a.name = s.name
                    a.up += s.up
                    a.down += s.down
                }
                cal = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            }
            return agg.map { (id, s) -> Server(id, s.name, s.up, s.down) }
                .sortedByDescending { it.total }
                .take(limit)
        }
    }

    fun reset() {
        synchronized(lock) {
            dayKeys.clear()
            prevUp = 0
            prevDown = 0
            lastFlushAt = 0
            flushLocked()
        }
        CrashLogger.breadcrumb("stats reset")
    }

    /** Called when the VPN goes down so the last seconds are not lost. */
    fun flush() {
        synchronized(lock) { flushLocked() }
    }

    private fun todayKey(): String = dayFormat.format(Calendar.getInstance().time)

    private fun load() {
        synchronized(lock) {
            loaded = true
            val ctx = appContext ?: return
            try {
                val f = File(ctx.filesDir, FILE)
                if (!f.exists()) return
                val root = JSONObject(f.readText())
                val daysObj = root.optJSONObject("days") ?: return
                val cutoff = cutoffKey(MAX_DAYS)
                for (key in daysObj.keys()) {
                    if (key < cutoff) continue
                    val o = daysObj.optJSONObject(key) ?: continue
                    val day = MutableDay(o.optLong("up"), o.optLong("down"))
                    val sObj = o.optJSONObject("s")
                    if (sObj != null) {
                        for (id in sObj.keys()) {
                            val so = sObj.optJSONObject(id) ?: continue
                            day.servers[id] = MutableServer(
                                so.optString("n"),
                                so.optLong("u"),
                                so.optLong("d"),
                            )
                        }
                    }
                    dayKeys[key] = day
                }
            } catch (t: Throwable) {
                CrashLogger.log("stats load failed, starting fresh", t)
                dayKeys.clear()
            }
        }
    }

    private fun flushLocked() {
        lastFlushAt = System.currentTimeMillis()
        val ctx = appContext ?: return
        try {
            val root = JSONObject()
            val daysObj = JSONObject()
            val cutoff = cutoffKey(MAX_DAYS)
            for ((key, day) in dayKeys) {
                if (key < cutoff) continue
                val o = JSONObject().put("up", day.up).put("down", day.down)
                val sObj = JSONObject()
                for ((id, s) in day.servers) {
                    sObj.put(id, JSONObject().put("n", s.name).put("u", s.up).put("d", s.down))
                }
                o.put("s", sObj)
                daysObj.put(key, o)
            }
            root.put("days", daysObj)
            val f = File(ctx.filesDir, FILE)
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(root.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            CrashLogger.log("stats flush failed", t)
        }
    }

    /** "yyyy-MM-dd" of the day [daysAgo] days before today. */
    private fun cutoffKey(daysAgo: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return dayFormat.format(cal.time)
    }
}
