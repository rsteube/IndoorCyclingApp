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
package app.indoorbike.ui.screens.companion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.companion.CompanionEngine
import app.indoorbike.shared.companion.EventFrame
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A hand on the numbers, for whoever is building the companion.
 *
 * ## Why this exists
 *
 * Building the other end of the Companion API means provoking specific
 * reactions: what does the game do at 400 W, at zero cadence, when the trainer
 * drops, when `worldTimeout` arrives? None of that is reachable from a desk.
 * The ordinary mock trainer plays a plausible rider, which is the right tool
 * for a smoke test and the wrong one here, because a plausible rider never does
 * the thing you are trying to test.
 *
 * So this hands the readings over. The stream a companion sees is otherwise
 * completely ordinary - the session machinery, the physics, the force model and
 * the wire format are all the real ones - and the only difference is that a
 * person is choosing the watts instead of a flywheel.
 *
 * ## Debug builds only
 *
 * Everything here hangs off the mock trainer, which is already hidden in
 * release. The caller gates on `BuildConfig.DEBUG` as well, so R8 removes the
 * whole panel from a release build rather than merely hiding it.
 */
@Composable
fun CompanionDebugPanel(modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val trainer = container.trainerManager
    val host = container.companionHost
    val engine = container.companionEngine

    val isMock by trainer.isMockTrainer.collectAsState()
    val manual by trainer.mockManual.collectAsState()
    val livePower by trainer.power.collectAsState()
    val liveCadence by trainer.cadence.collectAsState()
    val liveHr by trainer.heartRate.collectAsState()
    val status by host.status.collectAsState()

    var expanded by remember { mutableStateOf(false) }
    var power by remember { mutableIntStateOf(180) }
    var cadence by remember { mutableIntStateOf(88) }
    var heartRate by remember { mutableIntStateOf(145) }
    var wobble by remember { mutableStateOf(false) }

    // One writer, so the wobble and the steppers cannot fight over the flows.
    // A quarter-hertz sine is slow enough to read on a HUD and fast enough that
    // a companion's smoothing has something to smooth.
    LaunchedEffect(manual, power, cadence, heartRate, wobble) {
        if (!manual) return@LaunchedEffect
        var t = 0.0
        while (true) {
            val offset = if (wobble) (sin(t) * 18.0).roundToInt() else 0
            trainer.setMockTelemetry(
                powerWatts = (power + offset).coerceAtLeast(0),
                cadenceRpm = cadence,
                heartRateBpm = heartRate,
            )
            if (!wobble) break
            t += 0.5
            delay(500)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.s3)
            .clip(RoundedCornerShape(Radii.md))
            .background(CadenceColors.Bg2.copy(alpha = 0.94f))
            .border(1.dp, CadenceColors.Warning.copy(alpha = 0.5f), RoundedCornerShape(Radii.md))
            .padding(Spacing.s3),
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
        ) {
            Text(
                text = "DEBUG",
                style = CadenceType.eyebrow,
                color = CadenceColors.Warning,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = when {
                    !isMock -> "needs the mock trainer"
                    manual -> "manual  ${livePower}W  ${liveCadence}rpm  ${liveHr}bpm"
                    else -> "automatic mock"
                },
                style = CadenceType.small,
                color = CadenceColors.Fg2,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "Hide" else "Show",
                style = CadenceType.small,
                color = CadenceColors.Fg1,
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                if (!isMock) {
                    Text(
                        text = "Pair the mock trainer (Settings, Devices, Trainer) to take the " +
                            "readings by hand. A real trainer is never overridden.",
                        style = CadenceType.small,
                        color = CadenceColors.Warning,
                    )
                } else {
                    DebugRow {
                        DebugChip(
                            text = if (manual) "Manual: on" else "Manual: off",
                            active = manual,
                            onClick = { trainer.setMockManual(!manual) },
                        )
                        DebugChip(
                            text = if (wobble) "Wobble: on" else "Wobble: off",
                            active = wobble,
                            enabled = manual,
                            onClick = { wobble = !wobble },
                        )
                    }

                    DebugStepper("Power", power, "W", manual, 10) { power = (power + it).coerceIn(0, 2000) }
                    DebugStepper("Cadence", cadence, "rpm", manual, 5) { cadence = (cadence + it).coerceIn(0, 200) }
                    DebugStepper("Heart", heartRate, "bpm", manual, 5) { heartRate = (heartRate + it).coerceIn(0, 240) }

                    DebugRow {
                        DebugChip("Coast (0 W)", enabled = manual) {
                            power = 0; cadence = 0; wobble = false
                        }
                        DebugChip("Endurance", enabled = manual) {
                            power = 180; cadence = 88; heartRate = 140
                        }
                        DebugChip("Threshold", enabled = manual) {
                            power = 260; cadence = 92; heartRate = 165
                        }
                        DebugChip("Sprint", enabled = manual) {
                            power = 700; cadence = 105; heartRate = 178
                        }
                    }
                }

                SectionLine("Gears - real shifts, reported as `screen`")
                DebugRow {
                    DebugChip("Shift down") { engine.shiftDown(CompanionEngine.SOURCE_SCREEN) }
                    DebugChip("Shift up") { engine.shiftUp(CompanionEngine.SOURCE_SCREEN) }
                    DebugChip("Shift as Click") { engine.shiftUp(CompanionEngine.SOURCE_CLICK) }
                }

                SectionLine("One-off events - indistinguishable on the wire from real ones")
                val connected = status.controllerName != null
                DebugRow {
                    DebugChip("worldTimeout", enabled = connected) {
                        host.sendEvent(EventFrame(event = EventFrame.WORLD_TIMEOUT, fallback = "flat"))
                    }
                    DebugChip("worldResumed", enabled = connected) {
                        host.sendEvent(EventFrame(event = EventFrame.WORLD_RESUMED))
                    }
                    DebugChip("trainerLost", enabled = connected) {
                        host.sendEvent(EventFrame(event = EventFrame.TRAINER_LOST))
                    }
                    DebugChip("riderInput +", enabled = connected) {
                        host.sendEvent(EventFrame(event = EventFrame.RIDER_INPUT, input = "plus"))
                    }
                    DebugChip("riderInput -", enabled = connected) {
                        host.sendEvent(EventFrame(event = EventFrame.RIDER_INPUT, input = "minus"))
                    }
                }

                SectionLine("Real actions - these change the ride, not just the wire")
                DebugRow {
                    DebugChip("Revoke control", enabled = connected) { host.revokeControl() }
                    DebugChip("End ride") { container.sessionManager.endWorkoutEarly() }
                }
            }
        }
    }
}

