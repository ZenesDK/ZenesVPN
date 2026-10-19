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

    fun ensureStarted() {
        synchronized(lock) {
            if (client != null) return
            val options = CommandClientOptions()
            // Interval is a Go time.Duration (nanoseconds): 1 second.
            options.statusInterval = 1_000_000_000L
            options.addCommand(Libbox.CommandStatus)
            val c = Libbox.newCommandClient(this, options)
            client = c
            statsFlowLogged = false
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
            CrashLogger.breadcrumb(
                "stats flowing: down=" + Libbox.formatBytes(message.downlinkTotal) +
                    " up=" + Libbox.formatBytes(message.uplinkTotal),
            )
        }
        VpnStateBus.uplink.value = message.uplink
        VpnStateBus.downlink.value = message.downlink
        VpnStateBus.uplinkTotal.value = message.uplinkTotal
        VpnStateBus.downlinkTotal.value = message.downlinkTotal
    }

    override fun connected() {}
    override fun disconnected(message: String) {}
    override fun setDefaultLogLevel(level: Int) {}
    override fun clearLogs() {}
    override fun writeLogs(messageList: LogIterator) {}
    override fun writeGroups(message: OutboundGroupIterator) {}
    override fun writeOutbounds(message: OutboundGroupItemIterator) {}
    override fun initializeClashMode(modeList: StringIterator, currentMode: String) {}
    override fun updateClashMode(newMode: String) {}
    override fun writeConnectionEvents(events: ConnectionEvents) {}
}
