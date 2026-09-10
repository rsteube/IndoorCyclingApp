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

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.shared.route.RoutePosition
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.shared.simulation.Drivetrain
import app.indoorbike.ui.screens.CadenceFlankMetric
import app.indoorbike.ui.screens.FlankMetric
import app.indoorbike.ui.screens.WattsPerKgBar
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.DisplayFontFamily
import app.indoorbike.ui.theme.Spacing

/**
 * The heads-up display for a simulated route ride.
 *
 * A workout HUD is built around one number, the target, because a workout is a
 * contract: hold this wattage. A route has no contract, so this is built around
 * three things the road is doing to the rider instead - where they are, how
 * steep it is here, and what is coming - with their own effort read back
 * alongside.
 *
 * The reading order is deliberate and it is the same in both layouts:
 *
 *  1. **The map**, biggest, because it is the only element that says *where*.
 *  2. **Gradient and gear**, side by side, because they are a pair: the
 *     gradient is the question and the gear is the rider's answer to it.
 *  3. **Power, cadence, heart rate**, the rider's own numbers.
 *  4. **The elevation profile and progress**, pinned to the bottom, which is
 *     where a rider looks when they want to know how much is left rather than
 *     what is happening now.
 *
 * The layout is chosen from the space this composable actually gets rather
 * than from a global breakpoint, matching how the workout HUD decides, so it
 * behaves in split-screen and on a half-open foldable.
 */
@Composable
fun SimulationHud(
    route: RouteTrack,
    position: RoutePosition?,
    distanceMeters: Double,
    gradient: Double,
    speedMps: Double,
    drivetrain: Drivetrain,
    gearIndex: Int,
    elevationGainMeters: Double,
    powerWatts: Int,
    isPowerStale: Boolean,
    wattsPerKg: Float,
    cadenceRpm: Int,
    heartRate: Int,
    isHrStale: Boolean,
    hrColor: Color,
    hrSublabel: String?,
    zoneColor: Color,
    onShiftDown: () -> Unit,
    onShiftUp: () -> Unit,
    shiftingEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        // Captured out of the BoxWithConstraints scope: the layout lambdas
        // below introduce their own scopes, which shadow the implicit receiver.
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        // Side by side needs width to spare *relative to height*, not just an
        // absolute number. A tablet held upright is 800 dp wide, which passes
        // any width test, and putting a map in a narrow column beside a column
        // of air is the wrong answer for it: 1280 dp of height is exactly what
        // the stacked layout is for.
        val wide = availableWidth >= 600.dp && availableWidth >= availableHeight
        val tall = availableHeight >= 600.dp
        val expanded = availableWidth >= 840.dp && tall
        // A landscape phone is wide but genuinely short, around 400dp.
        val short = availableHeight < 460.dp

        val map = @Composable { mapModifier: Modifier ->
            RouteMapPanel(
                route = route,
                position = position,
                modifier = mapModifier,
            )
        }
        val profile = @Composable { height: Dp ->
            ElevationProfile(
                route = route,
                distanceMeters = distanceMeters,
                modifier = Modifier.fillMaxWidth().height(height),
            )
        }
        val progress = @Composable {
            RouteProgressStrip(
                distanceMeters = distanceMeters,
                totalDistanceMeters = route.totalDistanceMeters,
                elevationGainMeters = elevationGainMeters,
                totalAscentMeters = route.totalAscentMeters,
                speedKmh = speedMps * 3.6,
            )
        }
        val gauges = @Composable { compact: Boolean ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
            ) {
                GradientReadout(
                    gradient = gradient,
                    compact = compact,
                    valueFontSize = if (compact) 34.sp else 44.sp,
                    route = route,
                    distanceMeters = distanceMeters,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                GearShifter(
                    drivetrain = drivetrain,
                    gearIndex = gearIndex,
                    speedMps = speedMps,
                    onShiftDown = onShiftDown,
                    onShiftUp = onShiftUp,
                    enabled = shiftingEnabled,
                    compact = compact,
                    modifier = Modifier.weight(1.55f).fillMaxHeight(),
                )
            }
        }
        val rider = @Composable { flankWidth: Dp, flankFont: TextUnit ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s3, Alignment.CenterHorizontally),
            ) {
                CadenceFlankMetric(
                    rpm = cadenceRpm,
                    target = null,
                    cardWidth = flankWidth,
                    valueFontSize = flankFont,
                    compact = short,
                    modifier = Modifier.fillMaxHeight(),
                )
                FlankMetric(
                    label = "Heart rate",
                    value = if (isHrStale) "--" else heartRate.toString(),
                    unit = "bpm",
                    isStale = isHrStale,
                    valueColor = hrColor,
                    // On a landscape phone this row is the last thing to fit,
                    // and the zone caption is the one part of it the colour of
                    // the number already says.
                    sublabel = if (short) null else hrSublabel,
                    cardWidth = flankWidth,
                    valueFontSize = flankFont,
                    compact = short,
                    modifier = Modifier.fillMaxHeight(),
                )
            }
        }

        if (wide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.s4)
                    // A HUD read at a glance should not have anything sitting
                    // flush against the bottom edge of the screen.
                    .padding(bottom = Spacing.s2),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s4),
            ) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    map(Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(Spacing.s2))
                    profile(
                        when {
                            expanded -> 96.dp
                            short -> 52.dp
                            else -> 64.dp
                        }
                    )
                    Spacer(Modifier.height(Spacing.s2))
                    progress()
                }
                // This column has to fit a power hero, two gauges and two
                // metric cards into that height, so on a short screen
                // everything steps down together rather than letting the last
                // row fall off the bottom edge.
                Column(
                    modifier = Modifier
                        .width(if (expanded) 400.dp else 320.dp)
                        .fillMaxHeight(),
                    // Two different problems. On a landscape phone the column
                    // is shorter than its contents and the question is what
                    // gives way; on a tablet it is taller and the question is
                    // where the slack goes. Spreading it evenly beats pouring
                    // all of it into the power block, which left the number
                    // floating in the middle of an empty half-screen.
                    // Keyed on whether the column is short of height, not on
                    // how wide the screen is. A tablet held upright is 1280 dp
                    // tall and not "expanded", and it has more slack than any
                    // other case: keying this on expanded left the power
                    // number stranded in half a screen of nothing.
                    verticalArrangement = if (short) {
                        Arrangement.spacedBy(Spacing.s2)
                    } else {
                        Arrangement.SpaceEvenly
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // The gauges and the rider's own numbers take their height
                    // first and the power number lives on what is left, sized
                    // from it. The other way round - fixed type sizes stacked
                    // and hoped to fit - is what pushed cadence and heart rate
                    // off the bottom of a landscape phone: a Column does not
                    // shrink its children, it just runs past the edge, and the
                    // things that fell off were the two the rider cannot
                    // reconstruct from anything else on screen.
                    BoxWithConstraints(
                        // Only claim the leftover height where there is a
                        // shortage to manage. With room to spare the block
                        // takes its natural size and the column spaces it.
                        modifier = (if (short) Modifier.weight(1f) else Modifier)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Eyebrow, W/kg bar and its caption, plus the gaps.
                        val room = (this@BoxWithConstraints.maxHeight - 52.dp).value
                        val powerFont = minOf(
                            room,
                            if (expanded) 128f else 84f,
                        ).coerceAtLeast(34f).sp
                        SimulationPowerReadout(
                            powerWatts = powerWatts,
                            isStale = isPowerStale,
                            wattsPerKg = wattsPerKg,
                            zoneColor = zoneColor,
                            powerFontSize = powerFont,
                            barWidth = if (expanded) 280.dp else 200.dp,
                        )
                    }
                    gauges(!expanded)
                    rider(
                        when {
                            expanded -> 190.dp
                            short -> 140.dp
                            else -> 148.dp
                        },
                        when {
                            expanded -> 34.sp
                            short -> 20.sp
                            else -> 26.sp
                        },
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.s4),
                verticalArrangement = Arrangement.spacedBy(Spacing.s3),
            ) {
                map(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .heightIn(min = 140.dp)
                )
                gauges(!tall)
                SimulationPowerReadout(
                    powerWatts = powerWatts,
                    isStale = isPowerStale,
                    wattsPerKg = wattsPerKg,
                    zoneColor = zoneColor,
                    powerFontSize = if (tall) 76.sp else 60.sp,
                    barWidth = 220.dp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                rider((availableWidth - Spacing.s4 * 2 - Spacing.s3) / 2, 26.sp)
                profile(if (tall) 72.dp else 56.dp)
                progress()
                Spacer(Modifier.height(Spacing.s1))
            }
        }
    }
}

