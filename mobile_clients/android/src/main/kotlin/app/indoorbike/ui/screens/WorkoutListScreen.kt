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
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Search
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
import app.indoorbike.shared.Workout
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.FilterChip
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.MetaPill
import app.indoorbike.ui.components.TabBar
import app.indoorbike.ui.components.TabItem
import app.indoorbike.ui.components.TrainerStatusStrip
import app.indoorbike.ui.components.ZoneBar
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.workout.WorkoutSummary
import app.indoorbike.ui.workout.toSummary

private val Filters = listOf("All", "Endurance", "Tempo", "Threshold", "VO2 Max", "Recovery")

private val Tabs = listOf(
    TabItem("rides", Icons.AutoMirrored.Outlined.DirectionsBike, "Ride"),
    TabItem("history", Icons.Outlined.AccessTime, "History"),
    TabItem("progress", Icons.Outlined.EmojiEvents, "Progress"),
    TabItem("settings", Icons.Outlined.Settings, "Settings")
)

@Composable
fun WorkoutListScreen(
    workouts: List<Workout>,
    eyebrow: String,
    onPick: (Workout) -> Unit,
    activeTab: String = "rides",
    onTabSelected: (String) -> Unit = {}
) {
    var selectedFilter by remember { mutableStateOf("All") }
    val ftp by app.indoorbike.UserSettings.ftp.collectAsState()
    val summaries = remember(workouts, ftp) { workouts.map { it.toSummary(ftp) } }
    val visible = remember(summaries, selectedFilter) {
        if (selectedFilter == "All") summaries
        else summaries.filter { it.tag.equals(selectedFilter, ignoreCase = true) }
    }

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
            trailing = { IconBtn(icon = Icons.Outlined.Search, contentDescription = "Search") }
        )

        val connectionState by app.indoorbike.shared.ble.TrainerManager.connectionState.collectAsState()
        val pairedTrainerId by app.indoorbike.shared.ble.TrainerManager.pairedTrainerId.collectAsState()
        val isMockTrainer by app.indoorbike.shared.ble.TrainerManager.isMockTrainer.collectAsState()
        val connectedTrainerName by app.indoorbike.shared.ble.TrainerManager.connectedTrainerName.collectAsState()

        if (pairedTrainerId == null && !isMockTrainer) {
            // Show Add Trainer empty state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.s5)
                    .clip(RoundedCornerShape(Radii.md))
                    .background(CadenceColors.Brand500)
                    .clickable { onTabSelected("settings") }
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Add Trainer",
                    color = CadenceColors.Bg0,
                    style = CadenceType.bodyStrong
                )
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
            Filters.forEach { f ->
                FilterChip(label = f, selected = f == selectedFilter, onClick = { selectedFilter = f })
            }
        }
        Spacer(Modifier.height(Spacing.s3))

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
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f)) {
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
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = CadenceColors.Fg3,
                modifier = Modifier.size(18.dp)
            )
        }
        ZoneBar(segments = summary.zoneSegments, height = 8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetaPill(text = summary.durationLabel.uppercase())
            MetaPill(text = "TSS ${summary.tss}")
            MetaPill(text = "IF ${"%.2f".format(summary.intensityFactor)}")
        }
    }
}