@Composable
private fun SectionLine(text: String) {
    Text(
        text = text,
        style = CadenceType.eyebrow,
        color = CadenceColors.Fg4,
        modifier = Modifier.padding(top = Spacing.s2),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DebugRow(content: @Composable () -> Unit) {
    // A plain lambda rather than the flow-row receiver: the chips want nothing
    // from it, and taking it as a scope would spread an experimental
    // annotation across every call site in the panel for no benefit.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) { content() }
}

@Composable
private fun DebugStepper(
    label: String,
    value: Int,
    unit: String,
    enabled: Boolean,
    step: Int,
    onAdjust: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        Text(
            text = label,
            style = CadenceType.small,
            color = if (enabled) CadenceColors.Fg2 else CadenceColors.Fg4,
            modifier = Modifier.width(64.dp),
        )
        DebugChip("-", enabled = enabled) { onAdjust(-step) }
        Text(
            text = "$value",
            style = CadenceType.h3,
            color = if (enabled) CadenceColors.Fg1 else CadenceColors.Fg4,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(56.dp),
        )
        DebugChip("+", enabled = enabled) { onAdjust(step) }
        Text(
            text = unit,
            style = CadenceType.small,
            color = CadenceColors.Fg3,
        )
    }
}

@Composable
private fun DebugChip(
    text: String,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val background: Color = when {
        !enabled -> CadenceColors.Bg1
        active -> CadenceColors.Fg1
        else -> CadenceColors.Bg3
    }
    val foreground: Color = when {
        !enabled -> CadenceColors.Fg4
        active -> CadenceColors.Bg0
        else -> CadenceColors.Fg1
    }
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = foreground,
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.pill))
            .background(background)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.s3, vertical = Spacing.s2),
    )
}
