package com.zenes.vpn.bg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zenes.vpn.MainActivity
import com.zenes.vpn.R
import com.zenes.vpn.data.Store
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import java.net.InetAddress
import java.net.NetworkInterface as JavaNetworkInterface

class ZenesVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    companion object {
        const val ACTION_START = "com.zenes.vpn.action.START"
        const val ACTION_STOP = "com.zenes.vpn.action.STOP"
        private const val TAG = "ZenesVPN"
        private const val CHANNEL_ID = "zenes_vpn"
        private const val NOTIF_ID = 1
        private const val STATS_INTERVAL_MS = 1000L

        // ---- auto-reconnect (watchdog) ----
        private const val MAX_RETRIES = 5
        private val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000, 15_000, 30_000)
        private const val WATCHDOG_INTERVAL_MS = 5_000L
        private const val HUNG_CONNECTING_MS = 45_000L
        private const val STABLE_RESET_MS = 45_000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ZenesVpnService::class.java).setAction(ACTION_START),
                )
            } catch (t: Throwable) {
                CrashLogger.log("Failed to start VPN service from caller", t)
                VpnStateBus.post(VpnStateBus.State.ERROR, t.message ?: "service start failed")
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, ZenesVpnService::class.java).setAction(ACTION_STOP),
                )
            } catch (t: Throwable) {
                CrashLogger.log("Failed to stop VPN service", t)
            }
        }
    }

    private var commandServer: CommandServer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var shouldStop = false
    @Volatile private var boxStarted = false
    @Volatile private var stopInProgress = false
    @Volatile private var vpnPfd: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ---- auto-reconnect state ----
    @Volatile private var wantsConnected = false
    @Volatile private var retryAttempt = 0
    @Volatile private var retryPending = false
    @Volatile private var lastStartAttemptMs = 0L
    private var connectedSinceMs = 0L
    private var retryRunnable: Runnable? = null

    // ------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action=$action")
        CrashLogger.breadcrumb("service onStartCommand action=$action")

        if (action == ACTION_STOP) {
            // Explicit stop (UI, notification, tile): remember the intent so the
            // watchdog and a sticky restart never resurrect the tunnel.
            wantsConnected = false
            Store(this).serviceWanted = false
            cancelRecovery()
            stopBox()
            return START_NOT_STICKY
        }

        // Must reach the foreground state immediately (Android 14 requirement).
        if (!bringForeground()) return START_NOT_STICKY

        if (intent == null) {
            // START_STICKY recreation after the system killed the process: the
            // tunnel comes back only if the user had it up before the kill.
            wantsConnected = Store(this).serviceWanted
            CrashLogger.breadcrumb("sticky restart: serviceWanted=$wantsConnected")
        } else {
            wantsConnected = true
            Store(this).serviceWanted = true
        }
        if (!wantsConnected) {
            mainHandler.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        if (!boxStarted && !retryPending) {
            retryAttempt = 0
            startBox()
        }
        startWatchdog()
        // START_STICKY: if Android kills the process (OOM, MIUI battery killer),
        // the service is recreated and the tunnel is restored automatically.
        return START_STICKY
    }

    private fun bringForeground(): Boolean {
        val notification = buildNotification(VpnStateBus.state.value)
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
            CrashLogger.breadcrumb("startForeground OK (sdk=${Build.VERSION.SDK_INT})")
            true
        } catch (t: Throwable) {
            CrashLogger.log("startForeground failed", t)
            VpnStateBus.post(VpnStateBus.State.ERROR, t.message ?: "startForeground failed")
            stopSelf()
            false
        }
    }

    private fun startBox() {
        stopInProgress = false
        shouldStop = false
        lastStartAttemptMs = System.currentTimeMillis()
        connectedSinceMs = 0L
        VpnStateBus.post(VpnStateBus.State.CONNECTING)
        updateNotification()
        Thread({
            try {
                val configFile = filesDir.resolve("config.json")
                if (!configFile.exists()) {
                    throw Exception("config.json not found — open the app and press Connect")
                }
                val config = configFile.readText()
                CrashLogger.breadcrumb("config read (${config.length} bytes)")
                val server = Libbox.newCommandServer(this, this)
                commandServer = server
                server.start()
                CrashLogger.breadcrumb("command server started")
                // Blocking call: throws if the box fails to start.
                // NOTE: the second argument MUST be a real OverrideOptions instance —
                // passing null crashes inside libbox with a Go nil-pointer panic
                // (command_server.go StartOrReloadService dereferences options.IncludePackage).
                // Per-app routing lists travel INSIDE config.json ("include_package" /
                // "exclude_package" on the tun inbound, written by ConfigWriter) — the
                // same path sing-box for Android uses for its profiles. They reach
                // TunOptions and are applied to VpnService.Builder in openTun().
                val override = OverrideOptions()
                server.startOrReloadService(config, override)
                if (shouldStop) return@Thread
                boxStarted = true
                CrashLogger.breadcrumb("box started OK")
                VpnStateBus.post(VpnStateBus.State.CONNECTED)
                updateNotification()
                retryAttempt = 0
                connectedSinceMs = System.currentTimeMillis()
                startStatsTicker()
                BoxClient.ensureStarted()
            } catch (t: Throwable) {
                if (shouldStop) return@Thread
                stopStatsTicker()
                CrashLogger.log("Failed to start VPN service", t)
                val message = (t.message ?: t.javaClass.simpleName).take(300)
                cleanupServer()
                boxStarted = false
                try {
                    BoxClient.stop()
                } catch (_: Throwable) {
                }
                // A missing config is a setup error, not a network hiccup —
                // retrying could never succeed. Everything else gets the
                // watchdog treatment instead of dying on the first hiccup.
                val fatal = message.contains("config.json not found")
                if (wantsConnected && !fatal && Store(this).autoReconnect) {
                    scheduleRetry()
                } else {
                    VpnStateBus.post(VpnStateBus.State.ERROR, message)
                    mainHandler.post {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }, "zenes-start").start()
    }

    private fun stopBox() {
        if (stopInProgress) return
        stopInProgress = true
        shouldStop = true
        cancelRecovery()
        CrashLogger.breadcrumb("stopBox requested")
        stopStatsTicker()
        VpnStateBus.post(VpnStateBus.State.STOPPING)
        Thread({
            // Tear the Android VPN interface down FIRST (SFA pattern): closing the
            // ParcelFileDescriptor from establish() immediately removes the VPN
            // network and its status-bar icon, whatever the Go engine does next.
            CrashLogger.breadcrumb("stop: closing tun interface")
            closeTunInterface()
            CrashLogger.breadcrumb("stop: stopping box client")
            try {
                BoxClient.stop()
            } catch (_: Throwable) {
            }
            CrashLogger.breadcrumb("stop: closing engine")
            cleanupServer()
            boxStarted = false
            stopInProgress = false
            CrashLogger.breadcrumb("stop: done")
            VpnStateBus.post(VpnStateBus.State.DISCONNECTED)
            mainHandler.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "zenes-stop").start()
    }

    private fun closeTunInterface() {
        val pfd = vpnPfd
        vpnPfd = null
        try {
            pfd?.close()
        } catch (_: Throwable) {
        }
    }

    private fun cleanupServer() {
        val server = commandServer
        commandServer = null
        if (server == null) return
        try {
            server.closeService()
        } catch (_: Throwable) {
        }
        try {
            server.close()
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        cancelRecovery()
        stopStatsTicker()
        if (boxStarted || commandServer != null) {
            // Service killed by the system while running — clean up best effort.
            try {
                cleanupServer()
            } catch (_: Throwable) {
            }
        }
        // Never leak the VPN interface if the service is destroyed.
        closeTunInterface()
        networkCallback?.let { cb ->
            try {
                connectivity().unregisterNetworkCallback(cb)
            } catch (_: Throwable) {
            }
        }
        networkCallback = null
        super.onDestroy()
    }

    override fun onRevoke() {
        // The VPN was revoked outside the app (quick settings tile, another VPN
        // app, system settings). Stop the engine so it does not keep running
        // with a dead interface. Revocation means somebody took over — do not
        // fight for the tunnel, mark it as unwanted.
        CrashLogger.breadcrumb("onRevoke — VPN revoked by system")
        wantsConnected = false
        Store(this).serviceWanted = false
        cancelRecovery()
        stopBox()
        super.onRevoke()
    }

    // ------------------------------------------------------------ notification

    // Refreshes the foreground notification once per second while connected,
    // so the speed / session-traffic counters stay live (libbox pushes status
    // updates into VpnStateBus every second via BoxClient).
    private val statsTicker = object : Runnable {
        override fun run() {
            if (VpnStateBus.state.value != VpnStateBus.State.CONNECTED) return
            updateNotification()
            mainHandler.postDelayed(this, STATS_INTERVAL_MS)
        }
    }

    private fun startStatsTicker() {
        mainHandler.removeCallbacks(statsTicker)
        mainHandler.post(statsTicker)
    }

    private fun stopStatsTicker() {
        mainHandler.removeCallbacks(statsTicker)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: VpnStateBus.State): Notification {
        val contentPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPi = PendingIntent.getService(
            this,
            1,
            Intent(this, ZenesVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_zenes)
            .setContentTitle("ZenesVPN")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notif_disconnect), stopPi)

        when (state) {
            VpnStateBus.State.CONNECTED -> {
                // Live speeds + session totals (updated every second by statsTicker).
                val up = Libbox.formatBytes(VpnStateBus.uplink.value) + "/s"
                val down = Libbox.formatBytes(VpnStateBus.downlink.value) + "/s"
                val upTotal = Libbox.formatBytes(VpnStateBus.uplinkTotal.value)
                val downTotal = Libbox.formatBytes(VpnStateBus.downlinkTotal.value)
                builder.setContentText(getString(R.string.notif_protected_stats, down, up))
                builder.setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        getString(R.string.notif_connected) + "\n" +
                            getString(R.string.notif_speed_line, down, up) + "\n" +
                            getString(R.string.notif_session_traffic, downTotal, upTotal),
                    ),
                )
            }
            VpnStateBus.State.CONNECTING, VpnStateBus.State.STOPPING ->
                if (state == VpnStateBus.State.CONNECTING && retryPending && retryAttempt > 0) {
                    builder.setContentText(
                        getString(R.string.notif_reconnecting, retryAttempt, MAX_RETRIES),
                    )
                } else {
                    builder.setContentText(getString(R.string.notif_connecting))
                }
            VpnStateBus.State.ERROR ->
                builder.setContentText(getString(R.string.notif_error, VpnStateBus.error.value ?: ""))
            else ->
                builder.setContentText(getString(R.string.notif_disconnected))
        }
        return builder.build()
    }

    private fun updateNotification() {
        try {
            NotificationManagerCompat.from(this)
                .notify(NOTIF_ID, buildNotification(VpnStateBus.state.value))
        } catch (_: SecurityException) {
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ CommandServerHandler

    override fun serviceStop() {
        // The engine itself asked to stop (fatal internal error). If the user
        // still wants the tunnel, treat it as a drop and let the reconnect
        // machinery bring it back instead of dying quietly.
        if (wantsConnected && Store(this).autoReconnect) {
            CrashLogger.breadcrumb("engine requested stop — treating as drop")
            stopStatsTicker()
            forceEngineCleanup()
            scheduleRetry()
        } else {
            mainHandler.post { stopBox() }
        }
    }

    override fun serviceReload() {
        // not used
    }

    override fun getSystemProxyStatus(): io.nekohasekai.libbox.SystemProxyStatus {
        // libbox dereferences the returned object (command_server.go platformHandler.
        // SystemProxyStatus reads status.Enabled/status.Available) — returning null
        // would be a native crash. This app never enables a system proxy.
        return io.nekohasekai.libbox.SystemProxyStatus().apply {
            available = false
            enabled = false
        }
    }

    override fun setSystemProxyEnabled(enabled: Boolean) {
        // not used
    }

    override fun triggerNativeCrash() {
        throw UnsupportedOperationException("native crash reporting is disabled")
    }

    override fun writeDebugMessage(message: String) {
        Log.d(TAG, "libbox: $message")
    }

    override fun connectSSHAgent(): Int {
        throw UnsupportedOperationException("ssh agent is not supported")
    }

    // ------------------------------------------------------------ PlatformInterface

    override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport? {
        // Generated configs never use the "local" DNS type.
        return null
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!protect(fd)) {
            throw Exception("protect(fd) failed")
        }
    }

    override fun openTun(options: TunOptions): Int {
        CrashLogger.breadcrumb("openTun called")
        val builder = Builder()
        builder.setSession("ZenesVPN")
        builder.setMtu(options.mtu)

        var hasV4 = false
        var hasV6 = false
        val it4addr = options.inet4Address
        while (it4addr.hasNext()) {
            val p = it4addr.next()
            builder.addAddress(p.address(), p.prefix())
            hasV4 = true
        }
        val it6addr = options.inet6Address
        while (it6addr.hasNext()) {
            val p = it6addr.next()
            builder.addAddress(p.address(), p.prefix())
            hasV6 = true
        }
        if (!hasV4) builder.addAddress("172.19.0.1", 30)
        if (!hasV6) builder.addAddress("fdfe:dcba:9876::1", 126)

        // Per-app lists must be read FIRST: the DNS choice below depends on
        // whether split tunneling is active.
        val includePkgs = mutableListOf<String>()
        val includeIt = options.includePackage
        while (includeIt.hasNext()) includePkgs.add(includeIt.next())
        val excludePkgs = mutableListOf<String>()
        val excludeIt = options.excludePackage
        while (excludeIt.hasNext()) excludePkgs.add(excludeIt.next())
        val perAppActive = includePkgs.isNotEmpty() || excludePkgs.isNotEmpty()

        // DNS. sing-tun reports the tun-internal address (the one next to the tun
        // gateway, e.g. 172.19.0.2) as the system DNS so that DNS queries entering
        // the tun get hijacked. That is NOT safe in per-app mode: the system DNS
        // resolver daemon is not among the allowed packages, and on some Android
        // builds its queries follow the daemon's own uid rules instead of the
        // requesting app's — a tun-internal DNS then becomes a blackhole and every
        // allowed app loses name resolution ("selected apps don't work through
        // VPN"). Public anycast DNS works in both worlds: queries either enter the
        // tun (sniff + hijack-dns answer them through the proxy) or go out through
        // the underlying network and resolve anyway. Full-VPN mode keeps the
        // tun-internal DNS: there the resolver's packets always enter the tun.
        if (perAppActive) {
            builder.addDnsServer("1.1.1.1")
            CrashLogger.breadcrumb("openTun DNS: 1.1.1.1 (per-app mode, public resolver)")
        } else {
            var dnsAdded = false
            try {
                val dnsServers: StringIterator? = options.dnsServerAddress
                if (dnsServers != null) {
                    while (dnsServers.hasNext()) {
                        val addr = dnsServers.next()
                        if (addr.isNotEmpty()) {
                            builder.addDnsServer(addr)
                            dnsAdded = true
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            if (!dnsAdded) {
                builder.addDnsServer("1.1.1.1")
                CrashLogger.breadcrumb("openTun DNS: 1.1.1.1 (fallback, tun options had none)")
            } else {
                CrashLogger.breadcrumb("openTun DNS: taken from tun options")
            }
        }

        var routed = false
        val it4route = options.inet4RouteRange
        while (it4route.hasNext()) {
            val p = it4route.next()
            builder.addRoute(p.address(), p.prefix())
            routed = true
        }
        val it6route = options.inet6RouteRange
        while (it6route.hasNext()) {
            val p = it6route.next()
            builder.addRoute(p.address(), p.prefix())
            routed = true
        }
        if (!routed) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            val it4ex = options.inet4RouteExcludeAddress
            while (it4ex.hasNext()) {
                val p = it4ex.next()
                builder.excludeRoute(android.net.IpPrefix(InetAddress.getByName(p.address()), p.prefix()))
            }
            val it6ex = options.inet6RouteExcludeAddress
            while (it6ex.hasNext()) {
                val p = it6ex.next()
                builder.excludeRoute(android.net.IpPrefix(InetAddress.getByName(p.address()), p.prefix()))
            }
        }

        // Per-app routing: Android applies the include/exclude lists on the VPN
        // builder itself. The two modes are mutually exclusive in the VpnService
        // API — include wins if both are somehow present. Every add is guarded:
        // a package uninstalled since selection would throw and abort the whole
        // connection otherwise. Results are mirrored into the app-visible log:
        // logcat-only warnings made "why doesn't per-app work" undiagnosable.
        if (includePkgs.isNotEmpty()) {
            // SFA parity: the VPN app itself must stay on its own VPN network in
            // include mode — sing-box for Android always appends its own package
            // to the include list (and removes it from the exclude list).
            if (!includePkgs.contains(packageName)) includePkgs.add(packageName)
        }
        var includeApplied = 0
        for (pkg in includePkgs) {
            try {
                builder.addAllowedApplication(pkg)
                includeApplied++
            } catch (t: Throwable) {
                Log.w(TAG, "addAllowedApplication($pkg) failed: ${t.message}")
                CrashLogger.breadcrumb("per-app allow FAILED: $pkg (${t.message})")
            }
        }
        var excludeApplied = 0
        if (includeApplied == 0) {
            for (pkg in excludePkgs) {
                try {
                    builder.addDisallowedApplication(pkg)
                    excludeApplied++
                } catch (t: Throwable) {
                    Log.w(TAG, "addDisallowedApplication($pkg) failed: ${t.message}")
                    CrashLogger.breadcrumb("per-app deny FAILED: $pkg (${t.message})")
                }
            }
        }
        CrashLogger.breadcrumb(
            "openTun per-app applied: include=$includeApplied/${includePkgs.size}" +
                (if (excludePkgs.isNotEmpty()) " deny=$excludeApplied/${excludePkgs.size}" else ""),
        )
        if (includePkgs.isNotEmpty()) {
            CrashLogger.breadcrumb("per-app allow list: ${includePkgs.joinToString(",")}")
        }

        if (options.strictRoute && Build.VERSION.SDK_INT >= 29) {
            builder.setBlocking(true)
        }

        // Keep the ParcelFileDescriptor and do NOT detach the fd (SFA pattern):
        // the service keeps ownership of the handle, so stop()/onDestroy() can
        // tear the VPN interface down immediately, even if the Go engine is
        // unresponsive or slow to close the fd itself.
        val pfd = builder.establish()
            ?: throw Exception("establish() failed — VPN permission missing or revoked")
        closeTunInterface() // safety: replace any leftover previous interface
        vpnPfd = pfd
        val fd = pfd.fd
        Log.i(TAG, "tun device created, fd=$fd")
        CrashLogger.breadcrumb("openTun OK fd=$fd")
        return fd
    }

    override fun useProcFS(): Boolean = false

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): io.nekohasekai.libbox.ConnectionOwner {
        throw UnsupportedOperationException("connection owner lookup is not supported")
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                reportDefaultInterface(listener, network)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                reportDefaultInterface(listener, network)
            }
        }
        networkCallback = cb
        try {
            connectivity().registerNetworkCallback(request, cb)
        } catch (t: Throwable) {
            CrashLogger.log("registerNetworkCallback failed", t)
            throw t
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkCallback?.let { cb ->
            try {
                connectivity().unregisterNetworkCallback(cb)
            } catch (_: Throwable) {
            }
        }
        networkCallback = null
    }

    private fun reportDefaultInterface(listener: InterfaceUpdateListener, network: Network) {
        try {
            val linkProperties = connectivity().getLinkProperties(network) ?: return
            val name = linkProperties.interfaceName ?: return
            val index = try {
                JavaNetworkInterface.getByName(name)?.index ?: 0
            } catch (_: Throwable) {
                0
            }
            val caps = connectivity().getNetworkCapabilities(network)
            val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
            listener.updateDefaultInterface(name, index, expensive, false)
        } catch (_: Throwable) {
        }
    }

    override fun getInterfaces(): io.nekohasekai.libbox.NetworkInterfaceIterator {
        val items = mutableListOf<LibboxNetworkInterface>()
        val enumeration = JavaNetworkInterface.getNetworkInterfaces()
        if (enumeration != null) {
            for (jif in enumeration) {
                try {
                    val item = LibboxNetworkInterface()
                    item.index = jif.index
                    item.mtu = try { jif.mtu } catch (_: Throwable) { 0 }
                    item.name = jif.name
                    // libbox parses every address with netip.MustParsePrefix, so each
                    // entry MUST be in CIDR form "addr/prefixLen" — a bare address
                    // panics the Go side ("no '/'").
                    val addresses = jif.interfaceAddresses.mapNotNull { ia ->
                        val host = ia.address?.hostAddress?.split('%')?.firstOrNull()
                        if (host.isNullOrEmpty()) return@mapNotNull null
                        val prefixLen = ia.networkPrefixLength.toInt()
                        if (prefixLen < 0) return@mapNotNull null
                        "$host/$prefixLen"
                    }.distinct()
                    item.addresses = LibStringIterator(addresses)
                    item.flags = if (jif.isUp) 1 else 0
                    item.type = Libbox.InterfaceTypeOther
                    item.metered = false
                    items.add(item)
                } catch (_: Throwable) {
                }
            }
        }
        return LibNetworkInterfaceIterator(items)
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): io.nekohasekai.libbox.WIFIState? {
        // Returning null keeps libbox using the default network state.
        return null
    }

    override fun clearDNSCache() {
        // nothing to flush
    }

    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) {
        // libbox notifications (e.g. crash reports) are ignored
    }

    override fun cancelNotification(identifier: String, typeID: Int) {
        // ignored
    }

    override fun startNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener) {
        // Neighbor (ARP/NDP) monitoring is not supported. A no-op is safe: libbox
        // logs the error and continues without neighbor resolution.
    }

    override fun closeNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener) {
        // ignored
    }

    override fun registerMyInterface(name: String) {
        // not used
    }

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        throw UnsupportedOperationException("platform shell is not supported")
    }

    override fun openShellSession(
        user: io.nekohasekai.libbox.PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): io.nekohasekai.libbox.ShellSession {
        throw UnsupportedOperationException("shell is not supported")
    }

    override fun lookupUser(username: String?): io.nekohasekai.libbox.PlatformUser? = null

    override fun lookupSFTPServer(): String {
        throw UnsupportedOperationException("sftp is not supported")
    }

    override fun readSystemSSHHostKey(): String? = null

    override fun tailscaleHostname(): String = ""

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: io.nekohasekai.libbox.BridgeOptions?): io.nekohasekai.libbox.BridgeSession {
        throw UnsupportedOperationException("bridge is not supported")
    }

    // ------------------------------------------------------------ auto-reconnect watchdog

    /**
     * Periodic health check while the user wants the VPN up. Restores the
     * tunnel after an unexpected drop: engine death, a failed start or a hung
     * CONNECTING state. A connection that stays up for a while resets the
     * attempt counter, so a bad network hour does not burn the budget forever.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!wantsConnected) return
            when (VpnStateBus.state.value) {
                VpnStateBus.State.CONNECTED -> {
                    if (connectedSinceMs == 0L) connectedSinceMs = System.currentTimeMillis()
                    if (System.currentTimeMillis() - connectedSinceMs > STABLE_RESET_MS && retryAttempt != 0) {
                        retryAttempt = 0
                        CrashLogger.breadcrumb("connection stable — retry counter reset")
                    }
                }
                VpnStateBus.State.CONNECTING -> {
                    connectedSinceMs = 0L
                    val hung = System.currentTimeMillis() - lastStartAttemptMs > HUNG_CONNECTING_MS
                    if (hung && !retryPending && !stopInProgress) {
                        CrashLogger.breadcrumb("watchdog: CONNECTING is hung, forcing engine restart")
                        forceEngineCleanup()
                        scheduleRetry()
                    }
                }
                VpnStateBus.State.ERROR -> {
                    connectedSinceMs = 0L
                    if (!retryPending && !stopInProgress) {
                        CrashLogger.breadcrumb("watchdog: ERROR state, scheduling reconnect")
                        scheduleRetry()
                    }
                }
                else -> connectedSinceMs = 0L
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun startWatchdog() {
        mainHandler.removeCallbacks(watchdog)
        mainHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun scheduleRetry() {
        if (!wantsConnected || retryPending || stopInProgress) return
        if (retryAttempt >= MAX_RETRIES) {
            CrashLogger.breadcrumb("auto-reconnect: attempts exhausted")
            VpnStateBus.post(VpnStateBus.State.ERROR, getString(R.string.err_reconnect_failed))
            mainHandler.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        val attempt = ++retryAttempt
        val delay = RETRY_DELAYS_MS[attempt - 1]
        retryPending = true
        CrashLogger.breadcrumb("auto-reconnect attempt $attempt/$MAX_RETRIES in ${delay}ms")
        updateNotification() // shows "Reconnecting (N of M)…"
        val r = Runnable {
            retryPending = false
            retryRunnable = null
            if (wantsConnected && !boxStarted && !stopInProgress) startBox()
        }
        retryRunnable = r
        mainHandler.postDelayed(r, delay)
    }

    private fun cancelRecovery() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
        retryPending = false
        mainHandler.removeCallbacks(watchdog)
    }

    /** Tears the engine down without touching state posts (used before a retry). */
    private fun forceEngineCleanup() {
        closeTunInterface()
        try {
            BoxClient.stop()
        } catch (_: Throwable) {
        }
        cleanupServer()
        boxStarted = false
    }

    // ------------------------------------------------------------ helpers

    private fun connectivity(): ConnectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
}
