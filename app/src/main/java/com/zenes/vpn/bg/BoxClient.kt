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
 */
object BoxClient : CommandClientHandler {

    private var client: CommandClient? = null
    private val lock = Any()

    fun ensureStarted() {
        synchronized(lock) {
            if (client != null) return
            val options = CommandClientOptions()
            // Interval is a Go time.Duration (nanoseconds): 1 second.
            options.statusInterval = 1_000_000_000L
            options.addCommand(Libbox.CommandStatus)
            val c = Libbox.newCommandClient(this, options)
            client = c
            thread(name = "zenes-box-client") {
                try {
                    c.connect()
                } catch (t: Throwable) {
                    // Server not running yet is a normal situation after stop.
                    if (VpnStateBus.state.value == VpnStateBus.State.CONNECTED) {
                        CrashLogger.log("Command client disconnected", t)
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            val c = client ?: return
            client = null
            thread(name = "zenes-box-client-stop") {
                try {
                    c.disconnect()
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun writeStatus(message: StatusMessage) {
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
