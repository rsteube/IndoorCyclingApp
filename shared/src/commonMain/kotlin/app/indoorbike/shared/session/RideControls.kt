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
import app.indoorbike.shared.companion.CompanionEngine
import app.indoorbike.shared.simulation.SimulationEngine
import app.indoorbike.shared.workout.FREE_RIDE_STEP_WATTS
import app.indoorbike.shared.workout.isCompanion
import app.indoorbike.shared.workout.isFreeRide
import app.indoorbike.shared.workout.isSimulation

/**
 * One step of "harder" (+1) or "easier" (-1), from the ride screen's ± buttons
 * or a paired controller, done the way the ride in progress understands it: a
 * virtual gear on a road, the next wattage on a free ride, five percent of the
 * plan on a workout. Shared so a Zwift Click does the same thing on both phones.
 */
fun adjustEffort(
    direction: Int,
    workout: Workout?,
    sessionManager: WorkoutSessionManager,
    simulationEngine: SimulationEngine?,
    companionEngine: CompanionEngine?,
) {
    when {
        workout?.isSimulation == true -> simulationEngine?.shiftBy(direction)
        workout?.isCompanion == true -> companionEngine?.shiftBy(direction)
        workout?.isFreeRide == true -> sessionManager.adjustFreeRideTarget(direction * FREE_RIDE_STEP_WATTS)
        else -> sessionManager.adjustDifficulty(direction * DIFFICULTY_STEP_PERCENT)
    }
}

/** How much one step of a workout's difficulty moves every target. */
const val DIFFICULTY_STEP_PERCENT = 5
