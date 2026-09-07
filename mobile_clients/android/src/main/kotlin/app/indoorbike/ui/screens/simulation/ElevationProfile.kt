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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.ui.theme.CadenceColors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The road ahead, as a height profile.
 *
 * This is the piece of the HUD that answers the question a rider on a climb
 * actually asks, which is not "how steep is it here" (the gradient readout says
 * that) but "how much longer". So the whole route is always on screen, coloured
 * by steepness, with the rider's position marked and everything behind them
 * filled in.
 *
 * Each column is drawn in the colour of the gradient at that point
 * ([gradientColor]), which is the same scale the gradient readout uses. That
 * shared scale is what lets a rider glance at the profile and know, without
 * reading a number, that the red band two kilometres ahead is going to hurt.
 *
 * The profile is sampled to one value per pixel column rather than per route
 * point: a 100 km route has thousands of points and a phone has a few hundred
 * columns, so sampling is both cheaper and the only way to avoid drawing
 * aliased noise.
 */
@Composable
fun ElevationProfile(
    route: RouteTrack,
    distanceMeters: Double,
    modifier: Modifier = Modifier,
    /** Where this ride began; see [RouteShape]. */
    startedAtMeters: Double = 0.0,
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (route.isEmpty || size.width < 2f || size.height < 2f) return@Canvas
            drawProfile(route, distanceMeters, size)
            // A line at the point the rider joined the road, so the shaded
            // part reads as two sittings rather than one long one.
            if (startedAtMeters > 0.0 && route.totalDistanceMeters > 0.0) {
                val x = (startedAtMeters / route.totalDistanceMeters)
                    .coerceIn(0.0, 1.0).toFloat() * size.width
                drawLine(
                    color = CadenceColors.Fg1.copy(alpha = 0.5f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2f,
                )
            }
        }
    }
}

private fun DrawScope.drawProfile(
    route: RouteTrack,
    distanceMeters: Double,
    size: Size,
) {
    val columns = size.width.toInt().coerceIn(2, MAX_COLUMNS)
    val elevations = route.elevationSamples(columns)
    val gradients = route.gradientSamples(columns)

    val lowest = elevations.min()
    val highest = elevations.max()
    // A flat route has no range to scale against and would otherwise draw as a
    // single line clinging to the top or bottom. Give it a nominal one so it
    // reads as flat ground rather than as a rendering failure.
    val range = (highest - lowest).coerceAtLeast(MIN_RANGE_METERS)

    val baseline = size.height
    val top = size.height * TOP_MARGIN_FRACTION
    val usable = baseline - top

    fun yFor(elevation: Double): Float =
        (baseline - ((elevation - lowest) / range * usable)).toFloat()

    val columnWidth = size.width / (columns - 1)
    val progressX = if (route.totalDistanceMeters > 0.0) {
        (distanceMeters / route.totalDistanceMeters).coerceIn(0.0, 1.0).toFloat() * size.width
    } else {
        0f
    }

    // Filled columns, coloured by steepness. Drawn as rectangles rather than as
    // one path with a gradient brush, because the colour has to follow the
    // road's steepness along the x axis and a Brush can only follow position.
    for (i in 0 until columns - 1) {
        val x = i * columnWidth
        val y = yFor(elevations[i])
        val ridden = x + columnWidth <= progressX
        val colour = gradientColor(gradients[i])
        drawRect(
            color = colour.copy(alpha = if (ridden) 0.55f else 0.22f),
            topLeft = Offset(x, y),
            size = Size(columnWidth + 1f, (baseline - y).coerceAtLeast(0f)),
        )
    }

    // The skyline itself, so the shape of the road reads even where the fill is
    // faint.
    val outline = Path().apply {
        moveTo(0f, yFor(elevations[0]))
        for (i in 1 until columns) lineTo(i * columnWidth, yFor(elevations[i]))
    }
    drawPath(
        path = outline,
        color = CadenceColors.Fg2.copy(alpha = 0.55f),
        style = Stroke(width = 1.5f),
    )

    // Everything behind the rider, washed in the brand colour so progress is
    // legible at a glance from across a room.
    if (progressX > 0f) {
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    CadenceColors.Fg1.copy(alpha = 0.05f),
                    CadenceColors.Fg1.copy(alpha = 0.18f),
                ),
                startX = 0f,
                endX = progressX,
            ),
            topLeft = Offset(0f, top),
            size = Size(progressX, baseline - top),
        )
    }

    // The rider: a full-height hairline with a dot sitting on the road, which
    // is what makes "how far up the climb am I" a glance rather than a
    // calculation.
    val index = ((progressX / columnWidth).toInt()).coerceIn(0, columns - 1)
    val riderY = yFor(elevations[index])
    drawLine(
        color = CadenceColors.Fg1.copy(alpha = 0.85f),
        start = Offset(progressX, top * 0.4f),
        end = Offset(progressX, baseline),
        strokeWidth = 2f,
    )
    drawCircle(
        color = CadenceColors.Fg1,
        radius = 5f,
        center = Offset(progressX, riderY),
    )
    drawCircle(
        color = CadenceColors.Fg1,
        radius = 5f,
        center = Offset(progressX, riderY),
        style = Stroke(width = 1.5f),
    )
}

