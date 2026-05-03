package app.indoorbike.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.platform.LocalView
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import app.indoorbike.shared.ble.TrainerManager
import app.indoorbike.shared.session.WorkoutSessionManager
import app.indoorbike.shared.session.WorkoutState
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.workout.formatHHMM
import app.indoorbike.ui.workout.toSummary
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

@Composable
fun RideScreen(
    onBack: () -> Unit,
    onNavigateToOverview: (Long) -> Unit,
    historyRepository: app.indoorbike.shared.db.HistoryRepository
) {
    LockScreenOrientationAndFullScreen(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)

    val state by WorkoutSessionManager.state.collectAsState()
    val workout by WorkoutSessionManager.activeWorkout.collectAsState()
    val elapsed by WorkoutSessionManager.elapsedSeconds.collectAsState()
    val elapsedInSeg by WorkoutSessionManager.elapsedInSegment.collectAsState()
    val currentSegIdx by WorkoutSessionManager.currentSegmentIndex.collectAsState()
    val countdown by WorkoutSessionManager.countdownSeconds.collectAsState()
    
    val currentPower by TrainerManager.power.collectAsState()
    val currentCadence by TrainerManager.cadence.collectAsState()
    val currentHr by TrainerManager.heartRate.collectAsState()
    val targetPower by TrainerManager.targetPower.collectAsState()
    val isMockTrainer by TrainerManager.isMockTrainer.collectAsState()

    val ftp by app.indoorbike.UserSettings.ftp.collectAsState()

    val scope = rememberCoroutineScope()

    // When completed, pop to overview
    LaunchedEffect(state) {
        if (state == WorkoutState.Completed && workout != null) {
            val telemetry = WorkoutSessionManager.telemetry
            val startEpoch = WorkoutSessionManager.sessionStartTimeEpoch
            
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
                
                kotlinx.coroutines.delay(4000)
                onNavigateToOverview(newSessionId)
            }
        }
    }

    if (workout == null) {
        // Safety fallback
        Box(modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0))
        return
    }

    val totalDuration = remember(workout) { workout!!.segments.sumOf { it.lengthInSeconds } }
    val remaining = maxOf(0, totalDuration - elapsed)
    
    val currentSeg = workout!!.segments.getOrNull(currentSegIdx)
    val remainingInSeg = maxOf(0, (currentSeg?.lengthInSeconds ?: 0) - elapsedInSeg)
    val currentZone = currentSeg?.let { PowerZone.fromFtpPercent(it.powerPercentFTP) } ?: PowerZone.Z1

    val animatedBgColor by animateColorAsState(targetValue = currentZone.color.copy(alpha = 0.15f), animationSpec = tween(1000))
    val bgBrush = Brush.radialGradient(
        colors = listOf(animatedBgColor, CadenceColors.Bg0),
        center = Offset.Unspecified,
        radius = 1200f
    )

    val handleBack = {
        if (state != WorkoutState.Completed) {
            WorkoutSessionManager.stopWorkout()
        }
        onBack()
    }

    BackHandler {
        handleBack()
    }

    Box(modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0).background(bgBrush)) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            // 1. Header (Time, Title, Pause/Back)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 0.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBtn(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack, 
                    contentDescription = "Back", 
                    onClick = handleBack
                )
                
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(
                        text = workout!!.name, 
                        style = CadenceType.h3, 
                        color = CadenceColors.Fg1, 
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (state == WorkoutState.Standby) {
                        Text("STANDBY", style = CadenceType.bodyStrong, color = CadenceColors.Brand500)
                    } else if (state == WorkoutState.Paused) {
                        Text("PAUSED", style = CadenceType.bodyStrong, color = CadenceColors.Brand500)
                    }
                }
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${formatHHMM(elapsed)}  /  ${formatHHMM(totalDuration)}",
                        style = CadenceType.h3,
                        color = CadenceColors.Fg1
                    )
                    if (isMockTrainer) {
                        SecondaryBtn(text = "Mock", onClick = { TrainerManager.toggleMockPedaling() })
                    }
                    if (state == WorkoutState.Active) {
                        SecondaryBtn(text = "Pause", onClick = { WorkoutSessionManager.pauseWorkout() })
                    } else if (state == WorkoutState.Paused) {
                        SecondaryBtn(text = "Resume", onClick = { WorkoutSessionManager.resumeWorkout() })
                    }
                }
            }

            // 2. Main Metrics Board
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.s5),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Flank
                GlassCard { MetricBox("CADENCE", currentCadence.toString(), "RPM", CadenceColors.Fg1) }

                // Center Stage
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "POWER", style = CadenceType.eyebrow, color = currentZone.color)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = currentPower.toString(),
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
                    
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(CadenceColors.Bg1.copy(alpha = 0.5f))
                            .border(1.dp, CadenceColors.Line1, RoundedCornerShape(50))
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text(text = "TARGET: $targetPower W", style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                    }
                }

                // Right Flank
                val hrStr = if (currentHr > 0) currentHr.toString() else "--"
                GlassCard { MetricBox("HEART RATE", hrStr, "BPM", CadenceColors.Danger) }
            }

            // 3. Segment Progress & Upcoming
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
                    Text(text = "$segLabel - $segPower% FTP", style = CadenceType.bodyStrong, color = CadenceColors.Fg2)
                    Text(text = formatHHMM(remainingInSeg), style = CadenceType.h2, color = CadenceColors.Fg1)
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Full Workout Graph
                ActiveWorkoutGraph(workout = workout!!, currentSegIdx = currentSegIdx, chartHeight = 60)
            }
        }

        // Overlays
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
                modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.EmojiEvents,
                        contentDescription = "Finished",
                        tint = CadenceColors.Brand500,
                        modifier = Modifier.size(120.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "FINISHED!",
                        style = CadenceType.h1.copy(fontSize = 64.sp, fontWeight = FontWeight.Black),
                        color = CadenceColors.Fg1
                    )
                }
            }
        }
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
private fun MetricBox(label: String, value: String, unit: String, valueColor: androidx.compose.ui.graphics.Color) {
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
    chartHeight: Int
) {
    val maxPct = (workout.segments.maxOfOrNull { it.powerPercentFTP } ?: 100).coerceAtLeast(60)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(0.dp) // Touch each other for continuous mountain
    ) {
        workout.segments.forEachIndexed { index, seg ->
            val zone = app.indoorbike.ui.zones.PowerZone.fromFtpPercent(seg.powerPercentFTP)
            val h = (seg.powerPercentFTP.toFloat() / maxPct).coerceIn(0.1f, 1f)
            val isCurrent = index == currentSegIdx
            val alpha = if (isCurrent) 1f else 0.3f
            
            Box(
                modifier = Modifier
                    .weight(seg.lengthInSeconds.toFloat().coerceAtLeast(1f))
                    .fillMaxHeight(h)
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(zone.color.copy(alpha = alpha))
            )
        }
    }
}
