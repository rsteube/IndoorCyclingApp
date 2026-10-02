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
package app.indoorbike.shared.simulation

import app.indoorbike.shared.ble.SimulationParameters
import app.indoorbike.shared.physics.RideDynamics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What one shift does to the legs, pinned against what a bicycle does.
 *
 * The reference throughout is the instant after a shift on a real bike: the
 * bike has not changed speed, so the force at the wheel has not moved, and the
 * pedals meet it through the new ratio. Pedal force changes by exactly the
 * ratio between the two gears, on any terrain.
 */
class VirtualDrivetrainTest {

    private class Rig(
        var cadence: Int = 85,
        val drivetrain: Drivetrain = Drivetrain.GRAVEL,
        sprocket: Int = 15,
    ) {
        val roads = mutableListOf<SimulationParameters>()
        val gearing = VirtualDrivetrain(
            riderMassKg = { 75f },
            bikeMassKg = { 9f },
            cadenceRpm = { cadence },
            applyRoad = { roads += it },
        ).also { it.load(drivetrain, drivetrain.gears.indexOfFirst { g -> g.sprocket == sprocket }) }

        val gear: Int get() = gearing.gearIndex.value

        fun indexOf(sprocket: Int): Int = drivetrain.gears.indexOfFirst { it.sprocket == sprocket }

        fun development(sprocket: Int): Double = drivetrain.developmentMeters(indexOf(sprocket))

        /** Hold a steady road long enough for every filter to settle. */
        fun settle(gradient: Double, speedMps: Double) {
            gearing.setRoad(gradient = gradient, worldSpeedMps = speedMps)
            repeat(60) { gearing.update(0.25) }
        }

        fun forceNow(): Double = gearing.trainerForceNewtons(gear)

        private var nowMs = 1_000_000L

        /** One quarter-second tick, in the order the engines run it. */
        fun tick(gradient: Double, speedMps: Double) {
            gearing.setRoad(gradient = gradient, worldSpeedMps = speedMps)
            gearing.update(0.25)
            nowMs += 250
            gearing.writeRoad(forced = false, nowMs = nowMs)
        }

        fun ride(gradient: Double, speedMps: Double, seconds: Double) {
            repeat((seconds / 0.25).toInt()) { tick(gradient, speedMps) }
        }
    }

    /** Gravity and rolling for the rig's 84 kg: the part of a road that a gradient changes. */
    private fun gradeForce(gradient: Double): Double =
        RideDynamics.roadForceNewtons(gradient = gradient, speedMps = 0.0, totalMassKg = 84.0)

    /**
     * A rider whose gear the freewheel has measured: 28 km/h on the flat in
     * the 40x17 at 90 rpm, long enough for the launch assist to finish and the
     * coupling to be learned. Their legs are turning exactly with the wheel.
     */
    private fun calibrated(): Rig {
        val r = Rig(cadence = 90, sprocket = 17)
        r.ride(gradient = 0.0, speedMps = CRUISE, seconds = 30.0)
        assertTrue(r.gearing.freewheelCalibrated)
        return r
    }

    /**
     * The same rider in the hardest gear at 70 km/h on a -10 % descent,
     * turning [cadence]. The 40x10 needs 132 rpm to reach the wheel there.
     */
    private fun descending(cadence: Int): Rig {
        val r = calibrated()
        r.gearing.shiftBy(+20)
        r.cadence = cadence
        r.ride(gradient = -0.10, speedMps = FAST, seconds = 8.0)
        return r
    }

    /**
     * The report this was rebuilt for: on a 1x gravel bike at 30 km/h, the
     * fourth-hardest gear to the third felt like a wall. A 15 to a 13 is a 15 %
     * step, and that is what the legs should meet. The model this replaced asked
     * for 45 %, because it evaluated the air at the speed the new gear would turn
     * out before the bike had had any chance to reach it.
     */
    @Test
    fun `one sprocket at speed is the ratio not its cube`() {
        val r = Rig(sprocket = 15)
        r.settle(gradient = 0.0, speedMps = 30 / 3.6)
        val before = r.forceNow()

        r.gearing.shiftBy(+1)
        val after = r.forceNow()

        val ratio = r.development(13) / r.development(15)
        assertEquals(ratio, after / before, absoluteTolerance = 0.005)
        assertTrue(after / before < 1.2, "one sprocket asked for ${(after / before - 1) * 100} % more")
    }

