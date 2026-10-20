package com.zenes.vpn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
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
// Tonal lavender system — the monotone M3 Expressive redesign.
//
// One violet hue family carries the whole app: differentiation comes from
// TONE (depth), not hue, exactly like the reference — a calm lavender field,
// violet accents, white/pill controls. Rose is the single sanctioned
// deviation, reserved for attention (errors, expiring subscriptions) so it
// still reads against the lavender field.
//
// The mid tones (LavPrimary..LavSoft, Rose, State*) are tuned to stay
// readable on BOTH surfaceVariant faces: light #E5DFF5 and dark #272139.
// ---------------------------------------------------------------------------
val LavInk = Color(0xFF44339B)      // deepest violet — emphasis, light-theme icons
val LavPrimary = Color(0xFF6247C8)  // brand violet (the reference button)
val LavMid = Color(0xFF8A72DC)      // mid lavender — transitions, secondary icons
val LavSoft = Color(0xFFB3A3EC)     // soft lavender — decorative rings, dots
val LavPale = Color(0xFFD9CFF6)     // pale lavender — washes, tracks
val LavMist = Color(0xFF8D87A6)     // neutral lavender-gray — unknown/plain
val RoseDeep = Color(0xFFA84E77)    // error-end of the rose ramp (light theme)
val Rose = Color(0xFFC97397)        // attention — dual-readable dusty rose
val RoseSoft = Color(0xFFE5AEC5)    // attention on the violet hero gradient

/** Hero accent while CONNECTED: deep enough on light, open enough on dark. */
val StateConnected = Color(0xFF6E54CE)

/** Hero accent while CONNECTING/STOPPING — a lighter, "in motion" tone. */
val StateTransition = Color(0xFF9078DE)

/**
 * Deterministic tone per protocol, used by the protocol chips and the
 * per-server stats bars. Monotone system: every protocol lives in the violet
 * family and differs only by tone; the neutral lavender-gray is reserved for
 * plain/unknown protocols. Theme-aware — deep tones on light surfaces, light
 * tones on dark ones — so chips and bars stay readable in both themes.
 */
@Composable
@ReadOnlyComposable
fun protocolColor(proto: String): Color {
    val dark = isSystemInDarkTheme()
    return when (proto.lowercase().trim()) {
        "vless" -> if (dark) Color(0xFFC3B4F2) else LavPrimary
        "vmess" -> if (dark) Color(0xFFD6CBF8) else LavInk
        "trojan" -> if (dark) Color(0xFFB49FF0) else Color(0xFF6E53CE)
        "ss", "shadowsocks" -> if (dark) Color(0xFFCFC3F6) else Color(0xFF5445A8)
        "hysteria", "hysteria2", "hy2" -> if (dark) Color(0xFFAB95EC) else Color(0xFF7C63D2)
        "tuic" -> if (dark) Color(0xFFDAD0F9) else Color(0xFF5949AE)
        "wireguard" -> if (dark) Color(0xFFBCA9F1) else Color(0xFF664FBE)
        "socks", "http" -> if (dark) Color(0xFF9B95B0) else LavMist
        else -> if (dark) Color(0xFF9B95B0) else LavMist
    }
}

/** Deep-to-mid violet ramp for subscription hero cards (white content on top). */
@Composable
fun heroBrush(): Brush = Brush.linearGradient(
    colors = listOf(LavInk, LavPrimary, LavMid),
)

/**
 * Soft tonal overlay placed on top of surfaceVariant cards so the usual gray
 * cards pick up a subtle violet-on-lavender sheen (tertiary is a violet tone
 * too in the monotone scheme, so the sheen stays in one hue family).
 */
@Composable
fun cardTintBrush(): Brush = Brush.linearGradient(
    colors = listOf(
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        Color.Transparent,
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
    ),
)

/**
 * Small rounded pill with the protocol name in its own tone
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
