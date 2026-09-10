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
package app.indoorbike.shared.workout

import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout

/**
 * "Just ride": an open-ended ERG session with no plan behind it. The trainer
 * starts at [FREE_RIDE_START_PERCENT_FTP] of the rider's FTP and holds whatever
 * the rider dials in with the HUD's ± buttons until they end the ride.
 *
 * It is modelled as a synthetic single-segment [Workout] rather than as a
 * second kind of session, so every piece of ride machinery that already keys
 * off an active workout keeps working unchanged: the timer, telemetry capture,
 * the crash-recovery snapshot, the foreground service, and the save-to-history
 * path. What the segment's own power says is deliberately *not* what the
 * trainer holds - that comes from `WorkoutSessionManager.freeRideTargetWatts`,
 * which is the whole point of the mode.
 *
 * The synthetic workout is never added to the rider's library, so lookups by
 * id (history's "Ride again", the FIT exporter's per-segment laps) miss it and
 * fall back to their no-workout behaviour, which is the right answer for a ride
 * that had no plan. [resolveRideWorkout] is the one place that has to see
 * through that, because crash recovery genuinely needs the workout back.
 */

/** Stable id of the synthetic workout every free ride runs against. */
const val FREE_RIDE_WORKOUT_ID = "free-ride"

/** Name shown on the HUD and stored on the saved session. */
const val FREE_RIDE_NAME = "Just Ride"

/** Where the trainer starts: an easy spin the rider builds up from. */
const val FREE_RIDE_START_PERCENT_FTP = 40

/** One tap of the HUD's ± stepper. */
const val FREE_RIDE_STEP_WATTS = 10

/**
 * Floor for the ERG target. Below roughly this, trainers stop tracking a
 * commanded wattage at all, so letting the stepper go lower would only produce
 * taps that appear to do nothing.
 */
const val FREE_RIDE_MIN_WATTS = 30

/** Ceiling, well above any wattage anyone holds in a steady-state ERG ride. */
const val FREE_RIDE_MAX_WATTS = 1_000

/**
 * Length of the single segment, and so the hard stop on an open-ended ride.
 *
 * The ride is meant to run until the rider ends it, but the timer needs *some*
 * finite segment to run against, and an unbounded one would leave a forgotten
 * session pinning a wake lock indefinitely. Six hours is past any indoor ride
 * and past the service's own four-hour wake-lock cap, so in practice the rider
 * always gets there first.
 */
const val FREE_RIDE_MAX_SECONDS = 6 * 60 * 60

/** True for the synthetic workout backing a free ride. */
val Workout.isFreeRide: Boolean
    get() = id == FREE_RIDE_WORKOUT_ID

/**
 * The synthetic workout. Deterministic - two calls are `==`, so composables
 * that cache derived state on the workout (the ride backdrop) don't churn.
 */
fun freeRideWorkout(): Workout = Workout(
    id = FREE_RIDE_WORKOUT_ID,
    name = FREE_RIDE_NAME,
    description = "Open-ended ride. Set your own power, stop when you're done.",
    segments = listOf(
        PowerZone(
            lengthInSeconds = FREE_RIDE_MAX_SECONDS,
            powerPercentFTP = FREE_RIDE_START_PERCENT_FTP,
            intervalType = "FREE",
        )
    ),
)

/** Opening ERG target for a rider whose FTP is [ftp], clamped to the stepper's range. */
fun freeRideStartWatts(ftp: Int): Int =
    (ftp * FREE_RIDE_START_PERCENT_FTP / 100).coerceIn(FREE_RIDE_MIN_WATTS, FREE_RIDE_MAX_WATTS)

/** Applies one or more stepper taps, keeping the target inside the allowed range. */
fun clampFreeRideWatts(watts: Int): Int =
    watts.coerceIn(FREE_RIDE_MIN_WATTS, FREE_RIDE_MAX_WATTS)

/**
 * Find the workout a persisted ride belongs to.
 *
 * Neither of the two plan-less modes keeps its workout in [library] - both
 * synthesise one per ride - so a plain lookup would fail and the
 * unfinished-ride banner would silently drop a recoverable session.
 *
 * [routeNames] lets a simulated ride recover its route's name for the banner.
 * An id that isn't in it still resolves, just labelled with the raw route id:
 * the ride is recoverable either way, and refusing to resolve it would lose a
 * real session over a missing string.
 */
fun resolveRideWorkout(
    workoutId: String,
    library: List<Workout>,
    routeNames: Map<String, String> = emptyMap(),
): Workout? {
    if (workoutId == FREE_RIDE_WORKOUT_ID) return freeRideWorkout()
    val routeId = simulationRouteId(workoutId)
    if (routeId != null) {
        return simulationWorkout(routeId, routeNames[routeId] ?: routeId)
    }
    return library.find { it.id == workoutId }
}
