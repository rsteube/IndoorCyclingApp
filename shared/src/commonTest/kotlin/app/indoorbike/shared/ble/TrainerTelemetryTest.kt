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

/**
 * Which of a trainer's reports reach the ride, through [TrainerManager]'s
 * own handlers.
 */
class TrainerTelemetryTest {

    private fun bytes(vararg ints: Int) = ByteArray(ints.size) { ints[it].toByte() }

    /** Indoor Bike Data with no speed: cadence and power. */
    private fun bikeData(watts: Int, rpm: Int) = bytes(
        0x45, 0x00,
        (rpm * 2) and 0xFF, (rpm * 2) shr 8,
        watts and 0xFF, (watts shr 8) and 0xFF,
    )

    /** Cycling Power Measurement with nothing but power. */
    private fun cyclingPower(watts: Int) = bytes(0x00, 0x00, watts and 0xFF, (watts shr 8) and 0xFF)

    /**
     * The bug this was rebuilt for. On a machine with no Cycling Power
     * service, Indoor Bike Data's power was only taken while the current value
     * was zero, so the first real reading was the last one ever taken, and a
     * ride on a smart bike recorded 150 W from start to finish.
     */
    @Test
    fun `a machine with only Indoor Bike Data is followed reading by reading`() {
        val tm = TrainerManager()
        tm.onIndoorBikeData(bikeData(watts = 0, rpm = 0), atMs = 1_000)
        tm.onIndoorBikeData(bikeData(watts = 150, rpm = 80), atMs = 2_000)
        tm.onIndoorBikeData(bikeData(watts = 220, rpm = 92), atMs = 3_000)
        assertEquals(220, tm.power.value)
        assertEquals(92, tm.cadence.value)

        tm.onIndoorBikeData(bikeData(watts = 0, rpm = 0), atMs = 4_000)
        assertEquals(0, tm.power.value, "a rider who stopped kept their last wattage")
    }

    /**
     * A Kickr sends both. While its power service speaks, that is the power:
     * including when it honestly says zero, which the old rule let a lagging
     * Indoor Bike Data value overwrite.
     */
    @Test
    fun `a trainer's power service outranks its Indoor Bike Data while it speaks`() {
        val tm = TrainerManager()
        tm.onCyclingPower(cyclingPower(250), atMs = 10_000)
        tm.onIndoorBikeData(bikeData(watts = 240, rpm = 90), atMs = 10_100)
        assertEquals(250, tm.power.value)

        tm.onCyclingPower(cyclingPower(0), atMs = 11_000)
        tm.onIndoorBikeData(bikeData(watts = 240, rpm = 90), atMs = 11_100)
        assertEquals(0, tm.power.value)
    }

    @Test
    fun `a power service that falls silent hands over to Indoor Bike Data`() {
        val tm = TrainerManager()
        tm.onCyclingPower(cyclingPower(250), atMs = 10_000)
        tm.onIndoorBikeData(bikeData(watts = 180, rpm = 85), atMs = 10_000 + TelemetrySources.STALE_AFTER_MS + 1)
        assertEquals(180, tm.power.value)
    }

    /** A power meter with no crank sensor knows nothing about cadence, however much it speaks. */
    @Test
    fun `cadence comes from Indoor Bike Data when the power service has no crank`() {
        val tm = TrainerManager()
        tm.onCyclingPower(cyclingPower(200), atMs = 5_000)
        tm.onIndoorBikeData(bikeData(watts = 190, rpm = 87), atMs = 5_100)
        assertEquals(200, tm.power.value)
        assertEquals(87, tm.cadence.value)
    }

    @Test
    fun `a heart rate from the grips is used when nothing better is speaking`() {
        val tm = TrainerManager()
        // More Data, power and heart rate: 160 W, 132 bpm.
        tm.onIndoorBikeData(bytes(0x41, 0x02, 0xA0, 0x00, 0x84), atMs = 1_000)
        assertEquals(132, tm.heartRate.value)

        // A heart-rate service on the trainer outranks it while it speaks.
        tm.onHeartRate(bytes(0x00, 0x90), atMs = 2_000)
        tm.onIndoorBikeData(bytes(0x41, 0x02, 0xA0, 0x00, 0x84), atMs = 2_100)
        assertEquals(0x90, tm.heartRate.value)
    }

    @Test
    fun `negative watts from a power service read as zero`() {
        val tm = TrainerManager()
        tm.onCyclingPower(bytes(0x00, 0x00, 0xF6, 0xFF), atMs = 1_000) // -10 W
        assertEquals(0, tm.power.value)
    }
}
