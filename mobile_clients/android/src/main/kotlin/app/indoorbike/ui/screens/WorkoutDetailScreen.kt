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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.rememberCollapsingHeaderState
import app.indoorbike.ui.components.CardDivider
import app.indoorbike.ui.components.DestructiveBtn
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.ListCard
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.components.SectionLabel
import app.indoorbike.ui.components.SheetScrim
import app.indoorbike.ui.components.SlateCard
import app.indoorbike.ui.components.Stat
import app.indoorbike.ui.components.ZoneBadge
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.shared.workout.WorkoutSummary
import app.indoorbike.shared.workout.cadenceTarget
import app.indoorbike.shared.workout.formatDurationPretty
import app.indoorbike.shared.workout.toSummary
import app.indoorbike.ui.zones.PowerZone as ZonePalette
import kotlin.math.roundToInt

/**
 * Picks which bottom CTA the workout-detail screen shows. The screen is
 * shared between two callers:
 *  - **[Local]**: a workout already in the rider's library (bundled, imported,
 *    or freshly arrived via sync). CTA = Start workout / Pair trainer.
 *  - **[RemoteAdd]**: a workout being previewed inside the remote-repo
 *    browser before the rider has chosen to keep it. CTA = Add to my library
 *    (or "Added" pill if it already lives locally — same idempotent
 *    semantics as the inline Add button on the browser cards).
 *
 * Star + rename/delete affordances are hidden in [RemoteAdd] mode because
 * the workout isn't in the rider's library yet, so favouriting / renaming
 * have no on-disk surface to write to.
 */
sealed class WorkoutDetailMode {
    data object Local : WorkoutDetailMode()
    data class RemoteAdd(
        val isAlreadyAdded: Boolean,
        val onAdd: () -> Unit,
    ) : WorkoutDetailMode()
}

