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
package app.indoorbike.shared.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The fourth force of the road load starts here: how fast the speed is changing. */
class RoadAccelerationTest {

    @Test
    fun `the first sample only anchors`() {
        val a = RoadAcceleration()
        assertEquals(0.0, a.update(speedMps = 9.0, dtSeconds = 0.25))
    }

    @Test
    fun `a steady speed is not an acceleration`() {
        val a = RoadAcceleration()
        repeat(20) { a.update(speedMps = 9.0, dtSeconds = 0.25) }
        assertEquals(0.0, a.mps2, absoluteTolerance = 1e-9)
    }

    @Test
    fun `a sustained acceleration converges on the real one`() {
        val a = RoadAcceleration()
        var v = 5.0
        // 0.6 m/s^2 held for four seconds: a firm acceleration out of a corner.
        repeat(16) {
            v += 0.6 * 0.25
            a.update(speedMps = v, dtSeconds = 0.25)
        }
        assertEquals(0.6, a.mps2, absoluteTolerance = 0.05)
    }

    @Test
    fun `slowing down reports a negative acceleration`() {
        val a = RoadAcceleration()
        var v = 12.0
        repeat(16) {
            v -= 0.4 * 0.25
            a.update(speedMps = v, dtSeconds = 0.25)
        }
        assertTrue(a.mps2 < -0.2, "coasting down reported ${a.mps2} m/s^2")
    }

    /**
     * A companion that holds its speed between world frames must not read as a
     * train of spikes with nothing in between.
     */
    @Test
    fun `a stepped signal is smoothed rather than spiking`() {
        val a = RoadAcceleration()
        var v = 8.0
        var peak = 0.0
        repeat(24) { i ->
            // One 0.4 m/s step per second, sampled four times a second.
            if (i % 4 == 0) v += 0.4
            a.update(speedMps = v, dtSeconds = 0.25)
            if (a.mps2 > peak) peak = a.mps2
        }
        // The true average is 0.4 m/s^2. Raw differencing would have shown 1.6
        // on every fourth sample and zero on the rest.
        assertTrue(peak < 0.9, "the filter passed a $peak m/s^2 spike through")
        assertTrue(a.mps2 > 0.2, "the filter swallowed the acceleration entirely")
    }

    @Test
    fun `a long gap re-anchors rather than inventing an acceleration`() {
        val a = RoadAcceleration()
        a.update(speedMps = 0.0, dtSeconds = 0.25)
        // A ride resumed after the process was suspended, or a companion that
        // teleported its rider. No bicycle did this.
        assertEquals(0.0, a.update(speedMps = 20.0, dtSeconds = 600.0))
        assertEquals(0.0, a.mps2)
    }

    @Test
    fun `resetting forgets the momentum a pause left behind`() {
        val a = RoadAcceleration()
        var v = 5.0
        repeat(16) {
            v += 0.6 * 0.25
            a.update(speedMps = v, dtSeconds = 0.25)
        }
        assertTrue(a.mps2 > 0.3)
        a.reset()
        assertEquals(0.0, a.mps2)
    }
}
