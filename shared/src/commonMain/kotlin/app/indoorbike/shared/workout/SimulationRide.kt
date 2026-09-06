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
package app.indoorbike.shared.workout

import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout

/**
 * "Simulation": riding a real-world route, with the road rather than a plan
 * deciding what the legs are asked for.
 *
 * Modelled as a synthetic single-segment [Workout] for the same reason
 * [freeRideWorkout] is - every piece of ride machinery already keys off an
 * active workout, and a third kind of session would mean touching the timer,
 * the telemetry capture, the crash-recovery snapshot, the foreground service
 * and the save-to-history path to teach each of them about routes. The
 * synthetic workout keeps all of that working unchanged.
 *
 * What is different from a free ride is where the ride ends. A free ride ends
 * when the rider says so; a simulated one ends at the end of the route, which
 * is [app.indoorbike.shared.simulation.SimulationEngine]'s business rather than
 * the clock's. The segment length here is only the same six-hour backstop a
 * free ride uses, so a forgotten session cannot pin a wake lock forever.
 *
 * The id carries the route with it ("simulation:alpe-test"), so a ride
 * recovered after a crash can find its way back to the same road. The route
 * itself is not in the workout: it is loaded by the platform's route catalog,
 * which is where the GPX lives.
 */

/** Marks a workout id as a simulated route ride. */
const val SIMULATION_ID_PREFIX = "simulation:"

/** Where the trainer's resistance comes from, for the HUD's mode label. */
const val SIMULATION_NAME = "Simulation"

/**
 * Hard stop on an open-ended route ride. Six hours is past any indoor ride and
 * past the foreground service's own four-hour wake-lock cap, so in practice
 * the route always runs out first.
 */
const val SIMULATION_MAX_SECONDS = 6 * 60 * 60

/**
 * Nominal effort of the synthetic segment. Nothing holds the rider to it - the
 * trainer is in simulation mode, not ERG - but the HUD colours its furniture by
 * the segment's zone, and endurance pace is the honest description of a route
 * ride taken as a whole.
 */
const val SIMULATION_NOMINAL_PERCENT_FTP = 65

/** The workout id a ride down [routeId] runs against. */
fun simulationWorkoutId(routeId: String): String = SIMULATION_ID_PREFIX + routeId

/** The route behind a simulation workout id, or null if it isn't one. */
fun simulationRouteId(workoutId: String): String? =
    if (workoutId.startsWith(SIMULATION_ID_PREFIX)) {
        workoutId.removePrefix(SIMULATION_ID_PREFIX).takeIf { it.isNotEmpty() }
    } else {
        null
    }

/** True for the synthetic workout backing a route simulation. */
val Workout.isSimulation: Boolean
    get() = id.startsWith(SIMULATION_ID_PREFIX)

/**
 * The synthetic workout for a ride down [routeId]. Deterministic - two calls
 * with the same arguments are `==`, so composables that cache derived state on
 * the workout don't churn.
 */
fun simulationWorkout(routeId: String, routeName: String): Workout = Workout(
    id = simulationWorkoutId(routeId),
    name = routeName,
    description = "Simulated route ride. The road sets the resistance, you set the pace.",
    segments = listOf(
        PowerZone(
            lengthInSeconds = SIMULATION_MAX_SECONDS,
            powerPercentFTP = SIMULATION_NOMINAL_PERCENT_FTP,
            intervalType = "SIM",
        )
    ),
)
