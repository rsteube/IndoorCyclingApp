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

class IndoorBikeDataTest {

    private fun bytes(vararg ints: Int) = ByteArray(ints.size) { ints[it].toByte() }

    @Test
    fun `speed cadence and power as a smart bike sends them`() {
        // Flags 0x0044: speed present (bit 0 clear), cadence, power.
        // 30.00 km/h, 85 rpm (170 half-rpm), 200 W.
        val data = IndoorBikeData.parse(bytes(0x44, 0x00, 0xB8, 0x0B, 0xAA, 0x00, 0xC8, 0x00))
        assertEquals(IndoorBikeData(powerWatts = 200, cadenceRpm = 85), data)
    }

    @Test
    fun `a More Data packet carries no speed`() {
        // Flags 0x0045: More Data set, so the cadence comes straight after the flags.
        val data = IndoorBikeData.parse(bytes(0x45, 0x00, 0xB4, 0x00, 0x2C, 0x01))
        assertEquals(IndoorBikeData(powerWatts = 300, cadenceRpm = 90), data)
    }

    /**
     * Every field between power and heart rate present, so a walk that got
     * any one of their sizes wrong would read the heart rate from the wrong
     * byte.
     */
    @Test
    fun `heart rate is found behind every field before it`() {
        // Flags: speed (bit 0 clear), avg speed (1), cadence (2), avg cadence (3),
        // distance (4), resistance (5), power (6), avg power (7), energy (8), HR (9).
        val flags = 0x03FE
        val data = IndoorBikeData.parse(
            bytes(
                flags and 0xFF, flags shr 8,
                0x10, 0x0E, // speed
                0x00, 0x0E, // average speed
                0xB0, 0x00, // cadence 88 rpm
                0xA0, 0x00, // average cadence
                0x01, 0x02, 0x00, // distance
                0x0A, 0x00, // resistance level
                0xFA, 0x00, // power 250 W
                0xF0, 0x00, // average power
                0x10, 0x00, 0x20, 0x01, 0x05, // expended energy
                0x92, // heart rate 146
                0x00, 0x00, // metabolic equivalent and beyond, never read
            )
        )
        assertEquals(IndoorBikeData(powerWatts = 250, cadenceRpm = 88, heartRateBpm = 146), data)
    }

    @Test
    fun `a few negative watts while coasting read as zero`() {
        val data = IndoorBikeData.parse(bytes(0x41, 0x00, 0xF9, 0xFF)) // -7 W, no speed
        assertEquals(0, data?.powerWatts)
    }

    @Test
    fun `a heart rate of zero is no reading`() {
        // More Data set, heart rate only.
        val data = IndoorBikeData.parse(bytes(0x01, 0x02, 0x00))
        assertNull(data?.heartRateBpm)
    }

    @Test
    fun `a packet cut short keeps what came before the cut`() {
        // Cadence and power flagged, power's second byte missing.
        val data = IndoorBikeData.parse(bytes(0x45, 0x00, 0xB4, 0x00, 0x2C))
        assertEquals(IndoorBikeData(cadenceRpm = 90), data)
    }

    @Test
    fun `too short to hold its flags is nothing`() {
        assertNull(IndoorBikeData.parse(bytes(0x44)))
    }
}
