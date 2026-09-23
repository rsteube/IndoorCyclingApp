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

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ResistanceHold] against a bike it knows nothing about.
 *
 * The bike is a model: power grows with the brake's position through some
 * curve, rises with cadence, reads a few percent noisy, and the brake takes a
 * moment to move. The hold is never told any of it, which is the situation on
 * a real spin bike, so a pass here means it found the wattage by measuring.
 */
class ResistanceHoldTest {

    private class Bike(
        val range: ResistanceRange,
        /** Share of full power at a brake position from 0 to 1. */
        val curve: (Double) -> Double = { it + 0.15 },
        val wattsAtFull: Double = 420.0,
        val brakeSeconds: Double = 0.6,
        seed: Int = 1,
    ) {
        private val random = Random(seed)
        private var brake: Double? = null

        fun watts(cadence: Int): Int {
            val position = brake?.let { (it - range.min) / (range.max - range.min) } ?: 0.0
            val noise = 1.0 + random.nextDouble(-0.04, 0.04)
            return (wattsAtFull * curve(position) * (cadence / 90.0).pow(1.6) * noise).toInt()
        }

        fun command(level: Double, dt: Double) {
            brake = brake?.let { it + (level - it) * (1.0 - exp(-dt / brakeSeconds)) } ?: level
        }
    }

    private class Trace(val watts: List<Int>, val levels: List<Double>)

    private fun ride(
        bike: Bike,
        hold: ResistanceHold,
        target: Int,
        seconds: Double,
        cadence: Int = 88,
        dt: Double = 0.5,
    ): Trace {
        val watts = mutableListOf<Int>()
        val levels = mutableListOf<Double>()
        repeat((seconds / dt).toInt()) {
            val measured = bike.watts(cadence)
            val level = hold.update(target, measured, cadence, dt)
            bike.command(level, dt)
            watts += measured
            levels += level
        }
        return Trace(watts, levels)
    }

    private fun Trace.settledWithin(target: Int, fraction: Double, after: Int): Boolean =
        watts.drop(after).all { abs(it - target) <= target * fraction }

    private fun Trace.changesIn(lastSamples: Int): Int =
        levels.takeLast(lastSamples).zipWithNext().count { (a, b) -> a != b }

    @Test
    fun `finds a wattage it was never told how to reach`() {
        val range = ResistanceRange(1.0, 32.0, 1.0)
        val bike = Bike(range)
        val hold = ResistanceHold(range)

        val trace = ride(bike, hold, target = 220, seconds = 40.0)
        // Within 12 % from 20 s on (40 samples at 0.5 s), and quiet once there.
        assertTrue(trace.settledWithin(220, 0.12, after = 40), "never settled: ${trace.watts}")
        assertTrue(trace.changesIn(30) <= 3, "kept hunting: ${trace.levels.takeLast(30)}")
    }

    @Test
    fun `follows a step up and back down`() {
        val range = ResistanceRange(0.0, 100.0, 1.0)
        val bike = Bike(range)
        val hold = ResistanceHold(range)

        ride(bike, hold, target = 150, seconds = 30.0)
        val up = ride(bike, hold, target = 280, seconds = 30.0)
        assertTrue(up.settledWithin(280, 0.12, after = 30), "didn't climb to 280: ${up.watts}")
        val down = ride(bike, hold, target = 130, seconds = 30.0)
        assertTrue(down.settledWithin(130, 0.12, after = 30), "didn't come down to 130: ${down.watts}")
    }

    /**
     * The shapes the hold's own model gets wrong: a heavy floor at the lightest
     * level, and a brake that is slow to move. Both are real, and a hold tuned
     * only for its own assumption rings on them.
     */
    @Test
    fun `holds on a bike that is nothing like its model`() {
        val range = ResistanceRange(1.0, 24.0, 1.0)
        val bike = Bike(range, curve = { 0.7 * it + 0.35 }, brakeSeconds = 1.5, seed = 7)
        val hold = ResistanceHold(range)

        val trace = ride(bike, hold, target = 240, seconds = 45.0)
        assertTrue(trace.settledWithin(240, 0.12, after = 50), "never settled: ${trace.watts}")
        assertTrue(trace.changesIn(30) <= 3, "rang on a slow brake: ${trace.levels.takeLast(30)}")
    }

    @Test
    fun `nothing to hold is as light as the machine goes`() {
        val range = ResistanceRange(1.0, 32.0, 1.0)
        val hold = ResistanceHold(range)
        assertEquals(1.0, hold.update(targetWatts = 0, measuredWatts = 180, cadenceRpm = 85, dtSeconds = 0.5))
    }

    /** Winding the brake up against legs that are not moving would meet the first stroke with a wall. */
    @Test
    fun `a rider who stops pedalling leaves the level where it was`() {
        val range = ResistanceRange(1.0, 32.0, 1.0)
        val bike = Bike(range)
        val hold = ResistanceHold(range)
        ride(bike, hold, target = 200, seconds = 30.0)
        val before = hold.level

        repeat(20) { hold.update(targetWatts = 200, measuredWatts = 0, cadenceRpm = 0, dtSeconds = 0.5) }
        assertEquals(before, hold.level)
    }

    @Test
    fun `never asks for a level the machine does not have`() {
        val range = ResistanceRange(2.0, 20.0, 2.0)
        // A bike too weak to ever reach the target: the hold runs to the top and stops there.
        val bike = Bike(range, wattsAtFull = 150.0)
        val hold = ResistanceHold(range)
        val trace = ride(bike, hold, target = 400, seconds = 60.0)
        assertTrue(trace.levels.all { it in 2.0..20.0 && (it / 2.0) == kotlin.math.floor(it / 2.0) }, "${trace.levels}")
        assertEquals(20.0, trace.levels.last())
    }
}