/**
 * The map plus the two things that have to sit on top of it: the follow toggle
 * and OpenStreetMap's attribution, which its tile licence requires wherever the
 * tiles are shown.
 */
@Composable
private fun RouteMapPanel(
    route: RouteTrack,
    position: RoutePosition?,
    modifier: Modifier = Modifier,
) {
    // Follow is the default because that is what a rider wants nine tenths of
    // the time. The toggle is for the other tenth: seeing how far round the
    // loop they actually are.
    var followRider by remember { mutableStateOf(true) }
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .clip(shape)
    ) {
        RouteMapView(
            route = route,
            position = position,
            followRider = followRider,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(Spacing.s2)
                .clip(RoundedCornerShape(50))
                .background(CadenceColors.Bg0.copy(alpha = 0.78f))
                .clickable { followRider = !followRider }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = if (followRider) "Following" else "Whole route",
                style = CadenceType.eyebrow,
                color = if (followRider) CadenceColors.Fg1 else CadenceColors.Fg2,
            )
        }
        Text(
            text = "© OpenStreetMap",
            style = CadenceType.tick.copy(fontSize = 9.sp),
            color = CadenceColors.Fg2,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(CadenceColors.Bg0.copy(alpha = 0.62f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/**
 * Power, without the ERG furniture.
 *
 * The workout HUD's hero carries a target pill, a bias percentage and an "ERG
 * LOCKED" state, none of which exist here: on a route nothing is holding the
 * rider to a number. What is left is the number itself, coloured by the zone
 * their effort actually falls in, which on a route is information rather than
 * decoration - it is how a rider knows they are burning a match on a climb they
 * are only a third of the way up.
 */
@Composable
private fun SimulationPowerReadout(
    powerWatts: Int,
    isStale: Boolean,
    wattsPerKg: Float,
    zoneColor: Color,
    powerFontSize: TextUnit,
    barWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val animatedPower by animateIntAsState(
        targetValue = if (isStale) 0 else powerWatts,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "sim-power"
    )
    val animatedWkgCenti by animateIntAsState(
        targetValue = if (isStale) 0 else (wattsPerKg * 100f).toInt().coerceIn(0, 2000),
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "sim-wkg"
    )

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "Power", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (isStale) "--" else animatedPower.toString(),
                fontSize = powerFontSize,
                lineHeight = powerFontSize,
                fontWeight = FontWeight.Normal,
                fontFamily = DisplayFontFamily,
                color = zoneColor,
            )
            Text(
                text = " W",
                style = CadenceType.h2,
                color = CadenceColors.Fg2,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        WattsPerKgBar(wkgCenti = animatedWkgCenti, zoneColor = zoneColor, barWidth = barWidth)
    }
}
