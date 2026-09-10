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
package app.indoorbike.shared.simulation

import kotlin.math.exp

/**
 * How fast the rider's legs are turning the road out under them.
 *
 * A chain does not slip. On a real bike the gear is not a resistance knob, it
 * is a *kinematic constraint*: pick a gear, pick a cadence, and the speed is
 * decided for you.
 *
 *     v = cadence (rev/s) x development (metres per crank revolution)
 *
 * That one line is the whole difference between a virtual drivetrain that
 * feels like gears and one that feels like a volume control, and it is what
 * every shipping virtual-shifting implementation is built on. Without it a
 * shift can only scale the road force by the ratio between two gears, a few
 * per cent, and the rider absorbs it by pushing very slightly harder. With it
 * a shift moves the *speed* the road is evaluated at, so the aerodynamic term
 * moves with the square of the gear and the commanded force with its cube.
 * That is why a downshift at 35 km/h is felt and a downshift on a 10 % wall is
 * felt less, exactly as outdoors.
 *
 * ## Why the cadence is smoothed
 *
 * Cadence arrives from crank revolution counters, which on some trainers
 * update barely once a second and always in whole revolutions. Fed straight
 * through, that granularity would arrive at the rider's legs as a notchy
 * resistance that steps every time the counter ticks. A short first-order
 * filter costs a little response and buys a continuous road.
 *
 * The *gear* is deliberately not smoothed. [speedMps] multiplies the smoothed
 * cadence by whatever development it is handed, so a shift steps the drive
 * speed at once, which is the one transient the rider must feel immediately.
 *
 * ## When there is no cadence at all
 *
 * Plenty of power sources never report one. [speedMps] falls back to the
 * caller's own speed - the route's or the companion's - until a crank has
 * actually turned, so a trainer that cannot count cadence keeps the behaviour
 * it had before this class existed rather than riding a road with no air in
 * it. Once a crank *has* turned, a reported zero is believed: that is a rider
 * who has stopped pedalling, and their drivetrain really is delivering
 * nothing.
 */
class DriveSpeed {

    private var smoothedRpm = 0.0
    private var everPedalled = false

    /** The filtered cadence the drive speed is being built from, in rpm. */
    val cadenceRpm: Double get() = smoothedRpm

    /** True once a crank has turned, i.e. once cadence can be believed. */
    val available: Boolean get() = everPedalled

    /** Forget everything. Called whenever a ride is loaded or restarted. */
    fun reset() {
        smoothedRpm = 0.0
        everPedalled = false
    }

    /**
     * Take a fresh [cadenceRpm] reading, [dtSeconds] after the last one.
     *
     * A gap longer than [MAX_GAP_SECONDS] - a backgrounded app, a stalled loop
     * - is taken at face value rather than filtered, because there is nothing
     * left to smooth against.
     */
    fun update(cadenceRpm: Int, dtSeconds: Double) {
        val rpm = cadenceRpm.coerceIn(0, MAX_CADENCE_RPM).toDouble()
        if (!everPedalled) {
            // Nothing to believe yet, and nothing to filter against either:
            // the first turn of the crank is adopted whole rather than eased
            // into from a standstill the rider was never at.
            if (rpm <= 0.0) return
            everPedalled = true
            smoothedRpm = rpm
            return
        }
        if (rpm > 0.0 && smoothedRpm <= STOPPED_RPM) {
            // Pulling away again. There is nothing to average a turning crank
            // with: the rider was stopped, and a filter easing up from zero
            // would spend a second insisting they are still half stopped. This
            // is the same rule as the first crank of the ride, and it is what
            // makes the speed appear the instant they start pedalling rather
            // than a beat later.
            smoothedRpm = rpm
            return
        }
        if (dtSeconds <= 0.0) return
        if (dtSeconds >= MAX_GAP_SECONDS) {
            smoothedRpm = rpm
            return
        }
        val alpha = 1.0 - exp(-dtSeconds / SMOOTHING_SECONDS)
        smoothedRpm += (rpm - smoothedRpm) * alpha
    }

    /**
     * The road speed a gear of [developmentMeters] is turning out at the
     * cadence seen so far, or [fallbackMps] while no crank has turned.
     */
    fun speedMps(developmentMeters: Double, fallbackMps: Double): Double {
        if (!everPedalled) return fallbackMps.coerceIn(0.0, MAX_DRIVE_SPEED_MPS)
        return (smoothedRpm / 60.0 * developmentMeters).coerceIn(0.0, MAX_DRIVE_SPEED_MPS)
    }

    companion object {
        /**
         * Time constant of the cadence filter. Long enough to smooth a
         * once-a-second crank counter into something continuous, short enough
         * that spinning up out of a corner still reaches the legs while the
         * rider is still doing it.
         */
        const val SMOOTHING_SECONDS = 0.45

        /** Past this a sample is a fresh start rather than a step to filter. */
        const val MAX_GAP_SECONDS = 1.5

        /** Nobody pedals faster than this, so anything above it is a bad packet. */
        const val MAX_CADENCE_RPM = 220

        /**
         * Below this the rider counts as stopped, and the next crank they turn
         * is adopted whole rather than filtered up from a standstill.
         */
        const val STOPPED_RPM = 1.0

        /**
         * Ceiling on the derived speed, so a spurious cadence in the top gear
         * cannot command a road nobody could ride. Above the fastest descent
         * the rest of the model will produce, and well past 100 km/h.
         */
        const val MAX_DRIVE_SPEED_MPS = 30.0
    }
}
