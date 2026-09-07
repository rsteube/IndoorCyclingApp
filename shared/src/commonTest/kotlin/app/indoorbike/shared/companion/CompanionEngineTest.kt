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
package app.indoorbike.shared.companion

import app.indoorbike.shared.ble.SimulationParameters
import app.indoorbike.shared.simulation.Drivetrain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val RIDER_KG = 75f
private const val BIKE_KG = 9f

private class Recorder {
    val roads = mutableListOf<SimulationParameters>()
    val engine = CompanionEngine(
        powerWatts = { 200 },
        applyRoad = { roads += it },
        riderMassKg = { RIDER_KG },
        bikeMassKg = { BIKE_KG },
        drivetrainProvider = { Drivetrain.DEFAULT },
    ).also { it.load() }
}

class CompanionEngineTest {

    @Test
    fun `a fresh engine sits on a flat road with no companion`() {
        val r = Recorder()
        assertEquals(0.0, r.engine.gradient.value)
        assertEquals(0.0, r.engine.speedMps.value)
        assertFalse(r.engine.receivingRoad.value)
        assertFalse(r.engine.roadStale.value)
    }

    @Test
    fun `a road off the wire becomes the road under the rider`() {
        val r = Recorder()
        r.engine.setRoad(gradient = 0.062, speedMps = 7.42, nowMs = 1_000)
        assertEquals(0.062, r.engine.gradient.value)
        assertEquals(7.42, r.engine.speedMps.value)
        assertTrue(r.engine.receivingRoad.value)
    }

    /**
     * The force has to grow with the hill, with the speed, and with the gear.
     * If any of the three stopped mattering the mode would still appear to
     * work while feeling wrong, which is the failure worth a test.
     */
    @Test
    fun `commanded force grows with gradient, speed and gear`() {
        val r = Recorder()
        val gear = Drivetrain.DEFAULT.startIndex

        r.engine.setRoad(gradient = 0.0, speedMps = 8.0, nowMs = 0)
        val flat = r.engine.trainerForceNewtons(gear)

        r.engine.setRoad(gradient = 0.06, speedMps = 8.0, nowMs = 0)
        val climbing = r.engine.trainerForceNewtons(gear)
        assertTrue(climbing > flat, "a hill must push back harder than the flat")

        r.engine.setRoad(gradient = 0.06, speedMps = 16.0, nowMs = 0)
        val faster = r.engine.trainerForceNewtons(gear)
        assertTrue(faster > climbing, "drag grows with speed, so the same hill costs more")

        val harderGear = Drivetrain.DEFAULT.clampIndex(gear + 4)
        assertTrue(
            r.engine.trainerForceNewtons(harderGear) > faster,
            "a bigger gear multiplies the road force",
        )
    }

    /**
     * A companion sending nonsense must not be able to command more than a
     * trainer would have refused anyway. The ceiling is the shipped one, shared
     * with route mode.
     */
    @Test
    fun `an absurd road is held under the force ceiling`() {
        val r = Recorder()
        r.engine.setRoad(gradient = 0.30, speedMps = 30.0, nowMs = 0)
        val top = Drivetrain.DEFAULT.clampIndex(Drivetrain.DEFAULT.gearCount - 1)
        val force = r.engine.trainerForceNewtons(top)
        assertTrue(force < 265.0, "force was " + force)
    }

    @Test
    fun `a descent still leaves the pedals something to push against`() {
        val r = Recorder()
        r.engine.setRoad(gradient = -0.12, speedMps = 15.0, nowMs = 0)
        assertTrue(r.engine.trainerForceNewtons(Drivetrain.DEFAULT.startIndex) > 0.0)
    }

    // The deadman ────────────────────────────────────────────────────────────

    @Test
    fun `a silent companion ramps the hill away rather than dropping it`() {
        val r = Recorder()
        r.engine.setRoad(gradient = 0.12, speedMps = 9.0, nowMs = 1_000)

        r.engine.advance(2_500)
        assertEquals(0.12, r.engine.gradient.value, absoluteTolerance = 1e-9)
        assertFalse(r.engine.roadStale.value)

        r.engine.advance(3_500)
        assertEquals(0.06, r.engine.gradient.value, absoluteTolerance = 1e-9)
        assertTrue(r.engine.roadStale.value)

        r.engine.advance(4_500)
        assertEquals(0.0, r.engine.gradient.value)
    }

