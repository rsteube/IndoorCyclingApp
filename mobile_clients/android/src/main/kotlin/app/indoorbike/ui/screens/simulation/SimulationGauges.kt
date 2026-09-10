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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import app.indoorbike.shared.route.RouteTrack
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.shared.simulation.Drivetrain
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.DisplayFontFamily
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How steep the road is right now.
 *
 * The number is the point, so it gets the display face and the gradient
 * colour; the little inclined road under it is there because a sign and a
 * decimal take a beat to read and an angle does not. Both use [gradientColor],
 * the same scale the elevation profile is painted with, so the readout and the
 * profile agree without the rider having to learn two things.
 */
@Composable
fun GradientReadout(
    gradient: Double,
    modifier: Modifier = Modifier,
    valueFontSize: TextUnit = 44.sp,
    compact: Boolean = false,
    /**
     * The road and where the rider is on it. When given, the card is drawn on
     * the terrain around them; without it the card is just the number, which
     * is what a bare gradient reading was before.
     */
    route: RouteTrack? = null,
    distanceMeters: Double = 0.0,
) {
    val colour by animateColorAsState(
        targetValue = gradientColor(gradient),
        animationSpec = tween(600),
        label = "gradient-colour"
    )
    // Tween the angle too: raw gradient at 4 Hz twitches on every undulation,
    // and a glyph that jitters is harder to read than one that lags slightly.
    val animatedGradient by animateFloatAsState(
        targetValue = gradient.toFloat(),
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "gradient-angle"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.lg))
            .background(CadenceColors.Bg1.copy(alpha = 0.85f)),
    ) {
        if (route != null) {
            TerrainAhead(
                route = route,
                distanceMeters = distanceMeters,
                colour = colour,
                modifier = Modifier.matchParentSize(),
            )
        }
        Column(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(horizontal = Spacing.s3, vertical = if (compact) Spacing.s2 else Spacing.s3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Gradient", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            // Fixed width: the gradient runs from "-10.4" to "8.1" and back
            // several times a minute, and without a reserved slot the number
            // and its sign shuffle the whole readout every time.
            Box(
                modifier = Modifier.widthIn(min = valueFontSize.value.dp * 2.1f),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Text(
                    text = formatGradientPercent(gradient),
                    fontSize = valueFontSize,
                    lineHeight = valueFontSize,
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Normal,
                    color = colour,
                    maxLines = 1,
                )
            }
            Text(
                text = " %",
                style = CadenceType.h3,
                color = CadenceColors.Fg2,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (!compact) {
            Spacer(Modifier.height(6.dp))
            SlopeGlyph(
                gradient = animatedGradient.toDouble(),
                colour = colour,
                modifier = Modifier.width(64.dp).height(22.dp),
            )
        }
        }
    }
}

/**
 * The road immediately around the rider, drawn behind the gradient number.
 *
 * A fixed window - [TERRAIN_BEHIND_METERS] behind, [TERRAIN_AHEAD_METERS] in
 * front - rather than a share of the route, because the question it answers is
 * always the same one and does not depend on how long the ride is: how much
 * further does this climb go, and where is the next one. The whole-route
 * profile under the map cannot answer it, since on a 169 km stage a two
 * kilometre climb is a pixel wide.
 *
 * Faint on purpose. It is a background: the number in front of it is the
 * reading, and this is the context the reading sits in.
 */
@Composable
private fun TerrainAhead(
    route: RouteTrack,
    distanceMeters: Double,
    colour: Color,
    modifier: Modifier = Modifier,
) {
    val from = distanceMeters - TERRAIN_BEHIND_METERS
    val to = distanceMeters + TERRAIN_AHEAD_METERS
    val span = TERRAIN_BEHIND_METERS + TERRAIN_AHEAD_METERS

    // Sampled at whole metres off the smoothed profile, so the shape agrees
    // with the gradient the rider is being made to feel. Keyed on a rounded
    // distance: re-walking the profile four times a second for a picture that
    // moves a pixel is work nobody sees.
    val samples = remember(route, (distanceMeters / TERRAIN_RESAMPLE_METERS).toInt()) {
        val step = span / (TERRAIN_SAMPLES - 1)
        List(TERRAIN_SAMPLES) { i ->
            route.elevationAt((from + i * step).coerceIn(0.0, route.totalDistanceMeters))
        }
    }

    Canvas(modifier = modifier) {
        val low = samples.min()
        val high = samples.max()
        // A floor on the vertical range, or 4 m of drift over half a kilometre
        // is drawn as an alp and the flattest road on the route looks like the
        // steepest. Below this much relief, flat should look flat.
        val relief = maxOf(high - low, TERRAIN_MIN_RELIEF_METERS)
        val stepX = size.width / (samples.size - 1)
        // The road sits in the lower part of the card, leaving the number the
        // air above it.
        val floorY = size.height
        val roof = size.height * 0.42f
        fun y(v: Double) = (floorY - ((v - low) / relief) * (floorY - roof)).toFloat()

        val line = Path().apply {
            moveTo(0f, y(samples[0]))
            samples.forEachIndexed { i, v -> lineTo(i * stepX, y(v)) }
        }
        val filled = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(filled, color = colour.copy(alpha = 0.16f))
        drawPath(
            line,
            color = colour.copy(alpha = 0.55f),
            style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // Where the rider is now, so the road behind and the road ahead are
        // told apart at a glance.
        val nowX = (TERRAIN_BEHIND_METERS / span * size.width).toFloat()
        drawLine(
            color = CadenceColors.Fg1.copy(alpha = 0.35f),
            start = Offset(nowX, 0f),
            end = Offset(nowX, size.height),
            strokeWidth = 1.5f,
        )
    }
}

/**
 * A stretch of road tilted to match the gradient, with a wheel on it.
 *
 * The tilt is exaggerated by [SLOPE_VISUAL_GAIN]: drawn true to scale, a 6 %
 * climb is a three-degree line that reads as flat in a 22 dp box. What the
 * glyph is for is the instant impression of up, down or level, so the
 * exaggeration is the honest choice and the number beside it stays exact.
 */
@Composable
private fun SlopeGlyph(gradient: Double, colour: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val rise = (gradient * SLOPE_VISUAL_GAIN).coerceIn(-1.0, 1.0) * (h * 0.36)
        val midY = h * 0.66
        val left = Offset(0f, (midY + rise).toFloat())
        val right = Offset(w, (midY - rise).toFloat())

        drawLine(
            color = colour,
            start = left,
            end = right,
            strokeWidth = 3f,
        )
        // Hatching under the road, so the glyph reads as ground rather than as
        // a plain diagonal line.
        val hatch = Path().apply {
            moveTo(left.x, left.y)
            lineTo(right.x, right.y)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(path = hatch, color = colour.copy(alpha = 0.16f))
        drawCircle(
            color = colour,
            radius = 3.5f,
            center = Offset(w * 0.72f, (midY - rise * 0.44).toFloat() - 4f),
        )
    }
}

/**
 * The virtual drivetrain: which gear the rider is in, what it asks of them, and
 * the two buttons that change it.
 *
 * The cadence line is the part that makes a virtual gear mean something. A
 * number from 1 to 24 is arbitrary on its own; "this gear wants 78 rpm at the
 * speed you're doing" is the same information a rider reads off their legs
 * outdoors, and it is what tells them whether to shift before the gradient
 * does it for them.
 */
@Composable
fun GearShifter(
    drivetrain: Drivetrain,
    gearIndex: Int,
    speedMps: Double,
    onShiftDown: () -> Unit,
    onShiftUp: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
) {
    val askedCadence = drivetrain.cadenceRpmFor(speedMps, gearIndex)
    val gear = drivetrain.gearAt(gearIndex)
    val accent = if (enabled) CadenceColors.Fg1 else CadenceColors.Fg4

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.lg))
            .background(CadenceColors.Bg1.copy(alpha = 0.85f))
            .padding(horizontal = Spacing.s3, vertical = if (compact) Spacing.s2 else Spacing.s3),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Gear", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        Spacer(Modifier.height(2.dp))
        // The two buttons are fixed-size and the readout between them takes
        // whatever is left. Sizing the readout instead would let it push a
        // shift button off the edge of a narrow card, which is the one thing
        // this control cannot afford: a shifter you cannot reach.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2, Alignment.CenterHorizontally),
        ) {
            ShiftButton(
                label = "−",
                description = "Shift to an easier gear",
                accent = accent,
                enabled = enabled && gearIndex > 0,
                onClick = onShiftDown,
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Just the number. The "/ 20" that used to sit beside it was
                // the first thing squeezed off the card on a narrow screen,
                // and the ladder underneath already says where in the range
                // this gear is - more legibly than a fraction does.
                Text(
                    text = drivetrain.label(gearIndex).toString(),
                    fontSize = if (compact) 32.sp else 40.sp,
                    lineHeight = if (compact) 32.sp else 40.sp,
                    fontFamily = DisplayFontFamily,
                    color = CadenceColors.Fg1,
                    maxLines = 1,
                )
                GearLadder(
                    gearIndex = gearIndex,
                    gearCount = drivetrain.gearCount,
                    accent = accent,
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                )
            }
            ShiftButton(
                label = "+",
                description = "Shift to a harder gear",
                accent = accent,
                enabled = enabled && gearIndex < drivetrain.gearCount - 1,
                onClick = onShiftUp,
            )
        }
        if (!compact) {
            Spacer(Modifier.height(4.dp))
            // Two lines, each held to one, rather than one line that wraps.
            // Wrapping made the card's height depend on the digit count of a
            // cadence that changes every second, and the whole HUD above it
            // jumped every time the number crossed 99.
            Text(
                text = gear.label,
                style = CadenceType.tick,
                color = CadenceColors.Fg3,
                maxLines = 1,
            )
            Text(
                // The ratio makes the gear number concrete; the cadence it
                // wants is what tells the rider whether to shift.
                text = if (askedCadence > 0) "asks $askedCadence rpm" else "roll to see rpm",
                style = CadenceType.tick,
                color = CadenceColors.Fg3,
                maxLines = 1,
            )
        }
    }
}

