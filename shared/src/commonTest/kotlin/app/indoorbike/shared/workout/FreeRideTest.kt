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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FreeRideTest {

    private val library = listOf(
        Workout(
            id = "starter-15min",
            name = "First spin",
            segments = listOf(PowerZone(600, 55, "CONSTANT")),
        )
    )

    @Test
    fun startsAt40PercentOfFtp() {
        assertEquals(80, freeRideStartWatts(200))
        assertEquals(100, freeRideStartWatts(250))
        assertEquals(126, freeRideStartWatts(316))
    }

    @Test
    fun startingWattsNeverDropBelowWhatATrainerCanHold() {
        // A rider who hasn't set an FTP yet (or set an implausibly low one)
        // still has to get a target the trainer will actually track.
        assertEquals(FREE_RIDE_MIN_WATTS, freeRideStartWatts(0))
        assertEquals(FREE_RIDE_MIN_WATTS, freeRideStartWatts(50))
    }

    @Test
    fun steppingClampsToTheAllowedRange() {
        assertEquals(FREE_RIDE_MIN_WATTS, clampFreeRideWatts(FREE_RIDE_MIN_WATTS - 10))
        assertEquals(FREE_RIDE_MIN_WATTS, clampFreeRideWatts(-500))
        assertEquals(FREE_RIDE_MAX_WATTS, clampFreeRideWatts(FREE_RIDE_MAX_WATTS + 10))
        assertEquals(175, clampFreeRideWatts(175))
    }

    @Test
    fun theSyntheticWorkoutIsStableAcrossCalls() {
        // Composables cache derived state (the ride backdrop's terrain) on the
        // workout, so two free rides must not look like two different workouts.
        assertEquals(freeRideWorkout(), freeRideWorkout())
        assertEquals(FREE_RIDE_WORKOUT_ID, freeRideWorkout().id)
        assertEquals(1, freeRideWorkout().segments.size)
        assertEquals(FREE_RIDE_START_PERCENT_FTP, freeRideWorkout().segments.first().powerPercentFTP)
    }

    @Test
    fun theSingleSegmentBacksTheRideForHours() {
        // It is a backstop, not a plan: the rider is the one who ends the ride.
        assertEquals(FREE_RIDE_MAX_SECONDS, freeRideWorkout().segments.first().lengthInSeconds)
        assertTrue(FREE_RIDE_MAX_SECONDS >= 4 * 60 * 60)
    }

    @Test
    fun onlyTheSyntheticWorkoutIsAFreeRide() {
        assertTrue(freeRideWorkout().isFreeRide)
        assertFalse(library.first().isFreeRide)
    }

    @Test
    fun aPersistedFreeRideResolvesEvenThoughItIsNotInTheLibrary() {
        // Crash recovery needs the workout back, and a free ride's is
        // synthesised per ride rather than stored - a plain library lookup
        // would drop a recoverable session on the floor.
        assertEquals(freeRideWorkout(), resolveRideWorkout(FREE_RIDE_WORKOUT_ID, emptyList()))
        assertEquals(library.first(), resolveRideWorkout("starter-15min", library))
        assertNull(resolveRideWorkout("deleted-workout", library))
    }
}
