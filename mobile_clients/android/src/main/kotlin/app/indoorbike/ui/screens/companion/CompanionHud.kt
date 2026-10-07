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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
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
import app.indoorbike.shared.companion.RouteJunction
import app.indoorbike.shared.companion.RouteNavigation
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
    /**
     * The companion's accent and the text drawn on it, or null for the app's
     * own. Used only for the flash of a control being pressed: the paddles and
     * the remote pad are the companion's controls, and a press lighting up in
     * its colour is the part of the ride screen that most obviously belongs to
     * the game. Who is driving, and anything wrong with it, is on the header.
     */
    accent: Color? = null,
    onAccent: Color? = null,
    onShiftDown: () -> Unit,
    onShiftUp: () -> Unit,
    shiftingEnabled: Boolean,
    /**
     * Show every reading rather than the power alone. The developer view: see
     * [app.indoorbike.shared.settings.UserSettings.companionShowAllData].
     */
    showAllData: Boolean = false,
    /** A controllable screen the companion is showing, or null for none. */
    remoteControls: RemoteControls? = null,
    onControlPress: (String) -> Unit = {},
    /** The junction ahead on a free ride, or null. Drawn only for a game that takes the rider's pick. */
    junction: RouteJunction? = null,
    onChooseTurn: (junctionId: String, optionId: String) -> Unit = { _, _ -> },
    /** The game answers searches: show where it is taking the rider, or a way to say where. */
    navigationOffered: Boolean = false,
    navigation: RouteNavigation? = null,
    arrival: String? = null,
    onSearchPlaces: () -> Unit = {},
    onClearDestination: () -> Unit = {},
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
                accent = accent,
                onAccent = onAccent,
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
                accent = accent,
                onAccent = onAccent,
                modifier = slot,
            )
        }

        val readouts = @Composable { slot: Modifier, horizontalPadding: Dp ->
            Box(modifier = slot) {
                if (!showAllData) {
                    // What the rider came for. The game draws the road, the
                    // speed, the distance and the gear on the big screen a
                    // metre away; repeating them on the bars is a second place
                    // to look for something already in front of them. What it
                    // cannot draw is the power their legs are making, so that
                    // is what this is: one number, as large as the space allows.
                    CompanionPowerOnly(
                        powerWatts = powerWatts,
                        isPowerStale = isPowerStale,
                        wattsPerKg = wattsPerKg,
                        zoneColor = zoneColor,
                        drivetrain = drivetrain,
                        gearIndex = gearIndex,
                        speedMps = speedMps,
                        shiftingEnabled = shiftingEnabled,
                        horizontalPadding = horizontalPadding,
                    )
                } else CompanionReadouts(
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
                    shiftingEnabled = shiftingEnabled,
                    horizontalPadding = horizontalPadding,
                )
                // Over whichever of the two is showing, rather than in it. The pad is a visitor:
                // it belongs to a menu on somebody else's screen, comes and goes
                // on that menu's schedule, and the ride's own numbers must not
                // reflow every time it does. Bottom centre, between the two
                // paddles, so a rider answering a menu never lands a thumb on a
                // shift by mistake.
                // A free ride's road ahead, over the top of the readouts. Over
                // them for the same reason as the pad: a junction comes and goes
                // every few hundred metres, and the power must not jump each
                // time. The gear it covers is on the game's screen too, and is
                // back the moment the junction is passed.
                if (junction != null || navigationOffered || arrival != null) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.s3, vertical = Spacing.s2),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.s3),
                    ) {
                        when {
                            arrival != null -> CompanionArrivalBanner(name = arrival)
                            navigationOffered -> CompanionNavigationStrip(
                                navigation = navigation,
                                onSearch = onSearchPlaces,
                                onClear = onClearDestination,
                                modifier = if (navigation != null) Modifier.fillMaxWidth() else Modifier,
                            )
                        }
                        if (junction != null) {
                            CompanionJunctionCard(
                                junction = junction,
                                onChoose = onChooseTurn,
                                accent = accent,
                                onAccent = onAccent,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (remoteControls != null) {
                    CompanionControlPad(
                        controls = remoteControls,
                        onPress = onControlPress,
                        accent = accent,
                        onAccent = onAccent,
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
 * The power, alone, in whatever the paddles leave.
 *
 * Sized to the space rather than to a breakpoint: this is read at arm's length
 * by somebody breathing hard, so it takes as much of the height as it can get
 * and still leaves the zone colour and the W/kg legible underneath.
 */
@Composable
private fun CompanionPowerOnly(
    powerWatts: Int,
    isPowerStale: Boolean,
    wattsPerKg: Float,
    zoneColor: Color,
    drivetrain: Drivetrain,
    gearIndex: Int,
    speedMps: Double,
    shiftingEnabled: Boolean,
    horizontalPadding: Dp,
) {
    val animatedPower by animateIntAsState(
        targetValue = if (isPowerStale) 0 else powerWatts,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "companion-power-only",
    )
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = horizontalPadding)
            .padding(top = Spacing.s3, bottom = Spacing.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A gear the rider changes and cannot see is a control with no readout: the
        // paddles are right there on this screen, and until now the only way to
        // know what they had done was to look at the game.
        //
        // The app's own gear card, in the form it takes when there is no room for
        // its sublines, and without its own little shift buttons: two ways to do
        // the one thing, one of them a 46 dp circle, only invites the rider to
        // reach for the worse one. At the top because that is where the full view
        // keeps it, and because the bottom of this area belongs to a companion's
        // control pad whenever it sends one.
        GearShifter(
            drivetrain = drivetrain,
            gearIndex = gearIndex,
            speedMps = speedMps,
            onShiftDown = {},
            onShiftUp = {},
            compact = true,
            enabled = shiftingEnabled,
            shiftButtons = false,
            modifier = Modifier.width(GearCardWidth),
        )
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val reading = if (isPowerStale) "--" else animatedPower.toString()
            // As big as the space allows, and the space is measured rather than
            // guessed: the type is asked how wide this many digits come out and how
            // tall the two small lines are, and what is left over is the number's.
            // A ratio picked by eye would be wrong on the next font or the next
            // screen shape, and wrong here means either a number smaller than the
            // space it sits in or one that runs off the side of it.
            //
            // Sized for three digits until a fourth arrives, rather than for the
            // widest reading a rider could ever produce: holding room for a
            // four-figure sprint would shrink every ordinary ride for a number most
            // riders never see. Below a hundred watts it keeps the three-digit
            // size, so the figure does not breathe in and out around 99 W, which is
            // exactly where a warm-up sits.
            val digitSlots = reading.length.coerceIn(3, 4)
            val font: TextUnit = remember(digitSlots, maxWidth, maxHeight, density) {
                // Measured in the style it will be drawn in, letter spacing and
                // all, because a probe in some other style answers a question
                // nobody asked and the answer is a number off the side of the
                // screen.
                val probe = measurer.measure(
                    AnnotatedString("8".repeat(digitSlots)),
                    NUMBER_STYLE.copy(fontSize = PROBE_SIZE),
                    maxLines = 1,
                    softWrap = false,
                    density = density,
                )
                val unit = measurer.measure(AnnotatedString(" W"), CadenceType.h2, density = density)
                val label = measurer.measure(AnnotatedString("Power"), CadenceType.eyebrow, density = density)
                val perKg = measurer.measure(AnnotatedString("0.0 W/kg"), CadenceType.h3, density = density)
                with(density) {
                    // A hair under what fits, so that rounding in the measurement
                    // cannot be what decides whether the unit still has a home.
                    val roomAcross = (maxWidth.toPx() - unit.size.width) * 0.98f
                    val roomDown = maxHeight.toPx() - label.size.height - perKg.size.height -
                        Spacing.s3.toPx() * 2
                    val byWidth = PROBE_SIZE.toPx() * roomAcross / probe.size.width
                    // What the trim leaves: the digits themselves, not the line box
                    // they are drawn in. See [trimmedToGlyphs].
                    val byHeight = roomDown / GLYPH_BAND
                    minOf(byWidth, byHeight).coerceIn(48.dp.toPx(), 420.dp.toPx()).toSp()
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Power", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
                Spacer(modifier = Modifier.height(Spacing.s3))
                Row {
                    Text(
                        text = reading,
                        modifier = Modifier
                            .trimmedToGlyphs(font)
                            .alignByBaseline(),
                        color = zoneColor,
                        // One line whatever happens. A width measured a little
                        // tight would otherwise wrap the reading onto two, and two
                        // lines of this are not a smaller number, they are an
                        // unreadable screen.
                        maxLines = 1,
                        softWrap = false,
                        style = NUMBER_STYLE.copy(fontSize = font, lineHeight = font),
                    )
                    // The unit hangs off the number's own baseline rather than the
                    // bottom of its box, which is where it looks written by the
                    // same hand rather than dropped next to it.
                    Text(
                        text = " W",
                        style = CadenceType.h2,
                        color = CadenceColors.Fg2,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.s3))
                // Bigger than a sub-line elsewhere in the app. It is read from a
                // metre away by somebody who is not going to lean in.
                Text(
                    text = if (isPowerStale) "--" else formatOneDecimal(wattsPerKg.toDouble()) + " W/kg",
                    style = CadenceType.h3,
                    color = CadenceColors.Fg2,
                )
            }
        }
    }
}

/**
 * How wide the gear card is in the simple view.
 *
 * Wide enough for two digits and the cassette ladder under them, narrow enough
 * that it reads as a label on the screen rather than a second gauge competing
 * with the number. The power is measured against whatever height is left below
 * it, so this card's own size is the only thing that has to be stated.
 */
private val GearCardWidth = 132.dp

/**
 * How tall a digit is as a share of its font size: a cap height, near enough
 * across the faces this app ships that a few pixels either way are invisible
 * under a number this size.
 */
private const val GLYPH_BAND = 0.74f

/** Big enough to measure accurately, small enough to cost nothing. */
private val PROBE_SIZE = 100.sp

/**
 * The face the power is drawn in, with no size: the size is worked out from the
 * space, and the same style does the measuring so that the two agree.
 *
 * The app's own biggest number, weight, letter spacing and tabular figures
 * included. Tabular matters more here than anywhere: every digit the same width
 * means a reading that climbs from 99 to 100 to 260 does not shuffle sideways
 * under the rider's eye, and it means the width measured for "888" is the width
 * any three digits will take.
 */
private val NUMBER_STYLE = CadenceType.displayXl.copy(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/**
 * Report a line of digits as the height of the digits, not of the font.
 *
 * A line box is built for every letter a font can draw: room above for the
 * accents and below for the tail of a "y". At caption sizes that slack is a
 * pixel or two. At 300sp it is a hand's width of nothing, and it is charged
 * twice, once above the number and once below, which is what pushes the label
 * and the W/kg away from the figure they belong to and leaves the whole cluster
 * sitting high in its space. Neither `includeFontPadding` nor a trimmed
 * [LineHeightStyle] takes it back: those return what the *line height* added on
 * top of the ascent and the descent, and here it is the ascent and the descent.
 *
 * So measure the text, ask it where its baseline is, and report the band from
 * the top of a capital to that baseline. Digits have no descender and no
 * accent, so nothing is cut off. The glyphs are placed where they always were:
 * only what the parent is told about them changes.
 */
private fun Modifier.trimmedToGlyphs(fontSize: TextUnit): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val baseline = placeable[FirstBaseline]
    if (baseline == AlignmentLine.Unspecified) {
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val above = (fontSize.toPx() * GLYPH_BAND).roundToInt()
    val top = (baseline - above).coerceAtLeast(0)
    layout(placeable.width, (baseline - top).coerceAtLeast(0)) {
        placeable.place(0, -top)
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
                .padding(top = Spacing.s3, bottom = Spacing.s2),
            verticalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
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
