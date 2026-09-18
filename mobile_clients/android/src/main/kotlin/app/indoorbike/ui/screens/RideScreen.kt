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

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.scale
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import app.indoorbike.ui.theme.SlateMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.indoorbike.BuildConfig
import app.indoorbike.LocalAppContainer
import app.indoorbike.feedback.ReportProblemDialog
import app.indoorbike.ride.saveRideToHistory
import app.indoorbike.shared.physics.VirtualSpeed
import app.indoorbike.service.RideSessionService
import androidx.compose.ui.platform.LocalConfiguration
import app.indoorbike.shared.Workout
import app.indoorbike.shared.session.WorkoutState
import app.indoorbike.shared.workout.FREE_RIDE_STEP_WATTS
import app.indoorbike.shared.workout.isCompanion
import app.indoorbike.shared.workout.isSimulation
import app.indoorbike.ui.screens.companion.CompanionDebugPanel
import app.indoorbike.ui.screens.companion.CompanionHeaderStyle
import app.indoorbike.ui.screens.companion.CompanionHud
import app.indoorbike.ui.screens.companion.CompanionTitle
import app.indoorbike.ui.screens.companion.brandColor
import app.indoorbike.ui.screens.companion.companionBand
import app.indoorbike.ui.screens.companion.rememberCompanionNotice
import app.indoorbike.ui.screens.simulation.SimulationHud
import app.indoorbike.shared.workout.cadenceTarget
import app.indoorbike.shared.workout.formatClock
import app.indoorbike.shared.workout.isFreeRide
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.ZoneBadge
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.zones.HeartRateZone
import app.indoorbike.ui.zones.PowerZone

