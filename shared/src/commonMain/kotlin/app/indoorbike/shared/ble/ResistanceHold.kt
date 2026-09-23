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
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Holds a rider at a wattage on a machine that only accepts a resistance
 * level, by measuring the power each level produces and moving it.
 *
 * This is ERG built from the outside. A trainer with real ERG does the same
 * thing internally, many times a second, against a brake model it was
 * calibrated with. Here nothing is known about the brake: not how many watts
 * a level is worth, not whether the curve is straight, not how long the magnet
 * takes to move. So the level is steered by the ratio between the power asked
 * for and the power measured, in small steps, and each step waits to see what
 * it did before the next.
 *
 * Tuned in simulation against bikes of every shape that matters: linear,
 * convex and concave level curves, a heavy floor at the lightest level, power
 * rising with cadence anywhere from linearly to steeper than the square, and
 * brakes that settle in 0.3 s or 1.5 s. Across all of them a step in target
 * settles within 12 % in about 8 s (13 s at worst), holds within about 4 %,
 * and makes fewer than one change in 15 s once settled. Slower than a real
 * ERG trainer, and it has to be: a loop that moves faster than it can measure
 * rings.
 *
 * Not thread-safe; the owner serialises calls.
 */
class ResistanceHold(private val range: ResistanceRange) {

    /** The level last asked for, or null before the first [update]. */
    var level: Double? = null
        private set

    private var smoothedWatts: Double? = null
    private var sinceChangeSeconds = Double.MAX_VALUE

    /** Forget everything measured: a new connection, or a new rider on the bike. */
    fun reset() {
        level = null
        smoothedWatts = null
        sinceChangeSeconds = Double.MAX_VALUE
    }

    /**
     * One step of [dtSeconds]: the rider should be making [targetWatts], and
     * is making [measuredWatts] at [cadenceRpm]. Returns the level the machine
     * should be at now.
     */
    fun update(targetWatts: Int, measuredWatts: Int, cadenceRpm: Int, dtSeconds: Double): Double {
        val span = range.max - range.min
        val current = level ?: quantise(range.min + START_POSITION * span)
        val dt = dtSeconds.coerceIn(0.0, MAX_STEP_SECONDS)
        sinceChangeSeconds += dt

        // Nothing to hold: as light as the machine goes, like ERG at zero.
        if (targetWatts <= 0) {
            smoothedWatts = null
            return settle(range.min)
        }

        val alpha = 1.0 - exp(-dt / SMOOTHING_SECONDS)
        val smoothed = smoothedWatts?.let { it + (measuredWatts - it) * alpha } ?: measuredWatts.toDouble()
        smoothedWatts = smoothed

        // Nobody pedalling, so no measurement of what this level is worth.
        // Winding it up now would meet the rider's first stroke with a wall.
        // And a change still settling has not shown what it did yet.
        if (cadenceRpm < MIN_CADENCE_RPM || smoothed < MIN_MEASURED_WATTS ||
            sinceChangeSeconds < WAIT_AFTER_CHANGE_SECONDS
        ) {
            return settle(current)
        }

        // Power is taken to grow with the level from some floor the machine
        // has even at its lightest. Wrong in detail for every real bike, but
        // only its direction and rough scale steer anything: the measurement
        // corrects the rest on the next step.
        val position = (current - range.min) / span + FLOOR
        val error = ln(targetWatts / smoothed)
        // Closer than one notch can reach is as close as this machine gets.
        // Chasing it would only flick between the two levels either side.
        val notch = range.increment / span / position
        if (abs(error) < max(DEADBAND, NOTCH_HYSTERESIS * notch)) return settle(current)

        val wanted = position * exp(error * min(1.0, GAIN_PER_SECOND * dt))
        val maxStep = MAX_TRAVEL_PER_SECOND * dt
        var next = current + (wanted - position).coerceIn(-maxStep, maxStep) * span
        // At least one notch the right way, or a coarse machine near its
        // target would never move at all.
        if (abs(next - current) < range.increment) {
            next = current + if (error > 0) range.increment else -range.increment
        }
        return settle(quantise(next))
    }

    private fun settle(next: Double): Double {
        if (next != level) sinceChangeSeconds = 0.0
        level = next
        return next
    }

    private fun quantise(value: Double): Double {
        val clamped = value.coerceIn(range.min, range.max)
        val steps = ((clamped - range.min) / range.increment).roundToInt()
        return (range.min + steps * range.increment).coerceIn(range.min, range.max)
    }

    companion object {
        /** Where a machine nobody has measured yet starts: light, but not nothing. */
        const val START_POSITION = 0.25

        /** How quickly the measured power is believed, as a time constant. */
        const val SMOOTHING_SECONDS = 0.8

        /** Share of the remaining error closed per second of measurement. */
        const val GAIN_PER_SECOND = 0.8

        /** Most of the range the level may cross in a second. */
        const val MAX_TRAVEL_PER_SECOND = 0.15

        /** How long a change is left to show itself before the next. */
        const val WAIT_AFTER_CHANGE_SECONDS = 1.0

        /** Within this (as a log ratio, about 6 %) the rider is on target. */
        const val DEADBAND = 0.06

        /** A notch's share of the error that has to be missing before one is moved. */
        const val NOTCH_HYSTERESIS = 0.6

        /** The model's power at the lightest level, as a share of the range. */
        const val FLOOR = 0.15

        const val MIN_CADENCE_RPM = 30
        const val MIN_MEASURED_WATTS = 10.0

        /** A longer gap is a stall, not a measurement. */
        private const val MAX_STEP_SECONDS = 1.0
    }
}
