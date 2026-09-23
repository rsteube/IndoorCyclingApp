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
package app.indoorbike.shared.ble.zwift

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplayWindowTest {

    private fun ReplayWindow.take(counter: Long): Boolean =
        isFresh(counter).also { if (it) accept(counter) }

    @Test
    fun `each counter is accepted once`() {
        val w = ReplayWindow()
        assertTrue(w.take(0))
        assertTrue(w.take(1))
        assertFalse(w.take(1), "a replayed frame was accepted")
        assertFalse(w.take(0))
    }

    /** Two channels: a frame from one can land after a later one from the other. */
    @Test
    fun `a late frame inside the window is still fresh`() {
        val w = ReplayWindow()
        assertTrue(w.take(10))
        assertTrue(w.take(12))
        assertTrue(w.take(11))
        assertFalse(w.take(11))
    }

    @Test
    fun `anything older than the window is refused`() {
        val w = ReplayWindow()
        assertTrue(w.take(5))
        assertTrue(w.take(5 + ReplayWindow.SIZE.toLong()))
        assertFalse(w.isFresh(5))
        assertFalse(w.isFresh(4))
    }

    @Test
    fun `a jump forward keeps nothing from before it`() {
        val w = ReplayWindow()
        assertTrue(w.take(3))
        assertTrue(w.take(1_000))
        assertTrue(w.take(999))
        assertFalse(w.take(1_000))
    }
}
