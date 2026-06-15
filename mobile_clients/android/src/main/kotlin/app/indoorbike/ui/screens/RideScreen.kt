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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.indoorbike.BuildConfig
import app.indoorbike.LocalAppContainer
import app.indoorbike.service.RideSessionService
import app.indoorbike.shared.session.WorkoutState
import app.indoorbike.shared.workout.formatClock
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
    LockScreenOrientationAndFullScreen(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)

    val container = LocalAppContainer.current
    val context = LocalContext.current
    val sessionManager = container.sessionManager
    val trainerManager = container.trainerManager
    val historyRepository = container.historyRepository

    val state by sessionManager.state.collectAsState()
    val workout by sessionManager.activeWorkout.collectAsState()
    val elapsed by sessionManager.elapsedSeconds.collectAsState()
    val elapsedInSeg by sessionManager.elapsedInSegment.collectAsState()
    val currentSegIdx by sessionManager.currentSegmentIndex.collectAsState()
    val countdown by sessionManager.countdownSeconds.collectAsState()
    val biasPercent by sessionManager.targetBiasPercent.collectAsState()

    val currentPower by trainerManager.power.collectAsState()
    val currentCadence by trainerManager.cadence.collectAsState()
    val trainerHr by trainerManager.heartRate.collectAsState()
    val hrmHr by container.heartRateMonitor.heartRate.collectAsState()
    // Surfaces messages set by RideSessionService when the foreground service
    // can't start (BLE permission missing, background-start refused). The
    // session manager auto-pauses on this signal; the banner explains why
    // and gives the rider a path to recover.
    val fgsFailure by sessionManager.foregroundServiceFailure.collectAsState()
    // Prefer the dedicated HRM if it's emitting; fall back to whatever the trainer has.
    val currentHr = if (hrmHr > 0) hrmHr else trainerHr
    val targetPower by trainerManager.targetPower.collectAsState()
    val isMockTrainer by trainerManager.isMockTrainer.collectAsState()
    val maxHr by container.userSettings.maxHr.collectAsState()
    val hrZone = remember(currentHr, maxHr) {
        app.indoorbike.ui.zones.HeartRateZone.fromHr(currentHr, maxHr)
    }

    var continueRequested by remember { mutableStateOf(false) }
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

    // Bridge: external controller (Zwift Click v1, …) → ±difficulty steps,
    // matching the in-app stepper's ±5 % bias. Only active while the ride
    // screen is composed; the manager keeps running in the background but
    // emissions to nowhere are cheap.
    LaunchedEffect(Unit) {
        container.controllerManager.events.collect { event ->
            val delta = when (event) {
                app.indoorbike.shared.ble.ControllerEvent.Plus -> +5
                app.indoorbike.shared.ble.ControllerEvent.Minus -> -5
            }
            sessionManager.adjustDifficulty(delta)
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
        val telemetry = sessionManager.telemetrySnapshot()
        val startEpoch = sessionManager.sessionStartTimeEpoch
        // Persist actual time spent riding, not the planned timeline:
        // skipping a segment fast-forwards `elapsed` so it would inflate the
        // saved duration. `actualRideSeconds` only counts wall-clock time
        // spent in the Active state.
        val actualRide = sessionManager.actualRideSeconds.value

        val avgPower = if (telemetry.isNotEmpty()) telemetry.map { it.power }.average().toLong() else 0L
        val avgCadence = if (telemetry.isNotEmpty()) telemetry.map { it.cadence }.average().toLong() else 0L
        val avgHr = if (telemetry.isNotEmpty()) telemetry.map { it.heartRate }.average().toLong() else 0L

        val newSessionId = historyRepository.insertWorkoutSession(
            workoutId = workout!!.id,
            workoutName = workout!!.name,
            startTimeEpoch = startEpoch,
            durationSeconds = actualRide.toLong(),
            avgPower = avgPower,
            avgCadence = avgCadence,
            avgHeartRate = avgHr,
            // Globally-unique stable id for the backup-sync layer. The
            // device-id namespace + a fresh UUID per session means two
            // phones can never collide on a session id without any
            // cross-device coordination.
            externalId = "${container.userSettings.deviceId}:${java.util.UUID.randomUUID()}",
            telemetry = telemetry
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
    val remainingInSeg = maxOf(0, (currentSeg?.lengthInSeconds ?: 0) - elapsedInSeg)
    val currentZone = currentSeg?.let { PowerZone.fromFtpPercent(it.powerPercentFTP) } ?: PowerZone.Z1
    val nextZone = nextSeg?.let { PowerZone.fromFtpPercent(it.powerPercentFTP) }

    val handleBack = {
        if (state != WorkoutState.Completed) sessionManager.stopWorkout()
        onBack()
    }

    BackHandler { handleBack() }

    val showStaleHr = currentHr <= 0
    val showStalePower = currentPower <= 0 && (state == WorkoutState.Active)
    val weightKg by container.userSettings.weightKg.collectAsState()
    val wattsPerKg = if (weightKg > 0f && currentPower > 0) currentPower / weightKg else 0f

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

    Box(modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0)) {
        HorizonRidgeBackdrop(
            workout = workout!!,
            tintColor = animatedZoneColor,
            modifier = Modifier.fillMaxSize()
        )

        // Layer 2: foreground content
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
                    onDismiss = { sessionManager.clearForegroundServiceFailure() }
                )
            }
            RideHeader(
                workoutName = workout!!.name,
                state = state,
                elapsed = elapsed,
                totalDuration = totalDuration,
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
                onMockToggle = { trainerManager.toggleMockPedaling() }
            )

            // Expanded width (landscape tablet / unfolded foldable): scale the
            // HUD up so the numbers read from a distance and use the extra room.
            val isExpandedHud = LocalConfiguration.current.screenWidthDp >= 840
            // Live average power for the tablet stats strip. Recomputed each
            // second (keyed on `elapsed`); telemetrySnapshot() is a cheap copy.
            // Only computed on expanded screens where the strip is shown.
            val avgPowerWatts: Int = if (isExpandedHud) {
                remember(elapsed) {
                    val tele = sessionManager.telemetrySnapshot()
                    if (tele.isEmpty()) 0 else (tele.sumOf { it.power } / tele.size).toInt()
                }
            } else 0
            val flankWidth: Dp = if (isExpandedHud) 240.dp else 150.dp
            val flankFont: TextUnit = if (isExpandedHud) 56.sp else 32.sp
            val heroFont: TextUnit = if (isExpandedHud) 176.sp else 112.sp
            val heroNumberWidth: Dp = if (isExpandedHud) 440.dp else 280.dp
            val heroWkgBarWidth: Dp = if (isExpandedHud) 320.dp else 220.dp

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = if (isExpandedHud) Spacing.s8 else Spacing.s5),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlankMetric(
                    label = "CADENCE",
                    value = animatedCadence.toString(),
                    unit = "RPM",
                    isStale = false,
                    valueColor = CadenceColors.Fg1,
                    cardWidth = flankWidth,
                    valueFontSize = flankFont
                )

                HeroPowerReadout(
                    powerWatts = currentPower,
                    isStale = showStalePower,
                    targetWatts = targetPower,
                    wattsPerKg = wattsPerKg,
                    zoneColor = animatedZoneColor,
                    biasPercent = biasPercent,
                    ergLocked = state == WorkoutState.Active && targetPower > 0,
                    onAdjust = { delta -> sessionManager.adjustDifficulty(delta) },
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

            // Tablet-only live stats strip - uses the extra space to surface the
            // running ride numbers (elapsed, avg / normalised power, IF, TSS,
            // kJ, interval progress) without crowding the phone HUD.
            if (isExpandedHud && state != WorkoutState.Countdown) {
                RideStatsStrip(
                    elapsedSeconds = elapsed,
                    avgPowerWatts = avgPowerWatts,
                    intervalIndex = (currentSegIdx + 1).coerceAtMost(workout!!.segments.size),
                    intervalCount = workout!!.segments.size,
                    zoneColor = animatedZoneColor,
                    modifier = Modifier.padding(bottom = Spacing.s3)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.s5, vertical = Spacing.s3)
            ) {
                val segLabel = currentSeg?.intervalType ?: "Segment"
                val segPower = currentSeg?.powerPercentFTP ?: 0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Topographic peak glyph in zone color.
                    Text(
                        text = "▲",
                        style = CadenceType.bodyStrong,
                        color = animatedZoneColor
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$segLabel · $segPower% FTP",
                        style = CadenceType.bodyStrong,
                        color = CadenceColors.Fg1
                    )
                    Spacer(Modifier.width(8.dp))
                    // Timer attached to the segment label as a chip — reads as
                    // "this segment, ending in 4:25". Fixed width: clock format
                    // is M:SS (or H:MM:SS for very long segments).
                    SegmentTimerChip(remainingSeconds = remainingInSeg, zoneColor = animatedZoneColor)
                    Spacer(Modifier.weight(1f))
                    if (nextZone != null) {
                        Text(
                            text = "UP NEXT · Z${nextZone.number} ${nextZone.label.uppercase()}",
                            style = CadenceType.tick,
                            color = nextZone.color
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                RidgeTimeline(
                    workout = workout!!,
                    currentSegIdx = currentSegIdx,
                    elapsedInSeg = elapsedInSeg,
                    height = if (isExpandedHud) 112.dp else 72.dp
                )
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
            Box(
                modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.EmojiEvents,
                        contentDescription = "Finished",
                        tint = CadenceColors.Brand500,
                        modifier = Modifier.size(120.dp)
                    )
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
    onMockToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s5, vertical = Spacing.s3),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBtn(icon = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", onClick = onBack)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.s4)
        ) {
            Text(
                text = workoutName,
                style = CadenceType.h3,
                color = CadenceColors.Fg1,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            when (state) {
                WorkoutState.Standby -> Text("STANDBY · PEDAL TO START", style = CadenceType.tick, color = CadenceColors.Brand500)
                WorkoutState.Paused -> Text("PAUSED · TRAINER AT 0 W", style = CadenceType.tick, color = CadenceColors.Warning)
                else -> {}
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Fixed width so the elapsed clock ticking from "0:00" → "31:00" doesn't
            // resize the time block and shove the buttons around.
            Box(
                modifier = Modifier.width(180.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = "${formatClock(elapsed)}  /  ${formatClock(totalDuration)}",
                    style = CadenceType.h3,
                    color = CadenceColors.Fg1
                )
            }
            if (isMockTrainer) {
                SecondaryBtn(text = "Mock", onClick = onMockToggle)
            }
            if (state == WorkoutState.Active || state == WorkoutState.Paused) {
                SecondaryBtn(text = "Skip", leadingIcon = Icons.Outlined.SkipNext, onClick = onSkipStep)
            }
            if (state == WorkoutState.Active) {
                SecondaryBtn(text = "Pause", onClick = onPauseToggle)
            } else if (state == WorkoutState.Paused) {
                SecondaryBtn(text = "Resume", onClick = onPauseToggle)
            }
        }
    }
}

@Composable
private fun SegmentTimerChip(remainingSeconds: Int, zoneColor: Color) {
    Box(
        modifier = Modifier
            .widthIn(min = 76.dp)
            .clip(RoundedCornerShape(50))
            .background(zoneColor.copy(alpha = 0.18f))
            .border(1.dp, zoneColor.copy(alpha = 0.4f), RoundedCornerShape(50))
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
private fun FlankMetric(
    label: String,
    value: String,
    unit: String,
    valueColor: Color,
    isStale: Boolean = false,
    /** Small caption under the number — e.g. "Z3 · AEROBIC" for the HR zone. */
    sublabel: String? = null,
    // Default to the phone HUD; the ride screen passes larger values on
    // expanded-width screens so the flanking metrics stay legible.
    cardWidth: Dp = 150.dp,
    valueFontSize: TextUnit = 32.sp,
) {
    // Fixed width so 2-digit ↔ 3-digit transitions don't reflow the row.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(cardWidth)
            .clip(RoundedCornerShape(12.dp))
            .background(CadenceColors.Bg1.copy(alpha = 0.35f))
            .border(1.dp, CadenceColors.Line2.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
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
                color = valueColor,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
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
    intervalIndex: Int,
    intervalCount: Int,
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
            StatTile("INTERVAL", "$intervalIndex / $intervalCount", accent = zoneColor)
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
