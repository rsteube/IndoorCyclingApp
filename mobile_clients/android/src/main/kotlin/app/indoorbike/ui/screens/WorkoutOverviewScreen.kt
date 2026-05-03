package app.indoorbike.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.HistoryRepository
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.db.WorkoutTelemetry
import app.indoorbike.shared.progression.RideStats
import app.indoorbike.shared.progression.computeRideStats
import app.indoorbike.shared.progression.timeInZoneSeconds
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.workout.formatHHMM
import app.indoorbike.ui.zones.PowerZone as ZonePalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun WorkoutOverviewScreen(
    sessionId: Long,
    workoutList: List<Workout>,
    historyRepository: HistoryRepository,
    onBack: () -> Unit,
    fromRide: Boolean = false,
    onDone: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<WorkoutSession?>(null) }
    var telemetry by remember { mutableStateOf<List<WorkoutTelemetry>>(emptyList()) }
    var targetWorkout by remember { mutableStateOf<Workout?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val ftp by app.indoorbike.UserSettings.ftp.collectAsState()

    LaunchedEffect(sessionId) {
        scope.launch {
            val s = historyRepository.getSessionById(sessionId)
            if (s != null) {
                session = s
                telemetry = historyRepository.getTelemetryForSession(sessionId)
                targetWorkout = workoutList.find { it.id == s.workoutId }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
    ) {
        AppBar(
            title = session?.workoutName ?: "Loading...",
            eyebrow = session?.let { formatEpochToDate(it.startTimeEpoch) } ?: "",
            leading = {
                IconBtn(icon = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", onClick = onBack)
            },
            trailing = {
                IconBtn(
                    icon = Icons.Outlined.DeleteOutline,
                    contentDescription = "Delete session",
                    onClick = { confirmDelete = true }
                )
            }
        )

        if (session == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading...", color = CadenceColors.Fg3, style = CadenceType.body)
            }
            return
        }

        val activeSession = session!!
        val stats: RideStats = remember(telemetry, ftp, activeSession.durationSeconds) {
            computeRideStats(
                telemetryWatts = telemetry.map { it.power },
                durationSeconds = activeSession.durationSeconds,
                ftp = ftp
            )
        }
        val ttz: IntArray = remember(telemetry, ftp) {
            timeInZoneSeconds(telemetry.map { it.power }, ftp)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = Spacing.s5)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(Spacing.s5))

            // Headline metrics: duration, kJ, avg/np
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s4)
            ) {
                OverviewGlassCard(
                    modifier = Modifier.weight(1f),
                    label = "DURATION",
                    value = formatHHMM(activeSession.durationSeconds.toInt()),
                    unit = "",
                    valueColor = CadenceColors.Fg1
                )
                OverviewGlassCard(
                    modifier = Modifier.weight(1f),
                    label = "AVG POWER",
                    value = activeSession.avgPower.toString(),
                    unit = "W",
                    valueColor = CadenceColors.Brand500
                )
            }
            Spacer(modifier = Modifier.height(Spacing.s4))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s4)
            ) {
                OverviewGlassCard(
                    modifier = Modifier.weight(1f),
                    label = "AVG CADENCE",
                    value = activeSession.avgCadence.toString(),
                    unit = "RPM",
                    valueColor = CadenceColors.Fg1
                )
                OverviewGlassCard(
                    modifier = Modifier.weight(1f),
                    label = "AVG HR",
                    value = activeSession.avgHeartRate.toString(),
                    unit = "BPM",
                    valueColor = CadenceColors.Danger
                )
            }

            // Computed stats: NP / IF / TSS / kJ
            if (telemetry.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.s4))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s2)
                ) {
                    StatPill(label = "NP", value = "${stats.normalizedPowerWatts}", suffix = "W", modifier = Modifier.weight(1f))
                    StatPill(label = "IF", value = "%.2f".format(stats.intensityFactor), modifier = Modifier.weight(1f))
                    StatPill(label = "TSS", value = stats.tss.toString(), modifier = Modifier.weight(1f))
                    StatPill(label = "kJ", value = stats.kilojoules.toString(), modifier = Modifier.weight(1f))
                }
            }

            Spacer(modifier = Modifier.height(Spacing.s6))

            Text("Power Adherence", style = CadenceType.h3, color = CadenceColors.Fg1)
            Spacer(modifier = Modifier.height(Spacing.s3))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CadenceColors.Bg1)
                    .border(1.dp, CadenceColors.Line2, RoundedCornerShape(12.dp))
            ) {
                if (targetWorkout != null && telemetry.isNotEmpty()) {
                    ChartOverlay(
                        workout = targetWorkout!!,
                        telemetry = telemetry,
                        ftp = ftp
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No chart data available.", color = CadenceColors.Fg3, style = CadenceType.body)
                    }
                }
            }

            if (telemetry.isNotEmpty() && ttz.any { it > 0 }) {
                Spacer(modifier = Modifier.height(Spacing.s6))
                TimeInZoneCard(ttzSeconds = ttz, totalSeconds = activeSession.durationSeconds.toInt())
            }

            Spacer(modifier = Modifier.height(Spacing.s6))
        }

        if (fromRide) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CadenceColors.Bg0)
                    .border(1.dp, CadenceColors.Line1)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = Spacing.s5, vertical = Spacing.s3)
            ) {
                PrimaryBtn(
                    text = "Done",
                    leadingIcon = null,
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                    glow = true
                )
            }
        } else {
            Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }

    if (confirmDelete) {
        DeleteConfirmDialog(
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    historyRepository.deleteSession(sessionId)
                    onBack()
                }
            }
        )
    }
}

