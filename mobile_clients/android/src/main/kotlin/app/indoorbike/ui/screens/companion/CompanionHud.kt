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
package app.indoorbike.ui.screens.companion

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.shared.companion.RemoteControls
import app.indoorbike.shared.simulation.Drivetrain
import app.indoorbike.ui.screens.CadenceFlankMetric
import app.indoorbike.ui.screens.FlankMetric
import app.indoorbike.ui.screens.simulation.GearShifter
import app.indoorbike.ui.screens.simulation.GradientReadout
import app.indoorbike.ui.screens.simulation.formatOneDecimal
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.DisplayFontFamily
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * The HUD for a companion ride.
 *
 * This is [app.indoorbike.ui.screens.simulation.SimulationHud] with its three
 * "where" elements removed, and that is exactly the point: the map, the
 * elevation profile and the route progress bar are the parts of a route ride
 * that say *where the rider is*, and where the rider is is precisely what this
 * mode does not know. What is left is what the app does know - the road under
 * the wheel, the gear the rider chose, and what their body is doing about it.
 *
 * Reading order, deliberately the same as a route ride so the two modes do not
 * teach different habits: the road and the rider's answer to it first, then the
 * rider themselves, then the totals.
 *
 * The gauges are the shipped ones rather than copies. `GradientReadout` is
 * drawn without a route, which is the bare-number form it had before route
 * mode gained terrain behind it.
 */
