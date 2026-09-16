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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.LocalAppContainer
import app.indoorbike.ride.rideRoute
import app.indoorbike.share.ExportSheet
import app.indoorbike.share.ShareSheet
import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.db.WorkoutTelemetry
import app.indoorbike.sync.FitTrackeeUploader
import app.indoorbike.sync.IntervalsIcuUploader
import android.widget.Toast
import app.indoorbike.shared.progression.RideStats
import app.indoorbike.shared.progression.computeRideStats
import app.indoorbike.shared.progression.timeInZoneSeconds
import app.indoorbike.shared.workout.formatClock
import app.indoorbike.shared.workout.FREE_RIDE_WORKOUT_ID
import app.indoorbike.shared.workout.COMPANION_WORKOUT_ID
import app.indoorbike.shared.workout.simulationRouteId
import app.indoorbike.ui.screens.simulation.RouteSummaryPanel
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.rememberCollapsingHeaderState
import app.indoorbike.ui.components.DestructiveBtn
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.ListCard
import app.indoorbike.ui.components.MetaPill
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.RideScreenshotsCard
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.components.SheetScrim
import app.indoorbike.ui.components.SlateCard
import app.indoorbike.ui.components.Stat
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.SlateMotion
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.theme.enterReveal
import app.indoorbike.ui.theme.rememberEntryProgress
import app.indoorbike.ui.zones.PowerZone as ZonePalette
import kotlin.math.roundToInt
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
    val intervalsToken by container.userSettings.intervalsAccessToken.collectAsState()
    val autoUpload by container.userSettings.intervalsAutoUpload.collectAsState()
    val intervalsConfigured = !intervalsToken.isNullOrBlank() ||
        (!intervalsAthlete.isNullOrBlank() && !intervalsKey.isNullOrBlank())
    val ftToken by container.userSettings.fitTrackeeAccessToken.collectAsState()
    val ftAutoUpload by container.userSettings.fitTrackeeAutoUpload.collectAsState()
    // A sport is no longer part of being configured: it defaults to Cycling
    // (Virtual) when the server won't share its list.
    val ftConfigured = !ftToken.isNullOrBlank()
    val context = androidx.compose.ui.platform.LocalContext.current

    var uploading by remember { mutableStateOf(false) }
    var uploadAttemptedForSession by remember { mutableStateOf<Long?>(null) }
    var ftUploading by remember { mutableStateOf(false) }
    var ftAttemptedForSession by remember { mutableStateOf<Long?>(null) }

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

    val header = rememberCollapsingHeaderState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
            .nestedScroll(header.nestedScrollConnection)
    ) {
        AppBar(
            header = header,
            title = session?.workoutName ?: "Loading…",
            eyebrow = session?.let {
                val stamp = formatEpochToDate(it.startTimeEpoch)
                if (fromRide) "Ride complete · $stamp" else stamp
            } ?: "",
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

        // This is the reward screen, so it is allowed to take a moment. The
        // two headline numbers count up first; the adherence chart then draws
        // the ride over its plan, bars first and the rider's line after, so
        // the eye is led from what was asked to what was done.
        val entry by rememberEntryProgress(key = activeSession.id, delayMillis = 150)
        val chartReveal by rememberEntryProgress(
            key = activeSession.id,
            durationMillis = 1300,
            delayMillis = 350,
            easing = SlateMotion.Easing,
        )

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
                if (outcome is IntervalsIcuUploader.Outcome.Success) {
                    // Record it on the ride, then patch the copy this screen is
                    // showing so the card flips to "Uploaded" on this frame
                    // rather than on the next visit.
                    val uploadedAt = System.currentTimeMillis()
                    historyRepository.markIntervalsUploaded(activeSession.id, uploadedAt)
                    session = session?.copy(intervalsUploadedEpoch = uploadedAt)
                }
                val msg = when (outcome) {
                    is IntervalsIcuUploader.Outcome.Success -> "Uploaded to Intervals.icu"
                    is IntervalsIcuUploader.Outcome.NotConfigured -> "Connect Intervals.icu in Settings → Connections first"
                    is IntervalsIcuUploader.Outcome.Unauthorized ->
                        "Intervals.icu no longer accepts this connection. Connect again in Settings → Connections."
                    is IntervalsIcuUploader.Outcome.Failure -> outcome.message
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                uploading = false
            }
        }

        fun runFitTrackeeUpload() {
            if (ftUploading) return
            ftUploading = true
            scope.launch {
                val outcome = container.fitTrackeeUploader.upload(
                    session = activeSession,
                    workout = targetWorkout,
                    ftp = ftp,
                )
                if (outcome is FitTrackeeUploader.Outcome.Success) {
                    val uploadedAt = System.currentTimeMillis()
                    historyRepository.markFitTrackeeUploaded(activeSession.id, uploadedAt)
                    session = session?.copy(fitTrackeeUploadedEpoch = uploadedAt)
                }
                val msg = when (outcome) {
                    // FitTrackee can't take a GPS-less file, so a ride usually
                    // lands as a summary. Say which happened rather than imply
                    // the power trace went with it.
                    is FitTrackeeUploader.Outcome.Success ->
                        if (outcome.withStreams) "Uploaded to FitTrackee"
                        else "Uploaded to FitTrackee as a summary (it can't take indoor files)"
                    is FitTrackeeUploader.Outcome.NotConfigured ->
                        "Connect FitTrackee in Settings → Connections first"
                    // Tokens expire server-side, so this is a normal thing to
                    // happen rather than a fault, and the fix is specific.
                    is FitTrackeeUploader.Outcome.Unauthorized ->
                        "FitTrackee sign-in has expired. Sign in again in Settings → Connections."
                    is FitTrackeeUploader.Outcome.Failure -> outcome.message
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                ftUploading = false
            }
        }

        LaunchedEffect(activeSession.id, fromRide, ftAutoUpload, ftConfigured) {
            if (!fromRide || !ftAutoUpload || !ftConfigured) return@LaunchedEffect
            if (activeSession.fitTrackeeUploadedEpoch > 0L) return@LaunchedEffect
            if (ftAttemptedForSession == activeSession.id) return@LaunchedEffect
            ftAttemptedForSession = activeSession.id
            runFitTrackeeUpload()
        }

        // Auto-upload exactly once when arriving from a finished ride and the
        // user has opted in. Recompositions don't retry — `uploadAttemptedForSession`
        // gates per-session-id.
        LaunchedEffect(activeSession.id, fromRide, autoUpload, intervalsConfigured) {
            if (!fromRide || !autoUpload || !intervalsConfigured) return@LaunchedEffect
            if (activeSession.intervalsUploadedEpoch > 0L) return@LaunchedEffect
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

            // The two numbers a rider looks for first, at a size nothing else
            // on the screen competes with.
            SlateCard(
                padding = PaddingValues(Spacing.s6),
                modifier = Modifier.enterReveal(index = 0),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    HeroStat(
                        label = "Duration",
                        value = formatClock((activeSession.durationSeconds * entry).roundToInt())
                    )
                    HeroStat(
                        label = "Avg power",
                        value = (activeSession.avgPower * entry).roundToInt().toString(),
                        unit = "W",
                        alignEnd = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.s3))

            // Everything else, in one grid. Nothing here is tinted: a heart
            // rate is not more urgent than a cadence, and colouring it red only
            // made the card look like a warning.
            SlateCard(modifier = Modifier.enterReveal(index = 1)) {
                StatGrid(
                    buildList {
                        add(Triple("Cadence", activeSession.avgCadence.toString(), "rpm"))
                        add(Triple("Heart rate", activeSession.avgHeartRate.toString(), "bpm"))
                        if (telemetry.isNotEmpty()) {
                            add(Triple("NP", stats.normalizedPowerWatts.toString(), "W"))
                            add(Triple("IF", "%.2f".format(stats.intensityFactor), null))
                            add(Triple("TSS", stats.tss.toString(), null))
                            add(Triple("kJ", stats.kilojoules.toString(), null))
                        }
                    }
                )
            }

            // The road, for a ride that had one. Above the estimated-distance
            // cards on purpose: on a route those estimates are the less
            // interesting number, because the ground covered was real ground on
            // a real road rather than a guess from watts.
            // Either the catalogue route a simulated ride names, or the road a
            // companion handed over with its ride. Both arrive as a RouteTrack,
            // so the panel below cannot tell them apart and does not need to.
            val riddenRoute = remember(activeSession.id) {
                rideRoute(container, activeSession)
            }
            if (riddenRoute != null) {
                Spacer(modifier = Modifier.height(Spacing.s3))
                RouteSummaryPanel(
                    route = riddenRoute,
                    riddenMeters = activeSession.routeDistanceMeters,
                    elevationGainMeters = activeSession.routeElevationGainMeters,
                    startedAtMeters = activeSession.routeStartMeters,
                    startedAtElevationGainMeters = activeSession.routeStartElevationGainMeters,
                    modifier = Modifier.fillMaxWidth().enterReveal(index = 2),
                )
            }

            // Pictures a companion sent while the ride was on. Found by the
            // ride's start, which is all a picture taken before the ride had a
            // history row could know about it.
            RideScreenshotsCard(
                rideStartEpochMs = activeSession.startTimeEpoch,
                modifier = Modifier.enterReveal(index = 2),
            )

            // A trainer measures no ground speed, so these are estimates from
            // power rather than readings. Absent entirely when the rider has no
            // weight set, since there is then nothing to estimate from.
            if (stats.distanceMeters > 0) {
                Spacer(modifier = Modifier.height(Spacing.s3))
                SlateCard(modifier = Modifier.enterReveal(index = 2)) {
                    StatGrid(
                        listOf(
                            Triple("Distance", "%.2f".format(stats.distanceMeters / 1000f), "km"),
                            Triple("Avg speed", "%.1f".format(stats.avgSpeedKmh), "km/h"),
                        )
                    )
                    Spacer(modifier = Modifier.height(Spacing.s3))
                    Text(
                        text = "Estimated from power on a flat virtual road.",
                        style = CadenceType.small,
                        color = CadenceColors.Fg3
                    )
                }
            }

            // Adherence is a ride measured against its plan. Neither a free
            // ride, a route ride nor a companion ride had one, so the section is dropped rather
            // than shown empty - an empty chart there reads as missing data
            // instead of as an answer. A structured ride whose workout has
            // since been deleted still gets the empty state, because there the
            // data really is missing.
            val hadAPlan = activeSession.workoutId != FREE_RIDE_WORKOUT_ID &&
                activeSession.workoutId != COMPANION_WORKOUT_ID &&
                simulationRouteId(activeSession.workoutId) == null
            if (hadAPlan) {
                Spacer(modifier = Modifier.height(Spacing.s3))
                SlateCard(modifier = Modifier.enterReveal(index = 3)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Power adherence",
                            style = CadenceType.bodyStrong,
                            color = CadenceColors.Fg1
                        )
                        Text("target · actual", style = CadenceType.small, color = CadenceColors.Fg3)
                    }
                    Spacer(modifier = Modifier.height(Spacing.s4))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                    ) {
                        if (targetWorkout != null && telemetry.isNotEmpty()) {
                            ChartOverlay(
                                workout = targetWorkout!!,
                                telemetry = telemetry,
                                ftp = ftp,
                                reveal = chartReveal,
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No chart data available.", color = CadenceColors.Fg3, style = CadenceType.body)
                            }
                        }
                    }
                }
            }

            if (telemetry.isNotEmpty() && ttz.any { it > 0 }) {
                Spacer(modifier = Modifier.height(Spacing.s3))
                Box(Modifier.enterReveal(index = 4)) {
                    TimeInZoneCard(ttzSeconds = ttz, totalSeconds = activeSession.durationSeconds.toInt())
                }
            }

            if (telemetry.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Spacing.s3))
                Box(Modifier.enterReveal(index = 5)) {
                    ExportFileCard(onExport = { showExportSheet = true })
                }
            }

            if (intervalsConfigured) {
                Spacer(modifier = Modifier.height(Spacing.s3))
                Box(Modifier.enterReveal(index = 5)) {
                    UploadCard(
                        service = "Intervals.icu",
                        uploading = uploading,
                        autoEnabled = autoUpload,
                        uploadedEpoch = activeSession.intervalsUploadedEpoch,
                        onUpload = { runIntervalsUpload() }
                    )
                }
            }

            if (ftConfigured) {
                Spacer(modifier = Modifier.height(Spacing.s3))
                Box(Modifier.enterReveal(index = 5)) {
                    UploadCard(
                        service = "FitTrackee",
                        uploading = ftUploading,
                        autoEnabled = ftAutoUpload,
                        uploadedEpoch = activeSession.fitTrackeeUploadedEpoch,
                        onUpload = { runFitTrackeeUpload() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.s6))
        }

        if (fromRide) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CadenceColors.Bg0)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(
                        start = Spacing.s5,
                        end = Spacing.s5,
                        top = Spacing.s3,
                        bottom = Spacing.s6
                    )
            ) {
                PrimaryBtn(
                    text = "Done",
                    leadingIcon = null,
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth()
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
                val rideStart = session?.startTimeEpoch
                scope.launch {
                    withContext(Dispatchers.IO) {
                        historyRepository.deleteSession(sessionId)
                        rideStart?.let(container.rideScreenshots::deleteRide)
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

/** Duration and average power: the summary's two headline numbers. */
@Composable
private fun HeroStat(
    label: String,
    value: String,
    unit: String? = null,
    alignEnd: Boolean = false,
) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(text = label, style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, style = CadenceType.displayL, color = CadenceColors.Fg1)
            if (unit != null) {
                Text(
                    text = " $unit",
                    style = CadenceType.bodyStrong.copy(fontSize = 16.sp),
                    color = CadenceColors.Fg3,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
    }
}

/** Label, value, optional unit - laid out three to a row. */
@Composable
private fun StatGrid(entries: List<Triple<String, String, String?>>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s4)) {
        entries.chunked(3).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2)
            ) {
                row.forEachIndexed { colIndex, (label, value, unit) ->
                    // Reading order, one after the other: the grid fills the
                    // way the eye scans it.
                    Stat(
                        label = label,
                        value = value,
                        unit = unit,
                        modifier = Modifier
                            .weight(1f)
                            .enterReveal(index = 1 + rowIndex * 3 + colIndex, distance = 8.dp)
                    )
                }
                // Keep a short last row aligned with the columns above it.
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Where the ride's time went. One bar per zone rather than a stacked list, so
 * the shape of the effort is readable in a glance; a zone with no time in it
 * keeps a Bg2 stub so the axis stays seven wide.
 */
@Composable
private fun TimeInZoneCard(ttzSeconds: IntArray, totalSeconds: Int) {
    // The bars rise Z1 to Z7 in turn, the same left-to-right sweep as the
    // zone profile on the workout screen, so the two charts feel like kin.
    val growth by rememberEntryProgress(
        key = totalSeconds,
        durationMillis = 900,
        delayMillis = 450,
        easing = SlateMotion.Easing,
    )
    SlateCard {
        Text(text = "Time in zone", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
        Spacer(modifier = Modifier.height(Spacing.s4))
        val peak = (ttzSeconds.maxOrNull() ?: 0).coerceAtLeast(1)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ZonePalette.entries.forEachIndexed { idx, zone ->
                val secs = ttzSeconds.getOrElse(idx) { 0 }
                val slotStart = (idx / 7f) * 0.5f
                val local = ((growth - slotStart) / (1f - slotStart)).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (secs > 0)
                                    Modifier.fillMaxHeight(
                                        ((secs.toFloat() / peak).coerceIn(0.08f, 1f) * local)
                                            .coerceAtLeast(0.02f)
                                    )
                                else Modifier.height(4.dp)
                            )
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (secs > 0) zone.color else CadenceColors.Bg2)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.s2))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ZonePalette.entries.forEach { zone ->
                Text(
                    text = "Z${zone.number}",
                    style = CadenceType.micro,
                    color = CadenceColors.Fg3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * The plan behind the ride: target steps as Bg2 ghosts, what the rider actually
 * did as a single Fg1 line over them. The targets were zone-coloured once,
 * which turned the card into a second zone chart and made the line - the only
 * thing this chart is about - the least visible mark on it.
 */
@Composable
fun ChartOverlay(
    workout: Workout,
    telemetry: List<WorkoutTelemetry>,
    ftp: Int,
    /**
     * 0 → 1 as the chart appears: the target bars rise through the first half,
     * then the rider's line is drawn left to right over them. 1 draws the
     * finished chart, which is what anything rendering it offscreen wants.
     */
    reveal: Float = 1f,
) {
    val targetMaxPct = (workout.segments.maxOfOrNull { it.powerPercentFTP } ?: 100).coerceAtLeast(60)
    val telemetryMaxPct = if (ftp > 0) {
        ((telemetry.maxOfOrNull { it.power } ?: 0L).toFloat() / ftp * 100f).toInt()
    } else 0
    val maxChartPct = maxOf(targetMaxPct, telemetryMaxPct).coerceAtLeast(60)
    val barGrowth = (reveal / 0.45f).coerceIn(0f, 1f)
    val lineReveal = ((reveal - 0.3f) / 0.7f).coerceIn(0f, 1f)

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            workout.segments.forEach { seg ->
                val h = (seg.powerPercentFTP.toFloat() / maxChartPct).coerceIn(0.05f, 1f)
                Box(
                    modifier = Modifier
                        .weight(seg.lengthInSeconds.toFloat().coerceAtLeast(1f))
                        .fillMaxHeight((h * barGrowth).coerceAtLeast(0.01f))
                        .background(
                            CadenceColors.Bg2,
                            RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                        )
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
            val shown = (points * lineReveal).toInt().coerceAtMost(points)
            if (points <= 1 || shown <= 1) return@Canvas
            val path = Path()
            for (i in 0 until shown) {
                val x = (i.toFloat() / (points - 1)) * width
                val y = yFractions[i] * height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = CadenceColors.Fg1,
                style = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round)
            )
        }
    }
}

@Composable
private fun DeleteConfirmDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
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
            Text("Delete this ride?", style = CadenceType.h2, color = CadenceColors.Fg1)
            Text(
                "Telemetry and stats for this session will be removed. This can't be undone.",
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

private fun formatEpochToDate(epoch: Long): String {
    val fmt = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault())
    return fmt.format(Date(epoch))
}

/**
 * Bottom-of-overview card that exposes the manual Intervals.icu upload. Only
 * rendered when credentials are configured; the auto-upload toggle runs
 * silently in the background via the LaunchedEffect above.
 */
@Composable
private fun UploadCard(
    /** Shown as the card's title, e.g. "Intervals.icu" or "FitTrackee". */
    service: String,
    uploading: Boolean,
    autoEnabled: Boolean,
    /** When the service last accepted this ride, or 0 for never. */
    uploadedEpoch: Long,
    onUpload: () -> Unit
) {
    val uploaded = uploadedEpoch > 0L
    ListCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.s5, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudUpload,
                contentDescription = null,
                tint = CadenceColors.Fg2,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = service, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                Spacer(Modifier.height(2.dp))
                if (uploaded) {
                    // The tick carries the state at a glance; the button's
                    // wording has to agree with it, or the rider is left reading
                    // the small print to find out whether they already sent this
                    // ride.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = CadenceColors.Success,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Uploaded · ${formatEpochToDate(uploadedEpoch)}",
                            style = CadenceType.small,
                            color = CadenceColors.Fg3
                        )
                    }
                } else {
                    Text(
                        text = if (autoEnabled) "Auto-upload is on. Tap to re-send."
                        else "Tap to upload this ride.",
                        style = CadenceType.small,
                        color = CadenceColors.Fg3
                    )
                }
            }
            Spacer(Modifier.width(Spacing.s3))
            MetaPill(
                text = when {
                    uploading -> "Uploading…"
                    uploaded -> "Re-upload"
                    else -> "Upload"
                },
                onClick = { if (!uploading) onUpload() }
            )
        }
    }
}

@Composable
private fun ExportFileCard(onExport: () -> Unit) {
    ListCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.s5, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.SaveAlt,
                contentDescription = null,
                tint = CadenceColors.Fg2,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Export file", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Save or share this ride as FIT or TCX.",
                    style = CadenceType.small,
                    color = CadenceColors.Fg3
                )
            }
            Spacer(Modifier.width(Spacing.s3))
            MetaPill(text = "Export", onClick = onExport)
        }
    }
}
