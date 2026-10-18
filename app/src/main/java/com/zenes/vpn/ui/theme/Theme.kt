package com.zenes.vpn.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7C9EFF),
    onPrimary = Color(0xFF0A1730),
    primaryContainer = Color(0xFF2A3A5C),
    onPrimaryContainer = Color(0xFFD6E1FF),
    secondary = Color(0xFF9FCAFF),
    onSecondary = Color(0xFF00325A),
    secondaryContainer = Color(0xFF2A4A78),
    onSecondaryContainer = Color(0xFFD0E4FF),
    tertiary = Color(0xFF67D8B4),
    onTertiary = Color(0xFF003827),
    tertiaryContainer = Color(0xFF1F5A43),
    onTertiaryContainer = Color(0xFF86F5CE),
    background = Color(0xFF0F1216),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF171B22),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF232936),
    onSurfaceVariant = Color(0xFFB9C2D4),
    outline = Color(0xFF565C68),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3E5FC0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF00174B),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
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
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkScheme else LightScheme
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = ExpressiveTypography,
        shapes = ExpressiveShapes,
        content = content,
    )
}
