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
package app.indoorbike.shared.simulation

import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreewheelTest {

    /**
     * Ride [seconds] steadily at [worldSpeedMps] with the legs turning out
     * [driveSpeedMps]: a working road and a bike that is not changing speed.
     */
    private fun Freewheel.rideSteadily(worldSpeedMps: Double, driveSpeedMps: Double, seconds: Double = 15.0) {
        repeat((seconds / 0.25).toInt()) {
            update(
                dtSeconds = 0.25,
                driveSpeedMps = driveSpeedMps,
                cadenceRpm = 85.0,
                worldSpeedMps = worldSpeedMps,
                roadNewtons = 25.0,
                inertiaNewtons = 0.0,
                settled = true,
            )
        }
    }

    @Test
    fun `nothing is held before the rider's gear has been measured`() {
        val f = Freewheel()
        assertFalse(f.calibrated)
        assertEquals(0.0, f.slack(driveSpeedMps = 5.0, worldSpeedMps = 19.0))
        f.absorb(60.0, f.slack(driveSpeedMps = 5.0, worldSpeedMps = 19.0))
        assertEquals(0.0, f.absorbedNewtons)
    }

    @Test
    fun `the coupling is what steady riding says it is`() {
        val f = Freewheel()
        f.rideSteadily(worldSpeedMps = 8.4, driveSpeedMps = 6.0)
        assertTrue(f.calibrated)
        assertEquals(1.4, f.coupling, absoluteTolerance = 1e-9)
    }

    @Test
    fun `legs behind the wheel hold the whole change - legs keeping up hold none`() {
        val f = Freewheel()
        f.rideSteadily(worldSpeedMps = 8.0, driveSpeedMps = 8.0)

        assertEquals(1.0, f.slack(driveSpeedMps = 0.76 * 19.4, worldSpeedMps = 19.4), absoluteTolerance = 1e-9)
        assertEquals(1.0, f.slack(driveSpeedMps = Freewheel.BEHIND * 10.0, worldSpeedMps = 10.0), absoluteTolerance = 1e-9)
        assertEquals(0.5, f.slack(driveSpeedMps = 0.875 * 10.0, worldSpeedMps = 10.0), absoluteTolerance = 1e-9)
        assertEquals(0.0, f.slack(driveSpeedMps = Freewheel.KEEPING_UP * 10.0, worldSpeedMps = 10.0), absoluteTolerance = 1e-9)
        assertEquals(0.0, f.slack(driveSpeedMps = 10.0, worldSpeedMps = 10.0))
        // Ahead of the wheel is keeping up too: that is a rider driving it.
        assertEquals(0.0, f.slack(driveSpeedMps = 12.0, worldSpeedMps = 10.0))
    }

    /**
     * A rider whose own chain sits on a 50x17 turns every virtual gear at
     * about half the world's speed while riding perfectly steadily. Judged
     * against the assumed real gear they would freewheel through every climb.
     */
    @Test
    fun `a taller real gear is not mistaken for legs behind the wheel`() {
        val f = Freewheel()
        f.rideSteadily(worldSpeedMps = 9.0, driveSpeedMps = 9.0 / 1.85)
        assertEquals(0.0, f.slack(driveSpeedMps = 9.0 / 1.85, worldSpeedMps = 9.0), absoluteTolerance = 1e-9)
        // And their legs really behind the wheel still are.
        assertEquals(1.0, f.slack(driveSpeedMps = 0.7 * 19.0 / 1.85, worldSpeedMps = 19.0), absoluteTolerance = 1e-9)
    }

    @Test
    fun `what is held is let go over about a second`() {
        val f = Freewheel()
        f.rideSteadily(worldSpeedMps = 8.0, driveSpeedMps = 8.0)
        f.absorb(60.0, slack = 1.0)
        assertEquals(60.0, f.absorbedNewtons)

        fun tick() = f.update(0.25, 5.0, 85.0, 19.0, roadNewtons = 70.0, inertiaNewtons = -70.0, settled = true)
        repeat(4) { tick() }
        assertEquals(60.0 * exp(-1.0 / Freewheel.ABSORB_SECONDS), f.absorbedNewtons, absoluteTolerance = 1e-9)
        repeat(60) { tick() }
        assertEquals(0.0, f.absorbedNewtons)
    }

    @Test
    fun `a road getting easier is never held`() {
        val f = Freewheel()
        f.rideSteadily(worldSpeedMps = 8.0, driveSpeedMps = 8.0)
        f.absorb(-40.0, slack = 1.0)
        assertEquals(0.0, f.absorbedNewtons)
    }

    /** A rider changes down as a climb starts, and the climb has to reach them. */
    @Test
    fun `nothing is held while the legs catch up with a shift`() {
        val f = Freewheel()
        f.rideSteadily(worldSpeedMps = 8.0, driveSpeedMps = 8.0)
        f.onShift()
        assertEquals(0.0, f.slack(driveSpeedMps = 6.8, worldSpeedMps = 8.0))

        repeat(((Freewheel.SHIFT_GRACE_SECONDS) / 0.25).toInt()) {
            f.update(0.25, 6.8, 85.0, 8.0, roadNewtons = 25.0, inertiaNewtons = 0.0, settled = true)
        }
        assertTrue(f.slack(driveSpeedMps = 6.8, worldSpeedMps = 8.0) > 0.0)
    }

    @Test
    fun `a bike changing speed teaches nothing`() {
        val f = Freewheel()
        repeat(80) {
            f.update(0.25, 6.0, 85.0, 9.0, roadNewtons = 25.0, inertiaNewtons = -10.0, settled = true)
        }
        assertFalse(f.calibrated)
    }

    @Test
    fun `a road not in force teaches nothing`() {
        val f = Freewheel()
        repeat(80) {
            f.update(0.25, 6.0, 85.0, 9.0, roadNewtons = 25.0, inertiaNewtons = 0.0, settled = false)
        }
        assertFalse(f.calibrated)
    }

    @Test
    fun `a release hands everything back and keeps the rider's gear - a reset forgets both`() {
        val f = Freewheel()
        f.rideSteadily(worldSpeedMps = 8.4, driveSpeedMps = 6.0)
        f.absorb(40.0, slack = 1.0)

        f.release()
        assertEquals(0.0, f.absorbedNewtons)
        assertTrue(f.calibrated)
        assertEquals(1.4, f.coupling, absoluteTolerance = 1e-9)

        f.reset()
        assertFalse(f.calibrated)
        assertEquals(1.0, f.coupling, absoluteTolerance = 1e-9)
    }
}
