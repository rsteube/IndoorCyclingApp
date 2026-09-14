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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
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
    /**
     * Rides written to history so far this session, and the name of the one
     * most recently written. Nobody taps through a summary in this mode, so
     * the strip is where a rider finds out their lap was kept.
     */
    savedRideCount: Int = 0,
    lastSavedRideName: String? = null,
    onShiftDown: () -> Unit,
    onShiftUp: () -> Unit,
    shiftingEnabled: Boolean,
    /** A controllable screen the companion is showing, or null for none. */
    remoteControls: RemoteControls? = null,
    onControlPress: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        // Where the thumbs are decides where the shifter goes, and that is a
        // question about the shape of the space, not the device. Landscape puts
        // the hands at the two sides of the screen; portrait puts them at the
        // bottom. Tested on the space this HUD actually gets, so split screen
        // and a folding phone half-open land on whichever is true.
        val landscape = maxWidth > maxHeight && maxWidth >= 560.dp
        val roomy = maxWidth >= 560.dp && maxHeight >= 560.dp
        val symbolSize: TextUnit = if (roomy) 72.sp else 52.sp

        val downEnabled = shiftingEnabled && gearIndex > 0
        val upEnabled = shiftingEnabled && gearIndex < drivetrain.gearCount - 1

        // Easier on the left and harder on the right, in both layouts: the
        // same way round as the gear card, the ladder under the number, and
        // every handlebar remote this app pairs with.
        val shiftDown = @Composable { slot: Modifier ->
            ShiftPaddle(
                symbol = "\u2212",
                label = "Easier",
                description = "Shift to an easier gear",
                enabled = downEnabled,
                onShift = onShiftDown,
                symbolSize = symbolSize,
                modifier = slot,
            )
        }
        val shiftUp = @Composable { slot: Modifier ->
            ShiftPaddle(
                symbol = "+",
                label = "Harder",
                description = "Shift to a harder gear",
                enabled = upEnabled,
                onShift = onShiftUp,
                symbolSize = symbolSize,
                modifier = slot,
            )
        }

        val readouts = @Composable { slot: Modifier, horizontalPadding: Dp ->
            Box(modifier = slot) {
                CompanionReadouts(
                    gradient = gradient,
                    speedMps = speedMps,
                    distanceMeters = distanceMeters,
                    elevationGainMeters = elevationGainMeters,
                    drivetrain = drivetrain,
                    gearIndex = gearIndex,
                    powerWatts = powerWatts,
                    isPowerStale = isPowerStale,
                    wattsPerKg = wattsPerKg,
                    cadenceRpm = cadenceRpm,
                    heartRate = heartRate,
                    isHrStale = isHrStale,
                    hrColor = hrColor,
                    hrSublabel = hrSublabel,
                    zoneColor = zoneColor,
                    companionName = companionName,
                    roadStale = roadStale,
                    savedRideCount = savedRideCount,
                    lastSavedRideName = lastSavedRideName,
                    shiftingEnabled = shiftingEnabled,
                    horizontalPadding = horizontalPadding,
                )
                // Over the readouts rather than in them. The pad is a visitor:
                // it belongs to a menu on somebody else's screen, comes and goes
                // on that menu's schedule, and the ride's own numbers must not
                // reflow every time it does. Bottom centre, between the two
                // paddles, so a rider answering a menu never lands a thumb on a
                // shift by mistake.
                if (remoteControls != null) {
                    CompanionControlPad(
                        controls = remoteControls,
                        onPress = onControlPress,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(Spacing.s3),
                    )
                }
            }
        }

        if (landscape) {
            // Full-height paddles on the two outer edges. A thumb resting on the
            // bars finds one anywhere along the side without aiming, which on a
            // tablet clamped to a handlebar is the difference between a shift
            // and a glance at the screen to find a button.
            val paddleWidth = if (roomy) 148.dp else 108.dp
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = Spacing.s3, end = Spacing.s3, bottom = Spacing.s3),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
            ) {
                shiftDown(Modifier.width(paddleWidth).fillMaxHeight())
                readouts(Modifier.weight(1f).fillMaxHeight(), 0.dp)
                shiftUp(Modifier.width(paddleWidth).fillMaxHeight())
            }
        } else {
            // Portrait: the whole bottom of the screen, split in two. That is
            // where a thumb already is on a phone held upright, and halves of
            // the full width are the largest targets the screen can give.
            // Taller on an upright tablet, which has height to spare and is the
            // one shape where the paddles are furthest from a hand on the bars.
            val paddleHeight = if (roomy) 176.dp else 112.dp
            Column(modifier = Modifier.fillMaxSize()) {
                readouts(Modifier.fillMaxWidth().weight(1f), Spacing.s4)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(paddleHeight + Spacing.s3 * 2)
                        .padding(
                            start = Spacing.s4,
                            end = Spacing.s4,
                            top = Spacing.s3,
                            bottom = Spacing.s3,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
                ) {
                    shiftDown(Modifier.weight(1f).fillMaxHeight())
                    shiftUp(Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/**
 * Everything the rider reads, laid out in whatever the paddles leave.
 *
 * The readouts that were the whole HUD before shifting was given the edges of
 * the screen. Measures its own space for the same reason the HUD does: the
 * paddles take a fixed bite out of it, and the choice between side by side and
 * stacked has to be made on what is actually left.
 */
@Composable
private fun CompanionReadouts(
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
    companionName: String?,
    roadStale: Boolean,
    savedRideCount: Int,
    lastSavedRideName: String?,
    shiftingEnabled: Boolean,
    horizontalPadding: Dp,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        // Same test route mode uses: side by side needs width to spare relative
        // to height, not merely an absolute number, or a tablet held upright
        // passes a width check and then gets a squeezed two-column layout.
        // Measured between the paddles rather than across the whole HUD, so
        // the threshold is lower than route mode's: a landscape phone gives up
        // two paddle widths and still has more than enough for side by side.
        val wide = availableWidth >= 520.dp && availableWidth > availableHeight
        val short = availableHeight < 420.dp
        // Landscape with almost no height: a phone on its side, with a paddle
        // taken off each end. Neither the side-by-side layout nor the stack
        // fits here, and the stack failed badly, squeezing the power number,
        // the one the rider is actually watching, down to nothing.
        val cramped = !wide && availableWidth > availableHeight && availableHeight < 340.dp

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
                // A readout only. Shifting is the paddles' job, and a second,
                // smaller pair of buttons in the middle of the screen would only
                // be the wrong thing to reach for.
                GearShifter(
                    drivetrain = drivetrain,
                    gearIndex = gearIndex,
                    speedMps = speedMps,
                    onShiftDown = {},
                    onShiftUp = {},
                    compact = compact,
                    enabled = shiftingEnabled,
                    shiftButtons = false,
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
                .padding(horizontal = horizontalPadding)
                .padding(bottom = Spacing.s2),
            verticalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            CompanionSourceStrip(
                companionName = companionName,
                roadStale = roadStale,
                savedRideCount = savedRideCount,
                lastSavedRideName = lastSavedRideName,
            )

            if (cramped) {
                // Everything the rider reads off the phone, in two rows. Power
                // takes its place beside the road rather than below it, where
                // there was no height left to give it.
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
                ) {
                    GradientReadout(
                        gradient = gradient,
                        compact = true,
                        valueFontSize = 30.sp,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    GearShifter(
                        drivetrain = drivetrain,
                        gearIndex = gearIndex,
                        speedMps = speedMps,
                        onShiftDown = {},
                        onShiftUp = {},
                        compact = true,
                        enabled = shiftingEnabled,
                        shiftButtons = false,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    CompanionPowerReadout(
                        powerWatts = powerWatts,
                        isStale = isPowerStale,
                        wattsPerKg = wattsPerKg,
                        zoneColor = zoneColor,
                        powerFontSize = 44.sp,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                val cardWidth = ((availableWidth - horizontalPadding * 2 - Spacing.s3) / 2)
                    .coerceAtMost(170.dp)
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(
                        Spacing.s3, Alignment.CenterHorizontally,
                    ),
                ) {
                    CadenceFlankMetric(
                        rpm = cadenceRpm,
                        target = null,
                        cardWidth = cardWidth,
                        valueFontSize = 28.sp,
                        compact = true,
                        modifier = Modifier.fillMaxHeight(),
                    )
                    FlankMetric(
                        label = "Heart rate",
                        value = if (isHrStale) "--" else heartRate.toString(),
                        unit = "bpm",
                        isStale = isHrStale,
                        valueColor = hrColor,
                        cardWidth = cardWidth,
                        valueFontSize = 28.sp,
                        compact = true,
                        modifier = Modifier.fillMaxHeight(),
                    )
                }
            } else if (wide) {
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

            // Not when cramped. Speed, distance and climbing are the game's
            // numbers first, and it is already showing them on the big screen
            // the rider is facing; given the choice, the phone's height goes to
            // what only the phone knows.
            if (!cramped) {
                CompanionTotals(
                    speedMps = speedMps,
                    distanceMeters = distanceMeters,
                    elevationGainMeters = elevationGainMeters,
                )
            }
        }
    }
}

/**
 * Who is driving, whether they are still talking, and what has been kept.
 *
 * A rider whose pedals just went light deserves to know it was the game that
 * stopped rather than their legs, so the quiet state is stated rather than
 * left to be inferred from a gradient sliding towards zero.
 *
 * It also carries the one piece of news this mode has no other way to deliver.
 * Every other mode confirms a saved ride by showing the rider its summary; a
 * companion session never leaves the HUD, because the game is still running and
 * another lap may start at any moment. So the moment a ride is written, this
 * says so by name for a few seconds, and then keeps a running count for the
 * rest of the session. Without it a rider has no way to tell a lap that was
 * recorded from one that was not, which is precisely the doubt that sends
 * somebody to the history screen mid-ride to check.
 */
@Composable
private fun CompanionSourceStrip(
    companionName: String?,
    roadStale: Boolean,
    savedRideCount: Int = 0,
    lastSavedRideName: String? = null,
    modifier: Modifier = Modifier,
) {
    // Announce, then settle. Keyed on the count rather than the name, so two
    // laps of the same course still announce twice.
    var announcing by remember(savedRideCount) { mutableStateOf(savedRideCount > 0) }
    LaunchedEffect(savedRideCount) {
        if (savedRideCount == 0) return@LaunchedEffect
        delay(SAVED_ANNOUNCE_MS)
        announcing = false
    }

    val saved = when {
        savedRideCount == 0 -> null
        savedRideCount == 1 -> "1 ride saved"
        else -> savedRideCount.toString() + " rides saved"
    }
    val label = when {
        announcing && lastSavedRideName != null -> "Saved to history: " + lastSavedRideName
        announcing -> "Ride saved to history"
        companionName == null -> "No companion connected - flat road"
        roadStale -> companionName + " has gone quiet - flattening the road"
        else -> "Driven by " + companionName
    }
    val tint = when {
        announcing -> CadenceColors.Success
        companionName == null || roadStale -> CadenceColors.Warning
        else -> CadenceColors.Fg3
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .background(CadenceColors.Bg1.copy(alpha = 0.6f))
            .padding(horizontal = Spacing.s3, vertical = Spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = CadenceType.eyebrow,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        // Stays put once anything has been saved, because the useful question
        // late in a session is "did all of those land", not "did the last one".
        if (saved != null && !announcing) {
            Spacer(Modifier.width(Spacing.s2))
            Text(
                text = "\u00b7 " + saved,
                style = CadenceType.eyebrow,
                color = CadenceColors.Fg3,
                maxLines = 1,
            )
        }
    }
}

/** Long enough to read a course name, short enough not to hide the road state. */
private const val SAVED_ANNOUNCE_MS = 6_000L

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
