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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.shared.Workout
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.zones.PowerZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Layered horizon-ridge backdrop for the ride screen.
 *
 * Three planes:
 *  - Far ridge: procedural, low-amplitude, very faint — pure atmosphere.
 *  - Mid ridge: derived from the actual workout segment power profile, so the
 *    rider's effort profile is literally drawn as the horizon. Tinted by the
 *    current zone color and smoothly animated when the zone changes.
 *  - Near ridge: a darker silhouette anchored to the bottom edge, gives the
 *    "I'm in a valley looking up" depth cue.
 *
 * Above all three: a small scattering of "stars" in the upper-left, tuned for
 * the gravel-night feel without competing with the metrics.
 */
@Composable
fun HorizonRidgeBackdrop(
    workout: Workout,
    tintColor: Color,
    modifier: Modifier = Modifier,
) {
    // tintColor is already animated by the caller — we just consume it.
    val animatedTint = tintColor

    // Cache the procedural far-ridge points so they don't re-randomize every recomposition.
    val farPeaks = remember(workout.id) { generateFarPeaks(seed = workout.id.hashCode()) }
    val starField = remember(workout.id) { generateStars(seed = workout.id.hashCode() xor 0x5A) }
    val workoutProfile = remember(workout) {
        workout.segments.flatMap { seg ->
            // Sample segment power level at a few points so smoothing has data to work with.
            List(3) { seg.powerPercentFTP.toFloat() }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Star scatter — subtle, top quartile.
        starField.forEach { (xN, yN, alpha) ->
            drawCircle(
                color = CadenceColors.Fg2.copy(alpha = alpha),
                radius = 1.4f,
                center = Offset(xN * w, yN * h * 0.45f)
            )
        }

        // FAR ridge — slow procedural sine, very low contrast, low on screen.
        run {
            val ridgeBase = h * 0.78f
            val amplitude = h * 0.07f
            val path = Path().apply {
                moveTo(0f, h)
                lineTo(0f, ridgeBase)
                farPeaks.forEachIndexed { i, peak ->
                    val x = (i.toFloat() / (farPeaks.size - 1)) * w
                    lineTo(x, ridgeBase - peak * amplitude)
                }
                lineTo(w, h)
                close()
            }
            drawPath(
                path = path,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        animatedTint.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    startY = ridgeBase - amplitude,
                    endY = h
                )
            )
        }

        // MID ridge — the workout's own effort profile as terrain.
        run {
            val ridgeBase = h * 0.62f
            val amplitudeMax = h * 0.30f
            val pts = smoothProfile(workoutProfile)
            val maxPct = (pts.maxOrNull() ?: 100f).coerceAtLeast(60f)
            val path = Path()
            val firstY = ridgeBase - (pts.first() / maxPct) * amplitudeMax
            path.moveTo(0f, h)
            path.lineTo(0f, firstY)
            for (i in 1 until pts.size) {
                val x = (i.toFloat() / (pts.size - 1)) * w
                val y = ridgeBase - (pts[i] / maxPct) * amplitudeMax
                path.lineTo(x, y)
            }
            path.lineTo(w, h)
            path.close()
            drawPath(
                path = path,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        animatedTint.copy(alpha = 0.34f),
                        animatedTint.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    startY = ridgeBase - amplitudeMax,
                    endY = h
                )
            )
            // Crest line — the bright edge of the ridge.
            val crest = Path()
            crest.moveTo(0f, firstY)
            for (i in 1 until pts.size) {
                val x = (i.toFloat() / (pts.size - 1)) * w
                val y = ridgeBase - (pts[i] / maxPct) * amplitudeMax
                crest.lineTo(x, y)
            }
            drawPath(
                path = crest,
                color = animatedTint.copy(alpha = 0.55f),
                style = Stroke(width = 1.2f)
            )
        }

        // NEAR ridge — dark silhouette, foreground anchor.
        run {
            val ridgeBase = h * 0.92f
            val amplitude = h * 0.06f
            val rng = Random(workout.id.hashCode() xor 0xA1)
            val controls = (0..7).map { rng.nextFloat() }
            val path = Path()
            path.moveTo(0f, h)
            path.lineTo(0f, ridgeBase)
            controls.forEachIndexed { i, peak ->
                val x = (i.toFloat() / 7f) * w
                val y = ridgeBase - peak * amplitude
                path.lineTo(x, y)
            }
            path.lineTo(w, h)
            path.close()
            drawPath(
                path = path,
                color = CadenceColors.Bg0.copy(alpha = 0.85f)
            )
        }

        // Faint topographic contour line near the upper third — the "altitude rule".
        run {
            val y = h * 0.34f
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 14f), 0f)
            drawLine(
                color = CadenceColors.Line2.copy(alpha = 0.7f),
                start = Offset(w * 0.06f, y),
                end = Offset(w * 0.94f, y),
                strokeWidth = 0.8f,
                pathEffect = dashEffect
            )
        }
    }
}

