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
package app.indoorbike.shared.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RideEndWatchTest {

    /** Feed a run of states and collect which of them counted as an ending. */
    private fun endingsIn(vararg states: WorkoutState): Int {
        val watch = RideEndWatch()
        return states.count { watch.ended(it) }
    }

    @Test
    fun `a ride that runs and finishes ends exactly once`() {
        assertEquals(
            1,
            endingsIn(
                WorkoutState.Standby,
                WorkoutState.Active,
                WorkoutState.Paused,
                WorkoutState.Active,
                WorkoutState.Completed,
            ),
        )
    }

    @Test
    fun `the previous ride's Completed is not this watch's ending`() {
        // The regression. A watch started while the session still holds the
        // last ride's Completed - which is every visit to the pairing screen
        // between two rides - must not take that as an ending and tear down
        // what it has just set up.
        val watch = RideEndWatch()
        assertFalse(watch.ended(WorkoutState.Completed), "a stale Completed is not an ending")
    }

    @Test
    fun `a stale Completed does not stop the next real ride from ending`() {
        val watch = RideEndWatch()
        watch.ended(WorkoutState.Completed)
        assertFalse(watch.ended(WorkoutState.Standby))
        assertFalse(watch.ended(WorkoutState.Active))
        assertTrue(watch.ended(WorkoutState.Completed), "the ride it did watch has ended")
    }

    @Test
    fun `two rides in a row end once each`() {
        val watch = RideEndWatch()
        val run = listOf(
            WorkoutState.Standby, WorkoutState.Active, WorkoutState.Completed,
            WorkoutState.Standby, WorkoutState.Active, WorkoutState.Completed,
        )
        assertEquals(2, run.count { watch.ended(it) })
    }

    @Test
    fun `a repeated Completed does not end the same ride twice`() {
        val watch = RideEndWatch()
        watch.ended(WorkoutState.Active)
        assertTrue(watch.ended(WorkoutState.Completed))
        assertFalse(watch.ended(WorkoutState.Completed), "the same ending, delivered again")
    }

    @Test
    fun `a ride abandoned to Idle never ends`() {
        // Backing out does not complete anything, so nothing downstream should
        // be told a ride finished. The states still arm the watch, though: the
        // ride after this one must be able to end normally.
        val watch = RideEndWatch()
        assertEquals(
            0,
            listOf(WorkoutState.Standby, WorkoutState.Active, WorkoutState.Idle)
                .count { watch.ended(it) },
        )
        assertTrue(watch.ended(WorkoutState.Completed))
    }

    @Test
    fun `every state that is not Completed arms the watch`() {
        // Named one by one rather than looped, so adding a state to the enum
        // is a decision here and not a silent inheritance.
        for (state in listOf(
            WorkoutState.Idle,
            WorkoutState.Standby,
            WorkoutState.Countdown,
            WorkoutState.Active,
            WorkoutState.Paused,
        )) {
            val watch = RideEndWatch()
            assertFalse(watch.ended(state), "$state is not an ending")
            assertTrue(watch.ended(WorkoutState.Completed), "$state should arm the watch")
        }
    }
}