    @Test
    fun `a shift is the same ratio on a climb as on the flat`() {
        fun step(gradient: Double, speedMps: Double): Double {
            val r = Rig(sprocket = 28)
            r.settle(gradient, speedMps)
            val before = r.forceNow()
            r.gearing.shiftBy(+1)
            return r.forceNow() / before
        }
        val flat = step(gradient = 0.0, speedMps = 9.0)
        val climb = step(gradient = 0.08, speedMps = 3.9)
        assertEquals(flat, climb, absoluteTolerance = 0.005)
    }

    /**
     * The legs speeding up or slowing down at the same road speed is the rider
     * changing how they deliver their power, not the road getting harder. The
     * cadence reaches the telemetry and the HUD; it does not reach the force.
     */
    @Test
    fun `the road is evaluated at the bike's speed not at the legs'`() {
        val r = Rig(cadence = 90, sprocket = 17)
        r.settle(gradient = 0.02, speedMps = 7.0)
        val at90 = r.forceNow()

        r.cadence = 60
        repeat(20) { r.gearing.update(0.25) }
        assertEquals(at90, r.forceNow(), absoluteTolerance = 1e-9)
    }

    @Test
    fun `an upshift lands on its ratio at once with nothing to wait for`() {
        val r = Rig(sprocket = 15)
        r.settle(gradient = 0.0, speedMps = 8.0)
        val settledInThirteen = r.gearing.trainerForceNewtons(r.indexOf(13))

        r.gearing.shiftBy(+1)
        assertEquals(settledInThirteen, r.forceNow(), absoluteTolerance = 1e-9)
        repeat(12) { r.gearing.update(0.25) }
        assertEquals(settledInThirteen, r.forceNow(), absoluteTolerance = 1e-9)
    }

    /**
     * On a bike the legs spin up the instant a downshift lands, because the
     * bike's momentum drives them. A trainer cannot do that, so a downshift
     * lets go of a little more than its ratio for a moment, while the legs
     * catch up, and then settles on exactly the gear's own force.
     */
    @Test
    fun `a downshift lets go of more than its ratio then settles on it`() {
        val r = Rig(sprocket = 13)
        r.settle(gradient = 0.04, speedMps = 5.0)
        val settledInFifteen = r.gearing.trainerForceNewtons(r.indexOf(15))

        r.gearing.shiftBy(-1)
        val justAfter = r.forceNow()
        assertEquals(
            settledInFifteen * r.development(15) / r.development(13),
            justAfter,
            absoluteTolerance = 1e-6,
        )

        repeat(4) { r.gearing.update(0.25) } // one second
        val oneSecondOn = r.forceNow()
        assertTrue(oneSecondOn > justAfter, "the release never relaxed")
        assertTrue(oneSecondOn < settledInFifteen, "the release was over within a second")

        repeat(12) { r.gearing.update(0.25) } // four seconds
        assertEquals(settledInFifteen, r.forceNow(), absoluteTolerance = 1e-9)
    }

    @Test
    fun `an upshift ends a release that is still running`() {
        val r = Rig(sprocket = 13)
        r.settle(gradient = 0.0, speedMps = 9.0)
        val settledInThirteen = r.forceNow()

        r.gearing.shiftBy(-1)
        r.gearing.shiftBy(+1)
        assertEquals(settledInThirteen, r.forceNow(), absoluteTolerance = 1e-9)
    }

    @Test
    fun `no run of downshifts lets go of the road entirely`() {
        val r = Rig(sprocket = 10)
        r.settle(gradient = 0.06, speedMps = 4.0)

        repeat(7) { r.gearing.shiftBy(-1) }
        val settledHere = r.gearing.trainerForceNewtons(r.gear).let {
            // The settled force of this gear is what the release is a fraction
            // of, so read it once the release has run out.
            val probe = Rig(sprocket = r.drivetrain.gears[r.gear].sprocket)
            probe.settle(gradient = 0.06, speedMps = 4.0)
            probe.forceNow()
        }
        assertTrue(
            r.forceNow() >= settledHere * VirtualDrivetrain.MIN_SHIFT_RELEASE - 1e-9,
            "seven downshifts let go of ${(1 - r.forceNow() / settledHere) * 100} % of the road",
        )
    }

