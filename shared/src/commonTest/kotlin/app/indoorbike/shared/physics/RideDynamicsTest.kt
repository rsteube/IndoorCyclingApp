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

class RideDynamicsTest {

    private val riderKg = 75f
    private val totalMass = 84.0 // 75 kg rider + 9 kg bike, as VirtualSpeed assumes

    /** Run the integrator until it settles, so a steady state can be measured. */
    private fun settle(
        powerWatts: Double,
        gradient: Double,
        startSpeed: Double = 0.0,
        seconds: Int = 600,
    ): Double {
        var v = startSpeed
        repeat(seconds * 4) {
            v = RideDynamics.step(v, powerWatts, gradient, totalMass, 0.25)
        }
        return v
    }

    @Test
    fun `flat-road speed agrees with the workout HUD`() {
        // The whole point of sharing VirtualSpeed's constants: 200 W on a 0 %
        // road must settle at the number a workout would have shown.
        val simulated = settle(powerWatts = 200.0, gradient = 0.0)
        val hudReadout = VirtualSpeed.speedMps(200.0, riderKg)
        assertTrue(
            abs(simulated - hudReadout) < 0.15,
            "simulated ${simulated * 3.6} km/h vs HUD ${hudReadout * 3.6} km/h",
        )
    }

    @Test
    fun `steady state solver agrees with the integrator on the flat`() {
        for (watts in listOf(100.0, 200.0, 350.0)) {
            val integrated = settle(powerWatts = watts, gradient = 0.0)
            val solved = RideDynamics.steadyStateSpeedMps(watts, 0.0, totalMass)
            assertTrue(
                abs(integrated - solved) < 0.1,
                "$watts W: integrated $integrated vs solved $solved",
            )
        }
    }

    @Test
    fun `steady state solver agrees with the integrator on a climb`() {
        val integrated = settle(powerWatts = 250.0, gradient = 0.08)
        val solved = RideDynamics.steadyStateSpeedMps(250.0, 0.08, totalMass)
        assertTrue(
            abs(integrated - solved) < 0.1,
            "climb: integrated $integrated vs solved $solved",
        )
    }

    @Test
    fun `the same watts go slower uphill and faster downhill`() {
        val uphill = settle(powerWatts = 250.0, gradient = 0.06)
        val flat = settle(powerWatts = 250.0, gradient = 0.0)
        val downhill = settle(powerWatts = 250.0, gradient = -0.06)
        assertTrue(uphill < flat, "uphill $uphill should be slower than flat $flat")
        assertTrue(downhill > flat, "downhill $downhill should be faster than flat $flat")
    }

    @Test
    fun `a climb at a plausible speed`() {
        // 250 W up 8 % for a 75 kg rider is a real-world 10 to 12 km/h.
        val kmh = settle(powerWatts = 250.0, gradient = 0.08) * 3.6
        assertTrue(kmh in 8.0..14.0, "8 % at 250 W came out at $kmh km/h")
    }

    @Test
    fun `momentum carries the rider over a crest`() {
        // Rolling at speed, then stop pedalling on the flat. A second later the
        // bike must still be moving, and clearly slower than it was.
        var v = settle(powerWatts = 250.0, gradient = 0.0)
        val entry = v
        repeat(4) { v = RideDynamics.step(v, 0.0, 0.0, totalMass, 0.25) }
        assertTrue(v > entry * 0.85, "coasting bled too much speed: $entry to $v")
        assertTrue(v < entry, "coasting should slow the rider down")
    }

    @Test
    fun `a descent accelerates a rider who isn't pedalling`() {
        var v = 2.0
        repeat(60) { v = RideDynamics.step(v, 0.0, -0.07, totalMass, 0.25) }
        assertTrue(v > 8.0, "free-wheeling down 7 % only reached ${v * 3.6} km/h")
        assertTrue(
            v <= RideDynamics.MAX_DESCENT_SPEED_MPS,
            "descent exceeded the terminal-speed cap",
        )
    }

    @Test
    fun `a descent is capped rather than unbounded`() {
        var v = 10.0
        repeat(4 * 600) { v = RideDynamics.step(v, 300.0, -0.20, totalMass, 0.25) }
        assertEquals(RideDynamics.MAX_DESCENT_SPEED_MPS, v)
    }

    @Test
    fun `a rider who stops on a climb stops and does not roll backwards`() {
        var v = 4.0
        repeat(4 * 60) { v = RideDynamics.step(v, 0.0, 0.10, totalMass, 0.25) }
        assertEquals(0.0, v)
    }

    @Test
    fun `no drive force means no phantom rolling on the flat`() {
        var v = 0.0
        repeat(40) { v = RideDynamics.step(v, 0.0, 0.0, totalMass, 0.25) }
        assertEquals(0.0, v)
    }

    @Test
    fun `a standing start accelerates briskly but not absurdly`() {
        // The first second at 200 W from a standstill. A real rider is doing a
        // few km/h; without the drive-force cap the P over v term would launch
        // them into double figures.
        var v = 0.0
        repeat(4) { v = RideDynamics.step(v, 200.0, 0.0, totalMass, 0.25) }
        assertTrue(v * 3.6 in 2.0..12.0, "one second off the line reached ${v * 3.6} km/h")
    }

    @Test
    fun `a long gap is integrated as a short one`() {
        // A process frozen for an hour must not teleport the rider: the step is
        // capped, and it is the loop's job to re-anchor.
        val frozen = RideDynamics.step(8.0, 250.0, 0.0, totalMass, 3_600.0)
        val capped = RideDynamics.step(8.0, 250.0, 0.0, totalMass, 2.0)
        assertEquals(capped, frozen)
    }

    @Test
    fun `a gravel road pushes back harder than a tarmac one`() {
        // The whole point of the surface reaching the trainer: at 85 kg, going
        // from tarmac to loose gravel is about fifty watts at 25 km/h, which is
        // what a rider feels when the road turns to stone.
        val tarmac = RideDynamics.roadForceNewtons(0.0, 7.0, totalMass)
        val gravel = RideDynamics.roadForceNewtons(0.0, 7.0, totalMass, crr = 0.0122)
        assertTrue(gravel > tarmac, "gravel ${'$'}gravel against tarmac ${'$'}tarmac")
        val watts = (gravel - tarmac) * 7.0
        assertTrue(watts in 30.0..70.0, "the surface is worth ${'$'}watts W at 25 km/h")
    }

    @Test
    fun `an unhelpful surface is still only a surface`() {
        // It scales with weight and with the cosine of the road, like any
        // rolling resistance, and it does nothing at all to the air.
        val flat = RideDynamics.roadForceNewtons(0.0, 0.0, totalMass, crr = 0.02)
        val climbing = RideDynamics.roadForceNewtons(0.1, 0.0, totalMass, crr = 0.02)
        val gravity = RideDynamics.roadForceNewtons(0.1, 0.0, totalMass, crr = 0.0)
        assertEquals(climbing - gravity, flat / kotlin.math.sqrt(1.01), 0.01)
    }

    @Test
    fun `an unknown rider mass moves nobody`() {
        assertEquals(5.0, RideDynamics.step(5.0, 250.0, 0.0, 0.0, 0.25))
        assertEquals(0.0, RideDynamics.steadyStateSpeedMps(250.0, 0.0, 0.0))
    }
}
