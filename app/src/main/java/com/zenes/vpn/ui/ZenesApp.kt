package com.zenes.vpn.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zenes.vpn.R
import com.zenes.vpn.bg.BoxClient
import com.zenes.vpn.bg.VpnStateBus
import kotlinx.coroutines.launch
import com.zenes.vpn.core.Updater
import com.zenes.vpn.util.SubscriptionUpdater

sealed class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    data object Home : Tab("home", R.string.nav_home, Icons.Default.Home)
    data object Servers : Tab("servers", R.string.nav_servers, Icons.Default.Dns)
    data object Settings : Tab("settings", R.string.nav_settings, Icons.Default.Settings)
}

@Composable
fun ZenesApp(onConnectToggle: () -> Unit) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val tabs = listOf(Tab.Home, Tab.Servers, Tab.Settings)

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Show transient errors as a snackbar (never crash the UI).
    val error by VpnStateBus.error.collectAsStateWithLifecycle()
    val errorText = error?.let { msg ->
        // localized generic message is already provided by the producer
        msg
    }
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbarHostState.showSnackbar(errorText)
            // allow the same error to be shown again later
            VpnStateBus.post(VpnStateBus.State.DISCONNECTED)
        }
    }

    // Keep the traffic client alive while the UI is visible.
    val appContext = LocalContext.current
    LaunchedEffect(Unit) {
        BoxClient.ensureStarted()
        // Automatic update check (throttled to once per 4 h inside Updater);
        // if a new release exists, UpdateDialog pops up on any tab.
        Updater.checkAsync(appContext, auto = true)
        // Automatic subscription refresh: runs a due pass now, then re-checks
        // every 15 min while the app is alive; the interval itself is user
        // configurable in Settings → Subscriptions (default: on app launch).
        SubscriptionUpdater.start(appContext, this)
        // One clean retry through the tunnel: on carrier/censored networks the
        // launch-time update check and subscription refresh fail while the VPN
        // is down (direct GitHub / provider hosts are silently dropped). As
        // soon as the tunnel connects, give both a single forced retry.
        launch {
            VpnStateBus.state.collect { st ->
                if (st == VpnStateBus.State.CONNECTED) {
                    if (Updater.retryOnConnect) {
                        Updater.checkAsync(appContext, auto = true, force = true)
                    }
                    SubscriptionUpdater.retryDueIfFailed(appContext)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = { navigateToTab(navController, tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Home.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.Home.route) {
                MainScreen(
                    onConnectToggle = onConnectToggle,
                    onGoServers = {
                        navigateToTab(navController, Tab.Servers.route)
                    },
                    onOpenStats = {
                        navController.navigate("stats") { launchSingleTop = true }
                    },
                )
            }
            composable(Tab.Servers.route) {
                ServersScreen()
            }
            composable(Tab.Settings.route) {
                SettingsScreen(
                    onOpenPerApps = {
                        navController.navigate("perapp") { launchSingleTop = true }
                    },
                    onOpenStats = {
                        navController.navigate("stats") { launchSingleTop = true }
                    },
                )
            }
            composable("perapp") {
                PerAppScreen(onBack = { navController.popBackStack() })
            }
            composable("stats") {
                StatsScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    // Self-update popup (available / downloading / ready-to-install).
    UpdateDialog()
}

/**
 * Deterministic tab switching: unwind to the start destination, then land on the
 * requested tab. The canonical popUpTo(saveState)+restoreState recipe stalls on
 * navigation 2.8.x when the target is the start destination already present in the
 * back stack (user-visible as: after "Сменить" pushed Servers, tapping "Главная"
 * in the bottom bar did nothing until the system back gesture was used).
 */
private fun navigateToTab(navController: NavHostController, route: String) {
    if (navController.currentDestination?.route == route) return
    navController.popBackStack(Tab.Home.route, inclusive = false)
    if (route != Tab.Home.route) {
        navController.navigate(route) { launchSingleTop = true }
    }
}
