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
import kotlin.test.assertTrue

class FtmsSimulationCodecTest {

    private fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

    /** The two little-endian sint16 fields, sign-extended back to Int. */
    private fun ByteArray.s16(index: Int): Int {
        val raw = u(index) or (u(index + 1) shl 8)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    @Test
    fun `encodes a climb the way the spec says`() {
        val bytes = FtmsSimulationCodec.encode(
            SimulationParameters(
                windSpeedMps = 0.0,
                gradePercent = 6.4,
                rollingResistance = 0.005,
                windResistanceKgPerM = 0.22,
            )
        )
        assertEquals(7, bytes.size)
        assertEquals(0x11, bytes.u(0))
        assertEquals(0, bytes.s16(1))     // wind speed
        assertEquals(640, bytes.s16(3))   // 6.4 % at 0.01 % resolution
        assertEquals(50, bytes.u(5))      // 0.005 at 0.0001 resolution
        assertEquals(22, bytes.u(6))      // 0.22 kg/m at 0.01 resolution
    }

    @Test
    fun `a descent encodes as a negative grade`() {
        val bytes = FtmsSimulationCodec.encode(
            SimulationParameters(0.0, -8.25, 0.005, 0.22)
        )
        assertEquals(-825, bytes.s16(3))
        // Two's complement, little-endian: -825 is 0xFCC7.
        assertEquals(0xC7, bytes.u(3))
        assertEquals(0xFC, bytes.u(4))
    }

    @Test
    fun `a headwind encodes as a positive wind speed`() {
        val bytes = FtmsSimulationCodec.encode(
            SimulationParameters(3.5, 0.0, 0.005, 0.22)
        )
        assertEquals(3500, bytes.s16(1))
    }

    @Test
    fun `the byte-wide fields clamp instead of wrapping`() {
        // A hard gear on a rough road can ask for more Crr than a uint8 at
        // 0.0001 can hold. Wrapping would silently command an *easier* road,
        // which is the worst possible failure: the rider feels nothing and
        // nothing reports an error.
        val bytes = FtmsSimulationCodec.encode(
            SimulationParameters(
                windSpeedMps = 0.0,
                gradePercent = 5.0,
                rollingResistance = 0.5,
                windResistanceKgPerM = 40.0,
            )
        )
        assertEquals(255, bytes.u(5))
        assertEquals(255, bytes.u(6))
    }

    @Test
    fun `negative coefficients clamp to zero`() {
        val bytes = FtmsSimulationCodec.encode(
            SimulationParameters(0.0, 0.0, -1.0, -1.0)
        )
        assertEquals(0, bytes.u(5))
        assertEquals(0, bytes.u(6))
    }

    @Test
    fun `an absurd gradient stays inside the encodable range`() {
        val up = FtmsSimulationCodec.encode(SimulationParameters(0.0, 9_999.0, 0.005, 0.22))
        val down = FtmsSimulationCodec.encode(SimulationParameters(0.0, -9_999.0, 0.005, 0.22))
        assertEquals(32_767, up.s16(3))
        assertEquals(-32_767, down.s16(3))
    }

    @Test
    fun `the flat road is the one the workout HUD is calibrated to`() {
        val bytes = FtmsSimulationCodec.encode(SimulationParameters.FLAT)
        assertEquals(0, bytes.s16(3))
        assertEquals(50, bytes.u(5))
        // 0.5 * 1.225 * 0.36 = 0.2205 kg/m, which rounds to 22 units.
        assertEquals(22, bytes.u(6))
        assertTrue(SimulationParameters.FLAT.windSpeedMps == 0.0)
    }
}