/**
 * The colour scale shared by the profile and the gradient readout.
 *
 * Bands rather than a continuous ramp, because riders think in bands: a 4 %
 * drag, a 7 % climb, a 10 % wall. A continuous ramp would be prettier and would
 * say less. Descents get their own cool colour so the profile distinguishes
 * "free" from "flat" without a legend.
 */
fun gradientColor(gradient: Double): Color {
    val percent = gradient * 100.0
    return when {
        percent <= -6.0 -> DescentSteep
        percent <= -1.5 -> Descent
        percent < 1.5 -> CadenceColors.Fg3
        percent < 4.0 -> CadenceColors.Success
        percent < 7.0 -> CadenceColors.Warning
        percent < 10.0 -> CadenceColors.Fg1
        percent < 14.0 -> CadenceColors.Fg2
        else -> CadenceColors.Danger
    }
}

/** How the gradient reads on the HUD, e.g. "6.4" for a 6.4 % climb. */
fun formatGradientPercent(gradient: Double): String {
    val percent = gradient * 100.0
    val rounded = (abs(percent) * 10).roundToInt() / 10.0
    val sign = if (percent < -0.05) "-" else ""
    val whole = rounded.toInt()
    val tenth = ((rounded - whole) * 10).roundToInt()
    return "$sign$whole.$tenth"
}

/** Distance in the unit the HUD shows: metres under a kilometre, else km. */
fun formatDistance(meters: Double): String {
    if (meters < 1_000.0) return "${meters.roundToInt()} m"
    val km = meters / 1_000.0
    var whole = km.toInt()
    var decimals = ((km - whole) * 100).roundToInt()
    // Rounding the fraction can carry, and the carry has to reach the
    // kilometres. Without this, 7999.5 m renders as "7.100 km": the fraction
    // rounds to 100, and padding a three-digit number to two digits leaves it
    // alone. Any distance within half a metre of a whole kilometre hits it,
    // which on a route HUD counting down to the finish is exactly the moment
    // the rider is looking.
    if (decimals >= 100) {
        whole += 1
        decimals -= 100
    }
    return "$whole.${decimals.toString().padStart(2, '0')} km"
}

private val Descent = Color(0xFF4FA3D1)
private val DescentSteep = Color(0xFF6FD1E8)

/** One sample per pixel column, capped so a very wide screen doesn't over-sample. */
private const val MAX_COLUMNS = 1_200

/** Headroom above the highest point, so the peak isn't flush with the top edge. */
private const val TOP_MARGIN_FRACTION = 0.18f

/** Smallest elevation span a profile is scaled against. */
private const val MIN_RANGE_METERS = 20.0
