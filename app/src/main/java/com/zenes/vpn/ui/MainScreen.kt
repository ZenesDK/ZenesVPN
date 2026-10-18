package com.zenes.vpn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenes.vpn.R
import com.zenes.vpn.bg.VpnStateBus
import com.zenes.vpn.data.Store
import com.zenes.vpn.ui.theme.CookieShape
import io.nekohasekai.libbox.Libbox

@Composable
fun MainScreen(onConnectToggle: () -> Unit, onGoServers: () -> Unit) {
    val context = LocalContext.current
    val state by VpnStateBus.state.collectAsStateWithLifecycle()
    val uplink by VpnStateBus.uplink.collectAsStateWithLifecycle()
    val downlink by VpnStateBus.downlink.collectAsStateWithLifecycle()
    val uplinkTotal by VpnStateBus.uplinkTotal.collectAsStateWithLifecycle()
    val downlinkTotal by VpnStateBus.downlinkTotal.collectAsStateWithLifecycle()

    val selected = remember { Store(context).selectedServer() }
    val sub = remember(selected?.subId) {
        selected?.subId?.let { id -> Store(context).subs().firstOrNull { it.id == id } }
    }

    val statusText = when (state) {
        VpnStateBus.State.DISCONNECTED -> stringResource(R.string.state_disconnected)
        VpnStateBus.State.CONNECTING -> stringResource(R.string.state_connecting)
        VpnStateBus.State.CONNECTED -> stringResource(R.string.state_connected)
        VpnStateBus.State.STOPPING -> stringResource(R.string.state_stopping)
        VpnStateBus.State.ERROR -> stringResource(R.string.state_error)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))

        // ------------- cookie power button -------------
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val pressScale by animateFloatAsState(
            targetValue = if (pressed) 0.9f else 1f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = 380f),
            label = "pressScale",
        )
        val busy = state == VpnStateBus.State.CONNECTING || state == VpnStateBus.State.STOPPING
        val spin = if (busy) {
            val transition = rememberInfiniteTransition(label = "spin")
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
                label = "spinAngle",
            ).value
        } else {
            0f
        }
        val buttonColor by animateColorAsState(
            targetValue = when (state) {
                VpnStateBus.State.CONNECTED -> MaterialTheme.colorScheme.tertiary
                VpnStateBus.State.CONNECTING, VpnStateBus.State.STOPPING -> MaterialTheme.colorScheme.primary
                VpnStateBus.State.ERROR -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            animationSpec = tween(350),
            label = "powerColor",
        )

        Box(
            modifier = Modifier
                .size(216.dp)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                ) { onConnectToggle() },
            contentAlignment = Alignment.Center,
        ) {
            // Rotating background (the icon stays still).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = spin }
                    .background(MaterialTheme.colorScheme.surfaceVariant, CookieShape()),
            )
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = stringResource(R.string.btn_connect),
                tint = buttonColor,
                modifier = Modifier.size(96.dp),
            )
        }

        Spacer(Modifier.height(28.dp))
        Text(statusText, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = (selected?.flag?.plus(" ") ?: "") + (
                selected?.name ?: stringResource(R.string.no_server_selected)
                ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(28.dp))

        // ------------- current server card -------------
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.current_server),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        (selected?.flag?.plus(" ") ?: "") + (selected?.name ?: "—"),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = selected?.let { "${it.proto.uppercase()} · ${it.host}" } ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onGoServers,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(stringResource(R.string.change_server))
                }
            }
        }

        // ------------- subscription card -------------
        if (sub != null && subscriptionHasInfo(sub)) {
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.home_subscription),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        sub.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    SubInfoContent(sub, compact = false)
                }
            }
        }

        // ------------- traffic card -------------
        if (state == VpnStateBus.State.CONNECTED) {
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrafficCell(
                        icon = Icons.Default.ArrowUpward,
                        label = stringResource(R.string.upload_speed),
                        speed = Libbox.formatBytes(uplink) + "/s",
                        total = Libbox.formatBytes(uplinkTotal),
                    )
                    TrafficCell(
                        icon = Icons.Default.ArrowDownward,
                        label = stringResource(R.string.download_speed),
                        speed = Libbox.formatBytes(downlink) + "/s",
                        total = Libbox.formatBytes(downlinkTotal),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrafficCell(icon: ImageVector, label: String, speed: String, total: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(speed, style = MaterialTheme.typography.titleMedium)
            Text(
                total,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
