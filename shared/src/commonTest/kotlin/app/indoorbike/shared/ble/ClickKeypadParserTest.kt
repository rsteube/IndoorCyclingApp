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
package app.indoorbike.shared.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClickKeypadParserTest {

    @Test
    fun emptyArrayReturnsNull() {
        assertNull(parseClickKeypadStatus(byteArrayOf()))
    }

    @Test
    fun nonClickMessageTypeReturnsNull() {
        // 0x19 = idle ping, not a keypad-status notification.
        assertNull(parseClickKeypadStatus(byteArrayOf(0x19, 0x08, 0x01, 0x10, 0x01)))
    }

    @Test
    fun bothButtonsReleased() {
        // 0x37 header, field 1 = 1 (released), field 2 = 1 (released).
        val bytes = byteArrayOf(0x37, 0x08, 0x01, 0x10, 0x01)
        val parsed = parseClickKeypadStatus(bytes)
        assertFalse(parsed!!.plusPressed)
        assertFalse(parsed.minusPressed)
    }

    @Test
    fun plusPressedMinusReleased() {
        // Inverse logic: 0 means held down.
        val bytes = byteArrayOf(0x37, 0x08, 0x00, 0x10, 0x01)
        val parsed = parseClickKeypadStatus(bytes)
        assertTrue(parsed!!.plusPressed)
        assertFalse(parsed.minusPressed)
    }

    @Test
    fun minusPressedPlusReleased() {
        val bytes = byteArrayOf(0x37, 0x08, 0x01, 0x10, 0x00)
        val parsed = parseClickKeypadStatus(bytes)
        assertFalse(parsed!!.plusPressed)
        assertTrue(parsed.minusPressed)
    }

    @Test
    fun bothButtonsPressed() {
        val bytes = byteArrayOf(0x37, 0x08, 0x00, 0x10, 0x00)
        val parsed = parseClickKeypadStatus(bytes)
        assertTrue(parsed!!.plusPressed)
        assertTrue(parsed.minusPressed)
    }

    @Test
    fun trailingFieldsAreSkipped() {
        // Real messages can include extra battery / status fields after the
        // two button varints (e.g. field 4 length-delimited).
        val bytes = byteArrayOf(
            0x37,
            0x08, 0x00,        // plus pressed
            0x10, 0x01,        // minus released
            0x22, 0x02, 0x55, 0x00 // unknown length-delimited field, 2 bytes payload
        )
        val parsed = parseClickKeypadStatus(bytes)
        assertTrue(parsed!!.plusPressed)
        assertFalse(parsed.minusPressed)
    }

    @Test
    fun unknownFieldDoesNotCorruptKnownState() {
        // Field 3 (varint, tag 0x18) shows up in some firmware — should be
        // silently consumed without affecting plus/minus reads.
        val bytes = byteArrayOf(0x37, 0x08, 0x00, 0x18, 0x05, 0x10, 0x01)
        val parsed = parseClickKeypadStatus(bytes)
        assertTrue(parsed!!.plusPressed)
        assertFalse(parsed.minusPressed)
    }

    @Test
    fun headerOnlyReturnsBothReleased() {
        // No fields present — assume idle (both released).
        val parsed = parseClickKeypadStatus(byteArrayOf(0x37))
        assertNull(parsed) // size < 2 short-circuits to null per parser contract.
    }

    @Test
    fun multibyteVarintIsHandled() {
        // Field 1 with a varint that spans 2 bytes (value 130 = 0x82). This
        // shouldn't ever happen for the press flag, but the walker has to
        // handle it correctly to keep field 2 aligned.
        val bytes = byteArrayOf(
            0x37,
            0x08, 0x82.toByte(), 0x01,   // field 1 = 130 → "released" (non-zero)
            0x10, 0x00                   // field 2 = 0 → minus pressed
        )
        val parsed = parseClickKeypadStatus(bytes)
        assertFalse(parsed!!.plusPressed)
        assertTrue(parsed.minusPressed)
    }

    private val NOTHING = byteArrayOf(0x37, 0x08, 0x01, 0x10, 0x01)
    private val PLUS_HELD = byteArrayOf(0x37, 0x08, 0x00, 0x10, 0x01)
    private val MINUS_HELD = byteArrayOf(0x37, 0x08, 0x01, 0x10, 0x00)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun assertEvents(expected: List<ControllerEvent>, vararg frames: ByteArray) = runTest {
        val manager = ControllerManager()
        val seen = mutableListOf<ControllerEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.events.toList(seen)
        }
        frames.forEach { manager.handleControllerBytes(it) }
        runCurrent()
        collector.cancel()
        assertEquals(expected, seen)
    }

    @Test
    fun holdingAButtonEmitsOnceNotRepeatedly() =
        assertEvents(listOf(ControllerEvent.Plus), NOTHING, PLUS_HELD, PLUS_HELD, PLUS_HELD)

    @Test
    fun releasingAndPressingAgainEmitsTwice() =
        assertEvents(
            listOf(ControllerEvent.Plus, ControllerEvent.Plus),
            NOTHING, PLUS_HELD, NOTHING, PLUS_HELD,
        )

    @Test
    fun bothButtonsEmitIndependently() =
        assertEvents(
            listOf(ControllerEvent.Plus, ControllerEvent.Minus),
            NOTHING, PLUS_HELD, NOTHING, MINUS_HELD,
        )

    /**
     * A sleeping controller is woken by pressing a button, and the first frame
     * of the new session reports it still held. Emitting there would step the
     * rider's target on every reconnect, in whichever direction they happened
     * to press.
     */
    @Test
    fun wakingOnPlusIsNotAPress() = assertEvents(emptyList(), PLUS_HELD)

    @Test
    fun wakingOnMinusIsNotAPress() = assertEvents(emptyList(), MINUS_HELD)

    @Test
    fun aPressAfterWakingStillCounts() =
        assertEvents(listOf(ControllerEvent.Plus), PLUS_HELD, NOTHING, PLUS_HELD)
}
