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
package app.indoorbike.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import app.indoorbike.shared.workout.isCompanion
import app.indoorbike.shared.workout.isFreeRide
import app.indoorbike.shared.workout.isSimulation
import app.indoorbike.shared.workout.resolveRideWorkout
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
 *
 * A free ride is recoverable like any other, but its workout is synthesised
 * per ride rather than stored in the library, so it's resolved rather than
 * looked up. Having no plan behind it, it shows time ridden instead of
 * progress towards a finish.
 */
@Composable
fun UnfinishedRideBanner(
    modifier: Modifier = Modifier,
    /**
     * Whether the rider may wave this away. True on the home screen, where the
     * banner is one card among several and a rider who has decided to start
     * something else should be able to clear it. False everywhere it appears
     * as a persistent cross-tab notice, where hiding it would leave no way
     * back to the ride.
     */
    dismissable: Boolean = false,
) {
    val container = LocalAppContainer.current
    val workouts = LocalWorkouts.current
    val resumer = LocalRideResumer.current
    val snapshot by container.sessionManager.persistedSnapshot.collectAsState()

    val current = snapshot ?: return

    // Dismissal is per ride and per session: see
    // AppContainer.dismissedUnfinishedRideStart.
    val dismissedStart by container.dismissedUnfinishedRideStart.collectAsState()
    if (dismissable && dismissedStart == current.sessionStartTimeEpochMs) return
    // Route names come from the catalog so a recovered simulated ride is
    // labelled with the road it was on rather than with its route id.
    val workout = resolveRideWorkout(
        workoutId = current.workoutId,
        library = workouts,
        routeNames = container.routeCatalog.routeNames(),
    ) ?: return
    // Both plan-less modes have a six-hour backstop segment rather than a
    // planned length, so neither can report progress as a fraction of it.
    val openEnded = workout.isFreeRide || workout.isSimulation || workout.isCompanion

    // A finished ride that never reached history: either the app died between
    // the trophy screen and the summary, or the rider backed out of it. The
    // samples are the whole value here, so offer to save rather than resume.
    //
    // A companion ride is always offered as one to save. Resuming it would put
    // the rider back on a HUD whose game is gone, with nothing to drive the
    // road, so the only useful thing left to do with the effort is keep it.
    val isFinished = workout.isCompanion ||
        current.state == WorkoutState.Completed ||
        current.currentSegmentIndex >= workout.segments.size ||
        current.elapsedSeconds >= workout.segments.sumOf { it.lengthInSeconds }
    val recovered = remember(current) { container.sessionManager.pendingRideTelemetry(current) }

    if (isFinished && recovered.isEmpty()) {
        // Finished with nothing to show for it. Pre-fix snapshots land here:
        // they were written for a completed ride by the periodic tick, and no
        // telemetry log exists to go with them, so saving would only add an
        // empty session to history. Discard silently, as before.
        LaunchedEffect(current.workoutId, current.elapsedSeconds) {
            container.abandonUnfinishedRide()
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.lg))
            .background(CadenceColors.Bg1)
            .padding(Spacing.s5)
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
                tint = CadenceColors.Fg2,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = if (isFinished) "Ride not saved" else "Unfinished ride",
                style = CadenceType.eyebrow,
                color = CadenceColors.Fg3,
                modifier = Modifier.weight(1f)
            )
            if (dismissable) {
                // Hides the card; the ride stays on disk and the banner is
                // back next launch. Abandoning is the button below, and stays
                // the only thing that throws a ride away.
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Hide until next time",
                    tint = CadenceColors.Fg3,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable {
                            container.dismissedUnfinishedRideStart.value =
                                current.sessionStartTimeEpochMs
                        }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            // A companion's own name for the ride when it gave one, which is
            // how the ride will be listed once it is saved.
            text = current.companionCourse?.name?.takeIf { it.isNotBlank() } ?: workout.name,
            style = CadenceType.h3,
            color = CadenceColors.Fg1,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))

        // Mini progress bar. A free ride and a route ride have nothing to be a
        // fraction of - the segment length is only a backstop - so they report
        // time instead.
        if (!openEnded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(CadenceColors.Bg2)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(CadenceColors.Fg1)
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = when {
                isFinished -> "${formatClock(rideSeconds)} ridden · not yet in your history"
                openEnded -> "${formatClock(current.elapsedSeconds)} ridden · no set finish"
                else -> "${formatClock(current.elapsedSeconds)} / ${formatClock(total)} · $pct% complete"
            },
            style = CadenceType.small,
            color = CadenceColors.Fg3
        )

        Spacer(Modifier.height(Spacing.s4))

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
            total = if (openEnded) null else total,
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
private val BannerButtonShape = CircleShape

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
    /** Planned length, or null for an open-ended ride that never had one. */
    total: Int?,
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
                // 85 % of a phone is a dialog; 85 % of a tablet is a banner.
                // The cap has to come first: fillMaxWidth resolves against the
                // constraints it is handed and passes a fixed width down, so a
                // widthIn after it can only fit inside what it already chose.
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(Radii.lg))
                .background(CadenceColors.Bg1)
                .padding(Spacing.s6),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4)
        ) {
            Text(
                text = if (isFinished) "Discard this ride?" else "Abandon this ride?",
                style = CadenceType.h2,
                color = CadenceColors.Fg1
            )
            Text(
                text = when {
                    isFinished ->
                        "$workoutName - ${formatClock(elapsed)} you already rode. Discarding " +
                            "means it never reaches your history. This can't be undone."
                    total == null ->
                        "$workoutName - the ${formatClock(elapsed)} you rode won't be saved. " +
                            "This can't be undone."
                    else ->
                        "$workoutName - ${formatClock(elapsed)} of ${formatClock(total)} won't be saved. " +
                            "This can't be undone."
                },
                style = CadenceType.body,
                color = CadenceColors.Fg2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
                SecondaryBtn(
                    text = "Keep ride",
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                )
                DestructiveBtn(
                    text = if (isFinished) "Discard" else "Abandon",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
