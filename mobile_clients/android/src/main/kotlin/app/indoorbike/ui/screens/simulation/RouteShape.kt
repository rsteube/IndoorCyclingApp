/*
 * Copyright (C) 2026 Juhani Lehtimäki
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.indoorbike.ui.screens.simulation

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.ui.theme.CadenceColors
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/**
 * The shape of a route, drawn rather than mapped.
 *
 * The live HUD puts the route on OpenStreetMap tiles, which is right while
 * riding: a rider wants to know which road they are on. Afterwards, and
 * especially in a shared image, the tiles are the wrong thing. They need a
 * network round trip that a summary screen should not wait on, they cannot be
 * captured reliably into a bitmap, and reproducing them in an image the rider
 * posts somewhere carries an attribution obligation that a drawn line does not.
 *
 * What survives all of that is the shape itself, which is the part that says
 * "this was a ride somewhere real". So this projects the polyline and draws it:
 * the whole route faintly, the ridden part brightly, and a dot where the rider
 * stopped.
 *
 * Web Mercator, the same projection the tiles use, so the shape here is the
 * shape the map showed rather than a latitude-squashed version of it.
 */
@Composable
fun RouteShape(
    route: RouteTrack,
    /** How far along the route the ride reached, in metres. */
    riddenMeters: Double,
    modifier: Modifier = Modifier,
    accent: Color = CadenceColors.Fg1,
    remainingColor: Color = CadenceColors.Fg4,
    strokeWidth: Float = 6f,
    /**
     * Where on the road this ride began. Everything before it was covered in
     * an earlier sitting and is drawn between the two other states: not the
     * road still to do, and not what the rider did today.
     */
    startedAtMeters: Double = 0.0,
) {
    // Projection is per-route work, not per-frame work.
    val projected = remember(route) { project(route) }

    Canvas(modifier = modifier) {
        if (projected.isEmpty() || size.width < 4f || size.height < 4f) return@Canvas

        val pad = strokeWidth * 1.5f
        // One scale for both axes, so the route keeps its shape rather than
        // being stretched to fill the box.
        val scale = minOf(
            (size.width - pad * 2) / projected.spanX.toFloat(),
            (size.height - pad * 2) / projected.spanY.toFloat(),
        )
        // Centre whichever axis has slack, so a long thin route sits in the
        // middle of its box rather than hugging a corner.
        val offsetX = (size.width - projected.spanX.toFloat() * scale) / 2f
        val offsetY = (size.height - projected.spanY.toFloat() * scale) / 2f

        fun at(i: Int): Offset = Offset(
            x = offsetX + (projected.xs[i] - projected.minX).toFloat() * scale,
            // Screen y grows downwards, Mercator y grows northwards.
            y = offsetY + (projected.maxY - projected.ys[i]).toFloat() * scale,
        )

        val whole = Path().apply {
            moveTo(at(0).x, at(0).y)
            for (i in 1 until projected.size) {
                val p = at(i)
                lineTo(p.x, p.y)
            }
        }
        drawPath(
            path = whole,
            color = remainingColor,
            style = Stroke(width = strokeWidth * 0.7f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        val riddenCount = route.points.count { it.distanceMeters <= riddenMeters }
            .coerceIn(0, projected.size)
        // Where today's stretch begins. Zero for a ride from the trailhead,
        // which collapses the two paths below into the single one this drew
        // before routes could be resumed.
        val startCount = route.points.count { it.distanceMeters <= startedAtMeters }
            .coerceIn(0, riddenCount)

        if (startCount >= 2) {
            val earlier = Path().apply {
                moveTo(at(0).x, at(0).y)
                for (i in 1 until startCount) {
                    val p = at(i)
                    lineTo(p.x, p.y)
                }
            }
            drawPath(
                path = earlier,
                color = accent.copy(alpha = 0.35f),
                style = Stroke(width = strokeWidth * 0.85f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        if (riddenCount >= 2) {
            val ridden = Path().apply {
                val from = (startCount - 1).coerceAtLeast(0)
                moveTo(at(from).x, at(from).y)
                for (i in (from + 1) until riddenCount) {
                    val p = at(i)
                    lineTo(p.x, p.y)
                }
            }
            drawPath(
                path = ridden,
                color = accent,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            val end = at(riddenCount - 1)
            drawCircle(color = accent, radius = strokeWidth * 1.1f, center = end)
            drawCircle(
                color = CadenceColors.Fg1,
                radius = strokeWidth * 1.1f,
                center = end,
                style = Stroke(width = strokeWidth * 0.3f),
            )
        }

        // Where the road started, so a loop reads as a loop.
        drawCircle(
            color = CadenceColors.Fg2,
            radius = strokeWidth * 0.6f,
            center = at(0),
            style = Stroke(width = strokeWidth * 0.3f),
        )
    }
}

/** A route in Web Mercator, with the extent needed to fit it to a box. */
private class Projected(
    val xs: DoubleArray,
    val ys: DoubleArray,
) {
    val size: Int get() = xs.size
    val minX = xs.minOrNull() ?: 0.0
    val maxX = xs.maxOrNull() ?: 0.0
    val minY = ys.minOrNull() ?: 0.0
    val maxY = ys.maxOrNull() ?: 0.0

    /** Never zero, so a route that doubles back on one street still scales. */
    val spanX = (maxX - minX).coerceAtLeast(1e-9)
    val spanY = (maxY - minY).coerceAtLeast(1e-9)

    fun isEmpty(): Boolean = xs.size < 2
}

private fun project(route: RouteTrack): Projected {
    val n = route.points.size
    val xs = DoubleArray(n)
    val ys = DoubleArray(n)
    for (i in 0 until n) {
        val p = route.points[i]
        xs[i] = p.longitude / 180.0
        // Mercator, clamped short of the poles where the projection diverges.
        val lat = p.latitude.coerceIn(-85.0, 85.0) * PI / 180.0
        ys[i] = ln(tan(PI / 4.0 + lat / 2.0)) / PI
    }
    return Projected(xs, ys)
}
