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
 * The synthetic workout a companion ride runs against.
 *
 * Same trick as [freeRideWorkout] and [simulationWorkout], for the same payoff:
 * the timer, telemetry, crash recovery, the foreground service, the
 * unfinished-ride banner, history, progression and the FIT/TCX exporters all
 * work with no new plumbing, because as far as every one of them is concerned
 * this is a workout like any other.
 *
 * Unlike a route ride the id carries no identity, because there is nothing to
 * carry: the app does not know which world the companion is showing, and the
 * name the rider sees comes from the companion's own `label` frame rather than
 * from anything reconstructible here. A recovered companion ride therefore
 * resolves to the generic name, which is honest - the app really does not know
 * what was ridden, and inventing a label would be worse than admitting it.
 */

/** Stable id of the synthetic workout every companion ride runs against. */
const val COMPANION_WORKOUT_ID = "companion"

/** Name shown on the HUD and stored on the saved session, absent a label. */
const val COMPANION_NAME = "Companion ride"

/**
 * Nominal intensity for the synthetic segment.
 *
 * Nothing holds the rider to it - the trainer is in simulation mode and the
 * road decides - but the session machinery wants a target to compute a zone
 * colour from before any power arrives. The same 65 % a route ride uses, and
 * for the same reason: it is roughly what an endurance hour costs.
 */
const val COMPANION_NOMINAL_PERCENT_FTP = 65

/**
 * Length of the single segment, and so the hard stop.
 *
 * A companion ride ends when the companion says so or when the rider does, but
 * the timer needs a finite segment to run against. Six hours is past any indoor
 * ride and past the service's own four-hour wake-lock cap, so in practice
 * something else always gets there first.
 */
const val COMPANION_MAX_SECONDS = 6 * 60 * 60

/**
 * The `sport` a saved ride carries when its companion described it as a run
 * (protocol 0.14). Recorded and exported as a bike ride all the same; this is
 * only what the app shows of it.
 */
const val SPORT_RUN = "run"

/** True for a saved ride a companion described as a run. */
val app.indoorbike.shared.db.WorkoutSession.isRun: Boolean
    get() = sport == SPORT_RUN

/** True for the synthetic workout backing a companion ride. */
val Workout.isCompanion: Boolean
    get() = id == COMPANION_WORKOUT_ID

/**
 * The synthetic workout. Deterministic - two calls are `==`, so composables
 * that cache derived state on the workout do not churn.
 */
fun companionWorkout(): Workout = Workout(
    id = COMPANION_WORKOUT_ID,
    name = COMPANION_NAME,
    description = "A ride driven by a companion app on another device.",
    segments = listOf(
        PowerZone(
            lengthInSeconds = COMPANION_MAX_SECONDS,
            powerPercentFTP = COMPANION_NOMINAL_PERCENT_FTP,
            intervalType = "COMPANION",
        )
    ),
)
