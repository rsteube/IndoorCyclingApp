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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A hand off the line, and then out of the way. */
class LaunchAssistTest {

    private val above = LaunchAssist.THRESHOLD_MPS + 0.5
    private val below = LaunchAssist.THRESHOLD_MPS - 0.2

    @Test
    fun `a standing start is discounted`() {
        val a = LaunchAssist()
        assertEquals(LaunchAssist.ASSIST_FACTOR, a.factor)
        assertEquals(LaunchAssist.ASSIST_FACTOR, a.update(speedMps = 0.0, dtSeconds = 0.25))
        assertTrue(a.assisting)
    }

    @Test
    fun `the discount holds until the rider is actually moving`() {
        val a = LaunchAssist()
        repeat(40) { a.update(speedMps = below, dtSeconds = 0.25) }
        assertEquals(LaunchAssist.ASSIST_FACTOR, a.factor)
    }

    @Test
    fun `crossing the threshold hands back the physics over ten seconds`() {
        val a = LaunchAssist()
        a.update(speedMps = above, dtSeconds = 0.25)
        // The crossing itself changes nothing: the fade starts here, it does
        // not jump here.
        assertEquals(LaunchAssist.ASSIST_FACTOR, a.factor, absoluteTolerance = 1e-9)

        repeat(20) { a.update(speedMps = above, dtSeconds = 0.25) }
        val halfway = a.factor
        assertTrue(
            halfway > LaunchAssist.ASSIST_FACTOR && halfway < 1.0,
            "five seconds in, the factor was $halfway",
        )

        repeat(24) { a.update(speedMps = above, dtSeconds = 0.25) }
        assertEquals(1.0, a.factor, absoluteTolerance = 1e-9)
        assertFalse(a.assisting)
    }

    @Test
    fun `the fade only ever goes one way`() {
        val a = LaunchAssist()
        var previous = a.update(speedMps = above, dtSeconds = 0.25)
        repeat(60) {
            val now = a.update(speedMps = above, dtSeconds = 0.25)
            assertTrue(now >= previous - 1e-12, "the factor went backwards: $previous to $now")
            previous = now
        }
        assertEquals(1.0, previous, absoluteTolerance = 1e-9)
    }

    /**
     * The case that decides whether this is a launch assist or just a discount
     * on hills. A rider grinding up a wall is under the threshold and must not
     * be handed the help back.
     */
    @Test
    fun `a slow climb is not a standing start`() {
        val a = LaunchAssist()
        repeat(60) { a.update(speedMps = above, dtSeconds = 0.25) }
        assertEquals(1.0, a.factor, absoluteTolerance = 1e-9)

        // Four km/h up something horrible, for a minute.
        repeat(240) { a.update(speedMps = 4.0 / 3.6, dtSeconds = 0.25) }
        assertEquals(1.0, a.factor, absoluteTolerance = 1e-9)
        assertFalse(a.assisting)
    }

    @Test
    fun `stopping properly arms it again for the next pull away`() {
        val a = LaunchAssist()
        repeat(60) { a.update(speedMps = above, dtSeconds = 0.25) }
        assertEquals(1.0, a.factor, absoluteTolerance = 1e-9)

        a.update(speedMps = 0.0, dtSeconds = 0.25)
        assertEquals(LaunchAssist.ASSIST_FACTOR, a.factor)
        assertTrue(a.assisting)
    }

    @Test
    fun `a fade interrupted by a stop starts over rather than resuming`() {
        val a = LaunchAssist()
        a.update(speedMps = above, dtSeconds = 0.25)
        repeat(8) { a.update(speedMps = above, dtSeconds = 0.25) }
        assertTrue(a.factor > LaunchAssist.ASSIST_FACTOR)

        a.update(speedMps = 0.0, dtSeconds = 0.25)
        assertEquals(LaunchAssist.ASSIST_FACTOR, a.factor)

        // And the full ten seconds are owed again.
        a.update(speedMps = above, dtSeconds = 0.25)
        repeat(20) { a.update(speedMps = above, dtSeconds = 0.25) }
        assertTrue(a.factor < 1.0, "the second fade finished early: ${a.factor}")
    }

    @Test
    fun `a stalled tick cannot fast-forward the fade`() {
        val a = LaunchAssist()
        a.update(speedMps = above, dtSeconds = 0.25)
        // An app backgrounded for an hour must not come back at full physics
        // having skipped the hand-off entirely.
        a.update(speedMps = above, dtSeconds = 3_600.0)
        assertTrue(a.factor < 1.0, "one stalled tick finished the fade: ${a.factor}")
    }

    @Test
    fun `resetting puts the help back`() {
        val a = LaunchAssist()
        repeat(60) { a.update(speedMps = above, dtSeconds = 0.25) }
        a.reset()
        assertEquals(LaunchAssist.ASSIST_FACTOR, a.factor)
        assertTrue(a.assisting)
    }
}
