package com.zenes.vpn.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Wavy progress bar: the filled part is a sine wave that slowly drifts sideways,
 * the remaining part is a flat track line.
 *
 * Pass [brush] to paint the wave with a gradient; the track line always uses [color].
 */
@Composable
fun WavyProgressBar(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    brush: Brush? = null,
) {
    val density = LocalDensity.current
    val lambda = with(density) { 18.dp.toPx() }  // wavelength, px
    val amp = with(density) { 2.6.dp.toPx() }    // amplitude, px
    val stroke = with(density) { 4.dp.toPx() }

    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "phase",
    )

    Canvas(
        modifier
            // fillMaxWidth() must stay INSIDE the component: a Canvas without an
            // explicit width constraint collapses to zero width.
            .fillMaxWidth()
            .height(10.dp),
    ) {
        val w = size.width
        val mid = size.height / 2f
        val filled = (w * progress().coerceIn(0f, 1f)).coerceAtLeast(stroke)

        // Track line.
        drawLine(
            color = color.copy(alpha = 0.25f),
            start = Offset(stroke / 2f, mid),
            end = Offset(w - stroke / 2f, mid),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )

        // Wave: filled portion.
        val path = Path()
        var x = 0f
        var first = true
        while (x <= filled) {
            val y = mid + amp * sin(2.0 * PI * x / lambda + phase).toFloat()
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
            x += 4f
        }
        if (first) path.moveTo(0f, mid)
        if (brush != null) {
            drawPath(path, brush, style = Stroke(width = stroke, cap = StrokeCap.Round))
        } else {
            drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
    }
}
