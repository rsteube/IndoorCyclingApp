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
package app.indoorbike.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.settings.MAX_BIKE_WEIGHT_KG
import app.indoorbike.shared.settings.MIN_BIKE_WEIGHT_KG
import app.indoorbike.shared.simulation.Drivetrain
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.rememberCollapsingHeaderState
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.screens.simulation.formatOneDecimal
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * Settings that only a simulated route ride reads.
 *
 * On their own page rather than inline in Settings because they answer a
 * question the other two modes never ask: what bike are you on? A workout and a
 * free ride are pure wattage, and neither the weight of the frame nor the size
 * of the sprockets can change what the trainer is told. On a route both decide
 * what a hill costs and what a shift feels like.
 *
 * Two settings today. The drivetrain is a preset rather than a chainring and
 * cassette editor because a preset answers the question most riders actually
 * have ("I'm on a mountain bike") without asking them to type in a gear chart.
 * The model underneath is real teeth and a real wheel size, so an editor is a
 * screen away rather than a rewrite.
 */
@Composable
fun SimulationSettingsScreen(onBack: () -> Unit) {
    val settings = LocalAppContainer.current.userSettings
    val bikeWeight by settings.bikeWeightKg.collectAsState()
    val drivetrainId by settings.drivetrainId.collectAsState()
    val selected = Drivetrain.byId(drivetrainId)

    val header = rememberCollapsingHeaderState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
            .nestedScroll(header.nestedScrollConnection)
    ) {
        AppBar(
            header = header,
            title = "Bike & gearing",
            eyebrow = "Simulation",
            leading = {
                IconBtn(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                )
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s5)
        ) {
            Spacer(Modifier.height(Spacing.s4))
            Text(
                text = "How a route ride is simulated. Workouts and Just Ride are unaffected.",
                style = CadenceType.small,
                color = CadenceColors.Fg3,
            )

            Spacer(Modifier.height(Spacing.s6))
            SectionLabel("Bike weight")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.lg))
                    .background(CadenceColors.Bg1)
                    .padding(Spacing.s5)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${formatOneDecimal(bikeWeight.toDouble())} kg",
                            style = CadenceType.h2,
                            color = CadenceColors.Fg1,
                        )
                        Text(
                            text = "Added to your body weight on every climb.",
                            style = CadenceType.tick,
                            color = CadenceColors.Fg3,
                        )
                    }
                    Stepper(
                        description = "Decrease bike weight",
                        label = "−",
                        enabled = bikeWeight > MIN_BIKE_WEIGHT_KG,
                        onClick = { settings.setBikeWeightKg(roundHalf(bikeWeight - STEP_KG)) },
                    )
                    Spacer(Modifier.width(Spacing.s2))
                    Stepper(
                        description = "Increase bike weight",
                        label = "+",
                        enabled = bikeWeight < MAX_BIKE_WEIGHT_KG,
                        onClick = { settings.setBikeWeightKg(roundHalf(bikeWeight + STEP_KG)) },
                    )
                }
                Spacer(Modifier.height(Spacing.s3))
                // Equal thirds rather than wrap-content: three chips sized to
                // their own text do not fit a phone's width, and the odd one
                // out collapses into an unreadable blob.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                ) {
                    PresetWeight("Road", 8f, bikeWeight, Modifier.weight(1f)) {
                        settings.setBikeWeightKg(it)
                    }
                    PresetWeight("Gravel", 10f, bikeWeight, Modifier.weight(1f)) {
                        settings.setBikeWeightKg(it)
                    }
                    PresetWeight("MTB", 14f, bikeWeight, Modifier.weight(1f)) {
                        settings.setBikeWeightKg(it)
                    }
                }
            }

            Spacer(Modifier.height(Spacing.s6))
            SectionLabel("Gearing")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.lg))
                    .background(CadenceColors.Bg1)
            ) {
                Drivetrain.ALL.forEachIndexed { index, drivetrain ->
                    if (index > 0) HorizontalDivider(color = CadenceColors.Line1, thickness = 1.dp)
                    DrivetrainRow(
                        drivetrain = drivetrain,
                        selected = drivetrain.id == selected.id,
                        onSelect = { settings.setDrivetrainId(drivetrain.id) },
                    )
                }
            }

            Spacer(Modifier.height(Spacing.s3))
            Text(
                text = "Gears change the resistance, not the speed - the same watts carry you " +
                    "the same distance whichever gear you pick, exactly as on the road. " +
                    "A change takes effect on your next route ride.",
                style = CadenceType.tick,
                color = CadenceColors.Fg3,
            )

            Spacer(Modifier.height(Spacing.s6))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            Spacer(Modifier.height(Spacing.s5))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, style = CadenceType.eyebrow, color = CadenceColors.Fg3)
    Spacer(Modifier.height(Spacing.s2))
}

/**
 * One drivetrain option, showing what it actually is and what it gives the
 * rider: the range from easiest to hardest, which is the number that decides
 * whether a wall is rideable and whether a descent has anything left in it.
 */
@Composable
private fun DrivetrainRow(
    drivetrain: Drivetrain,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val easiest = drivetrain.gears.first()
    val hardest = drivetrain.gears.last()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(Spacing.s5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    if (selected) CadenceColors.Fg1 else CadenceColors.Bg2
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(CadenceColors.OnBrand)
                )
            }
        }
        Spacer(Modifier.width(Spacing.s3))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = drivetrain.name, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
            Text(text = drivetrain.summary, style = CadenceType.tick, color = CadenceColors.Fg3)
            Text(
                text = "${drivetrain.gearCount} gears · ${easiest.label} to ${hardest.label}",
                style = CadenceType.tick,
                color = CadenceColors.Fg4,
            )
        }
    }
}

@Composable
private fun PresetWeight(
    label: String,
    kg: Float,
    current: Float,
    modifier: Modifier = Modifier,
    onPick: (Float) -> Unit,
) {
    val active = kotlin.math.abs(current - kg) < 0.05f
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (active) CadenceColors.Fg1 else CadenceColors.Bg2)
            .clickable { onPick(kg) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$label ${kg.roundToInt()}",
            style = CadenceType.tick,
            color = if (active) CadenceColors.OnBrand else CadenceColors.Fg2,
            maxLines = 1,
        )
    }
}

@Composable
private fun Stepper(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(CadenceColors.Bg2)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = CadenceType.h3,
            color = if (enabled) CadenceColors.Fg1 else CadenceColors.Fg4,
        )
    }
}

/** Keep the stored weight on clean half-kilos rather than float drift. */
private fun roundHalf(value: Float): Float =
    ((value * 2f).roundToInt() / 2f).coerceIn(MIN_BIKE_WEIGHT_KG, MAX_BIKE_WEIGHT_KG)

private const val STEP_KG = 0.5f
