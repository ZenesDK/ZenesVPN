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
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
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
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface as JavaNetworkInterface
import java.net.URL

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

        /** Green-but-dead tunnel: self-check failed and no stats flowed — how long
         *  to wait after connect before the watchdog restarts the engine once. */
        private const val DEAD_TUNNEL_GRACE_MS = 30_000L

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

    /**
     * Default-interface monitor state. Tracks ONLY the system's best network
     * (SFA/shadowsocks approach). The old implementation used a plain
     * registerNetworkCallback, which fires for EVERY internet-capable network:
     * on phones with dual Wi-Fi + cellular (OriginOS dual-band boost and
     * friends) every onAvailable/onLinkPropertiesChanged was reported to the
     * engine as "the default interface changed", so sing-box re-dialled its
     * outbound connections (QUIC/hysteria2 especially) in a storm and could
     * settle on a blocked cellular interface — a green tunnel that passed no
     * traffic. registerDefaultNetworkCallback is not an option either: since
     * Android P it reports the VPN interface while our own tunnel is up.
     */
    @Volatile private var interfaceListener: InterfaceUpdateListener? = null
    @Volatile private var defaultNetwork: Network? = null
    private var defaultNetworkRegistered = false

    // Dedupe guard: the engine re-reads our whole interface list on EVERY
    // report, and OriginOS fires onCapabilitiesChanged continuously while
    // re-validating its dual-Wi-Fi setup. Re-reporting the same interface
    // would only burn CPU on JNI roundtrips and (on a reload) races the
    // fresh listener — so remember the last accepted report.
    @Volatile private var lastReportedName: String? = null
    @Volatile private var lastReportedIndex: Int = Int.MIN_VALUE
    private val netmonHandler by lazy {
        val ht = HandlerThread("zenes-netmon")
        ht.start()
        Handler(ht.looper)
    }

    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            defaultNetwork = network
            reportDefaultInterface(network)
        }

        override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
            if (defaultNetwork === network) reportDefaultInterface(network)
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            if (defaultNetwork === network) reportDefaultInterface(network)
        }

        override fun onLost(network: Network) {
            if (defaultNetwork === network) {
                defaultNetwork = null
                reportNoDefaultInterface()
            }
        }
    }

    // ---- auto-reconnect state ----
    @Volatile private var wantsConnected = false
    @Volatile private var retryAttempt = 0
    @Volatile private var retryPending = false
    @Volatile private var lastStartAttemptMs = 0L
    private var connectedSinceMs = 0L
    private var retryRunnable: Runnable? = null

    /**
     * Whether the app's own traffic actually rides the tunnel for the current
     * session (include list contains us, exclude list does not, or no per-app
     * mode). Computed in openTun(); a bypassing app would make the self-check
     * probe the physical network and report a verdict about nothing.
     */
    @Volatile private var selfCheckInTunnel = true

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
                // Fresh session — the new self-check run owns the verdict now.
                VpnStateBus.tunnelUnreachable.value = false
                updateNotification()
                retryAttempt = 0
                connectedSinceMs = System.currentTimeMillis()
                startStatsTicker()
                BoxClient.ensureStarted()
                startTunnelSelfCheck()
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
        if (defaultNetworkRegistered) {
            try {
                connectivity().unregisterNetworkCallback(defaultNetworkCallback)
            } catch (_: Throwable) {
            }
            defaultNetworkRegistered = false
        }
        interfaceListener = null
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
                if (VpnStateBus.tunnelUnreachable.value) {
                    // Engine says CONNECTED but the self-check could not get a
                    // byte through (and no user traffic did either). Say so —
                    // a green notification that cannot browse is a lie.
                    builder.setContentText(getString(R.string.notif_tunnel_dead))
                    builder.setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            getString(R.string.notif_connected) + "\n" +
                                getString(R.string.notif_tunnel_dead),
                        ),
                    )
                } else {
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
        // Self-check relevance: our own probe only measures the tunnel when the
        // VPN rules actually route this app's traffic into it.
        selfCheckInTunnel = when {
            includeApplied > 0 -> includePkgs.contains(packageName)
            excludeApplied > 0 -> !excludePkgs.contains(packageName)
            else -> true
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
        interfaceListener = listener
        // A fresh engine instance knows nothing about our previous reports —
        // reset the dedupe guard or the initial report would be swallowed
        // after a watchdog engine restart.
        lastReportedName = null
        lastReportedIndex = Int.MIN_VALUE
        // Synchronous initial report (same as SFA: DefaultNetworkMonitor already
        // holds the current network when setListener fires): the engine must know
        // the outbound interface BEFORE the first dial, otherwise it logs
        // "missing default interface" and pauses all networks.
        val initial = currentPhysicalNetwork()
        defaultNetwork = initial
        if (initial != null) {
            reportDefaultInterface(initial)
        } else {
            // No usable physical network right now (VPN transport active or
            // nothing up yet): tell the engine explicitly instead of leaving it
            // in the uninitialized state — it then waits instead of binding
            // dial attempts to stale candidates.
            CrashLogger.breadcrumb("netmon: no physical default at monitor start")
            reportNoDefaultInterface()
        }
        registerDefaultNetworkMonitor()
    }

    private fun currentPhysicalNetwork(): Network? {
        val cm = connectivity()
        defaultNetwork?.let { net ->
            val caps = cm.getNetworkCapabilities(net)
            if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return net
        }
        val active = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(active) ?: return null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
        return active
    }

    private fun registerDefaultNetworkMonitor() {
        if (defaultNetworkRegistered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        try {
            when {
                Build.VERSION.SDK_INT >= 31 -> connectivity()
                    .registerBestMatchingNetworkCallback(request, defaultNetworkCallback, netmonHandler)
                Build.VERSION.SDK_INT >= 28 -> connectivity()
                    .requestNetwork(request, defaultNetworkCallback, netmonHandler)
                Build.VERSION.SDK_INT >= 26 -> connectivity()
                    .registerDefaultNetworkCallback(defaultNetworkCallback, netmonHandler)
                else -> @Suppress("DEPRECATION") connectivity()
                    .registerDefaultNetworkCallback(defaultNetworkCallback)
            }
            defaultNetworkRegistered = true
        } catch (t: Throwable) {
            CrashLogger.log("default network monitor registration failed", t)
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        interfaceListener = null
        lastReportedName = null
        lastReportedIndex = Int.MIN_VALUE
        if (defaultNetworkRegistered) {
            try {
                connectivity().unregisterNetworkCallback(defaultNetworkCallback)
            } catch (_: Throwable) {
            }
            defaultNetworkRegistered = false
        }
    }

    /**
     * SFA semantics: when the tracked default network disappears, tell the
     * engine ("", -1). The engine then clears its default interface and
     * pauses new dials instead of silently binding to a stale interface —
     * and wakes up as soon as the next real report arrives.
     */
    private fun reportNoDefaultInterface() {
        val listener = interfaceListener ?: return
        if (lastReportedIndex == Int.MIN_VALUE) return // never reported — nothing to retract
        lastReportedName = null
        lastReportedIndex = Int.MIN_VALUE
        CrashLogger.breadcrumb("netmon: default network lost — engine notified")
        try {
            listener.updateDefaultInterface("", -1, false, false)
        } catch (t: Throwable) {
            CrashLogger.log("netmon: lost-report failed", t)
        }
    }

    /**
     * Reports the interface of a tracked (default) network to the engine.
     * Link properties and the interface index may not be ready right after
     * onAvailable — retry briefly, like SFA does. VPN-transport networks are
     * never reported: binding outbounds to a tun would loop packets back
     * into the engine.
     */
    private fun reportDefaultInterface(network: Network) {
        val listener = interfaceListener ?: return
        val cm = connectivity()
        for (attempt in 0 until 10) {
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
            val name = cm.getLinkProperties(network)?.interfaceName
            if (name == null) {
                Thread.sleep(100)
                continue
            }
            val index = try {
                JavaNetworkInterface.getByName(name)?.index
            } catch (_: Throwable) {
                null
            }
            if (index == null) {
                Thread.sleep(100)
                continue
            }
            val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
            if (name == lastReportedName && index == lastReportedIndex) return // engine already knows
            lastReportedName = name
            lastReportedIndex = index
            CrashLogger.breadcrumb("netmon: default interface \u2192 $name (index $index)")
            try {
                listener.updateDefaultInterface(name, index, expensive, false)
            } catch (t: Throwable) {
                CrashLogger.log("netmon: report failed for $name", t)
            }
            return
        }
        // Giving up WITHOUT a report is deliberate: the engine keeps its
        // previous default (protected dialing continues) instead of receiving
        // a bogus index that would poison the dialer's candidate list.
        CrashLogger.breadcrumb("netmon: interface not ready after 1s — previous default kept")
    }

    override fun getInterfaces(): io.nekohasekai.libbox.NetworkInterfaceIterator {
        val cm = connectivity()
        val javaInterfaces = JavaNetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val items = mutableListOf<LibboxNetworkInterface>()
        for (network in cm.allNetworks) {
            try {
                val linkProperties = cm.getLinkProperties(network) ?: continue
                val caps = cm.getNetworkCapabilities(network) ?: continue
                val name = linkProperties.interfaceName ?: continue
                val jif = javaInterfaces.firstOrNull { it.name == name } ?: continue
                val item = LibboxNetworkInterface()
                item.name = name
                item.dnsServer = LibStringIterator(
                    linkProperties.dnsServers.mapNotNull { it.hostAddress },
                )
                item.gateway = LibStringIterator(
                    linkProperties.routes
                        .filter { it.destination.prefixLength == 0 }
                        .mapNotNull { it.gateway }
                        .filterNot { it.isAnyLocalAddress }
                        .mapNotNull { it.hostAddress },
                )
                item.type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                item.index = jif.index
                item.mtu = try { jif.mtu } catch (_: Throwable) { 0 }
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
                // Raw Linux IFF_* semantics (libbox maps them with linkFlags):
                // UP|RUNNING for internet-capable networks — NOT Java's isUp,
                // which cannot be trusted to reflect connectivity on OEM ROMs.
                // The engine filters bind candidates by Flags&FlagUp, so a wrong
                // flag here silently empties the dialer's candidate list.
                var flags = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (jif.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
                if (jif.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
                if (jif.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
                item.flags = flags
                item.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                items.add(item)
            } catch (_: Throwable) {
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

    // ------------------------------------------------------------ tunnel self-check

    /**
     * The engine reports CONNECTED as soon as box starts — even if the proxy
     * server never answers (rotated subscription, dead node, broken handshake,
     * blocked IP). Push one real request through the tunnel from the app's own
     * UID (it is always on the include list, and full-VPN captures it anyway)
     * and record the verdict:
     *   - OK        -> breadcrumb only, business as usual;
     *   - FAILED    -> breadcrumb with the reason + "no traffic" notification
     *                  text, unless real user traffic has already proven the
     *                  tunnel alive (BoxClient.statsEverFlowed).
     * HTTP 204 probe: plain HTTP exercises DNS hijack + proxy TCP without
     * paying for a TLS handshake, and 204 says "bytes crossed the tunnel".
     */
    private fun startTunnelSelfCheck() {
        Thread({
            try {
                Thread.sleep(8_000L)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!wantsConnected || shouldStop || stopInProgress) return@Thread
            if (VpnStateBus.state.value != VpnStateBus.State.CONNECTED) return@Thread
            if (!selfCheckInTunnel) {
                CrashLogger.breadcrumb("tunnel self-check: skipped (app bypasses the tunnel in per-app mode)")
                return@Thread
            }
            val started = System.currentTimeMillis()
            try {
                val conn = URL("http://connectivitycheck.gstatic.com/generate_204")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "ZenesVPN self-check")
                val code = conn.responseCode
                try {
                    conn.errorStream?.close()
                } catch (_: Throwable) {
                }
                val ms = System.currentTimeMillis() - started
                if (code == 204) {
                    CrashLogger.breadcrumb("tunnel self-check: OK (204 in ${ms}ms)")
                } else {
                    CrashLogger.breadcrumb("tunnel self-check: unexpected HTTP $code in ${ms}ms")
                    if (!BoxClient.statsEverFlowed()) {
                        VpnStateBus.tunnelUnreachable.value = true
                        updateNotification()
                    }
                }
            } catch (t: Throwable) {
                if (!wantsConnected || VpnStateBus.state.value != VpnStateBus.State.CONNECTED) {
                    return@Thread
                }
                val reason = t.message?.take(160) ?: t.javaClass.simpleName
                CrashLogger.breadcrumb("tunnel self-check: FAILED (${t.javaClass.simpleName}: $reason)")
                if (!BoxClient.statsEverFlowed()) {
                    VpnStateBus.tunnelUnreachable.value = true
                    updateNotification()
                }
            }
        }, "zenes-selfcheck").start()
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
                    // Self-healing: the engine can report CONNECTED while not a
                    // single byte crosses the tunnel (self-check failed, stats
                    // never flowed — the classic green-dead-tunnel state). A
                    // controlled engine restart re-dials everything from a clean
                    // state; the retry budget (5) and the 45s stability reset
                    // bound it, and a genuinely dead server ends in an honest
                    // ERROR instead of an eternal green lie.
                    if (VpnStateBus.tunnelUnreachable.value &&
                        !retryPending && !stopInProgress &&
                        System.currentTimeMillis() - connectedSinceMs > DEAD_TUNNEL_GRACE_MS
                    ) {
                        CrashLogger.breadcrumb("watchdog: tunnel unreachable — controlled engine restart")
                        VpnStateBus.tunnelUnreachable.value = false // fresh verdict after reconnect
                        forceEngineCleanup()
                        scheduleRetry()
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
