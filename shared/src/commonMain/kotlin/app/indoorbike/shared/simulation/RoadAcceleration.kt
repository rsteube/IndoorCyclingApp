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

import kotlin.math.exp

/**
 * How hard the bike is changing speed, in metres per second per second.
 *
 * The reason this exists is a term the model used to drop on the floor. A
 * trainer in simulation mode owes the rider four forces - rolling, slope, air
 * and *acceleration* - and until now only the first three reached the machine.
 * The consequence is not subtle: at a gentle 0.3 m/s^2 roll-on, a rider and
 * bike of 85 kg need about 47 N at the wheel, which is more than the entire
 * steady road load of riding along a flat road at 34 km/h. Leave it out and
 * every acceleration is weightless, every crest hands nothing back, and the
 * bike has no momentum the legs can feel.
 *
 * It is also what makes the commanded force agree with the speed somebody else
 * is integrating. A route loop, or a companion, works out speed from
 *
 *     m_eff . dv/dt = P/v - F_road
 *
 * and the app was commanding only the `F_road` half of it. Adding `m_eff . a`
 * back is not a feel knob with a gain to tune; it is the missing term, and its
 * coefficient is the same effective mass the speed was integrated with.
 *
 * ## Why it is filtered, and why it is clamped
 *
 * Differentiating a sampled speed amplifies whatever noise is in it. A
 * companion sending world updates a few times a second holds its speed
 * constant in between, so the raw derivative is a train of spikes separated by
 * zeros; power-derived speed twitches with every pedal stroke. A first-order
 * filter turns both into the sustained accelerations a rider can actually feel
 * - an attack, a crest, a descent winding up - and drops the rest, which is
 * fine, because the control point cannot be written faster than five times a
 * second anyway.
 *
 * The clamp is the guard against the other failure. A companion that
 * teleports the rider, or a ride resumed after a suspension, presents a speed
 * step that no bicycle produced. [RideDynamics.inertiaForceNewtons] refuses to
 * turn that into a force, and re-anchoring across a long gap here means it
 * never reaches that far.
 */
class RoadAcceleration {

    private var lastSpeedMps = 0.0
    private var seeded = false
    private var filtered = 0.0

    /** The filtered acceleration, in m/s^2. Negative while the bike is slowing. */
    val mps2: Double get() = filtered

    fun reset() {
        lastSpeedMps = 0.0
        seeded = false
        filtered = 0.0
    }

    /**
     * Take a fresh [speedMps], [dtSeconds] after the last one, and return the
     * filtered acceleration it implies.
     *
     * The first sample of a ride, and any sample arriving after a gap longer
     * than [MAX_GAP_SECONDS], only re-anchors: a bike cannot be said to have
     * accelerated across time nobody was watching.
     */
    fun update(speedMps: Double, dtSeconds: Double): Double {
        val v = speedMps.coerceAtLeast(0.0)
        if (!seeded || dtSeconds <= 0.0 || dtSeconds >= MAX_GAP_SECONDS) {
            lastSpeedMps = v
            seeded = true
            filtered = 0.0
            return 0.0
        }
        val raw = (v - lastSpeedMps) / dtSeconds
        lastSpeedMps = v
        val alpha = 1.0 - exp(-dtSeconds / SMOOTHING_SECONDS)
        filtered += (raw - filtered) * alpha
        return filtered
    }

    companion object {
        /**
         * Time constant of the filter. Around the length of a hard standing
         * acceleration, so the sustained part of one survives while a single
         * noisy sample does not.
         */
        const val SMOOTHING_SECONDS = 0.7

        /** Past this a sample re-anchors rather than counting as acceleration. */
        const val MAX_GAP_SECONDS = 1.5
    }
}
