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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulationRideTest {

    private val library = listOf(
        Workout(
            id = "starter-15min",
            name = "Starter",
            segments = listOf(PowerZone(900, 60, "STEADY")),
        )
    )

    @Test
    fun `the route survives a round trip through the workout id`() {
        val id = simulationWorkoutId("alpe-test")
        assertEquals("simulation:alpe-test", id)
        assertEquals("alpe-test", simulationRouteId(id))
    }

    @Test
    fun `only simulation ids carry a route`() {
        assertNull(simulationRouteId(FREE_RIDE_WORKOUT_ID))
        assertNull(simulationRouteId("starter-15min"))
        // A prefix with nothing after it names no route.
        assertNull(simulationRouteId(SIMULATION_ID_PREFIX))
    }

    @Test
    fun `the synthetic workout is recognisable and deterministic`() {
        val a = simulationWorkout("demo-loop", "Demo Loop")
        val b = simulationWorkout("demo-loop", "Demo Loop")
        assertEquals(a, b)
        assertTrue(a.isSimulation)
        assertEquals("Demo Loop", a.name)
        assertFalse(a.isFreeRide)
        assertFalse(freeRideWorkout().isSimulation)
        assertFalse(library.first().isSimulation)
    }

    @Test
    fun `the ride is bounded by the route, not by the clock`() {
        // The single segment is only the wake-lock backstop, so it must be long
        // enough that no real route ride reaches it.
        val workout = simulationWorkout("demo-loop", "Demo Loop")
        assertEquals(1, workout.segments.size)
        assertEquals(SIMULATION_MAX_SECONDS, workout.segments.first().lengthInSeconds)
        assertTrue(SIMULATION_MAX_SECONDS >= 6 * 60 * 60)
    }

    @Test
    fun `a crashed route ride resolves back to its workout`() {
        val id = simulationWorkoutId("demo-loop")
        val recovered = assertNotNull(
            resolveRideWorkout(id, library, mapOf("demo-loop" to "Demo Loop"))
        )
        assertEquals(id, recovered.id)
        assertEquals("Demo Loop", recovered.name)
    }

    @Test
    fun `a route whose name is unknown still resolves`() {
        // Losing an hour of real riding because a display string went missing
        // would be a bad trade.
        val recovered = assertNotNull(
            resolveRideWorkout(simulationWorkoutId("mystery"), library)
        )
        assertEquals("mystery", recovered.name)
    }

    @Test
    fun `resolving still handles the other two modes`() {
        assertEquals(freeRideWorkout(), resolveRideWorkout(FREE_RIDE_WORKOUT_ID, library))
        assertEquals(library.first(), resolveRideWorkout("starter-15min", library))
        assertNull(resolveRideWorkout("deleted-workout", library))
    }
}
