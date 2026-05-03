package app.indoorbike.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.workout.formatHHMM
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun WorkoutOverviewScreen(
    sessionId: Long,
    workoutList: List<Workout>,
    historyRepository: HistoryRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<WorkoutSession?>(null) }
    var telemetry by remember { mutableStateOf<List<WorkoutTelemetry>>(emptyList()) }
    var targetWorkout by remember { mutableStateOf<Workout?>(null) }

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
            .safeDrawingPadding()
    ) {
        AppBar(
            title = session?.workoutName ?: "Loading...",
            eyebrow = session?.let { formatEpochToDate(it.startTimeEpoch) } ?: "",
            leading = {
                IconBtn(icon = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", onClick = onBack)
            }
        )

        if (session == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading...", color = CadenceColors.Fg3, style = CadenceType.body)
            }
            return
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.s5)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(Spacing.s5))

            // Summary Metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s4)
            ) {
                OverviewGlassCard(
                    modifier = Modifier.weight(1f),
                    label = "DURATION",
                    value = formatHHMM(session!!.durationSeconds.toInt()),
                    unit = "",
                    valueColor = CadenceColors.Fg1
                )
                OverviewGlassCard(
                    modifier = Modifier.weight(1f),
                    label = "AVG POWER",
                    value = session!!.avgPower.toString(),
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
                    value = session!!.avgCadence.toString(),
                    unit = "RPM",
                    valueColor = CadenceColors.Fg1
                )
                OverviewGlassCard(
                    modifier = Modifier.weight(1f),
                    label = "AVG HR",
                    value = session!!.avgHeartRate.toString(),
                    unit = "BPM",
                    valueColor = CadenceColors.Danger
                )
            }

            Spacer(modifier = Modifier.height(Spacing.s6))

            // Chart
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
                    ChartOverlay(workout = targetWorkout!!, telemetry = telemetry)
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No chart data available.", color = CadenceColors.Fg3, style = CadenceType.body)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(Spacing.s6))
        }
    }
}

@Composable
fun ChartOverlay(workout: Workout, telemetry: List<WorkoutTelemetry>) {
    val maxPct = (workout.segments.maxOfOrNull { it.powerPercentFTP } ?: 100).coerceAtLeast(60)
    val maxTelemetryPower = telemetry.maxOfOrNull { it.power } ?: 1L
    val maxChartValue = maxOf(maxPct.toFloat(), maxTelemetryPower.toFloat() / 200f * 100f) // roughly normalize

    Box(modifier = Modifier.fillMaxSize()) {
        // Draw Target Mountain
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            workout.segments.forEach { seg ->
                val zone = app.indoorbike.ui.zones.PowerZone.fromFtpPercent(seg.powerPercentFTP)
                val h = (seg.powerPercentFTP.toFloat() / maxChartValue).coerceIn(0.1f, 1f)
                
                Box(
                    modifier = Modifier
                        .weight(seg.lengthInSeconds.toFloat().coerceAtLeast(1f))
                        .fillMaxHeight(h)
                        .background(zone.color.copy(alpha = 0.3f))
                )
            }
        }

        // Draw Actual Telemetry Line overlay
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val points = telemetry.size

            if (points > 1) {
                val path = Path()
                
                telemetry.forEachIndexed { index, point ->
                    val x = (index.toFloat() / (points - 1)) * width
                    // Normalize actual power roughly
                    val normalizedPower = (point.power.toFloat() / 200f * 100f)
                    val y = height - ((normalizedPower / maxChartValue) * height).coerceIn(0f, height)

                    if (index == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }

                drawPath(
                    path = path,
                    color = CadenceColors.Brand500,
                    style = Stroke(width = 4f)
                )
            }
        }
    }
}

@Composable
private fun OverviewGlassCard(modifier: Modifier = Modifier, label: String, value: String, unit: String, valueColor: Color) {
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

private fun formatEpochToDate(epoch: Long): String {
    val fmt = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
    return fmt.format(Date(epoch))
}
