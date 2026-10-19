package com.zenes.vpn.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.zenes.vpn.ui.theme.ProtoChip
import com.zenes.vpn.ui.theme.VividAmber
import com.zenes.vpn.ui.theme.VividBlue
import com.zenes.vpn.ui.theme.VividCyan
import com.zenes.vpn.ui.theme.VividGreen
import com.zenes.vpn.ui.theme.VividMint
import com.zenes.vpn.ui.theme.VividOrange
import com.zenes.vpn.ui.theme.VividPink
import com.zenes.vpn.ui.theme.VividRed
import com.zenes.vpn.ui.theme.VividViolet
import com.zenes.vpn.ui.theme.cardTintBrush
import com.zenes.vpn.ui.theme.heroBrush
import io.nekohasekai.libbox.Libbox

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MainScreen(
    onConnectToggle: () -> Unit,
    onGoServers: () -> Unit,
    onOpenStats: () -> Unit,
) {
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

    val dark = isSystemInDarkTheme()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                // ---- aurora background: soft colorful blobs over the plain background ----
                val bigR = size.width * 1.05f
                val midR = size.width * 0.8f
                val smallR = size.width * 0.62f
                fun blob(cx: Float, cy: Float, radius: Float, color: Color) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(color, Color.Transparent),
                            center = Offset(cx, cy),
                            radius = radius,
                        ),
                        radius = radius,
                        center = Offset(cx, cy),
                    )
                }
                // top-left violet, top-right cyan, faint pink under the button
                blob(0f, 0f, bigR, VividViolet.copy(alpha = if (dark) 0.34f else 0.20f))
                blob(size.width, size.height * 0.16f, midR, VividCyan.copy(alpha = if (dark) 0.24f else 0.15f))
                blob(size.width * 0.12f, size.height * 0.50f, smallR, VividPink.copy(alpha = if (dark) 0.14f else 0.09f))
            },
    ) {
        // verticalScroll: on shorter screens (or large font scale) the content
        // used to overflow and the traffic card got clipped to an empty pill.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))

            // ------------- cookie power button with aurora halo -------------
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val pressScale by animateFloatAsState(
                targetValue = if (pressed) 0.9f else 1f,
                animationSpec = spring(dampingRatio = 0.55f, stiffness = 380f),
                label = "pressScale",
            )
            // Expressive shape morph: while pressed the cookie's rounded corners
            // pull further in, so the button visibly "squishes" under the finger.
            val pressMorph by animateFloatAsState(
                targetValue = if (pressed) 0.26f else 0.16f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                label = "pressMorph",
            )
            val faceShape = CookieShape(cornerFraction = pressMorph)
            val busy = state == VpnStateBus.State.CONNECTING || state == VpnStateBus.State.STOPPING
            // The halo always rotates: fast while busy, slow and calm otherwise.
            val spin = if (busy) {
                rememberInfiniteTransition(label = "spin").animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
                    label = "spinAngle",
                ).value
            } else {
                rememberInfiniteTransition(label = "spinSlow").animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(44000, easing = LinearEasing)),
                    label = "spinAngleSlow",
                ).value
            }

            // Aurora ring colors follow the connection state.
            val ringTargets = when (state) {
                VpnStateBus.State.CONNECTED -> listOf(VividGreen, VividCyan, VividMint)
                VpnStateBus.State.CONNECTING, VpnStateBus.State.STOPPING -> listOf(VividBlue, VividViolet, VividCyan)
                VpnStateBus.State.ERROR -> listOf(VividRed, VividOrange, VividPink)
                else -> listOf(VividViolet, VividBlue, VividCyan)
            }
            val ring = List(3) { i ->
                animateColorAsState(ringTargets[i], tween(700), label = "ring$i").value
            }
            val glowColor = ring[0]
            val ringBrush = Brush.sweepGradient(ring + ring.first())
            val fillBrush = Brush.linearGradient(
                listOf(ring[0].copy(alpha = 0.20f), ring[2].copy(alpha = 0.10f)),
            )

            val accent by animateColorAsState(
                targetValue = when (state) {
                    VpnStateBus.State.CONNECTED -> VividGreen
                    VpnStateBus.State.CONNECTING, VpnStateBus.State.STOPPING -> VividBlue
                    VpnStateBus.State.ERROR -> VividRed
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
                label = "powerColor",
            )

            Box(contentAlignment = Alignment.Center) {
                // soft glow behind everything
                Box(
                    modifier = Modifier
                        .size(258.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(glowColor.copy(alpha = if (dark) 0.30f else 0.20f), Color.Transparent),
                            ),
                        ),
                )
                // rotating aurora ring (the icon stays still)
                Box(
                    modifier = Modifier
                        .size(234.dp)
                        .graphicsLayer { rotationZ = spin }
                        .background(ringBrush, CookieShape()),
                )
                // cookie face
                Box(
                    modifier = Modifier
                        .size(212.dp)
                        .graphicsLayer {
                            scaleX = pressScale
                            scaleY = pressScale
                        }
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { onConnectToggle() }
                        .background(MaterialTheme.colorScheme.surfaceVariant, faceShape)
                        .background(fillBrush, faceShape),
                    contentAlignment = Alignment.Center,
                ) {
                    // M3E: while connecting/stopping the power icon yields to the
                    // expressive morphing loading indicator.
                    AnimatedContent(
                        targetState = busy,
                        transitionSpec = {
                            (fadeIn(tween(180)) + scaleIn(initialScale = 0.6f, animationSpec = tween(240)))
                                .togetherWith(fadeOut(tween(140)))
                        },
                        label = "powerFace",
                    ) { isBusy ->
                        if (isBusy) {
                            LoadingIndicator(color = accent, modifier = Modifier.size(88.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = stringResource(R.string.btn_connect),
                                tint = accent,
                                modifier = Modifier.size(96.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // status pill — springy color, slide-up text transition
            Surface(
                shape = RoundedCornerShape(50),
                color = accent.copy(alpha = 0.14f),
            ) {
                AnimatedContent(
                    targetState = statusText,
                    transitionSpec = {
                        (fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 3 })
                            .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(180)) { -it / 3 })
                    },
                    label = "statusText",
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleSmall,
                        color = accent,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                    )
                }
            }

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

            Spacer(Modifier.height(16.dp))

            // ------------- current server card -------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .background(cardTintBrush()),
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            selected?.let {
                                ProtoChip(it.proto)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = selected?.host ?: "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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

            // ------------- subscription card (hero gradient) -------------
            if (sub != null && subscriptionHasInfo(sub)) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(32.dp))
                        .background(heroBrush()),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.92f),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.home_subscription),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            sub.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        SubInfoContent(sub, compact = false, onHero = true)
                    }
                }
            }

            // ------------- traffic card (springy expand/collapse) -------------
            AnimatedVisibility(
                visible = state == VpnStateBus.State.CONNECTED,
                enter = fadeIn(tween(220)) +
                    expandVertically(tween(280, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(160)) +
                    shrinkVertically(tween(240, easing = FastOutSlowInEasing)),
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(32.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .background(cardTintBrush())
                            .clickable { onOpenStats() },
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
                                tint = VividAmber,
                                label = stringResource(R.string.upload_speed),
                                speed = Libbox.formatBytes(uplink) + "/s",
                                total = Libbox.formatBytes(uplinkTotal),
                            )
                            TrafficCell(
                                icon = Icons.Default.ArrowDownward,
                                tint = VividCyan,
                                label = stringResource(R.string.download_speed),
                                speed = Libbox.formatBytes(downlink) + "/s",
                                total = Libbox.formatBytes(downlinkTotal),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TrafficCell(icon: ImageVector, tint: Color, label: String, speed: String, total: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = label, tint = tint)
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
