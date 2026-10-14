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

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the trainer is actually told while a companion holds the rider at a
 * wattage, which is not always the wattage it asked for.
 *
 * A power target is the one thing in this protocol that keeps pushing back at
 * a rider who has stopped interacting with it. A road does not: stop pedalling
 * on a hill and the hill is still there, but it is not doing anything to you.
 * ERG is different. The trainer holds the watts by raising the brake as the
 * cranks slow, so a rider who gives up at 300 W is met with more resistance the
 * more they struggle, and a game that hangs leaves them there. Both of those are
 * the app's to prevent, because both happen exactly when the companion has
 * stopped being a reliable partner.
 *
 * So the commanded wattage is the target through two gates, each a factor in
 * `[0, 1]`:
 *
 *  - **The lease.** The same 1500 ms lease a road frame carries. Past it the
 *    factor falls to zero over [CompanionProtocol.WORLD_RAMP_MS], and when
 *    frames come back it climbs the same way rather than snapping on.
 *  - **The pedals.** A rider whose cadence falls below [RELEASE_CADENCE_RPM]
 *    for [RELEASE_AFTER_MS] is let go at once: nothing is gained by easing a
 *    rider off a brake they are already stalled against. Turning the cranks
 *    again past [RESUME_CADENCE_RPM] takes them back to the target over
 *    [RESUME_RAMP_MS].
 *
 * A target that has just been set also opens gently, from whatever the rider
 * was pushing to the target over [SOFT_START_MS], because a trainer that
 * receives 250 W while its rider is soft-pedalling at 80 does not negotiate.
 *
 * Pure and clock-free: every method takes the time, so the whole envelope is
 * testable in microseconds. The engine that owns the trainer drives it.
 */
class PowerHold {

    /** The wattage the companion asked for, after clamping. Null when none is held. */
    var target: Int? = null
        private set

    /** What [commanded] last worked out, so telemetry and the engine agree. */
    var commanded: Int = 0
        private set

    private var softStartFromWatts = 0
    private var setAtMs = 0L

    /** How long the ramp to [target] that began at [setAtMs] takes. */
    private var rampMs = SOFT_START_MS

    /** Where the ramp had got to at the last [update], before the two gates. */
    private var opened = 0.0

    private var leaseFactor = 1.0
    private var pedalFactor = 1.0
    private var lowCadenceSinceMs: Long? = null
    private var lastUpdateMs = 0L
    private var seeded = false

    /**
     * Hold [watts], or release the trainer back to the road with null.
     *
     * [riderWatts] is what the rider is pushing right now, which is where the
     * soft start opens from. Setting the same target again restarts that ramp,
     * which is the honest thing to do: the companion asked for it twice, and
     * the second ask is as good a moment to be gentle as the first.
     */
    fun set(watts: Int?, riderWatts: Int, nowMs: Long) {
        target = watts
        if (watts == null) {
            commanded = 0
            lowCadenceSinceMs = null
            return
        }
        softStartFromWatts = riderWatts.coerceIn(0, watts)
        setAtMs = nowMs
        rampMs = SOFT_START_MS
        opened = softStartFromWatts.toDouble()
        // A fresh target starts from a rider who is presumed to be turning the
        // cranks, and finds out otherwise within a couple of seconds if not.
        pedalFactor = 1.0
        lowCadenceSinceMs = null
        commanded = softStartFromWatts
    }

    /**
     * Move a target already held to [watts], as the rider's own + and - do on a
     * run (protocol 0.15).
     *
     * Not a new target, so not a soft start from the rider's power: the ramp
     * starts where the old one had got to and moves at [ADJUST_WATTS_PER_SECOND],
     * so one 5 W press lands in a tenth of a second and a held button climbs
     * steadily rather than dipping back to the legs at every press. Nothing
     * about the pedals or the lease is reset: a rider let go of stays let go.
     */
    fun adjust(watts: Int, nowMs: Long) {
        if (target == null) return
        target = watts
        softStartFromWatts = opened.roundToInt()
        setAtMs = nowMs
        rampMs = (abs(watts - softStartFromWatts) * 1_000L / ADJUST_WATTS_PER_SECOND).coerceAtMost(SOFT_START_MS)
    }

    /**
     * Work out what to command now, given the rider's [cadenceRpm] and how long
     * ago the last `world` frame arrived.
     *
     * [worldAgeMs] is null when no frame has ever arrived, which is not the same
     * as a stale one: a companion that has not described a road yet has not
     * abandoned the rider, and until it says something there is nothing to
     * decay. Returns the commanded wattage, also left in [commanded].
     */
    fun update(nowMs: Long, cadenceRpm: Int, worldAgeMs: Long?): Int {
        val held = target
        if (held == null) {
            commanded = 0
            return 0
        }
        val dtMs = if (seeded) (nowMs - lastUpdateMs).coerceAtLeast(0L) else 0L
        lastUpdateMs = nowMs
        seeded = true

        val leaseGoal = if (worldAgeMs != null && worldAgeMs > CompanionProtocol.WORLD_TIMEOUT_MS) 0.0 else 1.0
        leaseFactor = approach(leaseFactor, leaseGoal, dtMs, CompanionProtocol.WORLD_RAMP_MS)

        if (cadenceRpm <= RELEASE_CADENCE_RPM) {
            val since = lowCadenceSinceMs ?: nowMs.also { lowCadenceSinceMs = it }
            // Let go outright rather than easing off: the rider is already
            // stalled against the brake and wants it gone, not faded.
            if (nowMs - since >= RELEASE_AFTER_MS) pedalFactor = 0.0
        } else {
            lowCadenceSinceMs = null
            if (cadenceRpm >= RESUME_CADENCE_RPM) {
                pedalFactor = approach(pedalFactor, 1.0, dtMs, RESUME_RAMP_MS)
            }
        }

        opened = if (nowMs - setAtMs >= rampMs) {
            held.toDouble()
        } else {
            val into = (nowMs - setAtMs).coerceAtLeast(0L).toDouble() / rampMs
            softStartFromWatts + (held - softStartFromWatts) * into
        }
        commanded = (opened * leaseFactor * pedalFactor).roundToInt().coerceIn(0, maxOf(held, softStartFromWatts))
        return commanded
    }

    /** True while the rider has been let go of a target that is still set. */
    val released: Boolean get() = target != null && pedalFactor <= 0.0

    private fun approach(current: Double, goal: Double, dtMs: Long, overMs: Long): Double {
        if (dtMs <= 0L) return current
        val step = dtMs.toDouble() / overMs
        return if (goal > current) {
            (current + step).coerceAtMost(goal)
        } else {
            (current - step).coerceAtLeast(goal)
        }
    }

    companion object {
        /** At or below this cadence the rider is not riding, they are stopped. */
        const val RELEASE_CADENCE_RPM = 20

        /** How long they have to be stopped before the brake comes off. */
        const val RELEASE_AFTER_MS = 2_000L

        /** Turning the cranks this fast is riding again, and takes the target back. */
        const val RESUME_CADENCE_RPM = 30

        /** How long the target takes to come back once they are pedalling. */
        const val RESUME_RAMP_MS = 3_000L

        /** How long a newly set target takes to reach the rider. */
        const val SOFT_START_MS = 2_000L

        /** How fast an [adjust] moves the wattage: 25 W in half a second. */
        const val ADJUST_WATTS_PER_SECOND = 50L
    }
}
