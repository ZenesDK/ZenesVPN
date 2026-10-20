package com.zenes.vpn.util

import android.content.Context
import com.zenes.vpn.data.Store
import com.zenes.vpn.bg.CrashLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Automatic subscription refresh.
 *
 * Started once per app launch from the UI (ZenesApp): runs a refresh pass if
 * one is due, then re-checks every 15 minutes for as long as the process (and
 * therefore the composition scope) lives. When the process dies, the next
 * launch catches up — [Store.subAutoLastCheck] makes the schedule persistent.
 *
 * Modes (Store.subAutoUpdate):
 *   off    — never run automatically
 *   launch — at most once per 15 minutes, effectively "on app launch"
 *   1h/6h/24h — fixed interval
 *
 * The provider's own recommendation ("profile-update-interval" header, parsed
 * as SubFetch.updateIntervalHours) is respected as a floor: the effective
 * interval is never shorter than what the provider asks for.
 */
object SubscriptionUpdater {

    private const val TICK_MS = 15 * 60 * 1000L          // loop period
    private const val LAUNCH_THROTTLE_MS = 15 * 60 * 1000L // "launch" mode min gap

    /**
     * Set when the latest pass had failures (the typical case: VPN down and
     * the network drops the provider's host directly). ZenesApp watches it
     * and runs one forced pass as soon as the tunnel connects — the refresh
     * then rides the tunnel and succeeds on censored networks. Cleared by
     * any fully successful pass.
     */
    @Volatile
    var retryOnConnect: Boolean = false
        private set

    fun start(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            runDue(appContext)
            while (true) {
                delay(TICK_MS)
                try {
                    runDue(appContext)
                } catch (t: Throwable) {
                    // The loop must survive anything (prefs corruption, OOM-ish
                    // paths inside a fetch); it is a background convenience.
                    CrashLogger.log("subscription auto-update pass failed", t)
                }
            }
        }
    }

    /**
     * Refreshes every subscription if the configured interval has elapsed.
     *
     * @param force skip the interval check (tunnel-up retry: the user just
     * connected the VPN after a failed pass — refresh now, don't wait a
     * full period for an attempt that will likely fail again without it).
     */
    suspend fun runDue(context: Context, force: Boolean = false) {
        val store = Store(context)
        val mode = store.subAutoUpdate
        if (mode == Store.SUB_AUTO_OFF) return

        val userInterval = when (mode) {
            Store.SUB_AUTO_1H -> 60 * 60 * 1000L
            Store.SUB_AUTO_6H -> 6 * 60 * 60 * 1000L
            Store.SUB_AUTO_24H -> 24 * 60 * 60 * 1000L
            else -> LAUNCH_THROTTLE_MS // launch mode: effectively on app start
        }

        val subs = store.subs()
        if (subs.isEmpty()) return

        // Providers advertise their preferred refresh period in hours; never
        // poll more often than that.
        val providerFloorMs = subs.maxOf { it.updateIntervalHours.toLong() }
            .coerceAtLeast(0L) * 60 * 60 * 1000L
        val effective = maxOf(userInterval, providerFloorMs)

        val now = System.currentTimeMillis()
        if (force) {
            // A retry must not push the regular schedule forward.
        } else {
            if (now - store.subAutoLastCheck < effective) return
            store.subAutoLastCheck = now
        }

        var updated = 0
        var failed = 0
        for (sub in subs) {
            try {
                val result = Importer.importSubscription(context, sub.url)
                if (result.isSuccess) updated++ else failed++
            } catch (_: Throwable) {
                failed++
            }
        }
        CrashLogger.breadcrumb(
            "subscription auto-update: mode=$mode updated=$updated failed=$failed${if (force) " retry" else ""}",
        )
        retryOnConnect = failed > 0
    }

    /** One clean retry after the tunnel comes up (see ZenesApp) — only when the last pass failed. */
    suspend fun retryDueIfFailed(context: Context) {
        if (retryOnConnect) runDue(context, force = true)
    }
}