@Composable
fun LockScreenOrientationAndFullScreen(orientation: Int) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        val activity = context.findActivity() ?: return@DisposableEffect onDispose {}
        val originalOrientation = activity.requestedOrientation
        activity.requestedOrientation = orientation

        val window = activity.window
        val insetsController = WindowCompat.getInsetsController(window, view)

        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onDispose {
            activity.requestedOrientation = originalOrientation
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun bluetoothRuntimePerms(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun hasAnyBluetoothPermission(context: Context): Boolean =
    bluetoothRuntimePerms().any { perm ->
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

@Composable
fun RideScreen(
    onBack: () -> Unit,
    onNavigateToOverview: (Long) -> Unit,
    /**
     * The rider has finished with a companion session: they dismissed its
     * summary panel, or backed out of one nothing ever drove.
     */
    onCompanionSessionDone: () -> Unit = {},
) {
    // Follow the device's own rotation (respecting the user's auto-rotate
    // setting) rather than forcing landscape - the HUD adapts to whichever
    // orientation the rider chooses. Still goes fullscreen + keeps the screen on.
    LockScreenOrientationAndFullScreen(ActivityInfo.SCREEN_ORIENTATION_USER)

    val container = LocalAppContainer.current
    val context = LocalContext.current
    val sessionManager = container.sessionManager
    val trainerManager = container.trainerManager
    val historyRepository = container.historyRepository

    val state by sessionManager.state.collectAsState()
    val workout by sessionManager.activeWorkout.collectAsState()
    val elapsed by sessionManager.elapsedSeconds.collectAsState()
    val actualRide by sessionManager.actualRideSeconds.collectAsState()
    val elapsedInSeg by sessionManager.elapsedInSegment.collectAsState()
    val currentSegIdx by sessionManager.currentSegmentIndex.collectAsState()
    val countdown by sessionManager.countdownSeconds.collectAsState()
    val biasPercent by sessionManager.targetBiasPercent.collectAsState()
    // "Just ride": no plan, no finish line. The ± stepper moves an absolute
    // wattage, the timer counts up with nothing to count towards, and the
    // interval furniture (segment label, up-next, timeline) has nothing to
    // show, so it's left off entirely.
    val isFreeRide = workout?.isFreeRide == true
    val freeRideTarget by sessionManager.freeRideTargetWatts.collectAsState()
    val ftp by container.userSettings.ftp.collectAsState()

    // "Simulation": a real route under the wheels. The trainer is in FTMS
    // simulation mode rather than ERG, so there is no target to hold and no
    // interval furniture to show; what the rider needs instead is the road,
    // which is what [SimulationHud] draws.
    val isSimulation = workout?.isSimulation == true
    // The third engine-driven mode. Like a route ride nothing holds the rider
    // to a wattage, so it shares every `isFreeRide || isSimulation` branch
    // below; unlike a route ride there is no course, so it draws its own HUD.
    val isCompanion = workout?.isCompanion == true
    val simulationEngine = container.simulationEngine
    val simulationRoute by simulationEngine.route.collectAsState()
    val simulationPosition by simulationEngine.position.collectAsState()
    val simulationDistance by simulationEngine.distanceMeters.collectAsState()
    val simulationGradient by simulationEngine.gradient.collectAsState()
    val simulationSpeedMps by simulationEngine.speedMps.collectAsState()
    val simulationGear by simulationEngine.gearIndex.collectAsState()
    val simulationDrivetrain by simulationEngine.drivetrain.collectAsState()
    val simulationClimb by simulationEngine.elevationGainMeters.collectAsState()

    val currentPower by trainerManager.power.collectAsState()
    val currentCadence by trainerManager.cadence.collectAsState()
    val trainerHr by trainerManager.heartRate.collectAsState()
    val hrmHr by container.heartRateMonitor.heartRate.collectAsState()
    // Surfaces messages set by RideSessionService when the foreground service
    // can't start (BLE permission missing, background-start refused). The
    // session manager auto-pauses on this signal; the banner explains why
    // and gives the rider a path to recover.
    val fgsFailure by sessionManager.foregroundServiceFailure.collectAsState()
    // Surfaces ERG-mode problems from the trainer (no FTMS control point, control
    // not granted, write failed, or a command the trainer rejected) so target
    // power not reaching the trainer doesn't fail silently.
    val ergError by trainerManager.ergError.collectAsState()
    // Prefer the dedicated HRM if it's emitting; fall back to whatever the trainer has.
    val currentHr = if (hrmHr > 0) hrmHr else trainerHr
    val targetPower by trainerManager.targetPower.collectAsState()
    val isMockTrainer by trainerManager.isMockTrainer.collectAsState()
    val maxHr by container.userSettings.maxHr.collectAsState()
    val hrZone = remember(currentHr, maxHr) {
        app.indoorbike.ui.zones.HeartRateZone.fromHr(currentHr, maxHr)
    }

    var continueRequested by remember { mutableStateOf(false) }
    // Non-null (well, true) while the "End ride now?" confirmation is showing.
    // Ending is committal — the accumulated ride is saved and can't be resumed —
    // so we gate it behind an explicit confirm.
    var confirmingEnd by remember { mutableStateOf(false) }
    // A paused ride (manual Paused, or the stop-pedaling auto-pause that lands in
    // Standby) that has actually started can be ended and saved. The initial
    // "pedal to start" Standby has no ride time, so it's excluded.
    // A free ride also offers it while Active: ending IS how the mode
    // finishes, so burying it behind a pause first would be busywork.
    // A simulated ride is offered the same mid-ride exit as a free ride: a
    // rider who has had enough of a route is done, and making them pause first
    // to be allowed to save it would be busywork.
    // Untracked riding offers it too: the rider is on the bike with the clock
    // stopped, and what they rode before they went off the record is a ride
    // worth keeping. It saves nothing when they never started one.
    val canEndAndSave = (state == WorkoutState.Paused ||
        (state == WorkoutState.Standby && actualRide > 0) ||
        (state == WorkoutState.Untracked && actualRide > 0) ||
        ((isFreeRide || isSimulation) && state == WorkoutState.Active && actualRide > 0))
    // Idempotency guard for the post-completion save+navigate path. The
    // LaunchedEffect that watches `state` + `continueRequested` would otherwise
    // re-run if its keys re-emit, racing the in-flight insert and producing a
    // duplicate session row.
    var saved by remember { mutableStateOf(false) }

    // Permission flow only — FGS lifecycle is driven by `sessionManager.state`
    // observed in `IndoorBikeApp`, so a config change that tears down this
    // screen no longer kills the ride. We still need to *ask* for permissions
    // here because the FGS won't start without them, and the user can reach
    // this screen on first launch without ever visiting the pairing flow.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: service runs even without notification visibility */ }

    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // Permission just landed — nudge the FGS to retry now that it would
        // succeed. The state observer in IndoorBikeApp won't re-emit because
        // state is unchanged, so a manual one-shot is needed here.
        if (results.values.any { it }) RideSessionService.start(context)
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!hasAnyBluetoothPermission(context)) {
            bluetoothLauncher.launch(bluetoothRuntimePerms())
        }
    }

    // One place decides what a ± step means, so the in-app stepper and the
    // handlebar controller can't drift apart: ±5 % of the interval's target on
    // a structured workout, ±10 W on a free ride, which has no target of its
    // own to take a percentage of, and one gear on a simulated route, where a
    // shifter is exactly what a pair of ± buttons on the bars is.
    // Hoisted: an expanded debug panel takes the HUD's place rather than
    // squeezing it, so the screen has to know which of the two is on show.
    var companionDebugExpanded by remember { mutableStateOf(false) }
    val companionEngine = container.companionEngine
    val companionHost = container.companionHost
    val companionStatus by companionHost.status.collectAsState()
    // A menu on the companion's screen, when it has one up. Null the rest of
    // the time, which is nearly always.
    val companionControls by companionHost.remoteControls.collectAsState()
    // A companion can ask to be remembered after the ride is under way, in
    // which case the rider is looking at the HUD rather than the pairing card.
    val pendingPairing by companionHost.pendingPairing.collectAsState()
    val untrackedLabel by sessionManager.untrackedLabel.collectAsState()
    val companionPowerTarget by companionEngine.powerTarget.collectAsState()
    val companionPowerReleased by companionEngine.powerReleased.collectAsState()
    val companionGradient by companionEngine.gradient.collectAsState()
    val companionSpeed by companionEngine.speedMps.collectAsState()
    val companionDistance by companionEngine.distanceMeters.collectAsState()
    val companionClimb by companionEngine.elevationGainMeters.collectAsState()
    val companionDrivetrain by companionEngine.drivetrain.collectAsState()
    val companionGear by companionEngine.gearIndex.collectAsState()
    val companionRideStart by companionEngine.rideStartMeters.collectAsState()
    val companionCourse by companionEngine.course.collectAsState()
    val companionSessionRides by companionHost.sessionRides.collectAsState()
    val companionPhase by companionHost.sessionPhase.collectAsState()
    val companionSessionName by companionHost.sessionCompanionName.collectAsState()
    val companionHosting by companionHost.hosting.collectAsState()
    val companionLook by companionHost.look.collectAsState()
    val companionShowAllData by container.userSettings.companionShowAllData.collectAsState()
    val screenshotsOffered by companionHost.screenshotsOffered.collectAsState()
    val screenshotStatus by companionHost.screenshotStatus.collectAsState()
    // Whose job it is to write this ride. The host's, when it is hosting the
    // session this ride belongs to; this screen's for every other ride,
    // including a companion ride continued from the unfinished-ride banner,
    // which runs here with no host at all and used to end saving nothing.
    val hostSavesRide = isCompanion && companionHosting

    // The socket outlives the pairing screen so the ride can use it, which
    // makes this screen the one that has to close it. Leaving here means the
    // ride is over one way or another: saved, abandoned or backed out of.
    DisposableEffect(isCompanion) {
        onDispose { if (isCompanion) companionHost.stop() }
    }

    val adjustEffort: (Int) -> Unit = { direction ->
        when {
            isSimulation -> simulationEngine.shiftBy(direction)
            isCompanion -> companionEngine.shiftBy(direction)
            isFreeRide -> sessionManager.adjustFreeRideTarget(direction * FREE_RIDE_STEP_WATTS)
            else -> sessionManager.adjustDifficulty(direction * 5)
        }
    }

    // Bridge: external controller (Zwift Click v1, …) → the same ± steps the
    // in-app stepper takes. Only active while the ride screen is composed; the
    // manager keeps running in the background but emissions to nowhere are
    // cheap. Re-keyed on the mode so the collector always holds a lambda that
    // knows which kind of ride it's stepping.
    LaunchedEffect(isFreeRide, isSimulation) {
        container.controllerManager.events.collect { event ->
            val direction = when (event) {
                app.indoorbike.shared.ble.ControllerEvent.Plus -> +1
                app.indoorbike.shared.ble.ControllerEvent.Minus -> -1
            }
            adjustEffort(direction)
        }
    }

    // Not for a ride the companion host is hosting. That one is written by the
    // host the moment the game says it is over, because the rider never comes
    // to this screen's summary: they are mid-session and the next lap may
    // already be starting. Running this as well would save it twice.
    LaunchedEffect(state, continueRequested, hostSavesRide) {
        if (hostSavesRide) return@LaunchedEffect
        if (state != WorkoutState.Completed || workout == null || !continueRequested || saved) {
            return@LaunchedEffect
        }
        // Mark before the suspend point so a re-keyed effect can't squeeze a
        // second insert through. The button is also disabled on
        // `continueRequested`, but the LaunchedEffect's keys are independent
        // of that gate.
        saved = true
        // Persist actual time spent riding, not the planned timeline:
        // skipping a segment fast-forwards `elapsed` so it would inflate the
        // saved duration. `actualRideSeconds` only counts wall-clock time
        // spent in the Active state.
        val newSessionId = saveRideToHistory(
            container = container,
            workout = workout!!,
            startTimeEpoch = sessionManager.sessionStartTimeEpoch,
            durationSeconds = sessionManager.actualRideSeconds.value,
            telemetry = sessionManager.telemetrySnapshot(),
            road = sessionManager.pendingRideRoad(),
        )
        onNavigateToOverview(newSessionId)
    }

    if (workout == null) {
        Box(modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0))
        return
    }

    val totalDuration = remember(workout) { workout!!.segments.sumOf { it.lengthInSeconds } }

    val currentSeg = workout!!.segments.getOrNull(currentSegIdx)
    val nextSeg = workout!!.segments.getOrNull(currentSegIdx + 1)
    // Null for the overwhelming majority of segments — cadence targets are
    // opt-in per interval, so the readout stays exactly as it was without one.
    val cadenceTarget = currentSeg?.cadenceTarget
    val remainingInSeg = maxOf(0, (currentSeg?.lengthInSeconds ?: 0) - elapsedInSeg)
    // A free ride's synthetic segment is fixed at its opening percentage, so
    // colouring from it would leave the HUD in Z1 no matter how hard the
    // rider is working. Read the zone off what they actually asked for.
    val currentZone = when {
        // Nothing sets a target on a route, so the zone is read off the effort
        // the rider is actually producing. That makes the HUD's colour say
        // something true about the climb they're on rather than about a
        // synthetic segment nobody is holding them to.
        isSimulation -> PowerZone.fromFtpPercent(
            if (ftp > 0) currentPower * 100 / ftp else 0
        )
        isFreeRide -> PowerZone.fromFtpPercent(
            if (ftp > 0) freeRideTarget * 100 / ftp else 0
        )
        else -> currentSeg?.let { PowerZone.fromFtpPercent(it.powerPercentFTP) } ?: PowerZone.Z1
    }
    val nextZone = if (isFreeRide || isSimulation) null
        else nextSeg?.let { PowerZone.fromFtpPercent(it.powerPercentFTP) }
    // How long the next interval lasts, so "up next" says what is coming and
    // for how long rather than only naming it.
    val nextSeconds = if (isFreeRide || isSimulation) null else nextSeg?.lengthInSeconds
    // What the TARGET pill reads. On a free ride that's the rider's setting,
    // not the trainer's live target: the trainer sits at 0 W until the ride
    // goes active, and a pill stuck on "TARGET 0 W" would make the ± buttons
    // look broken exactly when they're being used to pick an opening effort.
    val displayTarget = if (isFreeRide) freeRideTarget else targetPower

    // A companion session shows its ending on this screen rather than moving
    // the rider somewhere else. The panel is up while the companion is away and
    // once the session has ended; only a session a companion actually drove has
    // anything to say.
    val showSessionPanel = hostSavesRide && companionSessionName != null &&
        companionPhase != app.indoorbike.companion.CompanionHost.SessionPhase.LIVE

    val handleBack = {
        when {
            // Backing out of a live companion session is the rider ending it.
            // End it the way the countdown would, which keeps the ride that is
            // underway, and let the panel show what the session produced.
            // Discarding it here is how a five-minute ride used to vanish.
            hostSavesRide && companionSessionName != null &&
                companionPhase != app.indoorbike.companion.CompanionHost.SessionPhase.ENDED ->
                companionHost.endSession()
            isCompanion -> {
                // An ended session, a HUD no companion ever drove, or a ride
                // continued from the banner with no host: nothing left to keep
                // here. A ride that was never completed stays resumable from
                // the banner, exactly as any other ride backed out of.
                if (state != WorkoutState.Completed) sessionManager.stopWorkout()
                onCompanionSessionDone()
            }
            // A ride that is running and has time on it: ask, rather than
            // taking it away. Back used to stop the workout and leave, and the
            // ride survived only as a row on the unfinished-ride banner, which
            // a rider who thought they had just quit has no reason to look for.
            // The same dialog End & save uses, from the same ride, saying the
            // same thing.
            state == WorkoutState.Active && actualRide > 0 -> {
                confirmingEnd = true
            }
            else -> {
                if (state != WorkoutState.Completed) sessionManager.stopWorkout()
                onBack()
            }
        }
    }

    BackHandler { handleBack() }

    if (confirmingEnd) {
        EndRideConfirmDialog(
            elapsed = actualRide,
            onCancel = { confirmingEnd = false },
            onConfirm = {
                confirmingEnd = false
                // Route through the same save+navigate path as a naturally
                // finished ride: mark it complete, then flag the continue so the
                // save LaunchedEffect fires and takes the rider to the summary.
                continueRequested = true
                sessionManager.endWorkoutEarly()
            }
        )
    }

    val showStaleHr = currentHr <= 0
    val showStalePower = currentPower <= 0 && (state == WorkoutState.Active)
    val weightKg by container.userSettings.weightKg.collectAsState()
    val wattsPerKg = if (weightKg > 0f && currentPower > 0) currentPower / weightKg else 0f
    // A trainer has no road under it, so speed is what the rider's current
    // watts would carry them at on a flat one, and distance is that integrated
    // over the ride (the session manager keeps the running total, so it
    // survives this screen being torn down and rebuilt). No weight set means
    // no estimate, and the readout stays away rather than showing zeroes.
    val virtualDistanceMeters by sessionManager.virtualDistanceMeters.collectAsState()
    val virtualSpeedKmh = VirtualSpeed.speedKmh(currentPower, weightKg)
    val showVirtualSpeed = weightKg > 0f

    // Single animated zone color — drives every zone-tinted element on the screen so
    // the crossfade across segment boundaries stays in sync.
    val animatedZoneColor by animateColorAsState(
        targetValue = currentZone.color,
        animationSpec = tween(durationMillis = 1200),
        label = "zone-color"
    )

    // Cadence + HR also tween to absorb 1-Hz BLE jitter.
    val animatedCadence by animateIntAsState(
        targetValue = currentCadence.coerceAtLeast(0),
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "cadence"
    )
    val animatedHr by animateIntAsState(
        targetValue = currentHr.coerceAtLeast(0),
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "hr"
    )

    // Interval-complete flash: when the segment index advances mid-ride, the
    // screen edges pulse once in the NEW zone's color - a half-second "next
    // effort" cue the rider catches without reading anything. Skipped on the
    // first composition so restoring a ride doesn't open with a flash.
    val segFlash = remember { Animatable(0f) }
    var lastFlashedSeg by remember { mutableStateOf(currentSegIdx) }
    LaunchedEffect(currentSegIdx) {
        if (currentSegIdx != lastFlashedSeg && state == WorkoutState.Active) {
            lastFlashedSeg = currentSegIdx
            segFlash.snapTo(1f)
            segFlash.animateTo(0f, tween(durationMillis = 850, easing = FastOutSlowInEasing))
        } else {
            lastFlashedSeg = currentSegIdx
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0)) {
        HorizonRidgeBackdrop(
            workout = workout!!,
            tintColor = animatedZoneColor,
            modifier = Modifier.fillMaxSize()
        )

        if (segFlash.value > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                currentZone.color.copy(alpha = 0.28f * segFlash.value),
                                Color.Transparent,
                                currentZone.color.copy(alpha = 0.28f * segFlash.value)
                            )
                        )
                    )
            )
        }

        // Layer 2: foreground content
        // Non-null while the "Report a problem" disclosure dialog is open; holds
        // the specific error the rider tapped Report on so it's pre-filled.
        var reportContext by remember { mutableStateOf<String?>(null) }
        var showReportDialog by remember { mutableStateOf(false) }
        if (showReportDialog) {
            ReportProblemDialog(
                errorContext = reportContext,
                onDismiss = { showReportDialog = false }
            )
        }
        // A companion's header colour under the system bar as well, so its band
        // reaches the top edge rather than starting a status bar's height down
        // with a strip of the app's slate above it.
        val bandPalette = companionLook?.palette
        if (hostSavesRide && bandPalette != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.safeDrawing)
                    .background(bandPalette.header.brandColor())
            )
        }
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            fgsFailure?.let { msg ->
                ForegroundServiceFailureBanner(
                    message = msg,
                    onRetry = {
                        if (!hasAnyBluetoothPermission(context)) {
                            bluetoothLauncher.launch(bluetoothRuntimePerms())
                        } else {
                            // Permission already granted — try the FGS again.
                            RideSessionService.start(context)
                        }
                    },
                    onReport = { reportContext = "Ride paused: $msg"; showReportDialog = true },
                    onDismiss = { sessionManager.clearForegroundServiceFailure() }
                )
            }
            ergError?.let { msg ->
                ErgModeErrorBanner(
                    message = msg,
                    onReport = { reportContext = "ERG mode: $msg"; showReportDialog = true },
                    onDismiss = { trainerManager.clearErgError() }
                )
            }
            val companionNotice = rememberCompanionNotice(
                connectedName = companionStatus.controllerName,
                roadStale = companionStatus.roadStale,
                savedRideCount = companionSessionRides.size,
                lastSavedRideName = companionSessionRides.lastOrNull()?.name,
                panelShowing = showSessionPanel,
                screenshot = screenshotStatus,
            )
            // Only for a ride a companion host is hosting. A companion ride
            // continued from the banner has no companion to speak of.
            val companionHeader = if (hostSavesRide) {
                val saved = companionSessionRides.size
                CompanionHeaderStyle(
                    name = companionLook?.name
                        ?: companionSessionName
                        ?: companionStatus.controllerName
                        ?: workout!!.name,
                    subtitle = listOfNotNull(
                        companionCourse.name ?: companionStatus.label,
                        companionCourse.world ?: companionStatus.world,
                        when (saved) {
                            0 -> null
                            1 -> "1 ride saved"
                            else -> "$saved rides saved"
                        },
                    ).joinToString(" \u00b7 ").ifBlank { null },
                    icon = companionLook?.icon,
                    palette = companionLook?.palette,
                    notice = companionNotice,
                )
            } else {
                null
            }
            RideHeader(
                workoutName = workout!!.name,
                state = state,
                untrackedLabel = untrackedLabel,
                powerTarget = companionPowerTarget,
                powerReleased = companionPowerReleased,
                elapsed = elapsed,
                totalDuration = totalDuration,
                // A companion ride has no total either: the game decides when it
                // ends, so a "/ 6:00:00" beside the clock is a number nobody set.
                openEnded = isFreeRide || isSimulation || isCompanion,
                isMockTrainer = isMockTrainer,
                onBack = handleBack,
                onPauseToggle = {
                    when (state) {
                        // Pausing untracked riding is an ordinary pause, and
                        // resuming puts the rider back on the walk they were
                        // on rather than starting to record them.
                        WorkoutState.Active, WorkoutState.Untracked ->
                            sessionManager.pauseWorkout()
                        WorkoutState.Paused -> sessionManager.resumeWorkout()
                        else -> {}
                    }
                },
                onSkipStep = { sessionManager.skipSegment() },
                onMockToggle = { trainerManager.toggleMockPedaling() },
                canEndAndSave = canEndAndSave,
                onEndAndSave = { confirmingEnd = true },
                // Skip means "the next interval", and only a structured workout has
                // one. A companion ride is a single synthetic segment the game
                // drives, so skipping it jumped past the whole ride and left the
                // session broken, with nothing the rider could have wanted from it.
                canSkip = !isFreeRide && !isSimulation && !isCompanion,
                awaitingCompanionStart = isCompanion,
                companion = companionHeader,
                // While the panel is up it is the one way to decide what happens
                // to the ride. A Resume pill beside it would restart a ride with
                // no game driving the road, and an End pill would be a second,
                // different ending for the same ride.
                rideControlsHidden = showSessionPanel,
                // Offered only while a ride is underway: a picture is kept with
                // a ride, and between rides there is none to keep it with.
                screenshot = if (
                    hostSavesRide && screenshotsOffered &&
                    sessionManager.sessionStartTimeEpoch != 0L &&
                    state != WorkoutState.Completed && state != WorkoutState.Idle
                ) {
                    ScreenshotButton(
                        busy = screenshotStatus?.kind ==
                            app.indoorbike.companion.CompanionHost.ScreenshotStatus.Kind.WAITING,
                        onClick = companionHost::requestScreenshot,
                    )
                } else {
                    null
                },
                // Narrow widths and portrait get a reflowed two-row header so the
                // title and state don't collapse into a one-letter-wide column.
                // Width alone missed an upright tablet: 800 dp passed, and one
                // row of title, status, clock and buttons cut the ride's name
                // down to its first letter.
                compact = LocalConfiguration.current.let {
                    it.screenWidthDp < 600 || it.screenWidthDp < it.screenHeightDp
                }
            )

            // Responsive HUD: the layout is chosen from the ACTUAL space the HUD
            // gets (covering every orientation, foldable posture, and split-screen
            // size) rather than a global width breakpoint, so nothing is ever
            // clipped or hidden by accident.
            //  - Portrait / narrow → vertical stack: power hero on top, cadence +
            //    HR side-by-side beneath, segment + timeline pinned to the bottom.
            //  - Landscape phone (wide but short) → three-across with medium
            //    numbers and NO stats strip, sized to fit the limited height
            //    (this is the case that used to clip the metrics).
            //  - Tablet / foldable (wide AND tall) → three-across with oversized
            //    numbers plus the live stats strip.
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val wide = maxWidth >= 600.dp
                val tall = maxHeight >= 600.dp
                val expanded = maxWidth >= 840.dp && tall

                val ergLocked = state == WorkoutState.Active && targetPower > 0
                // Interval types come out of the workout file in whatever case
                // its author wrote them ("CONSTANT", "ramp"); the HUD says them
                // in the app's voice.
                val segLabel = (currentSeg?.intervalType ?: "Segment")
                    .lowercase()
                    .replaceFirstChar { it.uppercase() }
                val segPower = currentSeg?.powerPercentFTP ?: 0

                val route = simulationRoute
                if (isCompanion) {
                  Column(modifier = Modifier.fillMaxSize()) {
                    if (!companionDebugExpanded) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    // SimulationHud minus its three "where" elements - the map,
                    // the profile and the progress bar - because where the
                    // rider is is exactly what this mode does not know.
                    CompanionHud(
                        gradient = companionGradient,
                        speedMps = companionSpeed,
                        distanceMeters = companionDistance,
                        elevationGainMeters = companionClimb,
                        drivetrain = companionDrivetrain,
                        gearIndex = companionGear,
                        powerWatts = currentPower,
                        isPowerStale = showStalePower,
                        wattsPerKg = wattsPerKg,
                        cadenceRpm = animatedCadence,
                        heartRate = animatedHr,
                        isHrStale = showStaleHr,
                        hrColor = hrZone?.color ?: CadenceColors.Danger,
                        hrSublabel = if (showStaleHr) null
                            else hrZone?.let { "Z${it.number} · ${it.label}" },
                        zoneColor = animatedZoneColor,
                        accent = companionLook?.palette?.accent?.brandColor(),
                        onAccent = companionLook?.palette?.onAccent?.brandColor(),
                        onShiftDown = { companionEngine.shiftDown() },
                        onShiftUp = { companionEngine.shiftUp() },
                        shiftingEnabled = state != WorkoutState.Countdown,
                        showAllData = companionShowAllData,
                        remoteControls = companionControls,
                        onControlPress = companionHost::pressControl,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (showSessionPanel) {
                        // Underway, which while the companion is away means paused.
                        val rideRunning = companionPhase ==
                            app.indoorbike.companion.CompanionHost.SessionPhase.COMPANION_AWAY &&
                            sessionManager.sessionStartTimeEpoch != 0L &&
                            state != WorkoutState.Completed && state != WorkoutState.Idle
                        app.indoorbike.ui.screens.companion.CompanionSessionPanel(
                            phase = companionPhase,
                            companionName = companionLook?.name ?: companionSessionName,
                            rides = companionSessionRides,
                            inProgress = if (rideRunning) {
                                app.indoorbike.ui.screens.companion.RideInProgress(
                                    name = companionCourse.name?.takeIf { it.isNotBlank() }
                                        ?: workout!!.name,
                                    durationSeconds = actualRide,
                                    distanceMeters = companionDistance - companionRideStart,
                                    startTimeEpoch = sessionManager.sessionStartTimeEpoch,
                                )
                            } else {
                                null
                            },
                            icon = companionLook?.icon,
                            palette = companionLook?.palette,
                            onEndSession = companionHost::endSession,
                            onDone = onCompanionSessionDone,
                        )
                    }
                    }
                    }
                    pendingPairing?.let {
                        app.indoorbike.ui.screens.companion.CompanionPairingDialog(
                            pending = it,
                            onAccept = companionHost::acceptPairing,
                            onDecline = companionHost::declinePairing,
                        )
                    }
                    // The harness for whoever is building the other end: a hand
                    // on the readings the companion receives, and a way to fire
                    // the events the app cannot otherwise produce on demand.
                    //
                    // In the column rather than over it. As an overlay the
                    // expanded panel covered the HUD, and a translucent panel
                    // on top of a translucent HUD left both unreadable.
                    //
                    // Gated on DEBUG rather than merely hidden, so R8 removes
                    // it from a release build entirely.
                    if (app.indoorbike.BuildConfig.DEBUG) {
                        CompanionDebugPanel(
                            expanded = companionDebugExpanded,
                            onExpandedChange = { companionDebugExpanded = it },
                            modifier = if (companionDebugExpanded) Modifier.weight(1f)
                            else Modifier,
                        )
                    }
                  }
                } else if (isSimulation && route != null) {
                    // The simulation HUD replaces the metrics area wholesale
                    // rather than decorating it: a route ride shares the power,
                    // cadence and heart-rate readouts with a workout but
                    // nothing else, and threading a map and an elevation
                    // profile through the workout layout's three-across
                    // arrangement would leave both worse.
                    SimulationHud(
                        route = route,
                        position = simulationPosition,
                        distanceMeters = simulationDistance,
                        gradient = simulationGradient,
                        speedMps = simulationSpeedMps,
                        drivetrain = simulationDrivetrain,
                        gearIndex = simulationGear,
                        elevationGainMeters = simulationClimb,
                        powerWatts = currentPower,
                        isPowerStale = showStalePower,
                        wattsPerKg = wattsPerKg,
                        cadenceRpm = animatedCadence,
                        heartRate = animatedHr,
                        isHrStale = showStaleHr,
                        hrColor = hrZone?.color ?: CadenceColors.Danger,
                        hrSublabel = if (showStaleHr) null
                            else hrZone?.let { "Z${it.number} · ${it.label}" },
                        zoneColor = animatedZoneColor,
                        onShiftDown = { simulationEngine.shiftDown() },
                        onShiftUp = { simulationEngine.shiftUp() },
                        // Shifting a bike that isn't moving does nothing on the
                        // road either, but the rider setting up before they
                        // start pedalling is a real thing to want, so it's only
                        // the countdown that locks the shifter out.
                        shiftingEnabled = state != WorkoutState.Countdown,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (wide) {
                    // ── three-across (landscape phone → tablet) ──
                    // Sizes step down with available height so the power column
                    // (eyebrow + number + W/kg + target steppers) always fits the
                    // metrics row without the bottom pill clipping into the
                    // timeline. A landscape phone is genuinely short (~330-410dp),
                    // hence the small bottom tier; tablets get the big numbers.
                    val flankWidth = if (expanded) 240.dp else 170.dp
                    val flankFont: TextUnit = when {
                        expanded -> 56.sp
                        maxHeight >= 440.dp -> 40.sp
                        else -> 34.sp
                    }
                    // The number takes whatever the flanks leave, capped so it
                    // stays a number and not a banner. Deriving the type size
                    // from that width as well as from the height is what lets a
                    // landscape phone carry the design's 132 sp without the
                    // digits running under the cadence card.
                    val heroSidePad = if (expanded) Spacing.s8 else Spacing.s5
                    val heroNumberWidth = (maxWidth - heroSidePad * 2 - flankWidth * 2 - Spacing.s4)
                        .coerceIn(180.dp, if (expanded) 560.dp else 340.dp)
                    // Manrope ExtraBold runs about 0.62 em per digit; three
                    // digits plus the unit fit in roughly 2.4 em.
                    val heroWidthCap = (heroNumberWidth.value / 2.4f)
                    val heroDesignCap = if (expanded) 176f else 132f
                    val heroWkgBarWidth = if (expanded) 320.dp else 220.dp
                    val wideTimelineHeight = when {
                        expanded -> 112.dp
                        maxHeight >= 440.dp -> 64.dp
                        else -> 56.dp
                    }

                    // Live average power for the stats strip; only computed when
                    // the strip is actually shown (expanded). Recomputed per second.
                    val avgPowerWatts = if (expanded) remember(elapsed) {
                        val tele = sessionManager.telemetrySnapshot()
                        if (tele.isEmpty()) 0 else (tele.sumOf { it.power } / tele.size).toInt()
                    } else 0

                    Column(modifier = Modifier.fillMaxSize()) {
                        // The number is sized from the height this row actually
                        // gets, not from the screen's. Everything under the row
                        // - the speed line, the segment row, the timeline - is
                        // laid out first, and what is left is what the hero has
                        // to fit its status line, W/kg bar and stepper into
                        // around the digits. Size the digits from the screen
                        // instead and the stepper is the thing that falls off
                        // the bottom, which is the one control on this screen a
                        // rider uses mid-interval.
                        BoxWithConstraints(
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        ) {
                        val heroFont: TextUnit = minOf(
                            heroWidthCap,
                            // Status line, W/kg bar and its label, stepper row
                            // and the gaps between them.
                            (this@BoxWithConstraints.maxHeight - 124.dp).value,
                            heroDesignCap
                        ).coerceAtLeast(48f).sp
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = heroSidePad),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CadenceFlankMetric(
                                rpm = animatedCadence,
                                target = cadenceTarget,
                                cardWidth = flankWidth,
                                valueFontSize = flankFont
                            )
                            HeroPowerReadout(
                                powerWatts = currentPower,
                                isStale = showStalePower,
                                targetWatts = displayTarget,
                                wattsPerKg = wattsPerKg,
                                zoneColor = animatedZoneColor,
                                biasPercent = biasPercent,
                                ergLocked = ergLocked,
                                onAdjust = adjustEffort,
                                freeRide = isFreeRide,
                                modifier = Modifier.weight(1f),
                                powerFontSize = heroFont,
                                numberWidth = heroNumberWidth,
                                wkgBarWidth = heroWkgBarWidth
                            )
                            FlankMetric(
                                label = "Heart rate",
                                value = if (showStaleHr) "--" else animatedHr.toString(),
                                unit = "bpm",
                                isStale = showStaleHr,
                                // Flanking metric, one ink step down: the
                                // wattage is what the rider is chasing. See
                                // CadenceFlankMetric.
                                valueColor = CadenceColors.Fg2,
                                zoneBadge = if (showStaleHr) null else hrZone,
                                cardWidth = flankWidth,
                                valueFontSize = flankFont
                            )
                        }
                        }

                        if (showVirtualSpeed && state != WorkoutState.Countdown) {
                            VirtualSpeedReadout(
                                speedKmh = virtualSpeedKmh,
                                distanceMeters = virtualDistanceMeters,
                                modifier = Modifier.padding(bottom = Spacing.s3)
                            )
                        }

                        if (expanded && state != WorkoutState.Countdown) {
                            RideStatsStrip(
                                elapsedSeconds = elapsed,
                                avgPowerWatts = avgPowerWatts,
                                // A free ride has a single endless interval, so
                                // the tile shows the wattage being held instead.
                                thirdLabel = if (isFreeRide) "Target" else "Interval",
                                thirdValue = if (isFreeRide) {
                                    displayTarget.toString()
                                } else {
                                    val shown = (currentSegIdx + 1).coerceAtMost(workout!!.segments.size)
                                    "$shown / ${workout!!.segments.size}"
                                },
                                thirdUnit = if (isFreeRide) "W" else "",
                                // A free ride has no timeline under this, so the
                                // strip is the last thing in the column and was
                                // being laid out flush against the bottom edge:
                                // the tiles' own rounded corners were cropped by
                                // the screen. Give it the room the timeline would
                                // otherwise have taken, plus the gesture inset.
                                modifier = Modifier
                                    .padding(bottom = if (isFreeRide) Spacing.s5 else Spacing.s3)
                                    .navigationBarsPadding()
                            )
                        }

                        if (!isFreeRide) {
                            SegmentAndTimeline(
                                workout = workout!!,
                                currentSegIdx = currentSegIdx,
                                elapsedInSeg = elapsedInSeg,
                                segLabel = segLabel,
                                segPower = segPower,
                                remainingInSeg = remainingInSeg,
                                nextZone = nextZone,
                                nextSeconds = nextSeconds,
                                zoneColor = animatedZoneColor,
                                timelineHeight = wideTimelineHeight
                            )
                        }
                    }
                } else {
                    // ── vertical stack (portrait, phone or tablet) ──
                    val heroFont: TextUnit = if (tall) 112.sp else 96.sp
                    val sidePad = Spacing.s5
                    val flankWidth = (maxWidth - sidePad * 2 - Spacing.s3) / 2
                    val heroNumberWidth = (maxWidth * 0.82f).coerceAtMost(340.dp)
                    val heroWkgBarWidth = (maxWidth * 0.6f).coerceAtMost(280.dp)

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Power + cadence/HR centered in the space above the
                        // timeline, so the metrics sit at eye level and the
                        // timeline stays pinned to the bottom - nothing overflows.
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(Spacing.s6, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            HeroPowerReadout(
                                powerWatts = currentPower,
                                isStale = showStalePower,
                                targetWatts = displayTarget,
                                wattsPerKg = wattsPerKg,
                                zoneColor = animatedZoneColor,
                                biasPercent = biasPercent,
                                ergLocked = ergLocked,
                                onAdjust = adjustEffort,
                                freeRide = isFreeRide,
                                modifier = Modifier.fillMaxWidth(),
                                powerFontSize = heroFont,
                                numberWidth = heroNumberWidth,
                                wkgBarWidth = heroWkgBarWidth
                            )
                            // Intrinsic-max height so the two cards stay the
                            // same size when only one of them has a cadence
                            // target bar to show. Side by side, a card that's
                            // a few dp taller than its neighbour reads as a
                            // layout bug rather than as extra information.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Max)
                                    .padding(horizontal = sidePad),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
                            ) {
                                CadenceFlankMetric(
                                    rpm = animatedCadence,
                                    target = cadenceTarget,
                                    cardWidth = flankWidth,
                                    valueFontSize = 34.sp,
                                    modifier = Modifier.fillMaxHeight()
                                )
                                FlankMetric(
                                    label = "Heart rate",
                                    value = if (showStaleHr) "--" else animatedHr.toString(),
                                    unit = "bpm",
                                    isStale = showStaleHr,
                                    valueColor = CadenceColors.Fg2,
                                    zoneBadge = if (showStaleHr) null else hrZone,
                                    cardWidth = flankWidth,
                                    valueFontSize = 34.sp,
                                    modifier = Modifier.fillMaxHeight()
                                )
                            }
                            if (showVirtualSpeed && state != WorkoutState.Countdown) {
                                VirtualSpeedReadout(
                                    speedKmh = virtualSpeedKmh,
                                    distanceMeters = virtualDistanceMeters
                                )
                            }
                        }
                        if (!isFreeRide) {
                            SegmentAndTimeline(
                                workout = workout!!,
                                currentSegIdx = currentSegIdx,
                                elapsedInSeg = elapsedInSeg,
                                segLabel = segLabel,
                                segPower = segPower,
                                remainingInSeg = remainingInSeg,
                                nextZone = nextZone,
                                nextSeconds = nextSeconds,
                                zoneColor = animatedZoneColor,
                                timelineHeight = 88.dp,
                                compact = true
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state == WorkoutState.Countdown,
            enter = fadeIn(),
            exit = fadeOut(tween(500)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = countdown,
                    transitionSpec = {
                        // Each tick punches in oversized with a spring overshoot,
                        // while the previous tick shrinks and fades so the beat lands.
                        // SizeTransform is disabled so the content box doesn't grow/
                        // shrink between digits — keeps every glyph centered in place.
                        ((scaleIn(
                            initialScale = 2.6f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        ) + fadeIn(tween(120))).togetherWith(
                            scaleOut(
                                targetScale = 0.4f,
                                animationSpec = tween(220, easing = FastOutSlowInEasing)
                            ) + fadeOut(tween(180))
                        )).using(SizeTransform(clip = false) { _, _ -> snap() })
                    },
                    contentAlignment = Alignment.Center,
                    label = "countdownNumber"
                ) { value ->
                    val isGo = value <= 0
                    Text(
                        text = if (isGo) "GO!" else value.toString(),
                        fontSize = if (isGo) 160.sp else 200.sp,
                        fontWeight = FontWeight.Black,
                        color = if (isGo) CadenceColors.Success else CadenceColors.Fg1,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        AnimatedVisibility(
            // Never in companion mode. A full-screen trophy over a HUD whose
            // game is still running, and whose next lap may start in seconds,
            // would cover the road at the moment the rider needs to see it.
            // The strip at the top says the ride was saved instead.
            visible = state == WorkoutState.Completed && !hostSavesRide,
            enter = fadeIn(tween(1000)),
            exit = fadeOut(tween(500)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            // Trophy springs in with an overshoot while a single ring expands
            // and fades behind it - one celebratory beat, then calm so the
            // rider can breathe and hit View Summary.
            val trophyPop = remember { Animatable(0f) }
            LaunchedEffect(state) {
                if (state == WorkoutState.Completed) {
                    trophyPop.snapTo(0f)
                    trophyPop.animateTo(
                        1f,
                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                    )
                }
            }
            Box(
                modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        val ring = (trophyPop.value * 2f).coerceIn(0f, 1f)
                        if (ring > 0.01f && ring < 1f) {
                            Box(
                                modifier = Modifier
                                    .size(120.dp + 140.dp * ring)
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.EmojiEvents,
                            contentDescription = "Finished",
                            tint = CadenceColors.Fg1,
                            modifier = Modifier
                                .size(96.dp)
                                .scale(0.4f + 0.6f * trophyPop.value)
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Finished",
                        style = CadenceType.h1,
                        color = CadenceColors.Fg1
                    )
                    Spacer(modifier = Modifier.height(28.dp))
                    PrimaryBtn(
                        text = if (continueRequested) "Saving…" else "View summary",
                        leadingIcon = null,
                        enabled = !continueRequested,
                        onClick = { continueRequested = true }
                    )
                }
            }
        }
    }
}

@Composable
private fun RideHeader(
    workoutName: String,
    state: WorkoutState,
    elapsed: Int,
    totalDuration: Int,
    isMockTrainer: Boolean,
    onBack: () -> Unit,
    onPauseToggle: () -> Unit,
    onSkipStep: () -> Unit,
    onMockToggle: () -> Unit,
    canEndAndSave: Boolean,
    onEndAndSave: () -> Unit,
    compact: Boolean,
    /**
     * The ride runs until the rider stops it, so there is no total to count
     * towards and the clock shows elapsed time alone.
     */
    openEnded: Boolean = false,
    /** Skipping ahead only means something when there's a next interval. */
    canSkip: Boolean = true,
    /**
     * A companion ride that has not started yet. Pedalling will not start it -
     * the game will - so the standby line has to say so rather than asking for
     * something that does nothing.
     */
    awaitingCompanionStart: Boolean = false,
    /**
     * A companion ride's header: the companion's mark, name and course in place
     * of the ride name, on the companion's own colours when it sent some, and
     * any notice about the companion in place of the ride's status line.
     */
    companion: CompanionHeaderStyle? = null,
    /** What the companion says an untracked rider is doing, when it said. */
    untrackedLabel: String? = null,
    /** The wattage an untracked ride is being held at, if any. */
    powerTarget: Int? = null,
    /** True while that target is set but the rider has been let go of it. */
    powerReleased: Boolean = false,
    /** Hide pause, resume and end, leaving only the debug mock toggle. */
    rideControlsHidden: Boolean = false,
    /** Ask the companion for a picture of its screen. Null when there is nobody to ask. */
    screenshot: ScreenshotButton? = null,
) {
    val rideStateText: String? = when (state) {
        // A Standby that can be ended-and-saved is a stop-pedaling auto-pause of
        // a ride in progress, not the initial "pedal to start" standby.
        WorkoutState.Standby -> when {
            canEndAndSave -> "Auto-paused · pedal to resume"
            awaitingCompanionStart -> "Ready · start the ride in the game"
            else -> "Standby · pedal to start"
        }
        WorkoutState.Paused -> "Paused · trainer at 0 W"
        // Says the thing that matters first: none of this is going into the
        // ride. What the rider is doing, and what they are being held at, come
        // after it.
        WorkoutState.Untracked -> buildString {
            append("Not recording")
            untrackedLabel?.let { append(" · ").append(it) }
            when {
                // Never a target the trainer is not actually holding.
                powerReleased -> append(" · target let go, pedal to take it back")
                powerTarget != null -> append(" · Target ").append(powerTarget).append(" W")
            }
        }
        else -> null
    }
    val rideStateColor =
        if (state == WorkoutState.Paused || state == WorkoutState.Untracked || canEndAndSave) {
            CadenceColors.Warning
        } else {
            CadenceColors.Fg3
        }
    // A notice about the companion outranks the ride's own state: "no companion,
    // flat road" matters more to a rider than "ready".
    val stateText = companion?.notice?.text ?: rideStateText
    val stateColor = companion?.notice?.color ?: rideStateColor

    if (compact) {
        // Portrait: two rows so nothing has to share a single narrow line.
        //   row 1: back · title (flexes, ellipsised) · controls
        //   row 2: state · elapsed/total
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .companionBand(companion?.palette)
                .padding(horizontal = Spacing.s5, vertical = Spacing.s3),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBtn(icon = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", onClick = onBack)
                if (companion != null) {
                    CompanionTitle(style = companion, markSize = 36.dp, modifier = Modifier.weight(1f))
                } else {
                    Text(
                        text = workoutName,
                        style = CadenceType.h3,
                        color = CadenceColors.Fg1,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                RideControls(
                    state, isMockTrainer, onMockToggle, onSkipStep, onPauseToggle,
                    canEndAndSave, onEndAndSave, canSkip, hidden = rideControlsHidden, screenshot = screenshot, compact = true
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RideStateText(
                    text = stateText,
                    color = stateColor,
                    modifier = Modifier.weight(1f)
                )
                RideClock(elapsed = elapsed, totalDuration = totalDuration, openEnded = openEnded)
            }
        }
        return
    }

    // Wide (landscape / tablet): single row.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .companionBand(companion?.palette)
            .padding(horizontal = Spacing.s5, vertical = Spacing.s3),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBtn(icon = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", onClick = onBack)

        // The ride name and the status line share whatever the row has spare,
        // inside one group that takes all of it. Two things that used to go
        // wrong here: the status was never allowed to shrink, so on a narrow
        // landscape screen it pushed the name down to its first letter; and a
        // non-filling weight leaves its unused share empty rather than handing
        // it on, so the clock and buttons stopped short of the right edge.
        // Both ellipsise rather than wrap: the row has a growing clock in it and
        // must stay one line tall.
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (companion != null) {
                CompanionTitle(
                    style = companion,
                    markSize = 40.dp,
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else {
                Text(
                    text = workoutName,
                    style = CadenceType.h3,
                    color = CadenceColors.Fg1,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            if (stateText != null) {
                RideStateText(
                    text = stateText,
                    color = stateColor,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }

        // Fixed width so the elapsed clock ticking from "0:00" → "1:23:45" doesn't
        // resize the time block and shove the buttons around. softWrap = false +
        // maxLines = 1 guarantee the clock stays on one line (no wrap flicker).
        Box(
            modifier = Modifier.width(150.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            RideClock(elapsed = elapsed, totalDuration = totalDuration, openEnded = openEnded)
        }
        RideControls(
            state, isMockTrainer, onMockToggle, onSkipStep, onPauseToggle,
            canEndAndSave, onEndAndSave, canSkip, hidden = rideControlsHidden, screenshot = screenshot, compact = false
        )
    }
}

/**
 * The header's one-line status. It changes between states rather than
 * snapping - the new line rises in as the old one sinks out, and the colour
 * eases from grey to amber - so "Auto-paused" arriving is something the rider
 * catches happening rather than finds already there.
 */
@Composable
private fun RideStateText(text: String?, color: Color, modifier: Modifier = Modifier) {
    val tint by animateColorAsState(
        targetValue = color,
        animationSpec = tween(SlateMotion.Base, easing = SlateMotion.Easing),
        label = "rideStateTint",
    )
    AnimatedContent(
        targetState = text.orEmpty(),
        transitionSpec = {
            (fadeIn(tween(SlateMotion.Base, easing = SlateMotion.Easing)) +
                slideInVertically(tween(SlateMotion.Base, easing = SlateMotion.Easing)) { it / 2 })
                .togetherWith(
                    fadeOut(tween(SlateMotion.Fast, easing = SlateMotion.Easing)) +
                        slideOutVertically(tween(SlateMotion.Fast, easing = SlateMotion.Easing)) { -it / 2 }
                )
                .using(SizeTransform(clip = false))
        },
        label = "rideState",
        modifier = modifier,
    ) { line ->
        Text(
            text = line,
            style = CadenceType.small,
            color = tint,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/**
 * Elapsed over total, with the total in the quieter ink. Which half is which
 * matters at a glance on a trainer: the number that is moving is the one the
 * rider is watching.
 */
@Composable
private fun RideClock(elapsed: Int, totalDuration: Int, openEnded: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatClock(elapsed),
            style = CadenceType.h3.copy(fontFeatureSettings = "tnum"),
            color = CadenceColors.Fg2,
            maxLines = 1,
            softWrap = false
        )
        if (!openEnded) {
            Text(
                text = " / ${formatClock(totalDuration)}",
                style = CadenceType.h3.copy(fontFeatureSettings = "tnum"),
                color = CadenceColors.Fg3,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun RideControls(
    state: WorkoutState,
    isMockTrainer: Boolean,
    onMockToggle: () -> Unit,
    onSkipStep: () -> Unit,
    onPauseToggle: () -> Unit,
    canEndAndSave: Boolean,
    onEndAndSave: () -> Unit,
    canSkip: Boolean,
    hidden: Boolean = false,
    screenshot: ScreenshotButton? = null,
    /**
     * Portrait, where these share one row with the back button and the ride
     * name. Three full-width labels leave the name nothing, so the most
     * verbose one is abbreviated to its icon plus a single word.
     */
    compact: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isMockTrainer) {
            HudPill(text = "Mock", onClick = onMockToggle)
        }
        if (hidden) return@Row
        // Before pause and end: it is the one a rider reaches for mid-effort,
        // and the one that must not be mistaken for ending the ride.
        if (screenshot != null) {
            HudPill(
                text = when {
                    compact -> null
                    screenshot.busy -> "Asking"
                    else -> "Screenshot"
                },
                icon = Icons.Outlined.PhotoCamera,
                contentDescription = "Take a screenshot of the companion",
                enabled = !screenshot.busy,
                onClick = screenshot.onClick,
            )
        }
        // Hidden on a free ride: its single segment is the whole session, so
        // skipping it would silently end the ride rather than move it on.
        if (canSkip && (state == WorkoutState.Active || state == WorkoutState.Paused)) {
            HudPill(text = "Skip", icon = Icons.Outlined.SkipNext, onClick = onSkipStep)
        }
        // The pause control is the ride's primary action, so it takes the white
        // fill - the same rule as a bottom-of-screen primary button.
        if (state == WorkoutState.Active || state == WorkoutState.Untracked) {
            HudPill(
                text = "Pause",
                icon = Icons.Filled.Pause,
                primary = true,
                onClick = onPauseToggle
            )
        } else if (state == WorkoutState.Paused) {
            HudPill(
                text = "Resume",
                icon = Icons.Filled.PlayArrow,
                primary = true,
                onClick = onPauseToggle
            )
        }
        // Explicit way to finish a paused/auto-paused ride and keep the data —
        // otherwise the only save path is riding all the way to completion.
        if (canEndAndSave) {
            HudPill(
                text = if (compact) "End" else "End & save",
                icon = Icons.Outlined.Stop,
                onClick = onEndAndSave
            )
        }
    }
}

/**
 * A 44 dp control for the ride header. Shorter than the 56 dp buttons the rest
 * of the app uses: the HUD header shares one line with a name and a clock, and
 * on a landscape phone every dp of height there comes off the power number.
 */
@Composable
private fun HudPill(
    /** Null for an icon alone, where the header has no room for a word. */
    text: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    /** Said for the icon when there is no [text] to say it. */
    contentDescription: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val fg = (if (primary) CadenceColors.OnBrand else CadenceColors.Fg1)
        .let { if (enabled) it else it.copy(alpha = 0.5f) }
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(if (primary) CadenceColors.Fg1 else CadenceColors.Bg1)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (text == null) 12.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = if (text == null) contentDescription else null,
                tint = fg,
                modifier = Modifier.size(if (text == null) 20.dp else 18.dp)
            )
        }
        if (text != null) {
            Text(
                text = text,
                style = CadenceType.bodyStrong,
                color = fg,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/** The header's screenshot control: whether a picture is already on its way, and how to ask. */
internal data class ScreenshotButton(val busy: Boolean, val onClick: () -> Unit)

/**
 * Confirmation shown when the rider taps "End & save" on a paused ride. Ending
 * is committal — the ride can't be resumed afterwards — so we spell out that the
 * ride so far *is* saved (unlike "Abandon" on the unfinished-ride banner, which
 * discards it). Confirm routes into the normal finish-and-save flow.
 */
@Composable
private fun EndRideConfirmDialog(
    elapsed: Int,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                // 85 % of a phone is a dialog; 85 % of a tablet is a
                // banner. The cap is what makes the fraction mean "most of a
                // narrow screen" rather than "most of any screen".
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(Radii.lg))
                .background(CadenceColors.Bg1)
                .padding(Spacing.s6),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4)
        ) {
            Text(
                text = "End ride now?",
                style = CadenceType.h2,
                color = CadenceColors.Fg1
            )
            Text(
                text = "Your ride so far (${formatClock(elapsed)}) will be saved to your history. " +
                    "You won't be able to resume it.",
                style = CadenceType.body,
                color = CadenceColors.Fg2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
                SecondaryBtn(
                    text = "Keep riding",
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                )
                PrimaryBtn(
                    text = "End & save",
                    leadingIcon = null,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SegmentAndTimeline(
    workout: Workout,
    currentSegIdx: Int,
    elapsedInSeg: Int,
    segLabel: String,
    segPower: Int,
    remainingInSeg: Int,
    nextZone: PowerZone?,
    nextSeconds: Int?,
    zoneColor: Color,
    timelineHeight: Dp,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val upNext: String? = nextZone?.let { "Z${it.number} ${it.label}" }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s5, vertical = Spacing.s3)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A square of the zone's colour: the same mark the timeline uses,
            // so the row and the chart agree at a glance.
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(zoneColor)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "$segLabel · $segPower% FTP",
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                // Compact: take the row's spare width and ellipsise so the timer
                // chip stays on-line and "UP NEXT" can drop to its own line.
                modifier = if (compact) Modifier.weight(1f) else Modifier
            )
            Spacer(Modifier.width(8.dp))
            // Timer attached to the segment label as a chip — reads as
            // "this segment, ending in 4:25".
            SegmentTimerChip(remainingSeconds = remainingInSeg, zoneColor = zoneColor)
            // Wide layout: "up next" sits inline on the right.
            if (!compact && upNext != null) {
                Spacer(Modifier.weight(1f))
                UpNextLabel(zoneLabel = upNext, zoneColor = nextZone.color, seconds = nextSeconds)
            }
        }

        // Compact (portrait): "up next" gets its own line so it can't be squeezed
        // into a vertical one-letter sliver on a narrow screen.
        if (compact && upNext != null) {
            Box(modifier = Modifier.padding(top = 4.dp)) {
                UpNextLabel(zoneLabel = upNext, zoneColor = nextZone.color, seconds = nextSeconds)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        RidgeTimeline(
            workout = workout,
            currentSegIdx = currentSegIdx,
            elapsedInSeg = elapsedInSeg,
            height = timelineHeight
        )
    }
}

/** "Up next · Z1 Recovery 1:00", with only the zone name carrying colour. */
@Composable
private fun UpNextLabel(zoneLabel: String, zoneColor: Color, seconds: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "Up next · ", style = CadenceType.small, color = CadenceColors.Fg3, maxLines = 1)
        Text(text = zoneLabel, style = CadenceType.small, color = zoneColor, maxLines = 1)
        if (seconds != null) {
            Text(
                text = " ${formatClock(seconds)}",
                style = CadenceType.small,
                color = CadenceColors.Fg3,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SegmentTimerChip(remainingSeconds: Int, zoneColor: Color) {
    // Final five seconds of a segment: the chip beats once per tick and the
    // fill brightens - anticipation the rider feels in peripheral vision
    // before the interval flips. Scale only, so the row never re-lays-out.
    val urgent = remainingSeconds in 1..5
    val beat = remember { Animatable(1f) }
    LaunchedEffect(remainingSeconds) {
        if (urgent) {
            beat.snapTo(1.16f)
            beat.animateTo(1f, tween(durationMillis = 380, easing = FastOutSlowInEasing))
        }
    }
    Row(
        modifier = Modifier
            .height(30.dp)
            .scale(beat.value)
            .clip(CircleShape)
            .background(CadenceColors.Bg1)
            .padding(horizontal = Spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Timer,
            contentDescription = null,
            tint = if (urgent) zoneColor else CadenceColors.Fg3,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = formatClock(remainingSeconds.coerceAtLeast(0)),
            style = CadenceType.bodyStrong.copy(fontSize = 14.sp),
            color = CadenceColors.Fg1
        )
    }
}

@Composable
internal fun FlankMetric(
    label: String,
    value: String,
    unit: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    isStale: Boolean = false,
    /** Small caption under the number. */
    sublabel: String? = null,
    /** Sublabel tint; defaults to [valueColor]. */
    sublabelColor: Color? = null,
    /** Zone pill under the number, for the live heart-rate zone. */
    zoneBadge: HeartRateZone? = null,
    /**
     * Replaces the whole card's spoken text. Set it when the visual shorthand
     * (an arrow, a bar) wouldn't survive being read out field by field.
     */
    contentDescription: String? = null,
    /** Optional extra row under the sublabel — e.g. the cadence target bar. */
    footer: (@Composable () -> Unit)? = null,
    // Default to the phone HUD; the ride screen passes larger values on
    // expanded-width screens so the flanking metrics stay legible.
    cardWidth: Dp = 170.dp,
    valueFontSize: TextUnit = 40.sp,
    /**
     * Trims the card's own breathing room. On a landscape phone the HUD has
     * about 390 dp of height for five blocks, and 16 dp of padding above and
     * below one line of text is the cheapest of them to give back.
     */
    compact: Boolean = false,
) {
    // Translucent so the ridge behind it still reads as one continuous
    // backdrop, and fixed width so 2-digit to 3-digit transitions don't reflow
    // the row.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(cardWidth)
            .clip(RoundedCornerShape(Radii.lg))
            .background(CadenceColors.Bg1.copy(alpha = 0.7f))
            .padding(horizontal = Spacing.s5, vertical = if (compact) Spacing.s2 else Spacing.s4)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics(mergeDescendants = true) {
                        this.contentDescription = contentDescription
                    }
                } else {
                    Modifier
                }
            )
    ) {
        Text(text = label, style = CadenceType.tick, color = CadenceColors.Fg3)
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = CadenceType.displayM.copy(
                        fontSize = valueFontSize,
                        lineHeight = valueFontSize * 1.1f
                    ),
                    color = valueColor
                )
                Text(
                    text = " $unit",
                    style = CadenceType.body.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = CadenceColors.Fg3,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
        if (zoneBadge != null) {
            Box(modifier = Modifier.padding(top = 10.dp)) {
                ZoneBadge(zone = zoneBadge, includeLabel = true)
            }
        }
        if (sublabel != null) {
            Text(
                text = sublabel,
                style = CadenceType.tick,
                color = sublabelColor ?: valueColor,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        footer?.invoke()
    }
}

/**
 * Speed and distance for a ride that has neither to measure, estimated from
 * power by [VirtualSpeed].
 *
 * Kept quiet next to the power hero on purpose. It's a modelled number, there
 * to pace by and to give the exported ride a distance, not a target to chase -
 * so it reads as one line under the metrics rather than as a fourth tile
 * competing with the ones the trainer actually reports.
 */
@Composable
private fun VirtualSpeedReadout(
    speedKmh: Double,
    distanceMeters: Double,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom
    ) {
        InlineStat("Speed", "%.1f".format(speedKmh), "km/h")
        Text(
            text = "  ·  ",
            style = CadenceType.tick,
            color = CadenceColors.Fg3
        )
        InlineStat("Distance", "%.2f".format(distanceMeters / 1000.0), "km")
    }
}

@Composable
private fun InlineStat(label: String, value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = label,
            style = CadenceType.tick,
            color = CadenceColors.Fg3,
            modifier = Modifier.padding(end = 6.dp, bottom = 2.dp)
        )
        Text(text = value, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
        Text(
            text = " $unit",
            style = CadenceType.tick,
            color = CadenceColors.Fg3,
            modifier = Modifier.padding(bottom = 1.dp)
        )
    }
}

/**
 * Tablet-only live stats strip shown under the main HUD metrics. A tidy
 * centered trio - elapsed time, average power, and interval progress - tinted
 * with the current zone color so it stays tied to the effort.
 */
@Composable
private fun RideStatsStrip(
    elapsedSeconds: Int,
    avgPowerWatts: Int,
    /**
     * The trailing tile. Interval progress on a structured workout; on a free
     * ride, where there is only ever one interval, the target being held.
     */
    thirdLabel: String,
    thirdValue: String,
    thirdUnit: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s4)) {
            StatTile("Elapsed", formatClock(elapsedSeconds))
            StatTile("Avg power", avgPowerWatts.toString(), unit = "W")
            StatTile(thirdLabel, thirdValue, unit = thirdUnit)
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String = "",
) {
    Column(
        modifier = modifier
            .width(220.dp)
            .clip(RoundedCornerShape(Radii.lg))
            .background(CadenceColors.Bg1.copy(alpha = 0.35f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = label, style = CadenceType.tick, color = CadenceColors.Fg3)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = CadenceType.displayM.copy(fontSize = 34.sp, lineHeight = 38.sp),
                color = CadenceColors.Fg1
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = " $unit",
                    style = CadenceType.body.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = CadenceColors.Fg3,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}

/**
 * Inline banner shown at the top of the ride screen when the foreground
 * service couldn't start. The session manager has already paused the ride
 * by the time this appears; the banner just explains *why* and offers a
 * one-tap retry (re-prompts for BLE permission if needed). Dismiss simply
 * clears the flag — the ride stays paused until the rider explicitly
 * resumes from the header pause/resume button.
 */
@Composable
private fun ForegroundServiceFailureBanner(
    message: String,
    onRetry: () -> Unit,
    onReport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shape = RoundedCornerShape(Radii.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s4, vertical = Spacing.s2)
            .clip(shape)
            .background(CadenceColors.Danger.copy(alpha = 0.15f))
            .padding(horizontal = Spacing.s3, vertical = Spacing.s3),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = CadenceColors.Danger,
            modifier = Modifier.size(20.dp).padding(top = 2.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Ride paused",
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1
            )
            Text(
                text = message,
                style = CadenceType.small,
                color = CadenceColors.Fg2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
                Text(
                    text = "Retry",
                    style = CadenceType.bodyStrong,
                    color = CadenceColors.Fg1,
                    modifier = Modifier
                        .clickable(onClick = onRetry)
                        .padding(vertical = 4.dp, horizontal = 6.dp)
                )
                Text(
                    text = "Report",
                    style = CadenceType.bodyStrong,
                    color = CadenceColors.Fg1,
                    modifier = Modifier
                        .clickable(onClick = onReport)
                        .padding(vertical = 4.dp, horizontal = 6.dp)
                )
                Text(
                    text = "Dismiss",
                    style = CadenceType.bodyStrong,
                    color = CadenceColors.Fg3,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 4.dp, horizontal = 6.dp)
                )
            }
        }
        IconBtn(
            icon = Icons.Outlined.Close,
            contentDescription = "Dismiss",
            tint = CadenceColors.Fg3,
            onClick = onDismiss
        )
    }
}

/**
 * Warns the rider that ERG mode isn't working — target power isn't reaching the
 * trainer. Unlike the foreground-service banner there's no Retry: the cause is
 * the trainer/connection, so the actionable next step is to reconnect or accept
 * a non-ERG ride. The banner clears itself once a target-power command succeeds.
 */
@Composable
private fun ErgModeErrorBanner(
    message: String,
    onReport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shape = RoundedCornerShape(Radii.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s4, vertical = Spacing.s2)
            .clip(shape)
            .background(CadenceColors.Danger.copy(alpha = 0.15f))
            .padding(horizontal = Spacing.s3, vertical = Spacing.s3),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = CadenceColors.Danger,
            modifier = Modifier.size(20.dp).padding(top = 2.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "ERG mode problem",
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1
            )
            Text(
                text = message,
                style = CadenceType.small,
                color = CadenceColors.Fg2
            )
            Text(
                text = "Report",
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1,
                modifier = Modifier
                    .clickable(onClick = onReport)
                    .padding(vertical = 4.dp, horizontal = 6.dp)
            )
        }
        IconBtn(
            icon = Icons.Outlined.Close,
            contentDescription = "Dismiss",
            tint = CadenceColors.Fg3,
            onClick = onDismiss
        )
    }
}
