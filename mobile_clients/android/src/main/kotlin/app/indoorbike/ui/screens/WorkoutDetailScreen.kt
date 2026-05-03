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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.ZoneBadge
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.workout.WorkoutSummary
import app.indoorbike.ui.workout.toSummary
import app.indoorbike.ui.zones.PowerZone as ZonePalette
import kotlin.math.roundToInt

@Composable
fun WorkoutDetailScreen(
    workout: Workout,
    onBack: () -> Unit,
    onStart: () -> Unit
) {
    val ftp by app.indoorbike.UserSettings.ftp.collectAsState()
    val summary = remember(workout, ftp) { workout.toSummary(ftp) }
    var stepsExpanded by remember(workout.id) { mutableStateOf(false) }
    val stepCutoff = 6
    val totalSteps = summary.workout.segments.size
    val visibleSteps = if (stepsExpanded || totalSteps <= stepCutoff)
        summary.workout.segments
    else summary.workout.segments.take(stepCutoff)

    Column(modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0)) {
        AppBar(
            title = workout.name,
            eyebrow = summary.tag,
            leading = {
                IconBtn(icon = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", onClick = onBack)
            }
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

        StartCta(onStart = onStart)
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
                    text = summary.durationLabel,
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
            Text(text = summary.durationLabel, style = CadenceType.tick.copy(fontSize = 10.sp), color = CadenceColors.Fg3)
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
private fun StartCta(onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(CadenceColors.Bg0)
            .border(1.dp, CadenceColors.Line1)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = Spacing.s5, vertical = Spacing.s3)
    ) {
        PrimaryBtn(
            text = "Start workout",
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            glow = true
        )
    }
}
