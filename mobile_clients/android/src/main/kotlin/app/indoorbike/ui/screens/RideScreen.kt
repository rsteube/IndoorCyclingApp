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
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import app.indoorbike.shared.workout.cadenceTarget
import app.indoorbike.shared.workout.formatClock
import app.indoorbike.shared.workout.isFreeRide
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
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
    val canEndAndSave = (state == WorkoutState.Paused ||
        (state == WorkoutState.Standby && actualRide > 0) ||
        (isFreeRide && state == WorkoutState.Active && actualRide > 0))
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
    // own to take a percentage of.
    val adjustEffort: (Int) -> Unit = { direction ->
        if (isFreeRide) {
            sessionManager.adjustFreeRideTarget(direction * FREE_RIDE_STEP_WATTS)
        } else {
            sessionManager.adjustDifficulty(direction * 5)
        }
    }

    // Bridge: external controller (Zwift Click v1, …) → the same ± steps the
    // in-app stepper takes. Only active while the ride screen is composed; the
    // manager keeps running in the background but emissions to nowhere are
    // cheap. Re-keyed on the mode so the collector always holds a lambda that
    // knows which kind of ride it's stepping.
    LaunchedEffect(isFreeRide) {
        container.controllerManager.events.collect { event ->
            val direction = when (event) {
                app.indoorbike.shared.ble.ControllerEvent.Plus -> +1
                app.indoorbike.shared.ble.ControllerEvent.Minus -> -1
            }
            adjustEffort(direction)
        }
    }

    LaunchedEffect(state, continueRequested) {
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
        isFreeRide -> PowerZone.fromFtpPercent(
            if (ftp > 0) freeRideTarget * 100 / ftp else 0
        )
        else -> currentSeg?.let { PowerZone.fromFtpPercent(it.powerPercentFTP) } ?: PowerZone.Z1
    }
    val nextZone = if (isFreeRide) null else nextSeg?.let { PowerZone.fromFtpPercent(it.powerPercentFTP) }
    // What the TARGET pill reads. On a free ride that's the rider's setting,
    // not the trainer's live target: the trainer sits at 0 W until the ride
    // goes active, and a pill stuck on "TARGET 0 W" would make the ± buttons
    // look broken exactly when they're being used to pick an opening effort.
    val displayTarget = if (isFreeRide) freeRideTarget else targetPower

    val handleBack = {
        if (state != WorkoutState.Completed) sessionManager.stopWorkout()
        onBack()
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
            RideHeader(
                workoutName = workout!!.name,
                state = state,
                elapsed = elapsed,
                totalDuration = totalDuration,
                openEnded = isFreeRide,
                isMockTrainer = isMockTrainer,
                onBack = handleBack,
                onPauseToggle = {
                    when (state) {
                        WorkoutState.Active -> sessionManager.pauseWorkout()
                        WorkoutState.Paused -> sessionManager.resumeWorkout()
                        else -> {}
                    }
                },
                onSkipStep = { sessionManager.skipSegment() },
                onMockToggle = { trainerManager.toggleMockPedaling() },
                canEndAndSave = canEndAndSave,
                onEndAndSave = { confirmingEnd = true },
                canSkip = !isFreeRide,
                // Narrow widths (portrait) get a reflowed two-row header so the
                // title and state don't collapse into a one-letter-wide column.
                compact = LocalConfiguration.current.screenWidthDp < 600
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
                val segLabel = currentSeg?.intervalType ?: "Segment"
                val segPower = currentSeg?.powerPercentFTP ?: 0

                if (wide) {
                    // ── three-across (landscape phone → tablet) ──
                    // Sizes step down with available height so the power column
                    // (eyebrow + number + W/kg + target steppers) always fits the
                    // metrics row without the bottom pill clipping into the
                    // timeline. A landscape phone is genuinely short (~330-410dp),
                    // hence the small bottom tier; tablets get the big numbers.
                    val flankWidth = if (expanded) 240.dp else 168.dp
                    val flankFont: TextUnit = when {
                        expanded -> 56.sp
                        maxHeight >= 440.dp -> 30.sp
                        else -> 26.sp
                    }
                    val heroFont: TextUnit = when {
                        expanded -> 176.sp
                        maxHeight >= 560.dp -> 120.sp
                        maxHeight >= 440.dp -> 92.sp
                        else -> 68.sp
                    }
                    val heroNumberWidth = if (expanded) 440.dp else 240.dp
                    val heroWkgBarWidth = if (expanded) 320.dp else 190.dp
                    val wideTimelineHeight = when {
                        expanded -> 112.dp
                        maxHeight >= 440.dp -> 64.dp
                        else -> 48.dp
                    }

                    // Live average power for the stats strip; only computed when
                    // the strip is actually shown (expanded). Recomputed per second.
                    val avgPowerWatts = if (expanded) remember(elapsed) {
                        val tele = sessionManager.telemetrySnapshot()
                        if (tele.isEmpty()) 0 else (tele.sumOf { it.power } / tele.size).toInt()
                    } else 0

                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = if (expanded) Spacing.s8 else Spacing.s5),
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
                                label = "HEART RATE",
                                value = if (showStaleHr) "--" else animatedHr.toString(),
                                unit = "BPM",
                                isStale = showStaleHr,
                                valueColor = hrZone?.color ?: CadenceColors.Danger,
                                sublabel = if (showStaleHr) null
                                    else hrZone?.let { "Z${it.number} · ${it.label.uppercase()}" },
                                cardWidth = flankWidth,
                                valueFontSize = flankFont
                            )
                        }

                        if (showVirtualSpeed && state != WorkoutState.Countdown) {
                            VirtualSpeedReadout(
                                speedKmh = virtualSpeedKmh,
                                distanceMeters = virtualDistanceMeters,
                                accent = animatedZoneColor,
                                modifier = Modifier.padding(bottom = Spacing.s3)
                            )
                        }

                        if (expanded && state != WorkoutState.Countdown) {
                            RideStatsStrip(
                                elapsedSeconds = elapsed,
                                avgPowerWatts = avgPowerWatts,
                                // A free ride has a single endless interval, so
                                // the tile shows the wattage being held instead.
                                thirdLabel = if (isFreeRide) "TARGET" else "INTERVAL",
                                thirdValue = if (isFreeRide) {
                                    displayTarget.toString()
                                } else {
                                    val shown = (currentSegIdx + 1).coerceAtMost(workout!!.segments.size)
                                    "$shown / ${workout!!.segments.size}"
                                },
                                thirdUnit = if (isFreeRide) "W" else "",
                                zoneColor = animatedZoneColor,
                                modifier = Modifier.padding(bottom = Spacing.s3)
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
                                    valueFontSize = 30.sp,
                                    modifier = Modifier.fillMaxHeight()
                                )
                                FlankMetric(
                                    label = "HEART RATE",
                                    value = if (showStaleHr) "--" else animatedHr.toString(),
                                    unit = "BPM",
                                    isStale = showStaleHr,
                                    valueColor = hrZone?.color ?: CadenceColors.Danger,
                                    sublabel = if (showStaleHr) null
                                        else hrZone?.let { "Z${it.number} · ${it.label.uppercase()}" },
                                    cardWidth = flankWidth,
                                    valueFontSize = 30.sp,
                                    modifier = Modifier.fillMaxHeight()
                                )
                            }
                            if (showVirtualSpeed && state != WorkoutState.Countdown) {
                                VirtualSpeedReadout(
                                    speedKmh = virtualSpeedKmh,
                                    distanceMeters = virtualDistanceMeters,
                                    accent = animatedZoneColor
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
                        color = if (isGo) CadenceColors.Success else CadenceColors.Brand500,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = state == WorkoutState.Completed,
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
                                    .border(
                                        2.dp,
                                        CadenceColors.Brand500.copy(alpha = (1f - ring) * 0.5f),
                                        CircleShape
                                    )
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.EmojiEvents,
                            contentDescription = "Finished",
                            tint = CadenceColors.Brand500,
                            modifier = Modifier
                                .size(120.dp)
                                .scale(0.4f + 0.6f * trophyPop.value)
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "FINISHED!",
                        style = CadenceType.h1.copy(fontSize = 56.sp, fontWeight = FontWeight.Black),
                        color = CadenceColors.Fg1
                    )
                    Spacer(modifier = Modifier.height(28.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(CadenceColors.Brand500)
                            .border(1.dp, CadenceColors.Brand500.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .clickable(
                                enabled = !continueRequested,
                                onClick = { continueRequested = true }
                            )
                            .padding(horizontal = 28.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = if (continueRequested) "Saving…" else "View Summary",
                            style = CadenceType.bodyStrong,
                            color = CadenceColors.OnBrand
                        )
                    }
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
) {
    val timeText = if (openEnded) {
        formatClock(elapsed)
    } else {
        "${formatClock(elapsed)}  /  ${formatClock(totalDuration)}"
    }
    val stateText: String? = when (state) {
        // A Standby that can be ended-and-saved is a stop-pedaling auto-pause of
        // a ride in progress, not the initial "pedal to start" standby.
        WorkoutState.Standby ->
            if (canEndAndSave) "AUTO-PAUSED · PEDAL TO RESUME" else "STANDBY · PEDAL TO START"
        WorkoutState.Paused -> "PAUSED · TRAINER AT 0 W"
        else -> null
    }
    val stateColor = if (state == WorkoutState.Paused || canEndAndSave) CadenceColors.Warning else CadenceColors.Brand500

    if (compact) {
        // Portrait: two rows so nothing has to share a single narrow line.
        //   row 1: back · title (flexes, ellipsised) · controls
        //   row 2: state · elapsed/total
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.s5, vertical = Spacing.s3),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBtn(icon = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", onClick = onBack)
                Text(
                    text = workoutName,
                    style = CadenceType.h3,
                    color = CadenceColors.Fg1,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                RideControls(
                    state, isMockTrainer, onMockToggle, onSkipStep, onPauseToggle,
                    canEndAndSave, onEndAndSave, canSkip, compact = true
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stateText.orEmpty(),
                    style = CadenceType.tick,
                    color = stateColor,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(text = timeText, style = CadenceType.bodyStrong, color = CadenceColors.Fg1, maxLines = 1)
            }
        }
        return
    }

    // Wide (landscape / tablet): single row.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s5, vertical = Spacing.s3),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBtn(icon = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", onClick = onBack)

        // The ride name is intentionally omitted in the wide layout: with the back
        // button, clock and controls on a single row there isn't room for it, and a
        // growing elapsed clock would otherwise wrap to two lines and flicker. Only
        // the live state (STANDBY / PAUSED) is kept here.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.s4)
        ) {
            if (stateText != null) {
                Text(stateText, style = CadenceType.tick, color = stateColor, maxLines = 1)
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Fixed width so the elapsed clock ticking from "0:00" → "1:23:45" doesn't
            // resize the time block and shove the buttons around. softWrap = false +
            // maxLines = 1 guarantee the clock stays on one line (no wrap flicker).
            Box(
                modifier = Modifier.width(220.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = timeText,
                    style = CadenceType.h3,
                    color = CadenceColors.Fg1,
                    maxLines = 1,
                    softWrap = false
                )
            }
            RideControls(
                state, isMockTrainer, onMockToggle, onSkipStep, onPauseToggle,
                canEndAndSave, onEndAndSave, canSkip, compact = false
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
            SecondaryBtn(text = "Mock", onClick = onMockToggle)
        }
        // Hidden on a free ride: its single segment is the whole session, so
        // skipping it would silently end the ride rather than move it on.
        if (canSkip && (state == WorkoutState.Active || state == WorkoutState.Paused)) {
            SecondaryBtn(text = "Skip", leadingIcon = Icons.Outlined.SkipNext, onClick = onSkipStep)
        }
        if (state == WorkoutState.Active) {
            SecondaryBtn(text = "Pause", onClick = onPauseToggle)
        } else if (state == WorkoutState.Paused) {
            SecondaryBtn(text = "Resume", onClick = onPauseToggle)
        }
        // Explicit way to finish a paused/auto-paused ride and keep the data —
        // otherwise the only save path is riding all the way to completion.
        if (canEndAndSave) {
            SecondaryBtn(
                text = if (compact) "End" else "End & save",
                leadingIcon = Icons.Outlined.Stop,
                onClick = onEndAndSave
            )
        }
    }
}

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
    val shape = RoundedCornerShape(14.dp)
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(Radii.lg))
                .background(CadenceColors.Bg1)
                .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.lg))
                .padding(Spacing.s5),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4)
        ) {
            Text(
                text = "End ride now?",
                style = CadenceType.h3,
                color = CadenceColors.Fg1
            )
            Text(
                text = "Your ride so far (${formatClock(elapsed)}) will be saved to your history. " +
                    "You won't be able to resume it.",
                style = CadenceType.body,
                color = CadenceColors.Fg2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(shape)
                        .background(CadenceColors.Bg2)
                        .border(1.dp, CadenceColors.Line2, shape)
                        .clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "Keep riding", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(shape)
                        .background(CadenceColors.Brand500)
                        .clickable(onClick = onConfirm),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "End & save", style = CadenceType.bodyStrong, color = CadenceColors.OnBrand)
                }
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
    zoneColor: Color,
    timelineHeight: Dp,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val upNext: String? = nextZone?.let { "UP NEXT · Z${it.number} ${it.label.uppercase()}" }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s5, vertical = Spacing.s3)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Topographic peak glyph in zone color.
            Text(text = "▲", style = CadenceType.bodyStrong, color = zoneColor)
            Spacer(Modifier.width(6.dp))
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
            // Wide layout: "UP NEXT" sits inline on the right.
            if (!compact && upNext != null) {
                Spacer(Modifier.weight(1f))
                Text(text = upNext, style = CadenceType.tick, color = nextZone.color, maxLines = 1)
            }
        }

        // Compact (portrait): "UP NEXT" gets its own line so it can't be squeezed
        // into a vertical one-letter sliver on a narrow screen.
        if (compact && upNext != null) {
            Text(
                text = upNext,
                style = CadenceType.tick,
                color = nextZone.color,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
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
    Box(
        modifier = Modifier
            .widthIn(min = 76.dp)
            .scale(beat.value)
            .clip(RoundedCornerShape(50))
            .background(zoneColor.copy(alpha = if (urgent) 0.30f else 0.18f))
            .border(1.dp, zoneColor.copy(alpha = if (urgent) 0.75f else 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "⏱ ",
                style = CadenceType.tick,
                color = zoneColor
            )
            Text(
                text = formatClock(remainingSeconds.coerceAtLeast(0)),
                style = CadenceType.bodyStrong.copy(fontSize = 14.sp),
                color = CadenceColors.Fg1
            )
        }
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
    /** Small caption under the number — e.g. "Z3 · AEROBIC" for the HR zone. */
    sublabel: String? = null,
    /** Sublabel tint; defaults to [valueColor], which is what the HR zone wants. */
    sublabelColor: Color? = null,
    /**
     * Replaces the whole card's spoken text. Set it when the visual shorthand
     * (an arrow, a bar) wouldn't survive being read out field by field.
     */
    contentDescription: String? = null,
    /** Optional extra row under the sublabel — e.g. the cadence target bar. */
    footer: (@Composable () -> Unit)? = null,
    // Default to the phone HUD; the ride screen passes larger values on
    // expanded-width screens so the flanking metrics stay legible.
    cardWidth: Dp = 150.dp,
    valueFontSize: TextUnit = 32.sp,
) {
    // Fixed width so 2-digit ↔ 3-digit transitions don't reflow the row.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(cardWidth)
            .clip(RoundedCornerShape(12.dp))
            .background(CadenceColors.Bg1.copy(alpha = 0.35f))
            .border(1.dp, CadenceColors.Line2.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
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
        Text(text = label, style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    fontSize = valueFontSize,
                    fontWeight = FontWeight.Bold,
                    color = valueColor
                )
                Text(
                    text = " $unit",
                    style = CadenceType.bodyStrong,
                    color = CadenceColors.Fg3,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
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
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom
    ) {
        InlineStat("SPEED", "%.1f".format(speedKmh), "KM/H", accent)
        Text(
            text = "  ·  ",
            style = CadenceType.tick,
            color = CadenceColors.Fg3
        )
        InlineStat("DIST", "%.2f".format(distanceMeters / 1000.0), "KM", accent)
    }
}

@Composable
private fun InlineStat(label: String, value: String, unit: String, accent: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = label,
            style = CadenceType.eyebrow,
            color = CadenceColors.Fg3,
            modifier = Modifier.padding(end = 6.dp, bottom = 2.dp)
        )
        Text(text = value, style = CadenceType.bodyStrong, color = accent)
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
    zoneColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s4)) {
            StatTile("ELAPSED", formatClock(elapsedSeconds), accent = zoneColor)
            StatTile("AVG", avgPowerWatts.toString(), unit = "W", accent = zoneColor)
            StatTile(thirdLabel, thirdValue, unit = thirdUnit, accent = zoneColor)
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
    unit: String = "",
) {
    Column(
        modifier = modifier
            .width(220.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CadenceColors.Bg1.copy(alpha = 0.35f))
            .border(1.dp, CadenceColors.Line2.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .width(30.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Text(text = label, style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = CadenceColors.Fg1)
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
            .border(1.dp, CadenceColors.Danger.copy(alpha = 0.4f), shape)
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
                    color = CadenceColors.Brand500,
                    modifier = Modifier
                        .clickable(onClick = onRetry)
                        .padding(vertical = 4.dp, horizontal = 6.dp)
                )
                Text(
                    text = "Report",
                    style = CadenceType.bodyStrong,
                    color = CadenceColors.Brand500,
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
            .border(1.dp, CadenceColors.Danger.copy(alpha = 0.4f), shape)
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
                color = CadenceColors.Brand500,
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
