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
package app.indoorbike.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SecureRandomTest {

    @Test
    fun `returns exactly the bytes asked for`() {
        assertEquals(0, secureRandomBytes(0).size)
        assertEquals(1, secureRandomBytes(1).size)
        assertEquals(32, secureRandomBytes(32).size)
    }

    @Test
    fun `a negative count is refused`() {
        assertFailsWith<IllegalArgumentException> { secureRandomBytes(-1) }
    }

    @Test
    fun `the buffer is actually filled`() {
        // 256 zero bits from a working source is a 2^-256 event: this only fails
        // when the platform call wrote nothing.
        assertFalse(secureRandomBytes(32).all { it == 0.toByte() })
    }

    @Test
    fun `two draws differ`() {
        assertFalse(secureRandomBytes(32).contentEquals(secureRandomBytes(32)))
    }
}
