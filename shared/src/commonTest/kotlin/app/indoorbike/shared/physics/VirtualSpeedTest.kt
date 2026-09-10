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
package app.indoorbike.shared.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VirtualSpeedTest {

    private val rider = 75f

    @Test
    fun noPowerMeansNoSpeed() {
        assertEquals(0.0, VirtualSpeed.speedMps(0.0, rider))
        assertEquals(0.0, VirtualSpeed.speedMps(-50.0, rider))
    }

    @Test
    fun noWeightMeansNoEstimate() {
        // Without a mass to move there is no answer to give, and a confident
        // zero is better than a made-up number.
        assertEquals(0.0, VirtualSpeed.speedMps(250.0, riderKg = 0f))
        assertEquals(0.0, VirtualSpeed.distanceMeters(List(600) { 250L }, riderKg = 0f))
    }

    /**
     * The real invariant: whatever speed comes back, feeding it through the
     * power model has to give the watts we asked about. This checks the solver
     * rather than a table of magic numbers, so the constants can be retuned
     * without rewriting the test.
     */
    @Test
    fun solvedSpeedReproducesTheInputPower() {
        for (watts in listOf(50, 100, 200, 300, 500, 900)) {
            val v = VirtualSpeed.speedMps(watts.toDouble(), rider)
            // Same model as VirtualSpeed, spelled out independently.
            val mass = rider + VirtualSpeed.DEFAULT_BIKE_MASS_KG
            val rebuilt = (0.005 * mass * 9.8067 * v + 0.5 * 1.225 * 0.36 * v * v * v) / 0.97
            assertTrue(
                abs(rebuilt - watts) < 0.01,
                "$watts W solved to $v m/s, which models back to $rebuilt W",
            )
        }
    }

    @Test
    fun landsInTheBandTheOutdoorEquivalentsUse() {
        val kmh = VirtualSpeed.speedKmh(200, rider)
        assertTrue(kmh in 30.0..34.0, "200 W for a 75 kg rider should be about 32 km/h, got $kmh")
    }

    @Test
    fun moreePowerIsFasterAndMoreMassIsSlower() {
        assertTrue(VirtualSpeed.speedMps(300.0, rider) > VirtualSpeed.speedMps(200.0, rider))
        assertTrue(VirtualSpeed.speedMps(200.0, 95f) < VirtualSpeed.speedMps(200.0, 60f))
    }

    @Test
    fun distanceIsSpeedHeldForEachSamplesSecond() {
        val oneHourAt200 = List(3600) { 200L }
        val expected = VirtualSpeed.speedMps(200.0, rider) * 3600
        assertTrue(abs(VirtualSpeed.distanceMeters(oneHourAt200, rider) - expected) < 1e-6)
    }

    /**
     * Speed goes as roughly the cube root of power, so the same average watts
     * spent in surges covers less ground than spending them steadily. This is
     * why distance integrates per sample instead of being derived from the
     * ride's average power.
     */
    @Test
    fun surgingCoversLessGroundThanRidingSteadily() {
        val surges = List(1800) { if (it % 2 == 0) 400L else 0L }
        val steady = List(1800) { 200L }
        assertTrue(
            VirtualSpeed.distanceMeters(surges, rider) < VirtualSpeed.distanceMeters(steady, rider),
        )
    }

    @Test
    fun averageSpeedIsDistanceOverTime() {
        assertEquals(36.0, VirtualSpeed.averageSpeedKmh(distanceMeters = 10_000.0, durationSeconds = 1000))
        assertEquals(0.0, VirtualSpeed.averageSpeedKmh(distanceMeters = 10_000.0, durationSeconds = 0))
    }
}
