package app.indoorbike.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.indoorbike.LocalAppContainer
import app.indoorbike.LocalRideResumer
import app.indoorbike.LocalWorkouts
import app.indoorbike.shared.workout.formatClock
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing

/**
 * Persistent banner shown on every top-level tab while an unfinished ride is
 * stored on disk. Drives the "you crashed mid-ride; continue or abandon?" UX.
 *
 * Returns nothing (renders nothing) when:
 *  - no snapshot exists, or
 *  - the snapshot's workout is no longer in the loaded workout list (deleted JSON, etc.)
 */
@Composable
fun UnfinishedRideBanner(
    modifier: Modifier = Modifier,
) {
    val container = LocalAppContainer.current
    val workouts = LocalWorkouts.current
    val resumer = LocalRideResumer.current
    val snapshot by container.sessionManager.persistedSnapshot.collectAsState()

    val current = snapshot ?: return
    val workout = workouts.find { it.id == current.workoutId } ?: return

    var confirmingAbandon by remember { mutableStateOf(false) }

    val total = workout.segments.sumOf { it.lengthInSeconds }.coerceAtLeast(1)
    val progress = (current.elapsedSeconds.toFloat() / total).coerceIn(0f, 1f)
    val pct = (progress * 100).toInt()

    val shape = RoundedCornerShape(Radii.md)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Brand500.copy(alpha = 0.5f), shape)
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.PauseCircleOutline,
                contentDescription = null,
                tint = CadenceColors.Brand500,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "UNFINISHED RIDE",
                style = CadenceType.eyebrow,
                color = CadenceColors.Brand500
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = workout.name,
            style = CadenceType.h3,
            color = CadenceColors.Fg1,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))

        // Mini progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(CadenceColors.Bg0)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(CadenceColors.Brand500)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${formatClock(current.elapsedSeconds)} / ${formatClock(total)} · $pct% complete",
            style = CadenceType.tick,
            color = CadenceColors.Fg2
        )

        Spacer(Modifier.height(Spacing.s3))

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
            ContinueButton(
                onClick = { resumer.continueRide(workout, current) },
                modifier = Modifier.weight(1f)
            )
            AbandonButton(
                onClick = { confirmingAbandon = true },
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (confirmingAbandon) {
        AbandonConfirmDialog(
            workoutName = workout.name,
            elapsed = current.elapsedSeconds,
            total = total,
            onCancel = { confirmingAbandon = false },
            onConfirm = {
                confirmingAbandon = false
                resumer.abandonRide()
            }
        )
    }
}

private val BannerButtonHeight = 44.dp
private val BannerButtonShape = RoundedCornerShape(12.dp)

@Composable
private fun ContinueButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(BannerButtonHeight)
            .clip(BannerButtonShape)
            .background(CadenceColors.Brand500)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        Icon(
            imageVector = Icons.Outlined.PlayArrow,
            contentDescription = null,
            tint = CadenceColors.OnBrand,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "Continue",
            style = CadenceType.bodyStrong,
            color = CadenceColors.OnBrand
        )
    }
}

@Composable
private fun AbandonButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(BannerButtonHeight)
            .clip(BannerButtonShape)
            .background(CadenceColors.Bg2)
            .border(1.dp, CadenceColors.Line2, BannerButtonShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Abandon",
            style = CadenceType.bodyStrong,
            color = CadenceColors.Fg2
        )
    }
}

@Composable
private fun AbandonConfirmDialog(
    workoutName: String,
    elapsed: Int,
    total: Int,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
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
                text = "Abandon this ride?",
                style = CadenceType.h3,
                color = CadenceColors.Fg1
            )
            Text(
                text = "$workoutName — ${formatClock(elapsed)} of ${formatClock(total)} won't be saved. " +
                        "This can't be undone.",
                style = CadenceType.body,
                color = CadenceColors.Fg2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(BannerButtonHeight)
                        .clip(BannerButtonShape)
                        .background(CadenceColors.Bg2)
                        .border(1.dp, CadenceColors.Line2, BannerButtonShape)
                        .clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Keep ride",
                        style = CadenceType.bodyStrong,
                        color = CadenceColors.Fg1
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(BannerButtonHeight)
                        .clip(BannerButtonShape)
                        .background(CadenceColors.Danger)
                        .clickable(onClick = onConfirm),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Abandon",
                        style = CadenceType.bodyStrong,
                        color = CadenceColors.Fg1
                    )
                }
            }
        }
    }
}
