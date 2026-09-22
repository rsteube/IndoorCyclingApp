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

import app.indoorbike.shared.simulation.TrainerWritePacer.Companion.MIN_SEND_INTERVAL_MS
import app.indoorbike.shared.simulation.TrainerWritePacer.Companion.MIN_SHIFT_INTERVAL_MS
import app.indoorbike.shared.simulation.TrainerWritePacer.Companion.REFRESH_INTERVAL_MS
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pace of every write to a trainer's control point.
 *
 * Nothing pinned this before it was pulled out of the two engines, and the one
 * rule that mattered most to the rider was the one that was wrong: a shift was
 * held back by any write at all, so one shift in four waited for the next tick.
 */
class TrainerWritePacerTest {

    private fun pacerThatWrote(atMs: Long, forced: Boolean): TrainerWritePacer =
        TrainerWritePacer().also {
            assertTrue(it.mayWrite(atMs, forced))
            it.wrote(atMs, forced)
        }

    @Test
    fun `routine writes wait for their floor`() {
        val p = pacerThatWrote(atMs = 10_000, forced = false)
        assertFalse(p.mayWrite(10_000 + MIN_SEND_INTERVAL_MS - 1, forced = false))
        assertTrue(p.mayWrite(10_000 + MIN_SEND_INTERVAL_MS, forced = false))
    }

    @Test
    fun `a shift goes at once, however recently the road was written`() {
        val p = pacerThatWrote(atMs = 10_000, forced = false)
        assertTrue(
            p.mayWrite(10_001, forced = true),
            "a shift a millisecond after a routine write was held back",
        )
    }

    @Test
    fun `two shifts inside the floor coalesce into one owed write`() {
        val p = pacerThatWrote(atMs = 10_000, forced = true)
        assertFalse(p.mayWrite(10_000 + MIN_SHIFT_INTERVAL_MS - 1, forced = true))
        assertTrue(p.isOwed, "a refused shift was dropped rather than owed")
    }

    @Test
    fun `an owed write is made up by the next caller once its floor has passed`() {
        val p = pacerThatWrote(atMs = 10_000, forced = true)
        p.mayWrite(10_010, forced = true)
        // The loop's routine tick, which knows nothing of the shift, is what
        // makes it up: an owed write is forced, so it takes the shift's floor.
        assertTrue(p.mayWrite(10_000 + MIN_SHIFT_INTERVAL_MS, forced = false))
        assertTrue(p.mustRestate(10_000 + MIN_SHIFT_INTERVAL_MS, forced = false))
        p.wrote(10_000 + MIN_SHIFT_INTERVAL_MS, forced = false)
        assertFalse(p.isOwed)
    }

    @Test
    fun `an unchanged road is restated after the refresh interval`() {
        val p = pacerThatWrote(atMs = 10_000, forced = false)
        assertFalse(p.mustRestate(10_000 + REFRESH_INTERVAL_MS - 1, forced = false))
        assertTrue(p.mustRestate(10_000 + REFRESH_INTERVAL_MS, forced = false))
    }

    @Test
    fun `a reset lets the next write go at once and forgets what was owed`() {
        val p = pacerThatWrote(atMs = 10_000, forced = true)
        p.mayWrite(10_010, forced = true)
        p.reset()
        assertFalse(p.isOwed)
        assertTrue(p.mayWrite(10_011, forced = false))
    }
}
