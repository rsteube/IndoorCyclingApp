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
package app.indoorbike.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.indoorbike.shared.session.WorkoutState
import app.indoorbike.shared.workout.formatClock
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing

/**
 * Persistent banner shown on every top-level tab while a ride is stored on disk
 * without having reached history. Two shapes, depending on how far the ride got:
 *
 *  - **Mid-ride** (the app died while riding): continue or abandon.
 *  - **Finished but unsaved** (the app died, or the rider walked away, between
 *    the completion screen and the summary): save it to history, or discard.
 *
 * Returns nothing (renders nothing) when:
 *  - no snapshot exists,
 *  - the snapshot's workout is no longer in the loaded workout list (deleted JSON, etc.), or
 *  - the ride is finished but has no telemetry to save.
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

    // A finished ride that never reached history: either the app died between
    // the trophy screen and the summary, or the rider backed out of it. The
    // samples are the whole value here, so offer to save rather than resume.
    val isFinished = current.state == WorkoutState.Completed ||
        current.currentSegmentIndex >= workout.segments.size ||
        current.elapsedSeconds >= workout.segments.sumOf { it.lengthInSeconds }
    val recovered = remember(current) { container.sessionManager.pendingRideTelemetry(current) }

    if (isFinished && recovered.isEmpty()) {
        // Finished with nothing to show for it. Pre-fix snapshots land here:
        // they were written for a completed ride by the periodic tick, and no
        // telemetry log exists to go with them, so saving would only add an
        // empty session to history. Discard silently, as before.
        LaunchedEffect(current.workoutId, current.elapsedSeconds) {
            container.sessionManager.abandonUnfinishedRide()
        }
        return
    }

    var confirmingAbandon by remember { mutableStateOf(false) }

    val total = workout.segments.sumOf { it.lengthInSeconds }.coerceAtLeast(1)
    val progress = (current.elapsedSeconds.toFloat() / total).coerceIn(0f, 1f)
    val pct = (progress * 100).toInt()
    // Time actually pedalled, which is what a saved ride reports. Older
    // snapshots default it to 0, so fall back to the planned elapsed.
    val rideSeconds = current.actualRideSeconds.takeIf { it > 0 } ?: current.elapsedSeconds

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
                imageVector = if (isFinished) {
                    Icons.Outlined.EmojiEvents
                } else {
                    Icons.Outlined.PauseCircleOutline
                },
                contentDescription = null,
                tint = CadenceColors.Brand500,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = if (isFinished) "RIDE NOT SAVED" else "UNFINISHED RIDE",
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
            text = if (isFinished) {
                "${formatClock(rideSeconds)} ridden · not yet in your history"
            } else {
                "${formatClock(current.elapsedSeconds)} / ${formatClock(total)} · $pct% complete"
            },
            style = CadenceType.tick,
            color = CadenceColors.Fg2
        )

        Spacer(Modifier.height(Spacing.s3))

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
            if (isFinished) {
                SaveButton(
                    onClick = { resumer.saveFinishedRide(workout, current) },
                    modifier = Modifier.weight(1f)
                )
            } else {
                ContinueButton(
                    onClick = { resumer.continueRide(workout, current) },
                    modifier = Modifier.weight(1f)
                )
            }
            AbandonButton(
                onClick = { confirmingAbandon = true },
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (confirmingAbandon) {
        AbandonConfirmDialog(
            workoutName = workout.name,
            elapsed = if (isFinished) rideSeconds else current.elapsedSeconds,
            total = total,
            isFinished = isFinished,
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
private fun SaveButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
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
            imageVector = Icons.Outlined.Save,
            contentDescription = null,
            tint = CadenceColors.OnBrand,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = "Save ride",
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
    isFinished: Boolean,
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
                text = if (isFinished) "Discard this ride?" else "Abandon this ride?",
                style = CadenceType.h3,
                color = CadenceColors.Fg1
            )
            Text(
                text = if (isFinished) {
                    "$workoutName - ${formatClock(elapsed)} you already rode. Discarding " +
                        "means it never reaches your history. This can't be undone."
                } else {
                    "$workoutName - ${formatClock(elapsed)} of ${formatClock(total)} won't be saved. " +
                        "This can't be undone."
                },
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
                        text = if (isFinished) "Discard" else "Abandon",
                        style = CadenceType.bodyStrong,
                        color = CadenceColors.Fg1
                    )
                }
            }
        }
    }
}
