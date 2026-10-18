package com.zenes.vpn.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Rounded regular polygon — the "cookie" silhouette used by the main power button.
 * Each vertex is cut back by [cornerFraction] of the edge length on both sides and
 * smoothed with a quadratic bezier whose control point is the vertex itself.
 */
class CookieShape(
    private val sides: Int = 9,
    private val cornerFraction: Float = 0.16f,
) : Shape {

    init {
        require(sides >= 3) { "CookieShape needs at least 3 sides" }
    }

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return Outline.Generic(Path())
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f

        // Vertices of the regular polygon, first one at the top.
        val vertices = Array(sides) { i ->
            val a = -PI / 2.0 + 2.0 * PI * i / sides
            Offset(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat())
        }

        val f = cornerFraction.coerceIn(0.02f, 0.45f)
        val path = Path()
        var first = true
        for (i in 0 until sides) {
            val prev = vertices[(i - 1 + sides) % sides]
            val cur = vertices[i]
            val next = vertices[(i + 1) % sides]
            // Entry point: current vertex pulled towards the previous one.
            val entry = Offset(
                cur.x + (prev.x - cur.x) * f,
                cur.y + (prev.y - cur.y) * f,
            )
            // Exit point: current vertex pulled towards the next one.
            val exit = Offset(
                cur.x + (next.x - cur.x) * f,
                cur.y + (next.y - cur.y) * f,
            )
            if (first) {
                path.moveTo(entry.x, entry.y)
                first = false
            } else {
                path.lineTo(entry.x, entry.y)
            }
            path.quadraticBezierTo(cur.x, cur.y, exit.x, exit.y)
        }
        path.close()
        return Outline.Generic(path)
    }
}
