package app.indoorbike.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
    val currentHr by trainerManager.heartRate.collectAsState()
    val targetPower by trainerManager.targetPower.collectAsState()
    val isMockTrainer by trainerManager.isMockTrainer.collectAsState()

    val scope = rememberCoroutineScope()
    var continueRequested by remember { mutableStateOf(false) }

    // Foreground service: started on first composition (the ride is active),
    // stopped on completion or back-out.
    DisposableEffect(Unit) {
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

    val animatedBgColor by animateColorAsState(
        targetValue = currentZone.color.copy(alpha = 0.15f),
        animationSpec = tween(1000),
        label = "ride-bg"
    )
    val bgBrush = Brush.radialGradient(
        colors = listOf(animatedBgColor, CadenceColors.Bg0),
        center = Offset.Unspecified,
        radius = 1200f
    )

    val handleBack = {
        if (state != WorkoutState.Completed) sessionManager.stopWorkout()
        onBack()
    }

    BackHandler { handleBack() }

    val showStaleHr = currentHr <= 0
    val showStalePower = currentPower <= 0 && (state == WorkoutState.Active)
    val powerDisplay = if (showStalePower) "--" else currentPower.toString()
    val hrDisplay = if (showStaleHr) "--" else currentHr.toString()

    Box(modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0).background(bgBrush)) {
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
                GlassCard { MetricBox("CADENCE", currentCadence.toString(), "RPM", CadenceColors.Fg1) }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "POWER", style = CadenceType.eyebrow, color = currentZone.color)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = powerDisplay,
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Black,
                            fontStyle = FontStyle.Italic,
                            color = currentZone.color
                        )
                        Text(
                            text = " W",
                            style = CadenceType.h1,
                            color = CadenceColors.Fg2,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    TargetWithDifficulty(
                        target = targetPower,
                        bias = biasPercent,
                        ergLocked = state == WorkoutState.Active && targetPower > 0,
                        onAdjust = { delta -> sessionManager.adjustDifficulty(delta) }
                    )
                }

                GlassCard { MetricBox("HEART RATE", hrDisplay, "BPM", CadenceColors.Danger) }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.s5, vertical = Spacing.s3)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    val segLabel = currentSeg?.intervalType ?: "Segment"
                    val segPower = currentSeg?.powerPercentFTP ?: 0
                    Column {
                        Text(
                            text = "$segLabel · $segPower% FTP",
                            style = CadenceType.bodyStrong,
                            color = CadenceColors.Fg2
                        )
                        if (nextZone != null) {
                            Text(
                                text = "UP NEXT · Z${nextZone.number} ${nextZone.label.uppercase()}",
                                style = CadenceType.tick,
                                color = nextZone.color
                            )
                        }
                    }
                    Text(text = formatClock(remainingInSeg), style = CadenceType.h2, color = CadenceColors.Fg1)
                }

                Spacer(modifier = Modifier.height(8.dp))

                ActiveWorkoutGraph(
                    workout = workout!!,
                    currentSegIdx = currentSegIdx,
                    elapsedInSeg = elapsedInSeg,
                    chartHeight = 60
                )
            }
        }

        AnimatedVisibility(
            visible = state == WorkoutState.Countdown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (countdown > 0) countdown.toString() else "GO!",
                    fontSize = 120.sp,
                    fontWeight = FontWeight.Black,
                    color = CadenceColors.Brand500
                )
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
            Text(
                text = "${formatClock(elapsed)}  /  ${formatClock(totalDuration)}",
                style = CadenceType.h3,
                color = CadenceColors.Fg1
            )
            if (isMockTrainer && BuildConfig.DEBUG) {
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
private fun TargetWithDifficulty(
    target: Int,
    bias: Int,
    ergLocked: Boolean,
    onAdjust: (Int) -> Unit
) {
    val biasLabel = when {
        bias > 0 -> "+$bias%"
        bias < 0 -> "$bias%"
        else -> null
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2)
    ) {
        StepperButton(icon = Icons.Outlined.Remove, contentDescription = "Decrease difficulty") { onAdjust(-5) }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(CadenceColors.Bg1.copy(alpha = 0.5f))
                .border(1.dp, CadenceColors.Line1, RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "TARGET: $target W",
                    style = CadenceType.bodyStrong,
                    color = CadenceColors.Fg1
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (ergLocked) PowerZone.Z3.color else CadenceColors.Fg4)
                    )
                    Text(
                        text = if (ergLocked) "ERG LOCKED" else "ERG IDLE",
                        style = CadenceType.tick.copy(fontSize = 9.sp),
                        color = if (ergLocked) PowerZone.Z3.color else CadenceColors.Fg3
                    )
                    if (biasLabel != null) {
                        Text(
                            text = " · $biasLabel",
                            style = CadenceType.tick.copy(fontSize = 9.sp),
                            color = if (bias > 0) CadenceColors.Brand500 else CadenceColors.Fg2
                        )
                    }
                }
            }
        }
        StepperButton(icon = Icons.Outlined.Add, contentDescription = "Increase difficulty") { onAdjust(5) }
    }
}

@Composable
private fun StepperButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(CadenceColors.Bg2)
            .border(1.dp, CadenceColors.Line2, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = CadenceColors.Fg1,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun GlassCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CadenceColors.Bg1.copy(alpha = 0.4f))
            .border(1.dp, CadenceColors.Line2.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun MetricBox(label: String, value: String, unit: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = 28.sp,
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

@Composable
fun ActiveWorkoutGraph(
    workout: app.indoorbike.shared.Workout,
    currentSegIdx: Int,
    elapsedInSeg: Int,
    chartHeight: Int
) {
    val maxPct = (workout.segments.maxOfOrNull { it.powerPercentFTP } ?: 100).coerceAtLeast(60)
    val totalSeconds = workout.segments.sumOf { it.lengthInSeconds }.coerceAtLeast(1)
    val elapsedTotal = workout.segments.take(currentSegIdx).sumOf { it.lengthInSeconds } + elapsedInSeg
    val progressFraction = (elapsedTotal.toFloat() / totalSeconds).coerceIn(0f, 1f)

    Box(modifier = Modifier.fillMaxWidth().height(chartHeight.dp)) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            workout.segments.forEachIndexed { index, seg ->
                val zone = PowerZone.fromFtpPercent(seg.powerPercentFTP)
                val h = (seg.powerPercentFTP.toFloat() / maxPct).coerceIn(0.1f, 1f)
                val isPast = index < currentSegIdx
                val isCurrent = index == currentSegIdx
                val alpha = when {
                    isCurrent -> 1f
                    isPast -> 0.55f
                    else -> 0.30f
                }
                Box(
                    modifier = Modifier
                        .weight(seg.lengthInSeconds.toFloat().coerceAtLeast(1f))
                        .fillMaxHeight(h)
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(zone.color.copy(alpha = alpha))
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth(progressFraction)
                .fillMaxHeight()
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(CadenceColors.Brand500)
            )
        }
    }
}
