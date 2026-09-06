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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * Three planes, all slate:
 *  - Far ridge: procedural, low-amplitude, Bg1 at 55 % - pure atmosphere.
 *  - Mid ridge: the workout's own power profile drawn as terrain, Bg2 at 50 %,
 *    with a crest line that is the one thing here the zone colour touches.
 *  - Near silhouette: Bg0 at 90 %, anchoring the bottom edge.
 *
 * The star field and the dashed contour rule are gone. Both were texture on a
 * screen whose whole job is one number, and neither survived a palette where
 * the background and the foreground are the same family of blues.
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
    val workoutProfile = remember(workout) {
        workout.segments.flatMap { seg ->
            // Sample segment power level at a few points so smoothing has data to work with.
            List(3) { seg.powerPercentFTP.toFloat() }
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

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
            drawPath(path = path, color = CadenceColors.Bg1.copy(alpha = 0.55f))
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
            drawPath(path = path, color = CadenceColors.Bg2.copy(alpha = 0.5f))

            // Crest line — the only place on the backdrop the zone reaches.
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
                style = Stroke(width = 1.2.dp.toPx())
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
            drawPath(path = path, color = CadenceColors.Bg0.copy(alpha = 0.9f))
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
 * The big power-readout block: the current wattage at a size nothing else on
 * the screen approaches, in the current zone's colour.
 *
 * This is the one place in the app where colour lands on type. A rider on a
 * trainer is reading this from a metre away, out of the corner of an eye, while
 * breathing hard - so the number says both how hard they are going and which
 * zone that is, without them having to look anywhere else.
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
    /**
     * One tap of the stepper, as a direction: -1 or +1. What a step *means* is
     * the caller's business - a percentage of the interval's target on a
     * structured workout, a fixed number of watts on a free ride.
     */
    onAdjust: (Int) -> Unit,
    /**
     * Free ride: the ± buttons move an absolute wattage rather than a
     * difficulty bias, which is all the labelling needs to say differently.
     */
    freeRide: Boolean = false,
    modifier: Modifier = Modifier,
    // Sizes default to the phone HUD; the ride screen passes larger values on
    // expanded-width screens (landscape tablets / unfolded foldables) so the
    // power number stays glanceable from across a room.
    powerFontSize: TextUnit = 132.sp,
    numberWidth: Dp = 320.dp,
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

    // "On target" is the rider doing their one job right - reward it. Within
    // ±3 % (min ±5 W) of the ERG target, a soft zone-colored halo breathes
    // behind the number and the eyebrow flips to ON TARGET. Purely additive:
    // the glow renders behind the fixed-width frame, so nothing moves.
    val onTarget = ergLocked && !isStale && targetWatts > 0 &&
        abs(powerWatts - targetWatts) <= maxOf(5, targetWatts * 3 / 100)
    val glowAlpha by animateFloatAsState(
        targetValue = if (onTarget) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "targetGlow"
    )
    val breath = rememberInfiniteTransition(label = "glowBreath")
    val breathPulse by breath.animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowBreathPulse"
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
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (ergLocked) zoneColor else CadenceColors.Fg4)
            )
            Text(
                text = when {
                    onTarget -> "Power · on target"
                    ergLocked -> "Power · ERG locked"
                    else -> "Power · ERG idle"
                },
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
            if (glowAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    zoneColor.copy(alpha = 0.16f * glowAlpha * breathPulse),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }
            // Baseline-aligned, not bottom-aligned: at 132 sp the digits' own
            // descender space is 20 dp of it, and a unit hung off the box's
            // bottom edge floats well below the number it belongs to.
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (isStale) "--" else animatedPower.toString(),
                    style = CadenceType.displayXl.copy(
                        fontSize = powerFontSize,
                        lineHeight = powerFontSize
                    ),
                    color = zoneColor,
                    modifier = Modifier.alignByBaseline()
                )
                Text(
                    text = " W",
                    style = CadenceType.h1.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.sp
                    ),
                    color = CadenceColors.Fg2,
                    modifier = Modifier.alignByBaseline()
                )
            }
        }

        WattsPerKgBar(wkgCenti = animatedWkgCenti, zoneColor = zoneColor, barWidth = wkgBarWidth)

        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val decreaseLabel = if (freeRide) "Decrease target power" else "Decrease difficulty"
            val increaseLabel = if (freeRide) "Increase target power" else "Increase difficulty"
            StepperPill(label = "−", contentDescription = decreaseLabel) { onAdjust(-1) }
            TargetPill(targetWatts = targetWatts, biasPercent = biasPercent)
            StepperPill(label = "+", contentDescription = increaseLabel) { onAdjust(1) }
        }
    }
}

/**
 * Thin power-to-weight indicator. Bar fills at 5.0 W/kg (the rough boundary
 * into "elite-amateur" sustained-power territory) so the typical training
 * range sits in the middle third.
 *
 * Flat Fg1 on Bg2: the zone is already stated by the number above it, twice
 * over, and a third zone-tinted element under it only competed.
 *
 * @param wkgCenti current W/kg expressed as integer centiwatts/kg (e.g. 245 = 2.45 W/kg).
 *   Using ints keeps the animator stable and avoids float-tween jitter at 1 Hz.
 */
@Composable
internal fun WattsPerKgBar(wkgCenti: Int, zoneColor: Color, barWidth: Dp = 220.dp) {
    val safe = wkgCenti.coerceAtLeast(0)
    val fraction = (safe / 500f).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .padding(top = 6.dp)
            .width(barWidth)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(CadenceColors.Bg2)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(3.dp)
                .background(CadenceColors.Fg1)
        )
    }
    Text(
        text = if (safe > 0) "${formatWkg(safe)} W/kg" else "--",
        style = CadenceType.tick,
        color = CadenceColors.Fg3,
        modifier = Modifier.padding(top = 6.dp)
    )
}

private fun formatWkg(centi: Int): String {
    val whole = centi / 100
    val frac = centi % 100
    // Always show two decimals so the readout doesn't shift width as the value crosses .x0.
    return "$whole.${frac.toString().padStart(2, '0')}"
}

@Composable
private fun StepperPill(label: String, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(CadenceColors.Bg1)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = CadenceType.h2.copy(fontWeight = FontWeight.Bold),
            color = CadenceColors.Fg1
        )
    }
}

@Composable
private fun TargetPill(targetWatts: Int, biasPercent: Int) {
    val biasLabel = when {
        biasPercent > 0 -> "+$biasPercent%"
        biasPercent < 0 -> "$biasPercent%"
        else -> "0%"
    }
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(CadenceColors.Bg1)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = "Target", style = CadenceType.small, color = CadenceColors.Fg3)
        Text(
            text = "$targetWatts W",
            style = CadenceType.h3.copy(fontWeight = FontWeight.ExtraBold),
            color = CadenceColors.Fg1
        )
        Text(text = "· $biasLabel", style = CadenceType.tick, color = CadenceColors.Fg3)
    }
}
