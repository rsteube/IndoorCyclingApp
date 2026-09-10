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

/**
 * A hand off the line, and then out of the way.
 *
 * ## The problem this solves, and why it is not a physics bug
 *
 * Pulling away from a standstill is genuinely hard, and the model is right
 * about that. At walking pace almost none of the resistance is air, so what is
 * left is gravity, rolling, and the mass of the bike and rider being
 * accelerated. Power is force times speed, so at 2 km/h the *power* is trivial
 * while the *force at the pedals* is not: the rider gets a heavy, grinding
 * pedal and no reward for it.
 *
 * Outdoors nobody minds, because the answer is to stand on the pedals for three
 * strokes and it is over. Indoors that is a much worse trade. The rider is
 * seated, there is no balance to keep and no scenery moving to say it is
 * working, and the first thirty seconds of every ride are spent grinding a gear
 * that will feel fine the moment they are moving. Riders read it as the app
 * being broken, and they are not entirely wrong: the simulation is faithful to
 * a moment nobody enjoys and nobody came here for.
 *
 * ## What it does
 *
 * Scales the whole commanded force. Below [THRESHOLD_MPS] the rider gets
 * [ASSIST_FACTOR] of the real thing, which is enough to spin up without
 * heaving. The first time they cross the threshold, the assist fades to nothing
 * over [FADE_SECONDS], and from then on the physics is the physics.
 *
 * The fade is what keeps this honest. A permanent discount would be a
 * different bicycle; ten seconds is long enough that the hand-off is never felt
 * as a step, and short enough that nothing anybody does with the ride happens
 * inside it.
 *
 * ## What it deliberately does not do
 *
 * Distinguish a flat start from a hill start. Scaling the whole force means a
 * rider pulling away on a 10 % wall is also helped, and gravity is the thing
 * being discounted. That is the intended reading: pulling away on a wall is the
 * single worst case of exactly this problem, and a version that assisted only
 * the inertia term would leave the hardest start unhelped while smoothing the
 * easiest one.
 *
 * ## Re-arming
 *
 * Coming back only when the bike is properly stopped, well below the threshold
 * that triggers the fade. A rider grinding up a climb at 4 km/h is under the
 * threshold and must not be handed the assist back: they are not launching,
 * they are climbing, and quietly halving the hill under them is the opposite of
 * what this is for.
 */
class LaunchAssist {

    private enum class Phase { LAUNCHING, FADING, FULL }

    private var phase = Phase.LAUNCHING
    private var fadedSeconds = 0.0

    /**
     * What to multiply the commanded force by: [ASSIST_FACTOR] off the line,
     * 1.0 once the fade has run.
     */
    var factor: Double = ASSIST_FACTOR
        private set

    /** True while the rider is getting any help at all. For the HUD to say so. */
    val assisting: Boolean get() = phase != Phase.FULL

    fun reset() {
        phase = Phase.LAUNCHING
        fadedSeconds = 0.0
        factor = ASSIST_FACTOR
    }

    /**
     * Advance by [dtSeconds] at [speedMps], and return the factor to use now.
     *
     * [speedMps] is the *world's* speed, not the drivetrain's. It has to be:
     * the drivetrain's answer is cadence times the gear, which on the first
     * turn of a crank in a starting gear is already well past the threshold
     * while the bike has not moved at all. Momentum is the thing being asked
     * about, and only the world's speed knows about it.
     */
    fun update(speedMps: Double, dtSeconds: Double): Double {
        val v = speedMps.coerceAtLeast(0.0)
        val dt = dtSeconds.coerceIn(0.0, MAX_STEP_SECONDS)

        if (phase != Phase.LAUNCHING && v < REARM_MPS) {
            // Stopped again. The next pull away gets the same help as the first.
            reset()
            return factor
        }

        when (phase) {
            Phase.LAUNCHING -> if (v >= THRESHOLD_MPS) {
                phase = Phase.FADING
                fadedSeconds = 0.0
            }
            Phase.FADING -> {
                fadedSeconds += dt
                if (fadedSeconds >= FADE_SECONDS) phase = Phase.FULL
            }
            Phase.FULL -> Unit
        }

        factor = when (phase) {
            Phase.LAUNCHING -> ASSIST_FACTOR
            Phase.FULL -> 1.0
            Phase.FADING -> {
                val t = (fadedSeconds / FADE_SECONDS).coerceIn(0.0, 1.0)
                // Smoothstep rather than a straight line, so neither end of the
                // fade arrives as a corner. The rider should not be able to
                // point at the moment the help stopped.
                val eased = t * t * (3.0 - 2.0 * t)
                ASSIST_FACTOR + (1.0 - ASSIST_FACTOR) * eased
            }
        }
        return factor
    }

    companion object {
        /**
         * Share of the real force a rider pulling away is asked for.
         *
         * A feel constant, and one of the few in the model that is not derived
         * from anything. Half is enough to turn a grind into a brisk push
         * without making the pedals feel disconnected, which is what happens
         * somewhere below a third.
         */
        const val ASSIST_FACTOR = 0.5

        /** 5 km/h: moving, upright, and past the part that needed help. */
        const val THRESHOLD_MPS = 5.0 / 3.6

        /** How long the hand-off to full physics takes. */
        const val FADE_SECONDS = 10.0

        /**
         * Stopped, for the purposes of getting the assist back. Roughly 1 km/h,
         * far enough below [THRESHOLD_MPS] that a slow climb can never be
         * mistaken for a standstill.
         */
        const val REARM_MPS = 0.3

        /** A tick longer than this is a stall, not riding. */
        private const val MAX_STEP_SECONDS = 1.5
    }
}
