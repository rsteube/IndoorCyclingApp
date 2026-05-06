package app.indoorbike.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.components.ZoneBadge
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.shared.workout.WorkoutSummary
import app.indoorbike.shared.workout.formatDurationPretty
import app.indoorbike.shared.workout.toSummary
import app.indoorbike.ui.zones.PowerZone as ZonePalette
import kotlin.math.roundToInt

@Composable
fun WorkoutDetailScreen(
    workout: Workout,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onNavigateToPairing: () -> Unit,
    isImported: Boolean = false,
    onRename: (String) -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val container = LocalAppContainer.current
    val ftp by container.userSettings.ftp.collectAsState()
    val trainerState by container.trainerManager.connectionState.collectAsState()
    val isMockTrainer by container.trainerManager.isMockTrainer.collectAsState()
    val isTrainerReady = trainerState == TrainerConnectionState.Connected || isMockTrainer
    val summary = remember(workout, ftp) { workout.toSummary(ftp) }
    var stepsExpanded by remember(workout.id) { mutableStateOf(false) }
    val stepCutoff = 6
    val totalSteps = summary.workout.segments.size
    val visibleSteps = if (stepsExpanded || totalSteps <= stepCutoff)
        summary.workout.segments
    else summary.workout.segments.take(stepCutoff)

    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0)) {
    Column(modifier = Modifier.fillMaxSize()) {
        AppBar(
            title = workout.name,
            eyebrow = summary.tag,
            leading = {
                IconBtn(icon = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", onClick = onBack)
            },
            trailing = if (isImported) {
                {
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
            } else null
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = Spacing.s5),
            verticalArrangement = Arrangement.spacedBy(Spacing.s5)
        ) {
            item { ZoneProfileCard(summary) }
            item { StatsGrid(summary) }
            item {
                Text(
                    text = "STEPS · $totalSteps",
                    style = CadenceType.eyebrow,
                    color = CadenceColors.Fg3
                )
            }
            itemsIndexed(visibleSteps) { _, segment ->
                StepRow(segment, ftp)
            }
            if (!stepsExpanded && totalSteps > stepCutoff) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { stepsExpanded = true }
                            .padding(vertical = Spacing.s2),
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
            item { Spacer(Modifier.height(Spacing.s4)) }
        }

        StartCta(
            isTrainerReady = isTrainerReady,
            onStart = onStart,
            onPairTrainer = onNavigateToPairing,
        )
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
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(Radii.lg))
                .background(CadenceColors.Bg1)
                .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.lg))
                .clickable(enabled = false, onClick = {})
                .padding(Spacing.s5),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4)
        ) {
            Text("Rename workout", style = CadenceType.h3, color = CadenceColors.Fg1)
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
                    .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.md))
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
                SecondaryBtn(text = "Cancel", onClick = onCancel, modifier = Modifier.weight(1f))
                val trimmed = input.trim()
                val canSave = trimmed.isNotEmpty() && trimmed != currentName
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (canSave) CadenceColors.Brand500 else CadenceColors.Bg2)
                        .clickable(enabled = canSave, onClick = { onConfirm(trimmed) })
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Save",
                        style = CadenceType.bodyStrong,
                        color = if (canSave) CadenceColors.OnBrand else CadenceColors.Fg3
                    )
                }
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
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(Radii.lg))
                .background(CadenceColors.Bg1)
                .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.lg))
                .clickable(enabled = false, onClick = {})
                .padding(Spacing.s5),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4)
        ) {
            Text("Delete \"$workoutName\"?", style = CadenceType.h3, color = CadenceColors.Fg1)
            Text(
                "The workout file will be removed from your device. Past rides of this workout stay in your history.",
                style = CadenceType.body,
                color = CadenceColors.Fg2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
                SecondaryBtn(text = "Cancel", onClick = onCancel, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CadenceColors.Danger)
                        .clickable(onClick = onConfirm)
                        .padding(horizontal = 20.dp, vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Delete", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                }
            }
        }
    }
}

@Composable
private fun ZoneProfileCard(summary: WorkoutSummary) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(text = "WORKOUT PROFILE", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatDurationPretty(summary.totalSeconds),
                    style = CadenceType.h2.copy(fontSize = 22.sp),
                    color = CadenceColors.Fg1
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = "NORMALIZED", style = CadenceType.tick, color = CadenceColors.Fg3)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${summary.normalizedPowerWatts} W",
                    style = CadenceType.h2.copy(fontSize = 22.sp),
                    color = CadenceColors.Brand500
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        SteppedZoneChart(summary, chartHeight = 110)
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "0:00", style = CadenceType.tick.copy(fontSize = 10.sp), color = CadenceColors.Fg3)
            Text(text = formatDurationPretty(summary.totalSeconds), style = CadenceType.tick.copy(fontSize = 10.sp), color = CadenceColors.Fg3)
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
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        summary.workout.segments.forEach { seg ->
            val zone = ZonePalette.fromFtpPercent(seg.powerPercentFTP)
            val heightFraction = (seg.powerPercentFTP.toFloat() / maxPct).coerceIn(0.05f, 1f)
            Box(
                modifier = Modifier
                    .weight(seg.lengthInSeconds.toFloat().coerceAtLeast(1f))
                    .fillMaxHeight(heightFraction)
                    .background(zone.color, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            )
        }
    }
}

@Composable
private fun StatsGrid(summary: WorkoutSummary) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCell(label = "TSS", value = summary.tss.toString(), modifier = Modifier.weight(1f))
        StatCell(label = "IF", value = "%.2f".format(summary.intensityFactor), modifier = Modifier.weight(1f))
        StatCell(label = "kJ", value = summary.kilojoules.toString(), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radii.md)
    Column(
        modifier = modifier
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = CadenceType.tick.copy(letterSpacing = 0.08.em),
            color = CadenceColors.Fg3
        )
        Spacer(Modifier.height(2.dp))
        Text(text = value, style = CadenceType.h2.copy(fontSize = 22.sp), color = CadenceColors.Fg1)
    }
}

@Composable
private fun StepRow(segment: PowerZone, ftp: Int) {
    val zone = ZonePalette.fromFtpPercent(segment.powerPercentFTP)
    val watts = (segment.powerPercentFTP / 100.0 * ftp).roundToInt()
    val mins = segment.lengthInSeconds / 60
    val secs = segment.lengthInSeconds % 60
    val durLabel = when {
        mins == 0 -> "${secs}s"
        secs == 0 -> "${mins}m"
        else -> "${mins}m ${secs}s"
    }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line1, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(28.dp)
                .background(zone.color, RoundedCornerShape(2.dp))
        )
        ZoneBadge(zone = zone)
        Text(
            text = "$durLabel · $watts W",
            style = CadenceType.tick.copy(fontSize = 12.sp),
            color = CadenceColors.Fg2,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
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
            .border(1.dp, CadenceColors.Line1)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = Spacing.s5, vertical = Spacing.s3)
    ) {
        if (isTrainerReady) {
            PrimaryBtn(
                text = "Start workout",
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                glow = true
            )
        } else {
            Text(
                text = if (app.indoorbike.BuildConfig.DEBUG)
                    "Pair a smart trainer (or use the mock trainer) before starting a ride."
                else
                    "Pair a smart trainer before starting a ride.",
                style = CadenceType.small,
                color = CadenceColors.Fg3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s2)
            )
            PrimaryBtn(
                text = "Pair a trainer to ride",
                leadingIcon = Icons.AutoMirrored.Outlined.BluetoothSearching,
                onClick = onPairTrainer,
                modifier = Modifier.fillMaxWidth(),
                glow = true
            )
        }
    }
}