@Composable
private fun StatPill(label: String, value: String, suffix: String = "", modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radii.md)
    Column(
        modifier = modifier
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line1, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, style = CadenceType.tick, color = CadenceColors.Fg3)
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = CadenceType.h3, color = CadenceColors.Fg1)
            if (suffix.isNotEmpty()) {
                Text(
                    " $suffix",
                    style = CadenceType.tick,
                    color = CadenceColors.Fg3,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun TimeInZoneCard(ttzSeconds: IntArray, totalSeconds: Int) {
    val shape = RoundedCornerShape(Radii.lg)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .padding(Spacing.s5)
    ) {
        Text(
            text = "TIME IN ZONE",
            style = CadenceType.eyebrow,
            color = CadenceColors.Fg3
        )
        Spacer(modifier = Modifier.height(Spacing.s3))
        val total = (ttzSeconds.sum().takeIf { it > 0 } ?: totalSeconds).coerceAtLeast(1)
        ttzSeconds.forEachIndexed { idx, secs ->
            if (secs <= 0) return@forEachIndexed
            val zone = ZonePalette.entries[idx]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text(
                    text = "Z${zone.number}",
                    style = CadenceType.tick.copy(fontSize = 12.sp),
                    color = CadenceColors.Fg2,
                    modifier = Modifier.width(28.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(CadenceColors.Bg0)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(secs.toFloat() / total)
                            .fillMaxHeight()
                            .background(zone.color)
                    )
                }
                Text(
                    text = formatHHMM(secs),
                    style = CadenceType.tick,
                    color = CadenceColors.Fg2,
                    modifier = Modifier.width(56.dp)
                )
            }
        }
    }
}

@Composable
fun ChartOverlay(
    workout: Workout,
    telemetry: List<WorkoutTelemetry>,
    ftp: Int
) {
    val targetMaxPct = (workout.segments.maxOfOrNull { it.powerPercentFTP } ?: 100).coerceAtLeast(60)
    val telemetryMaxPct = if (ftp > 0) {
        ((telemetry.maxOfOrNull { it.power } ?: 0L).toFloat() / ftp * 100f).toInt()
    } else 0
    val maxChartPct = maxOf(targetMaxPct, telemetryMaxPct).coerceAtLeast(60)

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            workout.segments.forEach { seg ->
                val zone = ZonePalette.fromFtpPercent(seg.powerPercentFTP)
                val h = (seg.powerPercentFTP.toFloat() / maxChartPct).coerceIn(0.05f, 1f)
                Box(
                    modifier = Modifier
                        .weight(seg.lengthInSeconds.toFloat().coerceAtLeast(1f))
                        .fillMaxHeight(h)
                        .background(zone.color.copy(alpha = 0.30f))
                )
            }
        }

        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val points = telemetry.size
            if (points <= 1 || ftp <= 0) return@Canvas
            val path = Path()
            telemetry.forEachIndexed { index, point ->
                val x = (index.toFloat() / (points - 1)) * width
                val pct = (point.power.toFloat() / ftp * 100f).coerceAtLeast(0f)
                val y = (height - (pct / maxChartPct) * height).coerceIn(0f, height)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = CadenceColors.Brand500,
                style = Stroke(width = 4f)
            )
        }
    }
}

@Composable
private fun OverviewGlassCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    unit: String,
    valueColor: Color
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CadenceColors.Bg1.copy(alpha = 0.4f))
            .border(1.dp, CadenceColors.Line2.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, style = CadenceType.eyebrow, color = CadenceColors.Fg3)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = valueColor
                )
                if (unit.isNotEmpty()) {
                    Text(
                        text = " $unit",
                        style = CadenceType.bodyStrong,
                        color = CadenceColors.Fg3,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
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
            Text("Delete this ride?", style = CadenceType.h3, color = CadenceColors.Fg1)
            Text(
                "Telemetry and stats for this session will be removed. This can't be undone.",
                style = CadenceType.body,
                color = CadenceColors.Fg2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
                SecondaryBtn(text = "Cancel", onClick = onCancel, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape.let { RoundedCornerShape(14.dp) })
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

private fun formatEpochToDate(epoch: Long): String {
    val fmt = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
    return fmt.format(Date(epoch))
}
