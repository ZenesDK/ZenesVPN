package com.zenes.vpn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Brand "Monotone lavender" schemes, locked to the Zenes identity (the app icon
 * gradient #8A7BFF -> #4E3ED9). Every role lives in ONE violet hue family —
 * surfaces are lavender-tinted, tertiary is a second violet tone (the old teal
 * is gone), error is a dusty rose so it still reads as attention. Dynamic
 * (wallpaper) color stays dropped on purpose: the monotone system is built on
 * fixed tonal anchors, and a wallpaper-driven primary would break the single-hue
 * calm. A fixed brand scheme also makes the app look the same on every device —
 * the Material 3 Expressive reference.
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFCBBDF6),
    onPrimary = Color(0xFF2F1670),
    primaryContainer = Color(0xFF4A35A8),
    onPrimaryContainer = Color(0xFFE5DCFF),
    secondary = Color(0xFFCCC5E2),
    onSecondary = Color(0xFF332B4A),
    secondaryContainer = Color(0xFF4A4362),
    onSecondaryContainer = Color(0xFFE6DFFA),
    tertiary = Color(0xFFB9A7EE),
    onTertiary = Color(0xFF241268),
    tertiaryContainer = Color(0xFF3D2C96),
    onTertiaryContainer = Color(0xFFE5DCFF),
    error = Color(0xFFF5B8CB),
    onError = Color(0xFF4E0922),
    errorContainer = Color(0xFF6C2A44),
    onErrorContainer = Color(0xFFFFD9E4),
    background = Color(0xFF12101E),
    onBackground = Color(0xFFE6E1F4),
    surface = Color(0xFF161327),
    onSurface = Color(0xFFE6E1F4),
    surfaceVariant = Color(0xFF272139),
    onSurfaceVariant = Color(0xFFC8C2DC),
    outline = Color(0xFF938DA8),
    outlineVariant = Color(0xFF47405C),
    surfaceContainerLowest = Color(0xFF100E1A),
    surfaceContainerLow = Color(0xFF1A1629),
    surfaceContainer = Color(0xFF201B31),
    surfaceContainerHigh = Color(0xFF262039),
    surfaceContainerHighest = Color(0xFF2C2541),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF6247C8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DCF9),
    onPrimaryContainer = Color(0xFF231263),
    secondary = Color(0xFF675E86),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4DEF4),
    onSecondaryContainer = Color(0xFF211A38),
    tertiary = Color(0xFF8A72DC),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE5DCFA),
    onTertiaryContainer = Color(0xFF2B1368),
    error = Color(0xFFB25577),
    onError = Color.White,
    errorContainer = Color(0xFFFADCE6),
    onErrorContainer = Color(0xFF40081F),
    background = Color(0xFFEFECF9),
    onBackground = Color(0xFF1A1726),
    surface = Color(0xFFF4F1FC),
    onSurface = Color(0xFF1A1726),
    surfaceVariant = Color(0xFFE5DFF5),
    onSurfaceVariant = Color(0xFF47415C),
    outline = Color(0xFF78728E),
    outlineVariant = Color(0xFFC9C3DC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0EDFA),
    surfaceContainer = Color(0xFFEAE6F7),
    surfaceContainerHigh = Color(0xFFE4DFF4),
    surfaceContainerHighest = Color(0xFFDFD9F1),
)

/** Heavier, more expressive type scale. */
private val ExpressiveTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
)

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ZenesTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = ExpressiveTypography,
        shapes = ExpressiveShapes,
        content = content,
    )
}