    /**
     * Ramping speed too would collapse the drag term as well, and the rider
     * would land on a road with no hill *and* no air in it.
     */
    @Test
    fun `speed is held through a timeout`() {
        val r = Recorder()
        r.engine.setRoad(gradient = 0.12, speedMps = 9.0, nowMs = 0)
        r.engine.advance(10_000)
        assertEquals(0.0, r.engine.gradient.value)
        assertEquals(9.0, r.engine.speedMps.value)
    }

    @Test
    fun `the stale flag flips once each way and says so`() {
        val r = Recorder()
        val seen = mutableListOf<Boolean>()
        r.engine.onRoadStaleChanged = { seen += it }

        r.engine.setRoad(gradient = 0.08, speedMps = 8.0, nowMs = 0)
        r.engine.advance(500)
        r.engine.advance(1_000)
        assertEquals(emptyList(), seen, "nothing to report inside the lease")

        r.engine.advance(3_000)
        r.engine.advance(3_500)
        assertEquals(listOf(true), seen, "reported once, not once per tick")

        r.engine.setRoad(gradient = 0.08, speedMps = 8.0, nowMs = 3_600)
        assertEquals(listOf(true, false), seen)
        assertFalse(r.engine.roadStale.value)
    }

    // Gears ──────────────────────────────────────────────────────────────────

    @Test
    fun `a shift reports where the drivetrain ended up and who asked`() {
        val r = Recorder()
        val events = mutableListOf<Triple<Int, Boolean, String>>()
        r.engine.onGearChanged = { i, changed, source -> events += Triple(i, changed, source) }

        val start = r.engine.gearIndex.value
        val after = r.engine.shiftUp(CompanionEngine.SOURCE_COMPANION)
        assertEquals(start + 1, after)
        assertEquals(Triple(start + 1, true, CompanionEngine.SOURCE_COMPANION), events.single())
    }

    /**
     * A companion needs to be able to tell "shifted" from "already in the
     * hardest gear", or it cannot cue the rider correctly.
     */
    @Test
    fun `a shift that changes nothing is still reported`() {
        val r = Recorder()
        val events = mutableListOf<Pair<Int, Boolean>>()
        // Walk to the top, then keep asking.
        repeat(Drivetrain.DEFAULT.gearCount + 5) { r.engine.shiftUp() }
        r.engine.onGearChanged = { i, changed, _ -> events += i to changed }

        val top = r.engine.gearIndex.value
        r.engine.shiftUp()
        assertEquals(listOf(top to false), events)
        assertEquals(top, r.engine.gearIndex.value)
    }

    // Progress ───────────────────────────────────────────────────────────────

    @Test
    fun `cumulative totals never go backwards`() {
        val r = Recorder()
        r.engine.setProgress(distanceM = 8412.5, ascentM = 214.0)
        r.engine.setProgress(distanceM = 12.0, ascentM = 1.0)
        assertEquals(8412.5, r.engine.distanceMeters.value)
        assertEquals(214.0, r.engine.elevationGainMeters.value)

        r.engine.setProgress(distanceM = 8500.0, ascentM = 220.0)
        assertEquals(8500.0, r.engine.distanceMeters.value)
        assertEquals(220.0, r.engine.elevationGainMeters.value)
    }

    @Test
    fun `a null total leaves what was already banked alone`() {
        val r = Recorder()
        r.engine.setProgress(distanceM = 100.0, ascentM = 10.0)
        r.engine.setProgress(distanceM = null, ascentM = null)
        assertEquals(100.0, r.engine.distanceMeters.value)
        assertEquals(10.0, r.engine.elevationGainMeters.value)
    }

    @Test
    fun `loading a fresh ride forgets the previous road and its totals`() {
        val r = Recorder()
        r.engine.setRoad(gradient = 0.1, speedMps = 9.0, nowMs = 0)
        r.engine.setProgress(distanceM = 5_000.0, ascentM = 120.0)

        r.engine.load()
        assertEquals(0.0, r.engine.gradient.value)
        assertEquals(0.0, r.engine.speedMps.value)
        assertEquals(0.0, r.engine.distanceMeters.value)
        assertEquals(0.0, r.engine.elevationGainMeters.value)
        assertFalse(r.engine.receivingRoad.value)
    }
}
