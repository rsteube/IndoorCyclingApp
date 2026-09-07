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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.indoorbike.shared.Workout
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.zones.PowerZone

/**
 * The workout as a row of bars, one per step, height by power and colour by
 * zone. Steps already ridden and the one being ridden are at full strength;
 * what is still ahead sits at 45 %, so the rider reads how far in they are from
 * the colour alone. A 2 dp Fg1 cursor marks the exact position.
 *
 * It used to be a continuous gradient-filled ridge with a crest line, which
 * looked like terrain but made the step boundaries - the thing a rider is
 * actually counting down to - the hardest edges on it to find.
 */
@Composable
fun RidgeTimeline(
    workout: Workout,
    currentSegIdx: Int,
    elapsedInSeg: Int,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
) {
    // The session ticks once a second, so drawn straight the cursor would hop
    // a step at a time. A linear second-long glide between ticks keeps it
    // rolling at the pace of the ride; on a skip it covers the distance in the
    // same second, which reads as a fast slide rather than a teleport.
    val segments = workout.segments
    val totalSeconds = segments.sumOf { it.lengthInSeconds }.coerceAtLeast(1)
    val elapsedTotal = segments.take(currentSegIdx).sumOf { it.lengthInSeconds } + elapsedInSeg
    val targetProgress = (elapsedTotal.toFloat() / totalSeconds).coerceIn(0f, 1f)
    val progress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 1000, easing = LinearEasing),
        label = "timelineCursor",
    )

    Box(modifier = modifier.fillMaxWidth().height(height)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (segments.isEmpty()) return@Canvas
            val maxPct = (segments.maxOfOrNull { it.powerPercentFTP } ?: 100).coerceAtLeast(60)
            val gap = 2.dp.toPx()
            val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())

            var cumSec = 0
            segments.forEachIndexed { i, seg ->
                val x0 = (cumSec.toFloat() / totalSeconds) * w
                cumSec += seg.lengthInSeconds
                val x1 = (cumSec.toFloat() / totalSeconds) * w
                val barWidth = (x1 - x0 - gap).coerceAtLeast(1f)
                val ratio = (seg.powerPercentFTP.toFloat() / maxPct).coerceIn(0.18f, 1f)
                val barHeight = ratio * h
                // Upcoming steps are the same colour, half-lit. Anything the
                // rider has already done, including the step under way, is full
                // strength.
                val alpha = if (i <= currentSegIdx) 1f else 0.45f
                // Rounded on top only: the bars stand on the baseline, and a
                // rounded foot would lift them off it.
                drawPath(
                    path = topRoundedBar(x0, h - barHeight, barWidth, barHeight, radius.x),
                    color = PowerZone.fromFtpPercent(seg.powerPercentFTP).color.copy(alpha = alpha)
                )
            }

            val cursorWidth = 2.dp.toPx()
            drawRoundRect(
                color = CadenceColors.Fg1,
                topLeft = Offset(
                    (progress * w).coerceIn(0f, w - cursorWidth),
                    -4.dp.toPx()
                ),
                size = Size(cursorWidth, h + 4.dp.toPx()),
                cornerRadius = CornerRadius(cursorWidth / 2, cursorWidth / 2)
            )
        }
    }
}

/** A bar with its two top corners rounded and its foot square. */
private fun topRoundedBar(x: Float, y: Float, width: Float, height: Float, radius: Float): Path {
    val r = radius.coerceAtMost(minOf(width, height) / 2f)
    return Path().apply {
        moveTo(x, y + height)
        lineTo(x, y + r)
        arcTo(Rect(x, y, x + 2 * r, y + 2 * r), 180f, 90f, false)
        lineTo(x + width - r, y)
        arcTo(Rect(x + width - 2 * r, y, x + width, y + 2 * r), 270f, 90f, false)
        lineTo(x + width, y + height)
        close()
    }
}