/** The cassette as one tick per gear, so the rider sees where in the range they are. */
@Composable
private fun GearLadder(
    gearIndex: Int,
    gearCount: Int,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val count = gearCount.coerceAtLeast(1)
        val slot = size.width / count
        val barWidth = (slot * 0.55f).coerceAtLeast(1.5f)
        for (i in 0 until count) {
            val selected = i == gearIndex
            // Ticks grow towards the harder end, so the ladder itself says
            // which way is up without a label.
            val heightFraction = 0.34f + 0.66f * (i / (count - 1f))
            val barHeight = size.height * heightFraction
            drawRect(
                color = when {
                    selected -> accent
                    i < gearIndex -> CadenceColors.Fg3.copy(alpha = 0.55f)
                    else -> CadenceColors.Fg4.copy(alpha = 0.55f)
                },
                topLeft = Offset(i * slot + (slot - barWidth) / 2f, size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
            )
        }
    }
}

@Composable
private fun ShiftButton(
    label: String,
    description: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(CadenceColors.Bg2)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = CadenceType.h2,
            color = if (enabled) accent else CadenceColors.Fg4,
        )
    }
}

/**
 * Where the rider is on the route, as one line: speed, ground left, and the
 * climbing done so far.
 *
 * No progress bar. The elevation profile directly above this says the same
 * thing and says it against the terrain, so a second bare bar was the same
 * fact twice, the second time with nothing to read it against.
 *
 * Distance remaining rather than distance done is deliberate. On a route the
 * rider already knows how far they have come; what they are deciding is
 * whether to spend now or later, and that is a question about what is left.
 */
