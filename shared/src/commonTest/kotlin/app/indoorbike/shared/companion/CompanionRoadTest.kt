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
package app.indoorbike.shared.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompanionRoadTest {

    private fun world(
        seq: Long,
        gradient: Double = 0.0,
        speed: Double = 8.0,
        distance: Double? = null,
        ascent: Double? = null,
        crr: Double? = null,
    ) = WorldFrame(
        seq = seq, gradient = gradient, speedMps = speed, distanceM = distance,
        ascentM = ascent, crr = crr,
    )

    @Test
    fun `takes the surface the companion says it is riding`() {
        val road = CompanionRoad()
        val update = road.accept(world(1, crr = 0.0122), nowMs = 0)
        assertEquals(emptyList(), assertIs<RoadUpdate.Applied>(update).clamps)
        assertEquals(0.0122, road.crr)
    }

    @Test
    fun `a frame that says nothing about the surface is tarmac`() {
        val road = CompanionRoad()
        road.accept(world(1, crr = 0.016), nowMs = 0)
        road.accept(world(2), nowMs = 100)
        assertEquals(CompanionProtocol.DEFAULT_CRR, road.crr)
    }

    @Test
    fun `clamps a surface nobody could ride and says so`() {
        val road = CompanionRoad()
        val update = road.accept(world(1, crr = 0.5), nowMs = 0)
        val clamps = assertIs<RoadUpdate.Applied>(update).clamps
        assertEquals(Clamped("crr", 0.5, CompanionProtocol.MAX_CRR), clamps.single())
        assertEquals(CompanionProtocol.MAX_CRR, road.crr)
    }

    @Test
    fun `the surface ramps back to tarmac when the companion goes away`() {
        val road = CompanionRoad()
        road.accept(world(1, gradient = 0.08, crr = 0.02), nowMs = 0)
        assertEquals(0.02, road.effectiveCrr(CompanionProtocol.WORLD_TIMEOUT_MS))
        val half = CompanionProtocol.WORLD_TIMEOUT_MS + CompanionProtocol.WORLD_RAMP_MS / 2
        val part = road.effectiveCrr(half)
        assertTrue(part < 0.02 && part > CompanionProtocol.DEFAULT_CRR, "ramping, at ${'$'}part")
        val after = CompanionProtocol.WORLD_TIMEOUT_MS + CompanionProtocol.WORLD_RAMP_MS + 1
        assertEquals(CompanionProtocol.DEFAULT_CRR, road.effectiveCrr(after))
    }

    @Test
    fun `letting go of the road puts the surface back to tarmac`() {
        val road = CompanionRoad()
        road.accept(world(1, crr = 0.018), nowMs = 0)
        road.release()
        assertEquals(CompanionProtocol.DEFAULT_CRR, road.crr)
    }

    @Test
    fun `takes a road inside the envelope unchanged`() {
        val road = CompanionRoad()
        val update = road.accept(world(1, gradient = 0.062, speed = 7.42), nowMs = 1_000)
        assertEquals(emptyList(), assertIs<RoadUpdate.Applied>(update).clamps)
        assertEquals(0.062, road.gradient)
        assertEquals(7.42, road.speedMps)
    }

    @Test
    fun `clamps a gradient beyond the envelope and says so`() {
        val road = CompanionRoad()
        val update = road.accept(world(1, gradient = 0.42), nowMs = 0)
        val clamps = assertIs<RoadUpdate.Applied>(update).clamps
        assertEquals(1, clamps.size)
        assertEquals(Clamped("gradient", 0.42, 0.30), clamps.single())
        assertEquals(0.30, road.gradient)
    }

    @Test
    fun `clamps a descent and an impossible speed too`() {
        val road = CompanionRoad()
        val update = road.accept(world(1, gradient = -5.0, speed = 400.0), nowMs = 0)
        val clamps = assertIs<RoadUpdate.Applied>(update).clamps
        assertEquals(2, clamps.size)
        assertEquals(-0.30, road.gradient)
        assertEquals(30.0, road.speedMps)
    }

    @Test
    fun `a NaN gradient becomes flat rather than poisoning the physics`() {
        val road = CompanionRoad()
        road.accept(world(1, gradient = Double.NaN), nowMs = 0)
        assertEquals(0.0, road.gradient)
    }

    @Test
    fun `drops a frame that arrived out of order`() {
        val road = CompanionRoad()
        road.accept(world(10, gradient = 0.10), nowMs = 0)
        val late = road.accept(world(9, gradient = 0.20), nowMs = 10)
        assertEquals(RoadUpdate.Stale, late)
        assertEquals(0.10, road.gradient)
    }

    @Test
    fun `cumulative totals never go backwards`() {
        val road = CompanionRoad()
        road.accept(world(1, distance = 8412.5, ascent = 214.0), nowMs = 0)
        // A companion that resets its own counter must not hand the ride record
        // a shrinking distance.
        road.accept(world(2, distance = 12.0, ascent = 1.0), nowMs = 250)
        assertEquals(8412.5, road.distanceM)
        assertEquals(214.0, road.ascentM)

        road.accept(world(3, distance = 8500.0, ascent = 220.0), nowMs = 500)
        assertEquals(8500.0, road.distanceM)
        assertEquals(220.0, road.ascentM)
    }

    // The deadman ────────────────────────────────────────────────────────────

    @Test
    fun `a road inside its lease is ridden as sent`() {
        val road = CompanionRoad()
        road.accept(world(1, gradient = 0.12), nowMs = 1_000)
        assertFalse(road.isStale(1_000))
        assertEquals(0.12, road.effectiveGradient(1_000))
        // Right at the edge of the lease, still trusted.
        assertFalse(road.isStale(2_500))
        assertEquals(0.12, road.effectiveGradient(2_500))
    }

    @Test
    fun `a silent companion ramps the hill away rather than dropping it`() {
        val road = CompanionRoad()
        road.accept(world(1, gradient = 0.12), nowMs = 1_000)

        assertTrue(road.isStale(2_501))
        // One second into a two-second ramp: half the hill is left. A step
        // straight to flat here is felt as the pedals falling away.
        assertEquals(0.06, road.effectiveGradient(3_500), absoluteTolerance = 1e-9)
        // Ramp complete.
        assertEquals(0.0, road.effectiveGradient(4_500))
        assertEquals(0.0, road.effectiveGradient(60_000))
    }

    /**
     * Ramping speed too would collapse the drag term as well, and the rider
     * would arrive at a road with no hill *and* no air in it. Holding it means
     * what they land on is an ordinary flat road at the pace they were going.
     */
    @Test
    fun `speed is held through a timeout never ramped`() {
        val road = CompanionRoad()
        road.accept(world(1, gradient = 0.12, speed = 9.0), nowMs = 1_000)
        assertEquals(0.0, road.effectiveGradient(10_000))
        assertEquals(9.0, road.speedMps)
    }

    @Test
    fun `a descent ramps back up to flat not down`() {
        val road = CompanionRoad()
        road.accept(world(1, gradient = -0.10), nowMs = 0)
        assertEquals(-0.05, road.effectiveGradient(2_500), absoluteTolerance = 1e-9)
        assertEquals(0.0, road.effectiveGradient(3_500))
    }

    @Test
    fun `a fresh frame ends the timeout`() {
        val road = CompanionRoad()
        road.accept(world(1, gradient = 0.12), nowMs = 0)
        assertTrue(road.isStale(3_000))
        road.accept(world(2, gradient = 0.08), nowMs = 3_000)
        assertFalse(road.isStale(3_000))
        assertEquals(0.08, road.effectiveGradient(3_000))
    }

    @Test
    fun `a road with nothing on it yet is flat not stale-ramping from a guess`() {
        val road = CompanionRoad()
        assertTrue(road.isStale(0))
        assertEquals(0.0, road.effectiveGradient(0))
    }

    @Test
    fun `releasing forgets the road but the ride carries on`() {
        val road = CompanionRoad()
        road.accept(world(5, gradient = 0.12, speed = 9.0, distance = 100.0), nowMs = 0)
        road.release()
        assertEquals(0.0, road.gradient)
        assertEquals(0.0, road.speedMps)
        // What the ride record already banked is not thrown away.
        assertEquals(100.0, road.distanceM)
        // And a companion reconnecting can start its sequence over.
        assertIs<RoadUpdate.Applied>(road.accept(world(1, gradient = 0.03), nowMs = 100))
    }
}
