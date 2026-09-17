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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The envelope around a companion's power target, at a hundred times real
 * speed. Both rules here exist for a rider who has stopped being able to argue
 * with the machine: a game that hung, and a pair of legs that gave up.
 */
class PowerHoldTest {

    /** Run the hold forward in 250 ms ticks, the engine's own rate. */
    private fun PowerHold.run(
        fromMs: Long,
        forMs: Long,
        cadenceRpm: Int,
        worldAgeAt: (Long) -> Long?,
    ): Int {
        var now = fromMs
        val end = fromMs + forMs
        while (now <= end) {
            update(now, cadenceRpm, worldAgeAt(now))
            now += 250L
        }
        return commanded
    }

    @Test
    fun `a fresh target opens from the rider's own power over two seconds`() {
        val hold = PowerHold()
        hold.set(125, riderWatts = 65, nowMs = 0)

        assertEquals(65, hold.commanded, "opens where the rider already is")
        assertEquals(95, hold.update(nowMs = 1_000, cadenceRpm = 85, worldAgeMs = 0))
        assertEquals(125, hold.update(nowMs = 2_000, cadenceRpm = 85, worldAgeMs = 0))
        assertEquals(125, hold.update(nowMs = 9_000, cadenceRpm = 85, worldAgeMs = 0))
    }

    @Test
    fun `a rider already pushing harder than the target is not ramped up to it`() {
        val hold = PowerHold()
        hold.set(125, riderWatts = 300, nowMs = 0)

        assertEquals(125, hold.commanded)
    }

    @Test
    fun `a game that goes quiet decays the target to zero and gives it back`() {
        val hold = PowerHold()
        hold.set(200, riderWatts = 200, nowMs = 0)
        hold.run(0, 3_000, cadenceRpm = 85) { 0L }
        assertEquals(200, hold.commanded)

        // The lease is 1500 ms, then a 2000 ms ramp. At 3 s since the last
        // frame the rider is still being asked for about a quarter of it.
        val lastFrameAt = 3_000L
        hold.run(3_000, 3_000, cadenceRpm = 85) { it - lastFrameAt }
        val atThreeSeconds = hold.commanded
        assertTrue(atThreeSeconds in 40..60, "a quarter of the way down, not off: $atThreeSeconds")

        hold.run(6_000, 1_000, cadenceRpm = 85) { it - lastFrameAt }
        assertEquals(0, hold.commanded, "3.5 s after the last frame the rider is let go")

        // Frames come back: the target returns over the same 2000 ms rather
        // than snapping on under a rider who has stopped expecting it.
        hold.update(nowMs = 7_100, cadenceRpm = 85, worldAgeMs = 0)
        assertTrue(hold.commanded < 200, "does not snap back")
        hold.run(7_100, 2_000, cadenceRpm = 85) { 0L }
        assertEquals(200, hold.commanded)
    }

    @Test
    fun `a rider who stops pedalling is let go and takes the target back when they pedal`() {
        val hold = PowerHold()
        hold.set(150, riderWatts = 150, nowMs = 0)
        hold.run(0, 3_000, cadenceRpm = 85) { 0L }
        assertEquals(150, hold.commanded)

        // Stopped, but not for long enough yet: ERG is still holding them.
        hold.run(3_000, 1_500, cadenceRpm = 0) { 0L }
        assertEquals(150, hold.commanded)
        assertFalse(hold.released)

        // Past two seconds the brake comes off outright. Easing a rider off
        // something they are already stalled against helps nobody.
        hold.run(4_500, 1_000, cadenceRpm = 0) { 0L }
        assertEquals(0, hold.commanded)
        assertTrue(hold.released)
        assertEquals(150, hold.target, "the target is still what the game asked for")

        // Turning the cranks again brings it back over three seconds.
        hold.update(nowMs = 6_000, cadenceRpm = 40, worldAgeMs = 0)
        assertTrue(hold.commanded < 150)
        hold.run(6_000, 3_000, cadenceRpm = 40) { 0L }
        assertEquals(150, hold.commanded)
        assertFalse(hold.released)
    }

    @Test
    fun `a soft pedal between the two thresholds neither releases nor takes back`() {
        val hold = PowerHold()
        hold.set(150, riderWatts = 150, nowMs = 0)
        hold.run(0, 3_000, cadenceRpm = 85) { 0L }
        hold.run(3_000, 4_000, cadenceRpm = 0) { 0L }
        assertEquals(0, hold.commanded)

        // 25 rpm is neither stopped nor riding: nothing moves either way.
        hold.run(7_000, 4_000, cadenceRpm = 25) { 0L }
        assertEquals(0, hold.commanded)
        hold.run(11_000, 3_000, cadenceRpm = 35) { 0L }
        assertEquals(150, hold.commanded)
    }

    @Test
    fun `a companion that has said nothing about the road yet is not decayed`() {
        // No world frame has ever arrived, which is not the same as one that
        // has gone stale: there is nothing to decay from.
        val hold = PowerHold()
        hold.set(100, riderWatts = 100, nowMs = 0)
        hold.run(0, 10_000, cadenceRpm = 85) { null }

        assertEquals(100, hold.commanded)
    }

    @Test
    fun `releasing the target commands nothing at all`() {
        val hold = PowerHold()
        hold.set(220, riderWatts = 220, nowMs = 0)
        hold.run(0, 3_000, cadenceRpm = 90) { 0L }

        hold.set(null, riderWatts = 220, nowMs = 3_000)
        assertEquals(null, hold.target)
        assertEquals(0, hold.commanded)
        assertEquals(0, hold.update(nowMs = 4_000, cadenceRpm = 90, worldAgeMs = 0))
        assertFalse(hold.released, "nothing is held, so nobody has been let go of it")
    }
}
