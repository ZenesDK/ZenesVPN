package com.zenes.vpn.bg

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.zenes.vpn.MainActivity
import com.zenes.vpn.R
import com.zenes.vpn.core.ConfigWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Quick settings tile: one tap toggles the VPN tunnel.
 *
 * State is read from [VpnStateBus] (same process as the VPN service, so it is
 * always live while the tunnel exists). Starting the tunnel works even from a
 * cold process: the config is built + validated right here, and the VPN consent
 * is normally already granted. When the consent dialog is needed, the user is
 * routed into MainActivity which finishes the connect flow.
 */
class QuickTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        render()
        stateJob?.cancel()
        stateJob = scope.launch {
            VpnStateBus.state.collect { render() }
        }
    }

    override fun onStopListening() {
        stateJob?.cancel()
        stateJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        when (VpnStateBus.state.value) {
            VpnStateBus.State.CONNECTED, VpnStateBus.State.CONNECTING -> disconnect()
            VpnStateBus.State.STOPPING -> Unit // already going down
            else -> connect()
        }
    }

    // ------------------------------------------------------------ actions

    private fun connect() {
        try {
            val context = applicationContext
            // Build + validate the config (with per-app routing lists) right here
            // so the tile works even when the app process is dead or the user
            // changed the server in the app without pressing Connect afterwards.
            val writeError = ConfigWriter.write(context)
            if (writeError != null) {
                // "no server" or config validation failed — the app surfaces it.
                openApp()
                return
            }
            val prepare = try {
                VpnService.prepare(context)
            } catch (_: Throwable) {
                null
            }
            if (prepare != null) {
                // VPN consent must be granted from an activity; the app finishes the flow.
                openApp()
                return
            }
            VpnStateBus.post(VpnStateBus.State.CONNECTING)
            ZenesVpnService.start(context)
        } catch (t: Throwable) {
            CrashLogger.log("tile: connect failed", t)
            openApp()
        }
    }

    private fun disconnect() {
        try {
            VpnStateBus.post(VpnStateBus.State.STOPPING)
            ZenesVpnService.stop(applicationContext)
        } catch (_: Throwable) {
            VpnStateBus.post(VpnStateBus.State.DISCONNECTED)
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_AUTO_CONNECT, true)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ rendering

    private fun render() {
        val tile = qsTile ?: return
        val state = VpnStateBus.state.value
        tile.state = when (state) {
            VpnStateBus.State.CONNECTED, VpnStateBus.State.CONNECTING -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (state) {
                VpnStateBus.State.CONNECTING -> getString(R.string.state_connecting)
                VpnStateBus.State.CONNECTED -> getString(R.string.state_connected)
                VpnStateBus.State.STOPPING -> getString(R.string.state_stopping)
                VpnStateBus.State.ERROR -> getString(R.string.state_error)
                else -> getString(R.string.state_disconnected)
            }
        }
        tile.updateTile()
    }
}
