package app.indoorbike.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.SkipNext
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.zones.PowerZone
import kotlinx.coroutines.launch

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
    // Prefer the dedicated HRM if it's emitting; fall back to whatever the trainer has.
    val currentHr = if (hrmHr > 0) hrmHr else trainerHr
    val targetPower by trainerManager.targetPower.collectAsState()
    val isMockTrainer by trainerManager.isMockTrainer.collectAsState()

    val scope = rememberCoroutineScope()
    var continueRequested by remember { mutableStateOf(false) }

    // Foreground service: started on first composition (the ride is active),
    // stopped on completion or back-out.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: service runs even without notification visibility */ }

    DisposableEffect(Unit) {
        // On Android 13+, ask for runtime notification permission so the
        // foreground-service notification is visible. The service still runs
        // if the user denies — they just won't see the workout chip.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        RideSessionService.start(context)
        onDispose { RideSessionService.stop(context) }
    }

    LaunchedEffect(state, continueRequested) {
        if (state == WorkoutState.Completed && workout != null && continueRequested) {
            val telemetry = sessionManager.telemetrySnapshot()
            val startEpoch = sessionManager.sessionStartTimeEpoch

            val avgPower = if (telemetry.isNotEmpty()) telemetry.map { it.power }.average().toLong() else 0L
            val avgCadence = if (telemetry.isNotEmpty()) telemetry.map { it.cadence }.average().toLong() else 0L
            val avgHr = if (telemetry.isNotEmpty()) telemetry.map { it.heartRate }.average().toLong() else 0L

            scope.launch {
                val newSessionId = historyRepository.insertWorkoutSession(
                    workoutId = workout!!.id,
                    workoutName = workout!!.name,
                    startTimeEpoch = startEpoch,
                    durationSeconds = elapsed.toLong(),
                    avgPower = avgPower,
                    avgCadence = avgCadence,
                    avgHeartRate = avgHr,
                    telemetry = telemetry
                )
                onNavigateToOverview(newSessionId)
            }
        }
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
    val ftp by container.userSettings.ftp.collectAsState()
    val ftpPctClean = if (ftp > 0 && currentPower > 0) (currentPower * 100 / ftp) else 0

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

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.s5),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlankMetric(
                    label = "CADENCE",
                    value = animatedCadence.toString(),
                    unit = "RPM",
                    isStale = false,
                    valueColor = CadenceColors.Fg1
                )

                HeroPowerReadout(
                    powerWatts = currentPower,
                    isStale = showStalePower,
                    targetWatts = targetPower,
                    ftpPercent = ftpPctClean,
                    zoneColor = animatedZoneColor,
                    biasPercent = biasPercent,
                    ergLocked = state == WorkoutState.Active && targetPower > 0,
                    onAdjust = { delta -> sessionManager.adjustDifficulty(delta) },
                    modifier = Modifier.weight(1f)
                )

                FlankMetric(
                    label = "HEART RATE",
                    value = if (showStaleHr) "--" else animatedHr.toString(),
                    unit = "BPM",
                    isStale = showStaleHr,
                    valueColor = CadenceColors.Danger
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
                    elapsedInSeg = elapsedInSeg
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
                        (scaleIn(
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
                        )
                    },
                    label = "countdownNumber"
                ) { value ->
                    val isGo = value <= 0
                    Text(
                        text = if (isGo) "GO!" else value.toString(),
                        fontSize = if (isGo) 160.sp else 200.sp,
                        fontWeight = FontWeight.Black,
                        color = if (isGo) CadenceColors.Success else CadenceColors.Brand500
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
    isStale: Boolean = false
) {
    // Fixed width so 2-digit ↔ 3-digit transitions don't reflow the row.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(150.dp)
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
                    fontSize = 32.sp,
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
    }
}
