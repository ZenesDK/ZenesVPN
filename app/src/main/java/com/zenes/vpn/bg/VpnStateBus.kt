package com.zenes.vpn.bg

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global VPN state shared between the foreground service and the UI.
 */
object VpnStateBus {

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, STOPPING, ERROR }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val uplink = MutableStateFlow(0L)      // bytes/sec
    val downlink = MutableStateFlow(0L)    // bytes/sec
    val uplinkTotal = MutableStateFlow(0L)
    val downlinkTotal = MutableStateFlow(0L)

    /**
     * The engine reports CONNECTED, but the post-connect self-check could not
     * push a request through the tunnel (dead server, broken handshake, DNS
     * blackhole...). Surfaced in the notification so "green but dead" sessions
     * stop looking healthy. Cleared when real traffic flows or on reconnect.
     */
    val tunnelUnreachable = MutableStateFlow(false)

    fun post(state: State, errorMessage: String? = null) {
        _state.value = state
        _error.value = if (state == State.ERROR) errorMessage else null
        if (state != State.CONNECTED) {
            uplink.value = 0
            downlink.value = 0
            uplinkTotal.value = 0
            downlinkTotal.value = 0
        }
        if (state == State.CONNECTING || state == State.DISCONNECTED ||
            state == State.STOPPING
        ) {
            tunnelUnreachable.value = false
        }
    }
}
