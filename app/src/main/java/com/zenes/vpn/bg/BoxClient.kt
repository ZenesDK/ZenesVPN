package com.zenes.vpn.bg

import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.StatusMessage
import com.zenes.vpn.data.StatsStore
import kotlin.concurrent.thread

/**
 * Connects to the libbox command server over its local unix socket and
 * streams traffic statistics into [VpnStateBus].
 *
 * Lifecycle note (the "speed always 0 kb/s" bug): the command server only
 * exists while the VPN service is running, but ensureStarted() is called much
 * earlier (App/MainActivity launch, launchService). libbox retries its initial
 * dial for ~3.5 s and then gives up, and the client object kept us from ever
 * trying again — so in the normal "open app, then press Connect" flow the
 * status stream never latched on and uplink/downlink/totals stayed 0 for the
 * whole session. The worker below now retries while the VPN is connecting or
 * connected, and disposes of the client when it gives up so that the next
 * ensureStarted() call can start fresh.
 */
object BoxClient : CommandClientHandler {

    private var client: CommandClient? = null
    private var worker: Thread? = null
    private val lock = Any()

    /** One-shot diagnostics: first status message that actually carries traffic. */
    @Volatile
    private var statsFlowLogged = false

    // ---- engine log gate (writeLogs) ----
    // libbox level ints (sing-box log package): 0 FATAL, 1 PANIC, 2 ERROR,
    // 3 WARN, 4 INFO, 5 DEBUG, 6 TRACE. Errors/warnings are what explains a
    // dead tunnel, so they get a deep budget; INFO is throttled hard (every
    // accepted connection logs one INFO line — a file download alone would
    // otherwise flood crash.log).
    private const val LOG_WINDOW_MS = 10_000L
    private const val LOG_BUDGET_BAD = 150
    private const val LOG_BUDGET_INFO = 30
    private val logGate = Any()
    private var logWindowStart = 0L
    private var logBudgetBad = LOG_BUDGET_BAD
    private var logBudgetInfo = LOG_BUDGET_INFO

    private fun gateLog(level: Int): Boolean {
        synchronized(logGate) {
            val now = System.currentTimeMillis()
            if (now - logWindowStart > LOG_WINDOW_MS) {
                logWindowStart = now
                logBudgetBad = LOG_BUDGET_BAD
                logBudgetInfo = LOG_BUDGET_INFO
            }
            return if (level <= 3) {
                if (logBudgetBad > 0) { logBudgetBad--; true } else false
            } else {
                if (logBudgetInfo > 0) { logBudgetInfo--; true } else false
            }
        }
    }

    fun ensureStarted() {
        synchronized(lock) {
            if (client != null) return
            val options = CommandClientOptions()
            // Interval is a Go time.Duration (nanoseconds): 1 second.
            options.statusInterval = 1_000_000_000L
            // CommandLog: stream the engine's own log (dial errors, TLS/Reality
            // handshake failures, DNS failures...) into the diagnostics log.
            // Without it a broken outbound looks like a healthy "CONNECTED" —
            // the engine knows why traffic is dead, we just never listened.
            options.addCommand(Libbox.CommandLog)
            options.addCommand(Libbox.CommandStatus)
            val c = Libbox.newCommandClient(this, options)
            client = c
            statsFlowLogged = false
            synchronized(logGate) { logWindowStart = 0L; logBudgetBad = LOG_BUDGET_BAD; logBudgetInfo = LOG_BUDGET_INFO }
            worker = thread(name = "zenes-box-client") {
                while (true) {
                    try {
                        // Blocks until the dial succeeds (streams dispatched) or
                        // the retry window (~3.5 s) is exhausted.
                        c.connect()
                        CrashLogger.breadcrumb("command client connected (stats stream up)")
                        return@thread
                    } catch (t: Throwable) {
                        val state = VpnStateBus.state.value
                        val keepWaiting = state == VpnStateBus.State.CONNECTED ||
                            state == VpnStateBus.State.CONNECTING
                        if (!keepWaiting) break
                        // VPN is up but the socket was not there yet — retry;
                        // the service (re)creates the command server on connect.
                    }
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                // Give up: dispose so the next ensureStarted() starts fresh.
                synchronized(lock) {
                    if (client === c) {
                        client = null
                        worker = null
                    }
                }
                try {
                    c.disconnect()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** True once any session status carried nonzero traffic (tunnel proven alive). */
    fun statsEverFlowed(): Boolean = statsFlowLogged

    fun stop() {
        synchronized(lock) {
            worker?.interrupt()
            worker = null
            val c = client ?: return
            client = null
            statsFlowLogged = false
            thread(name = "zenes-box-client-stop") {
                try {
                    c.disconnect()
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun writeStatus(message: StatusMessage) {
        if (!statsFlowLogged && (message.uplinkTotal > 0 || message.downlinkTotal > 0)) {
            statsFlowLogged = true
            VpnStateBus.tunnelUnreachable.value = false // real traffic beats the self-check verdict
            CrashLogger.breadcrumb(
                "stats flowing: down=" + Libbox.formatBytes(message.downlinkTotal) +
                    " up=" + Libbox.formatBytes(message.uplinkTotal),
            )
        }
        VpnStateBus.uplink.value = message.uplink
        VpnStateBus.downlink.value = message.downlink
        VpnStateBus.uplinkTotal.value = message.uplinkTotal
        VpnStateBus.downlinkTotal.value = message.downlinkTotal
        // Daily/server traffic accounting (deltas of the session totals).
        StatsStore.accumulate(message.uplinkTotal, message.downlinkTotal)
    }

    override fun connected() {}
    override fun disconnected(message: String) {}
    override fun setDefaultLogLevel(level: Int) {}
    override fun clearLogs() {}

    override fun writeLogs(messageList: LogIterator) {
        try {
            while (messageList.hasNext()) {
                val entry = messageList.next()
                val message = entry.message ?: continue
                if (!gateLog(entry.level)) continue
                CrashLogger.breadcrumb("box: " + message.take(220))
            }
        } catch (_: Throwable) {
            // log streaming must never take the stats stream down
        }
    }
    override fun writeGroups(message: OutboundGroupIterator) {}
    override fun writeOutbounds(message: OutboundGroupItemIterator) {}
    override fun initializeClashMode(modeList: StringIterator, currentMode: String) {}
    override fun updateClashMode(newMode: String) {}
    override fun writeConnectionEvents(events: ConnectionEvents) {}
}
