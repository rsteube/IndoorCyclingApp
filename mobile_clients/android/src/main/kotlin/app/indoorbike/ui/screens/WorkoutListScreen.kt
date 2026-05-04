package app.indoorbike.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.Workout
import app.indoorbike.shared.workout.WorkoutSummary
import app.indoorbike.shared.workout.formatDurationPretty
import app.indoorbike.shared.workout.toSummary
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.FilterChip
import app.indoorbike.ui.components.HrmHeartIndicator
import app.indoorbike.ui.components.MetaPill
import app.indoorbike.ui.components.TrainerStatusStrip
import app.indoorbike.ui.components.UnfinishedRideBanner
import app.indoorbike.ui.components.ZoneBar
import app.indoorbike.ui.workout.zoneSegments
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing

@Composable
fun WorkoutListScreen(
    workouts: List<Workout>,
    eyebrow: String,
    onPick: (Workout) -> Unit,
    activeTab: String = "rides",
    onTabSelected: (String) -> Unit = {}
) {
    val container = LocalAppContainer.current
    var selectedFilter by remember { mutableStateOf("All") }
    val ftp by container.userSettings.ftp.collectAsState()
    val summaries = remember(workouts, ftp) { workouts.map { it.toSummary(ftp) } }
    val availableTags = remember(summaries) {
        // Derive filter chips from the actual workouts present, not a hardcoded list.
        listOf("All") + summaries.map { it.tag }.distinct().sorted()
    }
    val visible = remember(summaries, selectedFilter) {
        if (selectedFilter == "All") summaries
        else summaries.filter { it.tag.equals(selectedFilter, ignoreCase = true) }
    }

    val hrmState by container.heartRateMonitor.connectionState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0)) {
        AppBar(
            title = "Pick a workout",
            eyebrow = eyebrow,
            leading = {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(Radii.sm))
                        .background(CadenceColors.Brand500.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.DirectionsBike,
                        contentDescription = null,
                        tint = CadenceColors.Brand500,
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            trailing = { HrmHeartIndicator(state = hrmState) }
        )

        UnfinishedRideBanner(
            modifier = Modifier.padding(horizontal = Spacing.s5, vertical = Spacing.s2)
        )

        val connectionState by container.trainerManager.connectionState.collectAsState()
        val pairedTrainerId by container.trainerManager.pairedTrainerId.collectAsState()
        val isMockTrainer by container.trainerManager.isMockTrainer.collectAsState()
        val connectedTrainerName by container.trainerManager.connectedTrainerName.collectAsState()

        if (pairedTrainerId == null && !isMockTrainer) {
            // Friendly nudge: not a saturated banner.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.s5)
                    .clip(RoundedCornerShape(Radii.md))
                    .background(CadenceColors.Bg1)
                    .border(1.dp, CadenceColors.Brand500.copy(alpha = 0.4f), RoundedCornerShape(Radii.md))
                    .clickable { onTabSelected("settings") }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CadenceColors.Brand500.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Bluetooth,
                        contentDescription = null,
                        tint = CadenceColors.Brand500,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Add a trainer", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                    Text(
                        text = "Pair your Wahoo Kickr or use the mock trainer",
                        style = CadenceType.tick,
                        color = CadenceColors.Fg3
                    )
                }
                Text(text = "›", style = CadenceType.h2, color = CadenceColors.Brand500)
            }
        } else {
            TrainerStatusStrip(
                deviceName = connectedTrainerName ?: "Unknown Trainer",
                statusLine = when (connectionState) {
                    app.indoorbike.shared.ble.TrainerConnectionState.Connected -> "Connected"
                    app.indoorbike.shared.ble.TrainerConnectionState.Connecting -> "Connecting..."
                    app.indoorbike.shared.ble.TrainerConnectionState.Scanning -> "Scanning..."
                    else -> "Not Connected"
                },
                icon = Icons.Outlined.Bluetooth,
                modifier = Modifier.padding(horizontal = Spacing.s5),
                connected = connectionState == app.indoorbike.shared.ble.TrainerConnectionState.Connected
            )
        }
        Spacer(Modifier.height(Spacing.s4))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s5),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            availableTags.forEach { f ->
                FilterChip(label = f, selected = f == selectedFilter, onClick = { selectedFilter = f })
            }
        }
        Spacer(Modifier.height(Spacing.s3))

        if (workouts.isEmpty()) {
            // Skeleton placeholders while the assets parser is still loading.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.s5),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(3) { SkeletonCard() }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = Spacing.s5, end = Spacing.s5,
                    top = 0.dp, bottom = Spacing.s5
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(visible, key = { it.workout.id }) { summary ->
                    WorkoutCard(summary = summary, onClick = { onPick(summary.workout) })
                }
            }
        }
    }
}

@Composable
private fun SkeletonCard() {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line1, shape)
            .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Box(
            modifier = Modifier
                .height(14.dp)
                .fillMaxWidth(0.4f)
                .clip(RoundedCornerShape(4.dp))
                .background(CadenceColors.Bg2)
        )
        Box(
            modifier = Modifier
                .height(20.dp)
                .fillMaxWidth(0.7f)
                .clip(RoundedCornerShape(4.dp))
                .background(CadenceColors.Bg2)
        )
        Box(
            modifier = Modifier
                .height(8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(CadenceColors.Bg2)
        )
    }
}

@Composable
private fun WorkoutCard(summary: WorkoutSummary, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .clickable(onClick = onClick)
            .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Column {
            Text(
                text = summary.tag.uppercase(),
                style = CadenceType.eyebrow,
                color = CadenceColors.Fg3
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary.workout.name,
                style = CadenceType.h3.copy(fontSize = 18.sp),
                color = CadenceColors.Fg1
            )
        }
        ZoneBar(segments = summary.zoneSegments(), height = 8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetaPill(text = formatDurationPretty(summary.totalSeconds).uppercase())
            MetaPill(text = "TSS ${summary.tss}")
            MetaPill(text = "IF ${"%.2f".format(summary.intensityFactor)}")
        }
    }
}