@Composable
fun WorkoutDetailScreen(
    workout: Workout,
    onBack: () -> Unit,
    onStart: () -> Unit = {},
    onNavigateToPairing: () -> Unit = {},
    mode: WorkoutDetailMode = WorkoutDetailMode.Local,
    isImported: Boolean = false,
    onRename: (String) -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val container = LocalAppContainer.current
    val ftp by container.userSettings.ftp.collectAsState()
    val favouriteIds by container.userSettings.favouriteWorkoutIds.collectAsState()
    val isFavourite = workout.id in favouriteIds
    val trainerState by container.trainerManager.connectionState.collectAsState()
    val isMockTrainer by container.trainerManager.isMockTrainer.collectAsState()
    val isTrainerReady = trainerState == TrainerConnectionState.Connected || isMockTrainer
    val summary = remember(workout, ftp) { workout.toSummary(ftp) }
    val isRemoteAdd = mode is WorkoutDetailMode.RemoteAdd
    var stepsExpanded by remember(workout.id) { mutableStateOf(false) }
    val stepCutoff = 6
    val totalSteps = summary.workout.segments.size
    val visibleSteps = if (stepsExpanded || totalSteps <= stepCutoff)
        summary.workout.segments
    else summary.workout.segments.take(stepCutoff)

    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val header = rememberCollapsingHeaderState()
    Box(modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0)) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(header.nestedScrollConnection)
    ) {
        AppBar(
            header = header,
            title = workout.name,
            eyebrow = "${summary.tag} · ${formatDurationPretty(summary.totalSeconds)}",
            leading = {
                IconBtn(icon = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", onClick = onBack)
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Hide the favourite + edit affordances in remote-add mode:
                    // the workout isn't local yet, so toggling a favourite or
                    // renaming has nothing to write against.
                    if (!isRemoteAdd) {
                        IconBtn(
                            icon = if (isFavourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (isFavourite) "Remove from favourites" else "Add to favourites",
                            // Full-strength white when starred, secondary when
                            // not. With no accent left, the filled glyph and the
                            // step up in contrast carry the state on their own.
                            tint = if (isFavourite) CadenceColors.Fg1 else CadenceColors.Fg2,
                            onClick = {
                                container.userSettings.setWorkoutFavourite(workout.id, !isFavourite)
                            }
                        )
                    }
                    if (!isRemoteAdd && isImported) {
                        Box {
                            IconBtn(
                                icon = Icons.Outlined.MoreVert,
                                contentDescription = "Workout actions",
                                onClick = { menuExpanded = true }
                            )
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Rename", color = CadenceColors.Fg1) },
                                    onClick = {
                                        menuExpanded = false
                                        showRenameDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete", color = CadenceColors.Danger) },
                                    onClick = {
                                        menuExpanded = false
                                        showDeleteDialog = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = Spacing.s5, end = Spacing.s5,
                top = Spacing.s5, bottom = Spacing.s4
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.s3)
        ) {
            item { ZoneProfileCard(summary) }
            item {
                SectionLabel(
                    text = "Steps · $totalSteps",
                    modifier = Modifier.padding(top = Spacing.s3)
                )
            }
            item {
                ListCard {
                    visibleSteps.forEachIndexed { index, segment ->
                        StepRow(segment, ftp)
                        if (index != visibleSteps.lastIndex) CardDivider()
                    }
                    if (!stepsExpanded && totalSteps > stepCutoff) {
                        CardDivider()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { stepsExpanded = true }
                                .padding(vertical = Spacing.s3),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "+ ${totalSteps - stepCutoff} more · tap to expand",
                                style = CadenceType.small,
                                color = CadenceColors.Fg3
                            )
                        }
                    }
                }
            }
        }

        when (mode) {
            is WorkoutDetailMode.Local -> StartCta(
                isTrainerReady = isTrainerReady,
                onStart = onStart,
                onPairTrainer = onNavigateToPairing,
            )
            is WorkoutDetailMode.RemoteAdd -> AddToLibraryCta(
                isAlreadyAdded = mode.isAlreadyAdded,
                onAdd = mode.onAdd,
            )
        }
    }

        if (showRenameDialog) {
            RenameWorkoutDialog(
                currentName = workout.name,
                onCancel = { showRenameDialog = false },
                onConfirm = { newName ->
                    showRenameDialog = false
                    onRename(newName)
                }
            )
        }
        if (showDeleteDialog) {
            DeleteWorkoutDialog(
                workoutName = workout.name,
                onCancel = { showDeleteDialog = false },
                onConfirm = {
                    showDeleteDialog = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun RenameWorkoutDialog(
    currentName: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf(currentName) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SheetScrim)
            .clickable(onClick = onCancel)
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(Radii.lg))
                .background(CadenceColors.Bg1)
                .clickable(enabled = false, onClick = {})
                .padding(Spacing.s6),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4)
        ) {
            Text("Rename workout", style = CadenceType.h2, color = CadenceColors.Fg1)
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = CadenceType.bodyStrong.copy(color = CadenceColors.Fg1),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val trimmed = input.trim()
                    if (trimmed.isNotEmpty() && trimmed != currentName) onConfirm(trimmed)
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.md))
                    .background(CadenceColors.Bg2)
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
                SecondaryBtn(text = "Cancel", onClick = onCancel, modifier = Modifier.weight(1f))
                val trimmed = input.trim()
                val canSave = trimmed.isNotEmpty() && trimmed != currentName
                PrimaryBtn(
                    text = "Save",
                    onClick = { onConfirm(trimmed) },
                    leadingIcon = null,
                    enabled = canSave,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DeleteWorkoutDialog(
    workoutName: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SheetScrim)
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(Radii.lg))
                .background(CadenceColors.Bg1)
                .clickable(enabled = false, onClick = {})
                .padding(Spacing.s6),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4)
        ) {
            Text("Delete \"$workoutName\"?", style = CadenceType.h2, color = CadenceColors.Fg1)
            Text(
                "The workout file will be removed from your device. Past rides of this workout stay in your history.",
                style = CadenceType.body,
                color = CadenceColors.Fg2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
                SecondaryBtn(text = "Cancel", onClick = onCancel, modifier = Modifier.weight(1f))
                DestructiveBtn(
                    text = "Delete",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * The workout in one card: its shape, then the four numbers that describe it.
 *
 * The chart and the stats used to be two cards, which read as two subjects.
 * They are one subject - what this session is - so they share a surface, and
 * the numbers sit under the profile they are derived from.
 */
@Composable
private fun ZoneProfileCard(summary: WorkoutSummary) {
    SlateCard {
        SteppedZoneChart(summary, chartHeight = 110)
        Spacer(Modifier.height(Spacing.s5))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2)
        ) {
            Stat(
                label = "NP",
                value = summary.normalizedPowerWatts.toString(),
                unit = "W",
                modifier = Modifier.weight(1f)
            )
            Stat(label = "TSS", value = summary.tss.toString(), modifier = Modifier.weight(1f))
            Stat(
                label = "IF",
                value = "%.2f".format(summary.intensityFactor),
                modifier = Modifier.weight(1f)
            )
            Stat(
                label = "kJ",
                value = summary.kilojoules.toString(),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SteppedZoneChart(summary: WorkoutSummary, chartHeight: Int) {
    val maxPct = (summary.workout.segments.maxOfOrNull { it.powerPercentFTP } ?: 100).coerceAtLeast(60)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        summary.workout.segments.forEach { seg ->
            val zone = ZonePalette.fromFtpPercent(seg.powerPercentFTP)
            val heightFraction = (seg.powerPercentFTP.toFloat() / maxPct).coerceIn(0.05f, 1f)
            Box(
                modifier = Modifier
                    .weight(seg.lengthInSeconds.toFloat().coerceAtLeast(1f))
                    .fillMaxHeight(heightFraction)
                    .background(zone.color, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
            )
        }
    }
}

@Composable
private fun StepRow(segment: PowerZone, ftp: Int) {
    val zone = ZonePalette.fromFtpPercent(segment.powerPercentFTP)
    val watts = (segment.powerPercentFTP / 100.0 * ftp).roundToInt()
    val mins = segment.lengthInSeconds / 60
    val secs = segment.lengthInSeconds % 60
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s5, vertical = Spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        ZoneBadge(zone = zone)
        Text(
            text = zone.label,
            style = CadenceType.body.copy(fontWeight = FontWeight.SemiBold),
            color = CadenceColors.Fg2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // Cadence is the segment's second ask, so it earns its own chip rather
        // than being appended to the power line — a rider scanning the list
        // before starting wants to spot which intervals have one.
        segment.cadenceTarget?.let { target ->
            Text(
                text = "${target.label} rpm",
                style = CadenceType.tick,
                color = CadenceColors.Fg2,
                modifier = Modifier
                    .clip(RoundedCornerShape(Radii.sm))
                    .background(CadenceColors.Bg2)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Text(
            text = "%d:%02d".format(mins, secs),
            style = CadenceType.bodyStrong,
            color = CadenceColors.Fg1
        )
        Text(
            text = "$watts W",
            style = CadenceType.small.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = CadenceColors.Fg3,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp)
        )
    }
}

/**
 * Bottom CTA used when the rider is previewing a workout from a remote
 * community repo. Mirrors the "Add" pill from the browser card list so the
 * affordance is consistent across the two surfaces. Idempotent: re-tapping
 * after a successful add is ignored — duplicate adds would clobber any
 * local edits the rider has made (e.g. rename) on the now-imported copy.
 */
@Composable
private fun AddToLibraryCta(
    isAlreadyAdded: Boolean,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CadenceColors.Bg0)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = Spacing.s5, end = Spacing.s5, top = Spacing.s3, bottom = Spacing.s6)
    ) {
        if (isAlreadyAdded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(CircleShape)
                    .background(CadenceColors.Bg2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = CadenceColors.Success,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(Spacing.s2))
                Text(
                    text = "Added to your library",
                    style = CadenceType.bodyStrong.copy(fontSize = 16.sp),
                    color = CadenceColors.Fg1
                )
            }
        } else {
            PrimaryBtn(
                text = "Add to my library",
                leadingIcon = Icons.Outlined.CloudDownload,
                onClick = onAdd,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun StartCta(
    isTrainerReady: Boolean,
    onStart: () -> Unit,
    onPairTrainer: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CadenceColors.Bg0)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = Spacing.s5, end = Spacing.s5, top = Spacing.s3, bottom = Spacing.s6)
    ) {
        if (isTrainerReady) {
            PrimaryBtn(
                text = "Start workout",
                onClick = onStart,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                text = if (app.indoorbike.BuildConfig.DEBUG)
                    "Pair a smart trainer (or use the mock trainer) before starting a ride."
                else
                    "Pair a smart trainer before starting a ride.",
                style = CadenceType.body.copy(fontSize = 14.sp),
                color = CadenceColors.Fg3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s3)
            )
            PrimaryBtn(
                text = "Pair a trainer to ride",
                leadingIcon = Icons.AutoMirrored.Outlined.BluetoothSearching,
                onClick = onPairTrainer,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