private fun generateFarPeaks(seed: Int, count: Int = 12): List<Float> {
    val rng = Random(seed)
    return (0 until count).map {
        // Combine two sines + a small random for organic peaks.
        val phase = it.toFloat() / count * 2f * PI.toFloat()
        val sineA = (sin(phase * 1.1f) + 1f) * 0.35f
        val sineB = (cos(phase * 2.3f + 0.4f) + 1f) * 0.20f
        val noise = rng.nextFloat() * 0.20f
        (sineA + sineB + noise).coerceIn(0f, 1f)
    }
}

private fun generateStars(seed: Int, count: Int = 18): List<Triple<Float, Float, Float>> {
    val rng = Random(seed)
    return (0 until count).map {
        Triple(rng.nextFloat(), rng.nextFloat(), 0.20f + rng.nextFloat() * 0.45f)
    }
}

/**
 * Mild moving-average smoothing so the workout's blocky FTP% steps render as
 * a flowing ridge rather than a staircase.
 */
private fun smoothProfile(values: List<Float>, window: Int = 3): List<Float> {
    if (values.size <= window) return values
    val out = FloatArray(values.size)
    for (i in values.indices) {
        var sum = 0f
        var n = 0
        for (k in -window..window) {
            val idx = i + k
            if (idx in values.indices) {
                sum += values[idx]
                n++
            }
        }
        out[i] = if (n > 0) sum / n else values[i]
    }
    return out.toList()
}

/**
 * The big power-readout block — Bebas Neue, very large, with a thin altitude-bar
 * that grows with current power and a small "ERG LOCKED" status.
 */
