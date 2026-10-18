package com.zenes.vpn.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zenes.vpn.data.Store

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            val store = Store(context)
            if (!store.autostart) return
            if (store.selectedServer() == null) return
            ZenesVpnService.start(context)
        } catch (t: Throwable) {
            CrashLogger.log("Autostart failed", t)
        }
    }
}
