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
package app.indoorbike.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.indoorbike.shared.workout.CadenceCue
import app.indoorbike.shared.workout.CadenceTarget
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType

/**
 * How the rider is doing against the cadence target, as a colour.
 *
 * Green for on target, amber for off. Amber and not red on purpose: missing a
 * cadence target is a nudge, not a fault - the power is still being held by the
 * trainer either way, and red is spoken for by genuine problems (stale sensor,
 * ERG failure). A null cue means we have no reading to judge, so the target is
 * shown in the neutral foreground colour with no verdict attached.
 */
fun cadenceCueColor(cue: CadenceCue?): Color = when (cue) {
    CadenceCue.OnTarget -> CadenceColors.Success
    CadenceCue.Below, CadenceCue.Above -> CadenceColors.Warning
    null -> CadenceColors.Fg1
}

/**
 * The short line under the cadence number: which way to move, and what to aim
 * for. The arrow is the instruction - it's readable at a glance mid-effort,
 * which a bare pair of numbers is not.
 */
fun cadenceCueLabel(target: CadenceTarget, cue: CadenceCue?): String = when (cue) {
    CadenceCue.Below -> "▲ Target ${target.label}"
    CadenceCue.Above -> "▼ Target ${target.label}"
    CadenceCue.OnTarget -> "✓ Target ${target.label}"
    null -> "Target ${target.label}"
}

/** Spoken description of the same thing, for riders using TalkBack. */
fun cadenceCueContentDescription(target: CadenceTarget, cue: CadenceCue?): String {
    val goal = if (target.isSingleValue) {
        "target ${target.minRpm} rpm"
    } else {
        "target ${target.minRpm} to ${target.maxRpm} rpm"
    }
    return when (cue) {
        CadenceCue.Below -> "Cadence below $goal, spin faster"
        CadenceCue.Above -> "Cadence above $goal, spin slower"
        CadenceCue.OnTarget -> "Cadence on $goal"
        null -> "Cadence $goal"
    }
}

/**
 * A thin track showing where the rider's cadence sits relative to the segment's
 * target band.
 *
 * The number above already says what the cadence *is*; this adds the thing a
 * rider wants mid-interval, which is *how far off* they are. Being 2 rpm under
 * and 30 rpm under call for different responses, and no amount of colour
 * conveys that on its own - which is just as well, because on this screen
 * colour is spoken for by the power zone.
 *
 * Sized and shaped after [WattsPerKgBar] in the power hero so the two read as
 * the same instrument.
 */
@Composable
fun CadenceTargetBar(
    target: CadenceTarget,
    currentRpm: Int,
    cue: CadenceCue?,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    // Animate along the track rather than snapping: cadence is sampled about
    // once a second, and an unsmoothed marker reads as broken rather than live.
    val position by animateFloatAsState(
        targetValue = target.barPositionOf(currentRpm),
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "cadenceMarker"
    )
    val bandStart = target.barPositionOf(target.lowBoundRpm)
    val bandEnd = target.barPositionOf(target.highBoundRpm)

    // The marker stands proud of the track, so the row is as tall as the marker
    // and the track sits centred inside it.
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(MARKER_HEIGHT),
        contentAlignment = Alignment.Center
    ) {
        val trackWidth = maxWidth

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(CadenceColors.Bg2)
        ) {
            // The band the rider is aiming for.
            Box(
                modifier = Modifier
                    .offset(x = trackWidth * bandStart)
                    .width(trackWidth * (bandEnd - bandStart))
                    .fillMaxHeight()
                    .background(CadenceColors.Fg3)
            )
        }

        // The rider. Hidden when there's no reading to place - a marker parked
        // at zero would read as "you have stopped", which isn't what a silent
        // cadence sensor means.
        if (cue != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(
                        x = (trackWidth * position - MARKER_WIDTH / 2)
                            .coerceIn(0.dp, trackWidth - MARKER_WIDTH)
                    )
                    .width(MARKER_WIDTH)
                    .height(MARKER_HEIGHT)
                    .clip(RoundedCornerShape(MARKER_WIDTH / 2))
                    .background(CadenceColors.Fg1)
            )
        }
    }
}

private val MARKER_WIDTH = 3.dp
private val MARKER_HEIGHT = 10.dp

/**
 * The cadence readout with its target, as shown on the ride screen.
 *
 * Falls back to a plain [FlankMetric] when the segment has no cadence target,
 * which is every segment in every workout written before targets existed.
 */
@Composable
fun CadenceFlankMetric(
    rpm: Int,
    target: CadenceTarget?,
    cardWidth: Dp,
    valueFontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val cue = target?.cueFor(rpm)
    FlankMetric(
        label = "Cadence",
        value = rpm.toString(),
        unit = "rpm",
        // The zone owns colour on this screen, and it is already spending it on
        // the power number. The cue survives as the arrow in the footer and as
        // the marker's distance from the band, which is the more useful half of
        // it anyway.
        valueColor = CadenceColors.Fg1,
        contentDescription = target?.let { cadenceCueContentDescription(it, cue) },
        cardWidth = cardWidth,
        valueFontSize = valueFontSize,
        compact = compact,
        modifier = modifier,
        footer = target?.let {
            {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CadenceTargetBar(
                        target = it,
                        currentRpm = rpm,
                        cue = cue,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        text = cadenceCueLabel(it, cue),
                        style = CadenceType.micro,
                        color = CadenceColors.Fg3,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
    )
}
