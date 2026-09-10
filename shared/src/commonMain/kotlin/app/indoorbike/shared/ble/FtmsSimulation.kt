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

import app.indoorbike.shared.physics.VirtualSpeed
import kotlin.math.roundToInt

/**
 * The road the trainer should pretend it is on, in the units FTMS speaks.
 *
 * This is the other half of ERG mode. In ERG the app names a wattage and the
 * trainer holds it whatever the rider does with their cadence. In simulation
 * mode the app names a *road* - how steep, how rough, how much air - and the
 * trainer applies the resistance that road would, leaving the rider free to
 * push hard or soft, spin or grind, exactly as outdoors.
 *
 * @param windSpeedMps headwind, positive into the rider's face.
 * @param gradePercent road gradient as a percentage, so 6.4 is a 6.4 % climb.
 * @param rollingResistance dimensionless Crr, tyres against tarmac.
 * @param windResistanceKgPerM the aerodynamic coefficient, one half of air
 *   density times drag area.
 */
data class SimulationParameters(
    val windSpeedMps: Double,
    val gradePercent: Double,
    val rollingResistance: Double,
    val windResistanceKgPerM: Double,
) {
    companion object {
        /** The flat, still-air road behind [VirtualSpeed]'s calibration. */
        val FLAT = SimulationParameters(
            windSpeedMps = 0.0,
            gradePercent = 0.0,
            rollingResistance = VirtualSpeed.CRR,
            windResistanceKgPerM = 0.5 * VirtualSpeed.AIR_DENSITY_KG_M3 * VirtualSpeed.CDA_M2,
        )
    }
}

/**
 * Encodes FTMS Set Indoor Bike Simulation Parameters (opcode 0x11).
 *
 * Layout, all little-endian, from the Fitness Machine Service specification:
 *
 * | offset | field             | type   | resolution     |
 * |--------|-------------------|--------|----------------|
 * | 0      | opcode 0x11       | uint8  |                |
 * | 1..2   | wind speed        | sint16 | 0.001 m/s      |
 * | 3..4   | grade             | sint16 | 0.01 %         |
 * | 5      | rolling resistance| uint8  | 0.0001         |
 * | 6      | wind coefficient  | uint8  | 0.01 kg/m      |
 *
 * The two single-byte fields are the tight ones: Crr tops out at 0.0255 and the
 * wind coefficient at 2.55 kg/m. Both are clamped rather than allowed to wrap,
 * because a wrapped byte does not fail loudly - it silently commands a road
 * that is far *easier* than the one the rider is looking at.
 */
object FtmsSimulationCodec {

    const val OP_SET_SIMULATION_PARAMETERS: Byte = 0x11

    /** Encodable extremes of the two byte-wide fields. */
    const val MAX_ROLLING_RESISTANCE = 0.0255
    const val MAX_WIND_RESISTANCE_KG_PER_M = 2.55

    /** Grade is a sint16 at 0.01 % resolution, so this is the whole encodable range. */
    const val MAX_GRADE_PERCENT = 327.67

    /** Wind speed is a sint16 at 0.001 m/s, giving roughly plus or minus 32 m/s. */
    const val MAX_WIND_SPEED_MPS = 32.767

    fun encode(parameters: SimulationParameters): ByteArray {
        val wind = (parameters.windSpeedMps.coerceIn(-MAX_WIND_SPEED_MPS, MAX_WIND_SPEED_MPS) * 1000.0)
            .roundToInt()
        val grade = (parameters.gradePercent.coerceIn(-MAX_GRADE_PERCENT, MAX_GRADE_PERCENT) * 100.0)
            .roundToInt()
        val crr = (parameters.rollingResistance.coerceIn(0.0, MAX_ROLLING_RESISTANCE) * 10_000.0)
            .roundToInt()
        val cw = (parameters.windResistanceKgPerM.coerceIn(0.0, MAX_WIND_RESISTANCE_KG_PER_M) * 100.0)
            .roundToInt()
        return byteArrayOf(
            OP_SET_SIMULATION_PARAMETERS,
            (wind and 0xFF).toByte(),
            ((wind shr 8) and 0xFF).toByte(),
            (grade and 0xFF).toByte(),
            ((grade shr 8) and 0xFF).toByte(),
            (crr and 0xFF).toByte(),
            (cw and 0xFF).toByte(),
        )
    }
}