    /**
     * The write that carries a downshift carries its release, so the first
     * thing the trainer hears after the tap is the lighter road.
     */
    @Test
    fun `the shift's own write carries the release`() {
        val r = Rig(sprocket = 13)
        r.settle(gradient = 0.03, speedMps = 6.0)
        r.gearing.shiftBy(-1)
        val expected = r.forceNow()

        assertTrue(r.gearing.writeRoad(forced = true, nowMs = 1_000_000))
        assertEquals(expected, r.gearing.commandedForceN.value, absoluteTolerance = 1e-9)
    }

    // The freewheel ─────────────────────────────────────────────────────────

    /**
     * The report: down a descent at 70 km/h on the gravel 1x in the hardest
     * gear, the road eased to about -2 % and the pedals went heavy at once, a
     * 22 % wall. At 100 rpm the 40x10 turns the wheel at 53 km/h. The legs
     * were never connected to a bike doing 70, and on a bicycle a flatter road
     * can only slow it down.
     */
    @Test
    fun `a flatter road at 70 km per h slows the bike and asks nothing of legs behind the wheel`() {
        val r = descending(cadence = 100)
        assertEquals(0.0, r.gearing.freewheelAbsorbedNewtons, "a steady road was held back")
        val before = r.forceNow()

        r.gearing.setRoad(gradient = -0.02, worldSpeedMps = FAST)
        assertEquals(before, r.forceNow(), absoluteTolerance = 0.5)

        // The bike slows, which is all a flatter road can do, and the inertia
        // term takes the change over from the freewheel without the legs
        // meeting any of it.
        var v = FAST
        var heaviest = 0.0
        repeat(32) { // eight seconds
            v -= RideDynamics.roadForceNewtons(-0.02, v, 84.0) / RideDynamics.effectiveMassKg(84.0) * 0.25
            r.tick(gradient = -0.02, speedMps = v)
            heaviest = maxOf(heaviest, r.forceNow())
        }
        assertTrue(heaviest <= before + 0.5, "the pedals went to $heaviest N from $before N")
        assertEquals(0.0, r.gearing.freewheelAbsorbedNewtons, absoluteTolerance = 0.1)
    }

    /** What the same moment asked for before the freewheel, for the record. */
    @Test
    fun `legs turning with the wheel at 70 km per h are connected to it - and meet the change`() {
        val r = descending(cadence = 132)
        val before = r.forceNow()
        r.gearing.setRoad(gradient = -0.02, worldSpeedMps = FAST)
        assertEquals(0.0, r.gearing.freewheelAbsorbedNewtons)
        assertTrue(r.forceNow() > before + 100.0)
    }

    @Test
    fun `legs keeping up with the wheel meet a climb exactly as before`() {
        val r = calibrated()
        val before = r.forceNow()
        r.gearing.setRoad(gradient = 0.08, worldSpeedMps = CRUISE)
        val step = (gradeForce(0.08) - gradeForce(0.0)) * r.drivetrain.loadFactor(r.gear)
        assertEquals(before + step, r.forceNow(), absoluteTolerance = 1e-6)
    }

    /**
     * One sprocket down leaves 90 rpm turning the wheel at 90 % of the bike's
     * speed until the legs spin up, and riders change down as a climb starts.
     */
    @Test
    fun `a downshift at the foot of a climb does not hide the climb`() {
        val r = calibrated()
        r.gearing.shiftBy(-1)
        r.gearing.setRoad(gradient = 0.08, worldSpeedMps = CRUISE)
        assertEquals(0.0, r.gearing.freewheelAbsorbedNewtons)
    }

    @Test
    fun `before the rider's gear has been measured nothing is held`() {
        val r = Rig(cadence = 100, sprocket = 10)
        // The launch assist is still fading, so nothing has been learned yet.
        r.ride(gradient = -0.10, speedMps = FAST, seconds = 8.0)
        assertFalse(r.gearing.freewheelCalibrated)

        r.gearing.setRoad(gradient = -0.02, worldSpeedMps = FAST)
        assertEquals(0.0, r.gearing.freewheelAbsorbedNewtons)
    }

    @Test
    fun `a new ride measures the rider's gear again`() {
        val r = calibrated()
        r.gearing.load(Drivetrain.GRAVEL, r.indexOf(17))
        assertFalse(r.gearing.freewheelCalibrated)
    }

    private companion object {
        /** 90 rpm in the gravel 40x17: about 28 km/h. */
        val CRUISE = 90 / 60.0 * Drivetrain.GRAVEL.gears.first { it.sprocket == 17 }.developmentMeters

        /** 70 km/h. */
        const val FAST = 70 / 3.6
    }
}
