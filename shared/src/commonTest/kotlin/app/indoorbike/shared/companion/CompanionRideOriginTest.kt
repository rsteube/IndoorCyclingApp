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
package app.indoorbike.shared.companion

import app.indoorbike.shared.simulation.Drivetrain
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each ride of a session is measured from where that ride started.
 *
 * `distanceM` is defined as a cumulative total, so a game running four laps in
 * one sitting is entitled to count straight through all of them. Every lap is
 * its own row in history, and a lap recorded as everything ridden since the
 * game launched is wrong in the direction that flatters the rider, which is the
 * worst direction for a training record to be wrong in.
 */
class CompanionRideOriginTest {

    private fun engine() = CompanionEngine(
        powerWatts = { 200 },
        cadenceRpm = { 90 },
        applyRoad = {},
        riderMassKg = { 75f },
        bikeMassKg = { 9f },
        drivetrainProvider = { Drivetrain.DEFAULT },
    ).also { it.load() }

    /** What a ride actually covered, the way the ride record computes it. */
    private fun CompanionEngine.ridden(): Double =
        distanceMeters.value - rideStartMeters.value

    @Test
    fun `a game that counts through the session records each ride separately`() {
        val e = engine()

        e.markRideStart()
        e.setProgress(distanceM = 0.0, ascentM = 0.0)
        e.setProgress(distanceM = 5_000.0, ascentM = 60.0)
        assertEquals(5_000.0, e.ridden(), "the first ride covered all of it")

        // Second lap, same counter carrying on from where the first left off.
        e.markRideStart()
        e.setProgress(distanceM = 5_000.0, ascentM = 60.0)
        e.setProgress(distanceM = 7_500.0, ascentM = 90.0)
        assertEquals(
            2_500.0,
            e.ridden(),
            "the second ride is 2.5 km, not the 7.5 km the game has counted",
        )
        assertEquals(
            30.0,
            e.elevationGainMeters.value - e.rideStartElevationGainMeters.value,
            "and it climbed 30 m, not 90",
        )
    }

    @Test
    fun `a game that restarts its counter each ride is unaffected`() {
        val e = engine()

        e.markRideStart()
        e.setProgress(distanceM = 0.0, ascentM = 0.0)
        e.setProgress(distanceM = 3_000.0, ascentM = 20.0)
        assertEquals(3_000.0, e.ridden())

        // The engine is cleared between rides, which is what a real session
        // does, and this game starts counting again from nothing too.
        e.load()
        e.markRideStart()
        e.setProgress(distanceM = 0.0, ascentM = 0.0)
        e.setProgress(distanceM = 1_200.0, ascentM = 8.0)
        assertEquals(1_200.0, e.ridden())
    }

    @Test
    fun `the first report after a start is the origin whatever number it carries`() {
        // The rule, stated on its own, because it is the one thing here that
        // could reasonably have been decided the other way.
        //
        // The engine cannot tell a counter that is ride-relative from one that
        // runs for the whole session, and between two rides it has been reset
        // while the game's has not. So it trusts the game's own first word
        // after the start rather than anything it held before. The cost is
        // whatever the rider covered between the start and that first frame,
        // which at the 4 to 10 Hz the protocol asks for is metres. The
        // alternative cost was recording an entire session as one lap.
        val e = engine()
        e.markRideStart()
        e.setProgress(distanceM = 42_195.0, ascentM = 500.0)
        assertEquals(0.0, e.ridden(), "nothing has been covered since that frame yet")

        e.setProgress(distanceM = 43_195.0, ascentM = 510.0)
        assertEquals(1_000.0, e.ridden())
    }

    @Test
    fun `the origin is the first report after the ride starts not the last before it`() {
        val e = engine()
        // Rolling around a lobby before the game says the ride has begun. None
        // of it belongs to the ride that is about to start.
        e.setProgress(distanceM = 400.0, ascentM = 3.0)

        e.markRideStart()
        e.setProgress(distanceM = 400.0, ascentM = 3.0)
        e.setProgress(distanceM = 1_400.0, ascentM = 13.0)
        assertEquals(1_000.0, e.ridden(), "the warm-up lap is not part of the ride")
    }

    @Test
    fun `a ride nobody reported progress for covers nothing`() {
        val e = engine()
        e.markRideStart()
        assertEquals(0.0, e.ridden())
    }

    @Test
    fun `a resume does not re-base the ride`() {
        val e = engine()
        e.markRideStart()
        e.setProgress(distanceM = 0.0, ascentM = 0.0)
        e.setProgress(distanceM = 2_000.0, ascentM = 10.0)
        // A pause and a resume are not a new ride, so nothing marks a start and
        // the distance already covered stays part of it.
        e.setProgress(distanceM = 3_000.0, ascentM = 15.0)
        assertEquals(3_000.0, e.ridden())
    }

    @Test
    fun `load clears the origin along with everything else`() {
        val e = engine()
        e.markRideStart()
        e.setProgress(distanceM = 9_000.0, ascentM = 100.0)
        e.load()
        assertEquals(0.0, e.rideStartMeters.value)
        assertEquals(0.0, e.rideStartElevationGainMeters.value)
        assertEquals(0.0, e.ridden())
    }
}
