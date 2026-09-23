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
package app.indoorbike.shared.session

import app.indoorbike.shared.Workout
import app.indoorbike.shared.workout.isCompanion
import app.indoorbike.shared.workout.isFreeRide
import app.indoorbike.shared.workout.isSimulation

/**
 * What the unfinished-ride banner says about a ride the app was stopped in the
 * middle of: whether there is anything left to ride, and how far it got.
 *
 * Both apps show that banner, and the rules for which of its two offers it
 * makes - save the ride, or carry on with it - live here so they can't drift.
 */
data class UnfinishedRide(
    val snapshot: ActiveSessionSnapshot,
    val workout: Workout,
    /**
     * No planned length to be a fraction of: a free ride, a route ride and a
     * companion ride each have a six-hour backstop segment instead.
     */
    val openEnded: Boolean,
    /**
     * Nothing left to ride, so the banner offers to save the ride rather than
     * continue it: the app died between the finish and the summary, or the
     * rider backed out of it. A companion ride is always finished, since
     * resuming it would put the rider back on a HUD whose game is gone.
     */
    val isFinished: Boolean,
    /** The plan's length, or null for an [openEnded] ride. */
    val plannedSeconds: Int?,
    /** Time actually pedalled, which is what a saved ride reports. */
    val rideSeconds: Int,
    /** How far through the plan, 0..1; 0 for an [openEnded] ride. */
    val progress: Float,
)

fun describeUnfinishedRide(snapshot: ActiveSessionSnapshot, workout: Workout): UnfinishedRide {
    val openEnded = workout.isFreeRide || workout.isSimulation || workout.isCompanion
    val total = workout.segments.sumOf { it.lengthInSeconds }
    val isFinished = workout.isCompanion ||
        snapshot.state == WorkoutState.Completed ||
        snapshot.currentSegmentIndex >= workout.segments.size ||
        snapshot.elapsedSeconds >= total
    return UnfinishedRide(
        snapshot = snapshot,
        workout = workout,
        openEnded = openEnded,
        isFinished = isFinished,
        plannedSeconds = if (openEnded) null else total,
        // Older snapshots default it to 0, so fall back to the planned elapsed.
        rideSeconds = snapshot.actualRideSeconds.takeIf { it > 0 } ?: snapshot.elapsedSeconds,
        progress = if (openEnded) 0f else (snapshot.elapsedSeconds.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f),
    )
}
