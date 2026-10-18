package com.zenes.vpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Vivid accent anchors — the "Aurora" palette used by gradients, chips and
// state glows. Chosen to be readable on both dark and light backgrounds.
// ---------------------------------------------------------------------------
val VividViolet = Color(0xFF8B5CF6)
val VividBlue = Color(0xFF5B8CFF)
val VividCyan = Color(0xFF22D3EE)
val VividGreen = Color(0xFF34D399)
val VividMint = Color(0xFF4ADE80)
val VividAmber = Color(0xFFFBBF24)
val VividOrange = Color(0xFFFB923C)
val VividPink = Color(0xFFF472B6)
val VividRed = Color(0xFFF87171)
val VividSlate = Color(0xFF94A3B8)

/** Deterministic vivid color per protocol, used by the protocol chips. */
fun protocolColor(proto: String): Color = when (proto.lowercase().trim()) {
    "vless" -> VividViolet
    "vmess" -> VividBlue
    "trojan" -> VividPink
    "ss", "shadowsocks" -> VividGreen
    "hysteria", "hysteria2", "hy2" -> VividAmber
    "tuic" -> VividCyan
    "wireguard" -> Color(0xFF2DD4BF)
    "socks", "http" -> VividSlate
    else -> VividSlate
}

/** Diagonal hero gradient used by subscription cards (white content on top). */
@Composable
fun heroBrush(): Brush = Brush.linearGradient(
    colors = listOf(VividBlue, VividViolet, VividPink),
)

/**
 * Soft colorful overlay placed on top of surfaceVariant cards so that the
 * usual gray cards pick up a subtle blue-to-teal sheen.
 */
@Composable
fun cardTintBrush(): Brush = Brush.linearGradient(
    colors = listOf(
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        Color.Transparent,
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f),
    ),
)

/**
 * Small rounded pill with the protocol name in its own vivid color
 * (semi-transparent tint of the same color as the background).
 */
@Composable
fun ProtoChip(proto: String, modifier: Modifier = Modifier) {
    val color = protocolColor(proto)
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.16f),
        modifier = modifier,
    ) {
        Text(
            text = proto.uppercase(),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
