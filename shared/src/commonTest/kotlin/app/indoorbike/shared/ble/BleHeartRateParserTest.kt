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
package app.indoorbike.shared.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BleHeartRateParserTest {

    @Test
    fun emptyArrayReturnsNull() {
        assertNull(parseHeartRateMeasurement(byteArrayOf()))
    }

    @Test
    fun uint8FormatExtractsBpm() {
        // flags=0x00 (uint8), bpm=72
        val bytes = byteArrayOf(0x00, 72)
        assertEquals(72, parseHeartRateMeasurement(bytes))
    }

    @Test
    fun uint8MaxValueIs255() {
        // flags=0x00, bpm=0xFF — should be parsed as unsigned (255), not -1.
        val bytes = byteArrayOf(0x00, 0xFF.toByte())
        assertEquals(255, parseHeartRateMeasurement(bytes))
    }

    @Test
    fun uint8FormatWithMissingByteReturnsNull() {
        // flags=0x00 says uint8 follows but there's no second byte.
        assertNull(parseHeartRateMeasurement(byteArrayOf(0x00)))
    }

    @Test
    fun uint16FormatExtractsLittleEndianBpm() {
        // flags=0x01 (uint16), bpm low=0x90, high=0x01 → 0x0190 = 400
        val bytes = byteArrayOf(0x01, 0x90.toByte(), 0x01)
        assertEquals(400, parseHeartRateMeasurement(bytes))
    }

    @Test
    fun uint16FormatWithTruncatedHighByteReturnsNull() {
        // flags=0x01 says uint16 follows but only one byte after.
        assertNull(parseHeartRateMeasurement(byteArrayOf(0x01, 0x50)))
    }

    @Test
    fun extraBytesAfterBpmAreIgnored() {
        // Real packets append energy expended / RR intervals; we only need BPM.
        val bytes = byteArrayOf(0x00, 0x4B, 0x10, 0x00, 0x20, 0x00)  // bpm=75, then noise
        assertEquals(75, parseHeartRateMeasurement(bytes))
    }

    @Test
    fun uint16FlagPicksFormatRegardlessOfOtherFlagBits() {
        // Other flag bits (e.g. sensor-contact, energy-expended-present) shouldn't
        // change which byte format we read. Bit 0 is the only one we care about.
        // flags=0b00010001 (0x11) — uint16 + sensor contact bits set.
        val bytes = byteArrayOf(0x11, 0x50, 0x00)  // bpm=80
        assertEquals(80, parseHeartRateMeasurement(bytes))
    }
}
