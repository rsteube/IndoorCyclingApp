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
package app.indoorbike.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.indoorbike.ui.theme.CadenceColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Ridges in the space a wide screen leaves beside the content.
 *
 * Capping a list at [ContentMaxWidth] fixes the reading and leaves a tall empty
 * column on a tablet. This puts the ride screen's own horizon in it: the same
 * layered slate planes as [app.indoorbike.ui.screens.HorizonRidgeBackdrop],
 * quieter, because that one backs a single number and this one sits beside
 * things people are reading.
 *
 * Deliberately not:
 *  - **Animated.** It is the wallpaper behind a list. Anything moving out
 *    there would pull the eye away from the thing the eye came for.
 *  - **Coloured.** The palette spends saturation on power zones and nothing
 *    else, so a decorative graphic is the last place to start.
 *  - **Random per frame.** The shape comes from fixed sine components, so it
 *    is the same hill every time the screen is drawn rather than a horizon
 *    that reshuffles on recomposition.
 *
 * Nothing is drawn until the margin is at least [MIN_MARGIN], so a phone and a
 * tablet held upright, where the content already fills the width, pay only a
 * width comparison.
 */
fun Modifier.marginRidges(contentWidth: Dp = ContentMaxWidth): Modifier = drawBehind {
    val marginStart = contentWidth.toPx()
    val marginWidth = size.width - marginStart
    if (marginWidth < MIN_MARGIN.toPx()) return@drawBehind
    drawRidges(marginStart, marginWidth, size.height)
}

/**
 * Three planes, far to near, each lower and more opaque than the last, so the
 * column reads as depth rather than as a pattern. Alphas are well under the
 * ride screen's: there the ridge is the backdrop to one number, here it shares
 * a screen with text.
 */
private fun DrawScope.drawRidges(x0: Float, width: Float, height: Float) {
    data class Plane(
        val baseline: Float,
        val amplitude: Float,
        val alpha: Float,
        val phase: Float,
        val crest: Boolean,
    )

    val planes = listOf(
        Plane(baseline = 0.58f, amplitude = 0.055f, alpha = 0.30f, phase = 0.0f, crest = false),
        Plane(baseline = 0.72f, amplitude = 0.085f, alpha = 0.45f, phase = 1.7f, crest = true),
        Plane(baseline = 0.86f, amplitude = 0.050f, alpha = 0.60f, phase = 3.1f, crest = false),
    )

    for (plane in planes) {
        val base = height * plane.baseline
        val amp = height * plane.amplitude
        val path = Path()
        val steps = 48
        // Two sine components of different periods: one hill would read as a
        // wave, and this has to read as ground.
        fun yAt(t: Float): Float {
            val a = sin((t * 2.2f + plane.phase) * PI.toFloat())
            val b = sin((t * 5.3f + plane.phase * 0.6f) * PI.toFloat()) * 0.35f
            return base - (a + b) * amp
        }

        path.moveTo(x0, height)
        path.lineTo(x0, yAt(0f))
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            path.lineTo(x0 + t * width, yAt(t))
        }
        path.lineTo(x0 + width, height)
        path.close()
        drawPath(path, color = CadenceColors.Bg2.copy(alpha = plane.alpha * 0.5f))

        // One crest line, on the middle plane only. It is what stops the three
        // fills reading as a single flat shape.
        if (plane.crest) {
            val line = Path()
            line.moveTo(x0, yAt(0f))
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                line.lineTo(x0 + t * width, yAt(t))
            }
            drawPath(
                line,
                color = CadenceColors.Fg4.copy(alpha = 0.22f),
                style = Stroke(width = 1.5f),
            )
        }
    }

    // A single faint contour above the ridges, echoing the elevation profiles
    // that appear all over this app. One line, because two would be a texture.
    val contourY = height * 0.44f
    drawLine(
        color = CadenceColors.Fg4.copy(alpha = 0.10f),
        start = Offset(x0, contourY),
        end = Offset(x0 + width, contourY),
        strokeWidth = 1f,
    )
}

/**
 * Below this the margin is a gap rather than a space, and a horizon squeezed
 * into it would read as an artefact.
 */
private val MIN_MARGIN = 220.dp
