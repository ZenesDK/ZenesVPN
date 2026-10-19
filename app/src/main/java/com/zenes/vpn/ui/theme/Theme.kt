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
 * Brand "Aurora" schemes, locked to the Zenes identity (the app icon gradient
 * #8A7BFF -> #4E3ED9). Dynamic (wallpaper) color was dropped on purpose: the
 * expressive gradients/chips across the app use fixed vivid anchors, and a
 * wallpaper-driven primary clashed with them. A fixed brand scheme also makes
 * the app look the same on every device — the Material 3 Expressive reference.
 */
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFCBBEFF),
    onPrimary = Color(0xFF3A0091),
    primaryContainer = Color(0xFF5433B8),
    onPrimaryContainer = Color(0xFFE7DEFF),
    secondary = Color(0xFFC9C3DD),
    onSecondary = Color(0xFF322D47),
    secondaryContainer = Color(0xFF484264),
    onSecondaryContainer = Color(0xFFE6DFFA),
    tertiary = Color(0xFF85D5C6),
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF005043),
    onTertiaryContainer = Color(0xFFA1F2E1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F0D17),
    onBackground = Color(0xFFE7E1F4),
    surface = Color(0xFF131019),
    onSurface = Color(0xFFE7E1F4),
    surfaceVariant = Color(0xFF221C30),
    onSurfaceVariant = Color(0xFFCBC4D9),
    outline = Color(0xFF948FA3),
    outlineVariant = Color(0xFF484154),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5B3FD1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1C0063),
    secondary = Color(0xFF615B78),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7DFF8),
    onSecondaryContainer = Color(0xFF1D192C),
    tertiary = Color(0xFF1F6B5C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFA7F2E0),
    onTertiaryContainer = Color(0xFF00201A),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFDF8FF),
    onBackground = Color(0xFF1B1A22),
    surface = Color(0xFFFDF8FF),
    onSurface = Color(0xFF1B1A22),
    surfaceVariant = Color(0xFFE6E1F0),
    onSurfaceVariant = Color(0xFF484551),
    outline = Color(0xFF787481),
    outlineVariant = Color(0xFFC9C5D4),
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