@Composable
fun CompanionHud(
    gradient: Double,
    speedMps: Double,
    distanceMeters: Double,
    elevationGainMeters: Double,
    drivetrain: Drivetrain,
    gearIndex: Int,
    powerWatts: Int,
    isPowerStale: Boolean,
    wattsPerKg: Float,
    cadenceRpm: Int,
    heartRate: Int,
    isHrStale: Boolean,
    hrColor: Color,
    hrSublabel: String?,
    zoneColor: Color,
    /** Name of the companion driving, shown so the rider knows what is in charge. */
    companionName: String?,
    /** True once the companion has gone quiet past its lease and the road is flattening. */
    roadStale: Boolean,
    onShiftDown: () -> Unit,
    onShiftUp: () -> Unit,
    shiftingEnabled: Boolean,
    /** A controllable screen the companion is showing, or null for none. */
    remoteControls: RemoteControls? = null,
    onControlPress: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        // Same test route mode uses: side by side needs width to spare relative
        // to height, not merely an absolute number, or a tablet held upright
        // passes a width check and then gets a squeezed two-column layout.
        val wide = availableWidth >= 640.dp && availableWidth > availableHeight
        val short = availableHeight < 420.dp

        val flankWidth: Dp = if (wide) 190.dp else 150.dp
        val flankFont: TextUnit = when {
            wide -> 44.sp
            short -> 30.sp
            else -> 36.sp
        }
        val powerFont: TextUnit = when {
            wide -> 92.sp
            short -> 56.sp
            else -> 76.sp
        }

        val road = @Composable { compact: Boolean, slot: Modifier ->
            Row(
                modifier = slot.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
            ) {
                GradientReadout(
                    gradient = gradient,
                    compact = compact,
                    valueFontSize = if (compact) 32.sp else 44.sp,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                GearShifter(
                    drivetrain = drivetrain,
                    gearIndex = gearIndex,
                    speedMps = speedMps,
                    onShiftDown = onShiftDown,
                    onShiftUp = onShiftUp,
                    compact = compact,
                    enabled = shiftingEnabled,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }

        val rider = @Composable { slot: Modifier ->
            Row(
                modifier = slot.fillMaxWidth().height(IntrinsicSize.Max),
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
                    sublabel = if (short) null else hrSublabel,
                    cardWidth = flankWidth,
                    valueFontSize = flankFont,
                    compact = short,
                    modifier = Modifier.fillMaxHeight(),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.s4)
                .padding(bottom = Spacing.s2),
            verticalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            CompanionSourceStrip(companionName = companionName, roadStale = roadStale)

            if (wide) {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Cards keep their natural height and the group is
                    // centred in whatever is left. A tablet has more room than
                    // this HUD has content - it is the route HUD without the
                    // map - and centring reads as a deliberate composition
                    // where stretching read as four empty boxes.
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(
                            Spacing.s3, Alignment.CenterVertically,
                        ),
                    ) {
                        road(false, Modifier)
                        rider(Modifier)
                    }
                    CompanionPowerReadout(
                        powerWatts = powerWatts,
                        isStale = isPowerStale,
                        wattsPerKg = wattsPerKg,
                        zoneColor = zoneColor,
                        powerFontSize = powerFont,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                road(short, Modifier)
                CompanionPowerReadout(
                    powerWatts = powerWatts,
                    isStale = isPowerStale,
                    wattsPerKg = wattsPerKg,
                    zoneColor = zoneColor,
                    powerFontSize = powerFont,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                rider(Modifier)
            }

            CompanionTotals(
                speedMps = speedMps,
                distanceMeters = distanceMeters,
                elevationGainMeters = elevationGainMeters,
            )
        }

        // Over the HUD rather than in it. The pad is a visitor: it belongs to a
        // menu on somebody else's screen, it comes and goes on that menu's
        // schedule, and the ride's own readouts must not reflow every time it
        // does. Bottom-end so it falls under the hand already on the bars.
        if (remoteControls != null) {
            CompanionControlPad(
                controls = remoteControls,
                onPress = onControlPress,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.s4),
            )
        }
    }
}

/**
 * Who is driving, and whether they are still talking.
 *
 * A rider whose pedals just went light deserves to know it was the game that
 * stopped rather than their legs, so the quiet state is stated rather than
 * left to be inferred from a gradient sliding towards zero.
 */
@Composable
private fun CompanionSourceStrip(
    companionName: String?,
    roadStale: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = when {
        companionName == null -> "No companion connected - flat road"
        roadStale -> companionName + " has gone quiet - flattening the road"
        else -> "Driven by " + companionName
    }
    val tint = if (companionName == null || roadStale) CadenceColors.Warning else CadenceColors.Fg3

    Text(
        text = label,
        style = CadenceType.eyebrow,
        color = tint,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .background(CadenceColors.Bg1.copy(alpha = 0.6f))
            .padding(horizontal = Spacing.s3, vertical = Spacing.s2),
    )
}

/**
 * Power, in the zone colour of what the rider is actually producing.
 *
 * Read off actual power rather than a target, for the same reason route mode
 * does: nothing in this mode is holding them to a wattage, so a target-derived
 * colour would be describing a plan that does not exist.
 */
@Composable
private fun CompanionPowerReadout(
    powerWatts: Int,
    isStale: Boolean,
    wattsPerKg: Float,
    zoneColor: Color,
    powerFontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val animatedPower by animateIntAsState(
        targetValue = if (isStale) 0 else powerWatts,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "companion-power",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
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
        Text(
            text = if (isStale) "--" else formatOneDecimal(wattsPerKg.toDouble()) + " W/kg",
            style = CadenceType.small,
            color = CadenceColors.Fg3,
        )
    }
}

/**
 * Speed, ground covered and metres climbed.
 *
 * All three come from the companion. None of them is a position: they are how
 * fast, how far and how much up, which is everything the ride record needs and
 * nothing that says where.
 */
@Composable
private fun CompanionTotals(
    speedMps: Double,
    distanceMeters: Double,
    elevationGainMeters: Double,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        CompanionStat("Speed", formatOneDecimal(speedMps * 3.6), "km/h", Modifier.weight(1f))
        CompanionStat("Distance", formatOneDecimal(distanceMeters / 1000.0), "km", Modifier.weight(1f))
        CompanionStat("Climbed", elevationGainMeters.roundToInt().toString(), "m", Modifier.weight(1f))
    }
}

@Composable
private fun CompanionStat(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = label, style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, style = CadenceType.h2, color = CadenceColors.Fg1)
            Text(
                text = " " + unit,
                style = CadenceType.small,
                color = CadenceColors.Fg3,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}