@Composable
fun RouteProgressStrip(
    distanceMeters: Double,
    totalDistanceMeters: Double,
    elevationGainMeters: Double,
    totalAscentMeters: Double,
    speedKmh: Double,
    modifier: Modifier = Modifier,
) {
    val remaining = (totalDistanceMeters - distanceMeters).coerceAtLeast(0.0)

    Column(modifier = modifier.fillMaxWidth()) {
        // Equal thirds, each anchored at its own left edge. SpaceBetween sized
        // the columns to their content, so every time a number gained or lost a
        // digit the whole row shuffled sideways - a readout that twitches while
        // the rider is trying to read it at a glance.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            ProgressStat(
                label = "Speed",
                value = formatOneDecimal(speedKmh),
                unit = "km/h",
                accent = CadenceColors.Fg1,
                valueWidth = 54.dp,
                modifier = Modifier.weight(1f),
            )
            ProgressStat(
                label = "TO GO",
                value = formatDistance(remaining),
                unit = "",
                accent = CadenceColors.Fg1,
                valueWidth = 96.dp,
                modifier = Modifier.weight(1f),
            )
            ProgressStat(
                label = "Climbed",
                value = "${elevationGainMeters.roundToInt()}",
                unit = "/ ${totalAscentMeters.roundToInt()} m",
                accent = CadenceColors.Fg1,
                valueWidth = 46.dp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ProgressStat(
    label: String,
    value: String,
    unit: String,
    accent: Color,
    /**
     * Reserved for the number, so its unit stays put as digits come and go.
     * Without it "9.8 km/h" and "28.6 km/h" put the unit in two different
     * places, and the eye reads the movement before it reads the number.
     *
     * A minimum rather than a fixed width: an unusually long value on some
     * future route should push the unit along rather than be clipped.
     */
    valueWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Text(text = label, style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        Row(verticalAlignment = Alignment.Bottom) {
            Box(
                modifier = Modifier.widthIn(min = valueWidth),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = value,
                    style = CadenceType.h3,
                    color = accent,
                    maxLines = 1,
                )
            }
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    style = CadenceType.tick,
                    color = CadenceColors.Fg3,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

/** One decimal, without pulling in a locale-aware formatter for two digits. */
fun formatOneDecimal(value: Double): String {
    val safe = if (value.isFinite()) abs(value) else 0.0
    val scaled = (safe * 10).roundToInt()
    return "${scaled / 10}.${scaled % 10}"
}

/** How much steeper the slope glyph draws than the road really is. */
/** The road behind the rider that the gradient card shows. */
private const val TERRAIN_BEHIND_METERS = 50.0

/** And the road in front, which is the part the rider is deciding about. */
private const val TERRAIN_AHEAD_METERS = 500.0

/** Points across that window. Enough for the shape, cheap enough for 4 Hz. */
private const val TERRAIN_SAMPLES = 96

/** Redraw granularity: the window is re-walked once per this much ground. */
private const val TERRAIN_RESAMPLE_METERS = 5.0

/** Least vertical range the window is drawn against. See [TerrainAhead]. */
private const val TERRAIN_MIN_RELIEF_METERS = 12.0

private const val SLOPE_VISUAL_GAIN = 3.4
