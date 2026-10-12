/*
 * Copyright (C) 2026 Snapp Technology Ltd
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

import app.indoorbike.shared.route.gradientBand
import app.indoorbike.shared.route.GradientBand
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
 * by steepness, with the rider's position marked and everything behind them,
 * back to where the ride began, filled in.
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
    /**
     * A tap or a drag along the profile, as the distance under the finger.
     * Null for a profile that only shows, which is every profile during a
     * ride.
     */
    onPick: ((distanceMeters: Double) -> Unit)? = null,
) {
    val currentOnPick by rememberUpdatedState(onPick)
    val total = route.totalDistanceMeters
    // The x axis is linear in distance, so the distance under a finger is its
    // share of the width. Dragging is the point: a long route is a few
    // hundred metres a pixel, and sliding to the foot of the climb you want
    // is easier than landing on it with one tap.
    val picking = if (onPick == null || total <= 0.0) {
        Modifier
    } else {
        Modifier
            .pointerInput(route) {
                detectTapGestures { at ->
                    currentOnPick?.invoke((at.x / size.width).coerceIn(0f, 1f) * total)
                }
            }
            .pointerInput(route) {
                detectHorizontalDragGestures(
                    onDragStart = { at -> currentOnPick?.invoke((at.x / size.width).coerceIn(0f, 1f) * total) },
                ) { change, _ ->
                    change.consume()
                    currentOnPick?.invoke((change.position.x / size.width).coerceIn(0f, 1f) * total)
                }
            }
    }
    Box(modifier = modifier.then(picking)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (route.isEmpty || size.width < 2f || size.height < 2f) return@Canvas
            drawProfile(route, distanceMeters, startedAtMeters, size)
        }
    }
}

private fun DrawScope.drawProfile(
    route: RouteTrack,
    distanceMeters: Double,
    startedAtMeters: Double,
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
    // Where this ride joined the road. Everything behind that is left as
    // faint as the road ahead: it was not ridden today, whether skipped or
    // done in an earlier sitting, and shading it would make a ride that
    // began at 600 km look like 600 km of riding.
    val startX = if (startedAtMeters > 0.0 && route.totalDistanceMeters > 0.0) {
        val share = (startedAtMeters / route.totalDistanceMeters).coerceIn(0.0, 1.0).toFloat()
        // Never past the rider: the engine does not go backwards from its
        // start, but a drawing should not depend on that.
        (share * size.width).coerceAtMost(progressX)
    } else {
        0f
    }

    // Filled columns, coloured by steepness. Drawn as rectangles rather than as
    // one path with a gradient brush, because the colour has to follow the
    // road's steepness along the x axis and a Brush can only follow position.
    for (i in 0 until columns - 1) {
        val x = i * columnWidth
        val y = yFor(elevations[i])
        val ridden = x >= startX && x + columnWidth <= progressX
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
    if (progressX > startX) {
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    CadenceColors.Fg1.copy(alpha = 0.05f),
                    CadenceColors.Fg1.copy(alpha = 0.18f),
                ),
                startX = startX,
                endX = progressX,
            ),
            topLeft = Offset(startX, top),
            size = Size(progressX - startX, baseline - top),
        )
    }

    // Where the ride began, when that was not the trailhead: a hairline and
    // a hollow dot on the road, the counterpart of the rider's filled one, so
    // the start and the end of the ride are read off the same two marks.
    if (startX > 0f) {
        drawLine(
            color = CadenceColors.Fg1.copy(alpha = 0.5f),
            start = Offset(startX, top * 0.4f),
            end = Offset(startX, baseline),
            strokeWidth = 2f,
        )
        val startIndex = ((startX / columnWidth).toInt()).coerceIn(0, columns - 1)
        drawCircle(
            color = CadenceColors.Fg1,
            radius = 4.5f,
            center = Offset(startX, yFor(elevations[startIndex])),
            style = Stroke(width = 1.5f),
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
fun gradientColor(gradient: Double): Color = when (gradientBand(gradient)) {
    GradientBand.SteepDescent -> DescentSteep
    GradientBand.Descent -> Descent
    GradientBand.Flat -> CadenceColors.Fg3
    GradientBand.Gentle -> CadenceColors.Success
    GradientBand.Moderate -> CadenceColors.Warning
    GradientBand.Hard -> CadenceColors.Fg1
    GradientBand.VeryHard -> CadenceColors.Fg2
    GradientBand.Wall -> CadenceColors.Danger
}

/** How the gradient reads on the HUD, e.g. "6.4" for a 6.4 % climb. Shared with iOS. */
fun formatGradientPercent(gradient: Double): String = app.indoorbike.shared.route.formatGradientPercent(gradient)

/** Distance in the unit the HUD shows: metres under a kilometre, else km. Shared with iOS. */
fun formatDistance(meters: Double): String = app.indoorbike.shared.route.formatDistance(meters)

private val Descent = Color(0xFF4FA3D1)
private val DescentSteep = Color(0xFF6FD1E8)

/** One sample per pixel column, capped so a very wide screen doesn't over-sample. */
private const val MAX_COLUMNS = 1_200

/** Headroom above the highest point, so the peak isn't flush with the top edge. */
private const val TOP_MARGIN_FRACTION = 0.18f

/** Smallest elevation span a profile is scaled against. */
private const val MIN_RANGE_METERS = 20.0
