package com.zenes.vpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.zenes.vpn.bg.BoxClient
import com.zenes.vpn.bg.CrashLogger
import com.zenes.vpn.bg.ZenesVpnService
import com.zenes.vpn.bg.VpnStateBus
import com.zenes.vpn.core.SbConfig
import com.zenes.vpn.data.Store
import com.zenes.vpn.ui.ZenesApp
import com.zenes.vpn.ui.theme.ZenesTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            launchService()
        } else {
            VpnStateBus.post(VpnStateBus.State.ERROR, getString(R.string.vpn_permission_denied))
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZenesTheme {
                ZenesApp(
                    onConnectToggle = { onConnectToggle() },
                )
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Launched from the Quick Settings tile: finish the connect flow here.
        maybeAutoConnect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeAutoConnect(intent)
    }

    private fun maybeAutoConnect(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) == true) {
            connect()
        }
    }

    override fun onResume() {
        super.onResume()
        BoxClient.ensureStarted()
    }

    /** Called from the UI power button. */
    private fun onConnectToggle() {
        if (VpnStateBus.state.value == VpnStateBus.State.CONNECTED ||
            VpnStateBus.state.value == VpnStateBus.State.CONNECTING
        ) {
            disconnect()
        } else {
            connect()
        }
    }

    private fun connect() {
        try {
            val store = Store(this)
            val server = store.selectedServer()
            if (server == null) {
                VpnStateBus.post(VpnStateBus.State.ERROR, getString(R.string.no_server))
                return
            }
            // Build + validate the config BEFORE touching the VPN permission dialog.
            val config = SbConfig.build(server)
            File(filesDir, "config.json").writeText(config)
            CrashLogger.breadcrumb("connect: config built (${config.length} bytes)")
            try {
                LibboxCheck.check(config)
            } catch (t: Throwable) {
                CrashLogger.log("Config validation failed", t)
                VpnStateBus.post(
                    VpnStateBus.State.ERROR,
                    (t.message ?: "invalid config").take(300),
                )
                return
            }
            CrashLogger.breadcrumb("connect: checkConfig OK")
            val prepare = try {
                VpnService.prepare(this)
            } catch (t: Throwable) {
                CrashLogger.log("VpnService.prepare failed", t)
                null
            }
            if (prepare != null) {
                vpnPermissionLauncher.launch(prepare)
            } else {
                launchService()
            }
        } catch (t: Throwable) {
            CrashLogger.log("connect() failed", t)
            VpnStateBus.post(VpnStateBus.State.ERROR, t.message ?: "connect failed")
        }
    }

    private fun launchService() {
        try {
            CrashLogger.breadcrumb("connect: launching VPN service")
            VpnStateBus.post(VpnStateBus.State.CONNECTING)
            BoxClient.ensureStarted()
            ZenesVpnService.start(this)
        } catch (t: Throwable) {
            CrashLogger.log("launchService failed", t)
            VpnStateBus.post(VpnStateBus.State.ERROR, t.message ?: "service start failed")
        }
    }

    private fun disconnect() {
        try {
            VpnStateBus.post(VpnStateBus.State.STOPPING)
            ZenesVpnService.stop(this)
        } catch (t: Throwable) {
            CrashLogger.log("disconnect() failed", t)
            VpnStateBus.post(VpnStateBus.State.DISCONNECTED)
        }
    }

    /** Small indirection so the check call is always guarded. */
    private object LibboxCheck {
        fun check(config: String) {
            io.nekohasekai.libbox.Libbox.checkConfig(config)
        }
    }

    companion object {
        /** QuickTileService routes the user here when the consent dialog or UI feedback is needed. */
        const val EXTRA_AUTO_CONNECT = "com.zenes.vpn.extra.AUTO_CONNECT"

        fun startServiceCompat(context: android.content.Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ZenesVpnService::class.java).setAction(ZenesVpnService.ACTION_START),
            )
        }
    }
}
