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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.SaveAlt
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
import app.indoorbike.LocalAppContainer
import app.indoorbike.share.ExportSheet
import app.indoorbike.share.ShareSheet
import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.db.WorkoutTelemetry
import app.indoorbike.sync.IntervalsIcuUploader
import android.widget.Toast
import app.indoorbike.shared.progression.RideStats
import app.indoorbike.shared.progression.computeRideStats
import app.indoorbike.shared.progression.timeInZoneSeconds
import app.indoorbike.shared.workout.formatClock
import app.indoorbike.shared.workout.FREE_RIDE_WORKOUT_ID
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.zones.PowerZone as ZonePalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WorkoutOverviewScreen(
    sessionId: Long,
    workoutList: List<Workout>,
    onBack: () -> Unit,
    fromRide: Boolean = false,
    onDone: () -> Unit = {}
) {
    val container = LocalAppContainer.current
    val historyRepository = container.historyRepository
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<WorkoutSession?>(null) }
    var telemetry by remember { mutableStateOf<List<WorkoutTelemetry>>(emptyList()) }
    var targetWorkout by remember { mutableStateOf<Workout?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    val ftp by container.userSettings.ftp.collectAsState()
    // Speed and distance are estimated from power and the mass being moved,
    // so a rider who hasn't set a weight gets no estimate at all.
    val weightKg by container.userSettings.weightKg.collectAsState()
    val intervalsAthlete by container.userSettings.intervalsAthleteId.collectAsState()
    val intervalsKey by container.userSettings.intervalsApiKey.collectAsState()
    val autoUpload by container.userSettings.intervalsAutoUpload.collectAsState()
    val intervalsConfigured = !intervalsAthlete.isNullOrBlank() && !intervalsKey.isNullOrBlank()
    val context = androidx.compose.ui.platform.LocalContext.current

    var uploading by remember { mutableStateOf(false) }
    var uploadAttemptedForSession by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(sessionId) {
        // SQLDelight's Android driver does its query work synchronously on the
        // calling thread; a long ride's telemetry table read can be thousands
        // of rows. Hop to IO so a frame stutter on the overview's first frame
        // doesn't bite — Composable state writes still reach the UI safely.
        val (s, tele) = withContext(Dispatchers.IO) {
            val s = historyRepository.getSessionById(sessionId)
            val tele = if (s != null) historyRepository.getTelemetryForSession(sessionId) else emptyList()
            s to tele
        }
        if (s != null) {
            session = s
            telemetry = tele
            targetWorkout = workoutList.find { it.id == s.workoutId }
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
                Row {
                    IconBtn(
                        icon = Icons.Outlined.IosShare,
                        contentDescription = "Share ride",
                        onClick = { showShareSheet = true }
                    )
                    IconBtn(
                        icon = Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete session",
                        onClick = { confirmDelete = true }
                    )
                }
            }
        )

        if (session == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading...", color = CadenceColors.Fg3, style = CadenceType.body)
            }
            return
        }

        val activeSession = session!!
        val stats: RideStats = remember(telemetry, ftp, weightKg, activeSession.durationSeconds) {
            computeRideStats(
                telemetryWatts = telemetry.map { it.power },
                durationSeconds = activeSession.durationSeconds,
                ftp = ftp,
                riderKg = weightKg
            )
        }
        val ttz: IntArray = remember(telemetry, ftp) {
            timeInZoneSeconds(telemetry.map { it.power }, ftp)
        }

        // Helper that runs the actual upload — used by both the manual button
        // and the auto-upload-on-arrival LaunchedEffect below. Posts a toast
        // with the result; doesn't block navigation.
        fun runIntervalsUpload() {
            if (uploading) return
            uploading = true
            scope.launch {
                val outcome = container.intervalsIcuUploader.upload(
                    session = activeSession,
                    workout = targetWorkout,
                    ftp = ftp,
                )
                val msg = when (outcome) {
                    is IntervalsIcuUploader.Outcome.Success -> "Uploaded to Intervals.icu"
                    is IntervalsIcuUploader.Outcome.NotConfigured -> "Add credentials in Settings → Cloud sync first"
                    is IntervalsIcuUploader.Outcome.Failure -> outcome.message
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                uploading = false
            }
        }

        // Auto-upload exactly once when arriving from a finished ride and the
        // user has opted in. Recompositions don't retry — `uploadAttemptedForSession`
        // gates per-session-id.
        LaunchedEffect(activeSession.id, fromRide, autoUpload, intervalsConfigured) {
            if (!fromRide || !autoUpload || !intervalsConfigured) return@LaunchedEffect
            if (uploadAttemptedForSession == activeSession.id) return@LaunchedEffect
            uploadAttemptedForSession = activeSession.id
            runIntervalsUpload()
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
                    value = formatClock(activeSession.durationSeconds.toInt()),
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

            // A trainer measures no ground speed, so these are estimates from
            // power rather than readings. Absent entirely when the rider has no
            // weight set, since there is then nothing to estimate from.
            if (stats.distanceMeters > 0) {
                Spacer(modifier = Modifier.height(Spacing.s4))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s4)
                ) {
                    OverviewGlassCard(
                        modifier = Modifier.weight(1f),
                        label = "DISTANCE",
                        value = "%.2f".format(stats.distanceMeters / 1000f),
                        unit = "KM",
                        valueColor = CadenceColors.Fg1
                    )
                    OverviewGlassCard(
                        modifier = Modifier.weight(1f),
                        label = "AVG SPEED",
                        value = "%.1f".format(stats.avgSpeedKmh),
                        unit = "KM/H",
                        valueColor = CadenceColors.Fg1
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.s2))
                Text(
                    text = "Estimated from power on a flat virtual road.",
                    style = CadenceType.tick,
                    color = CadenceColors.Fg3
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

            // Adherence is a ride measured against its plan. A free ride had
            // none, so the section is dropped rather than shown empty - an
            // empty chart there reads as missing data instead of as an answer.
            // A structured ride whose workout has since been deleted still
            // gets the empty state, because there the data really is missing.
            if (activeSession.workoutId != FREE_RIDE_WORKOUT_ID) {
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
            }

            if (telemetry.isNotEmpty() && ttz.any { it > 0 }) {
                Spacer(modifier = Modifier.height(Spacing.s6))
                TimeInZoneCard(ttzSeconds = ttz, totalSeconds = activeSession.durationSeconds.toInt())
            }

            if (telemetry.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.s6))
                ExportFileCard(onExport = { showExportSheet = true })
            }

            if (intervalsConfigured) {
                Spacer(modifier = Modifier.height(Spacing.s6))
                IntervalsUploadCard(
                    uploading = uploading,
                    autoEnabled = autoUpload,
                    onUpload = { runIntervalsUpload() }
                )
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
                    withContext(Dispatchers.IO) {
                        historyRepository.deleteSession(sessionId)
                    }
                    onBack()
                }
            }
        )
    }

    if (showShareSheet) {
        val sessionForSheet = session
        if (sessionForSheet != null) {
            ShareSheet(
                session = sessionForSheet,
                stats = computeRideStats(
                    telemetryWatts = telemetry.map { it.power },
                    durationSeconds = sessionForSheet.durationSeconds,
                    ftp = ftp,
                    riderKg = weightKg
                ),
                workout = targetWorkout,
                telemetry = telemetry,
                ftp = ftp,
                onDismiss = { showShareSheet = false }
            )
        }
    }

    if (showExportSheet) {
        val sessionForExport = session
        if (sessionForExport != null) {
            ExportSheet(
                session = sessionForExport,
                telemetry = telemetry,
                workout = targetWorkout,
                ftp = ftp,
                riderKg = weightKg,
                onDismiss = { showExportSheet = false }
            )
        }
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
                    text = formatClock(secs),
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

        // Pre-compute the y-fraction per telemetry point. Caching this avoids
        // recomputing 1800–14400 (point.power / ftp / maxChartPct) divisions
        // on every Canvas redraw. The Path itself depends on canvas size so it
        // still has to be assembled per draw, but the per-point math is cheap
        // once the y-fractions are an `IntArray`-equivalent.
        val yFractions = remember(telemetry, ftp, maxChartPct) {
            if (telemetry.isEmpty() || ftp <= 0) FloatArray(0)
            else FloatArray(telemetry.size) { i ->
                val pct = (telemetry[i].power.toFloat() / ftp * 100f).coerceAtLeast(0f)
                1f - (pct / maxChartPct).coerceIn(0f, 1f)
            }
        }
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val points = yFractions.size
            if (points <= 1) return@Canvas
            val path = Path()
            for (i in 0 until points) {
                val x = (i.toFloat() / (points - 1)) * width
                val y = yFractions[i] * height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
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

/**
 * Bottom-of-overview card that exposes the manual Intervals.icu upload. Only
 * rendered when credentials are configured; the auto-upload toggle runs
 * silently in the background via the LaunchedEffect above.
 */
@Composable
private fun IntervalsUploadCard(
    uploading: Boolean,
    autoEnabled: Boolean,
    onUpload: () -> Unit
) {
    val shape = RoundedCornerShape(Radii.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .padding(Spacing.s5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CadenceColors.Brand500.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudUpload,
                contentDescription = null,
                tint = CadenceColors.Brand500,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(Spacing.s3))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Intervals.icu", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
            Text(
                text = if (autoEnabled) "Auto-upload is on. Tap to re-send."
                else "Tap to upload this ride.",
                style = CadenceType.tick,
                color = CadenceColors.Fg3
            )
        }
        SecondaryBtn(
            text = if (uploading) "Uploading…" else "Upload",
            onClick = { if (!uploading) onUpload() }
        )
    }
}

@Composable
private fun ExportFileCard(onExport: () -> Unit) {
    val shape = RoundedCornerShape(Radii.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .padding(Spacing.s5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CadenceColors.Brand500.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.SaveAlt,
                contentDescription = null,
                tint = CadenceColors.Brand500,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(Spacing.s3))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Export file", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
            Text(
                text = "Save or share this ride as FIT or TCX.",
                style = CadenceType.tick,
                color = CadenceColors.Fg3
            )
        }
        SecondaryBtn(
            text = "Export",
            onClick = onExport
        )
    }
}