@Composable
fun HeroPowerReadout(
    powerWatts: Int,
    isStale: Boolean,
    targetWatts: Int,
    wattsPerKg: Float,
    zoneColor: Color,
    biasPercent: Int,
    ergLocked: Boolean,
    onAdjust: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // Sizes default to the phone HUD; the ride screen passes larger values on
    // expanded-width screens (landscape tablets / unfolded foldables) so the
    // power number stays glanceable from across a room.
    powerFontSize: TextUnit = 112.sp,
    numberWidth: Dp = 280.dp,
    wkgBarWidth: Dp = 220.dp,
) {
    // Smoothly tween power and W/kg across 1-second BLE samples. We tween W/kg
    // as an Int * 100 (centiwatts/kg) so the existing Int animator works without
    // pulling in a Float animator just for one readout.
    val animatedPower by animateIntAsState(
        targetValue = if (isStale) 0 else powerWatts,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "power"
    )
    val animatedWkgCenti by animateIntAsState(
        targetValue = if (isStale) 0 else (wattsPerKg * 100f).toInt().coerceIn(0, 2000),
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "wkg"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Eyebrow with status dot
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (ergLocked) zoneColor else CadenceColors.Fg4)
            )
            Text(
                text = "POWER · ${if (ergLocked) "ERG LOCKED" else "ERG IDLE"}",
                style = CadenceType.eyebrow,
                color = if (ergLocked) zoneColor else CadenceColors.Fg3
            )
        }
        Spacer(Modifier.height(2.dp))

        // Fixed-width frame so digit count changes don't shift the rest of the row.
        Box(
            modifier = Modifier.width(numberWidth),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (isStale) "--" else animatedPower.toString(),
                    fontSize = powerFontSize,
                    lineHeight = powerFontSize,
                    fontWeight = FontWeight.Normal,
                    fontFamily = app.indoorbike.ui.theme.DisplayFontFamily,
                    fontStyle = FontStyle.Normal,
                    color = zoneColor
                )
                Text(
                    text = " W",
                    style = CadenceType.h1,
                    color = CadenceColors.Fg2,
                    modifier = Modifier.padding(bottom = 14.dp)
                )
            }
        }

        WattsPerKgBar(wkgCenti = animatedWkgCenti, zoneColor = zoneColor, barWidth = wkgBarWidth)

        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StepperPill(label = "−", contentDescription = "Decrease difficulty", zoneColor = zoneColor) { onAdjust(-5) }
            TargetPill(targetWatts = targetWatts, biasPercent = biasPercent, ergLocked = ergLocked, zoneColor = zoneColor)
            StepperPill(label = "+", contentDescription = "Increase difficulty", zoneColor = zoneColor) { onAdjust(5) }
        }
    }
}

/**
 * Thin power-to-weight indicator. Bar fills at 5.0 W/kg (the rough boundary
 * into "elite-amateur" sustained-power territory) so the typical training
 * range sits in the middle third.
 *
 * @param wkgCenti current W/kg expressed as integer centiwatts/kg (e.g. 245 = 2.45 W/kg).
 *   Using ints keeps the animator stable and avoids float-tween jitter at 1 Hz.
 */
@Composable
private fun WattsPerKgBar(wkgCenti: Int, zoneColor: Color, barWidth: Dp = 220.dp) {
    val safe = wkgCenti.coerceAtLeast(0)
    val fraction = (safe / 500f).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .padding(top = 6.dp)
            .width(barWidth)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(CadenceColors.Bg2.copy(alpha = 0.6f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(3.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(zoneColor.copy(alpha = 0.4f), zoneColor)
                    )
                )
        )
    }
    Text(
        text = if (safe > 0) "${formatWkg(safe)} W/kg" else "—",
        style = CadenceType.tick.copy(fontSize = 10.sp),
        color = CadenceColors.Fg3,
    )
}

private fun formatWkg(centi: Int): String {
    val whole = centi / 100
    val frac = centi % 100
    // Always show two decimals so the readout doesn't shift width as the value crosses .x0.
    return "$whole.${frac.toString().padStart(2, '0')}"
}

@Composable
private fun StepperPill(label: String, contentDescription: String, zoneColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(CadenceColors.Bg2)
            .border(1.5.dp, zoneColor.copy(alpha = 0.45f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = CadenceType.h2,
            color = zoneColor
        )
    }
}

@Composable
private fun TargetPill(targetWatts: Int, biasPercent: Int, ergLocked: Boolean, zoneColor: Color) {
    val biasLabel = when {
        biasPercent > 0 -> " · +$biasPercent%"
        biasPercent < 0 -> " · $biasPercent%"
        else -> ""
    }
    Box(
        modifier = Modifier
            .widthIn(min = 130.dp)
            .clip(RoundedCornerShape(50))
            .background(CadenceColors.Bg1)
            .border(1.dp, zoneColor.copy(alpha = if (ergLocked) 0.5f else 0.2f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "TARGET $targetWatts W$biasLabel",
            style = CadenceType.bodyStrong.copy(fontSize = 13.sp),
            color = CadenceColors.Fg1
        )
    }
}
