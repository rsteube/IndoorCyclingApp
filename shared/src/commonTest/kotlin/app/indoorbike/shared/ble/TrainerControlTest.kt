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
import app.indoorbike.shared.simulation.Drivetrain
import app.indoorbike.shared.simulation.VirtualDrivetrain
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainerControlTest {

    private fun bytes(vararg ints: Int) = ByteArray(ints.size) { ints[it].toByte() }

    // What a machine says it takes ─────────────────────────────────────────

    @Test
    fun `the target-setting half of the feature characteristic is read`() {
        // Machine features (ignored), then targets: resistance (bit 2),
        // power (bit 3) and simulation (bit 13).
        val all = FtmsTargets.parse(bytes(0xFF, 0xFF, 0x00, 0x00, 0x0C, 0x20, 0x00, 0x00))
        assertEquals(FtmsTargets(power = true, resistance = true, simulation = true), all)

        val resistanceOnly = FtmsTargets.parse(bytes(0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00))
        assertEquals(FtmsTargets(power = false, resistance = true, simulation = false), resistanceOnly)

        assertNull(FtmsTargets.parse(bytes(0x00, 0x00, 0x00, 0x00)))
    }

    @Test
    fun `the resistance range is in tenths`() {
        // 1.0 to 32.0 in steps of 1.0.
        assertEquals(
            ResistanceRange(1.0, 32.0, 1.0),
            ResistanceRange.parse(bytes(0x0A, 0x00, 0x40, 0x01, 0x0A, 0x00)),
        )
        // A machine that says its smallest step is zero steps in tenths.
        assertEquals(0.1, ResistanceRange.parse(bytes(0x00, 0x00, 0xE8, 0x03, 0x00, 0x00))?.increment)
        // Backwards is no range to steer in.
        assertNull(ResistanceRange.parse(bytes(0x40, 0x01, 0x0A, 0x00, 0x0A, 0x00)))
    }

    @Test
    fun `a resistance level goes out in one byte or two`() {
        assertContentEquals(bytes(0x04, 125), FtmsResistanceCodec.encode(12.5, wide = false))
        assertContentEquals(bytes(0x04, 0x2C, 0x01), FtmsResistanceCodec.encode(30.0, wide = true))

        assertFalse(FtmsResistanceCodec.startsWide(ResistanceRange(0.0, 25.5, 0.1)))
        assertTrue(FtmsResistanceCodec.startsWide(ResistanceRange(1.0, 32.0, 1.0)))
    }

    // How each command reaches it ──────────────────────────────────────────

    private val range = ResistanceRange(1.0, 32.0, 1.0)

    private fun control(power: Boolean, resistance: Boolean, simulation: Boolean, range: ResistanceRange? = this.range) =
        TrainerControl(FtmsTargets(power, resistance, simulation), range, hasControlPoint = true)

    @Test
    fun `a smart trainer takes both commands as themselves`() {
        val c = control(power = true, resistance = true, simulation = true)
        assertEquals(ControlPath.NATIVE, c.power)
        assertEquals(ControlPath.NATIVE, c.road)
    }

    @Test
    fun `a trainer with ERG but no simulation rides a road as ERG`() {
        val c = control(power = true, resistance = false, simulation = false)
        assertEquals(ControlPath.NATIVE, c.power)
        assertEquals(ControlPath.VIA_POWER, c.road)
    }

    @Test
    fun `a bike with only a resistance level is steered through it in every mode`() {
        val c = control(power = false, resistance = true, simulation = false)
        assertEquals(ControlPath.VIA_RESISTANCE, c.power)
        assertEquals(ControlPath.VIA_RESISTANCE, c.road)
    }

    @Test
    fun `a resistance level with no known range is not steered into`() {
        val c = control(power = false, resistance = true, simulation = false, range = null)
        assertEquals(ControlPath.MANUAL, c.power)
        assertEquals(ControlPath.MANUAL, c.road)
    }

    @Test
    fun `a machine with no control point is ridden by hand`() {
        assertEquals(ControlPath.MANUAL, TrainerControl.NONE.power)
        assertEquals(ControlPath.MANUAL, TrainerControl.NONE.road)
    }

    @Test
    fun `a refusal moves a command to the next way down`() {
        val trainer = control(power = true, resistance = true, simulation = true)
        assertEquals(ControlPath.VIA_POWER, trainer.withoutSimulation().road)
        assertEquals(ControlPath.VIA_RESISTANCE, trainer.withoutSimulation().withoutPower().road)
        assertEquals(ControlPath.MANUAL, trainer.withoutSimulation().withoutPower().withoutResistance().road)
    }

    // A road as a wattage ──────────────────────────────────────────────────

    @Test
    fun `a road asks for its force at the speed the rider's legs turn the trainer`() {
        // 30 N, sent the way the virtual drivetrain sends a force: as a grade
        // against the assumed mass, with nothing else.
        val force = 30.0
        val road = SimulationParameters(
            windSpeedMps = 0.0,
            gradePercent = force / (VirtualDrivetrain.ASSUMED_TRAINER_MASS_KG * VirtualSpeed.GRAVITY_MS2) * 100.0,
            rollingResistance = 0.0,
            windResistanceKgPerM = 0.0,
        )
        val speedAt90 = 90 / 60.0 * Drivetrain.ASSUMED_REAL_DEVELOPMENT_M
        assertEquals(force * speedAt90, RoadAsPower.watts(road, 90).toDouble(), absoluteTolerance = 1.0)
    }

    /** Which is what makes it a road rather than a fixed wattage. */
    @Test
    fun `spinning faster on the same road costs more`() {
        val road = SimulationParameters(0.0, gradePercent = 4.0, rollingResistance = 0.0, windResistanceKgPerM = 0.0)
        val at60 = RoadAsPower.watts(road, 60)
        val at90 = RoadAsPower.watts(road, 90)
        assertEquals(1.5, at90.toDouble() / at60, absoluteTolerance = 0.02)
    }

    @Test
    fun `no cadence and a descent both ask for nothing`() {
        val climb = SimulationParameters(0.0, 6.0, 0.004, 0.0)
        assertEquals(0, RoadAsPower.watts(climb, 0))
        val descent = SimulationParameters(0.0, -8.0, 0.0, 0.0)
        assertEquals(0, RoadAsPower.watts(descent, 90))
    }

    /**
     * End to end: the grade the virtual drivetrain writes, held as ERG, asks
     * the rider for exactly the force it commanded at the speed it assumed.
     * The two assumptions (mass, real development) cancel, so a trainer
     * without simulation gets the same road a trainer with it does.
     */
    @Test
    fun `the drivetrain's own road comes back as the force it commanded`() {
        val roads = mutableListOf<SimulationParameters>()
        val gearing = VirtualDrivetrain(
            riderMassKg = { 75f },
            bikeMassKg = { 9f },
            cadenceRpm = { 88 },
            applyRoad = { roads += it },
        )
        gearing.load(Drivetrain.ROAD, Drivetrain.ROAD.startIndex)
        gearing.setRoad(gradient = 0.05, worldSpeedMps = 4.5)
        repeat(40) { gearing.update(0.25) }
        assertTrue(gearing.writeRoad(forced = true, nowMs = 1_000_000))

        val expected = gearing.commandedForceN.value * 88 / 60.0 * Drivetrain.ASSUMED_REAL_DEVELOPMENT_M
        assertEquals(expected, RoadAsPower.watts(roads.last(), 88).toDouble(), absoluteTolerance = 1.0)
    }
}
