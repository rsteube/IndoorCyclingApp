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

/** One value the app refused to take at face value. */
data class Clamped(val field: String, val sent: Double, val applied: Double)

/** What [CompanionRoad.accept] did with a frame. */
sealed interface RoadUpdate {
    /** Taken. [clamps] is empty unless something was out of bounds. */
    data class Applied(val clamps: List<Clamped>) : RoadUpdate

    /** Arrived out of order and was dropped. Not an error, and not warned about. */
    data object Stale : RoadUpdate
}

/**
 * The road the companion says the rider is on, and how much of it is still
 * trusted.
 *
 * Deliberately pure: no clock of its own, no coroutines, no flows. The caller
 * passes the time in, exactly as `SimulationEngine` takes its own `dtSeconds`,
 * so the deadman behaviour can be tested at a hundred times real speed with no
 * waiting and no flakiness.
 *
 * ## The deadman, and why it ramps
 *
 * A companion can crash, be alt-tabbed, or lose Wi-Fi while the rider is
 * halfway up a 12 % wall. What must not happen is that the last commanded force
 * stays in place: the rider is clipped into a machine that is now grinding
 * against a hill no longer being simulated by anything.
 *
 * So a [WorldFrame] carries an implicit lease of [CompanionProtocol.WORLD_TIMEOUT_MS].
 * Past it the gradient ramps to flat over [CompanionProtocol.WORLD_RAMP_MS]
 * rather than stepping there, because a step off a steep climb is felt as the
 * pedals falling away.
 *
 * **Speed is held rather than zeroed.** Ramping it too would collapse the drag
 * term as well, and the rider would arrive at a road with no hill *and* no air
 * in it. Holding it means what they land on is an ordinary flat road at the
 * pace they were already going, which is the mildest honest thing to give them.
 */
class CompanionRoad {

    private var lastSeq: Long = Long.MIN_VALUE
    private var acceptedAtMs: Long = 0
    private var everAccepted = false

    /** The gradient as last accepted, before any deadman ramp. */
    var gradient: Double = 0.0
        private set

    /** Virtual speed as last accepted. Held through a timeout, never ramped. */
    var speedMps: Double = 0.0
        private set

    /**
     * Rolling resistance as last accepted, before any deadman ramp.
     *
     * A frame that says nothing about it means tarmac, not "as you were": a
     * companion that stops describing the surface has stopped modelling one,
     * and the rider should be given the road every build before this one gave
     * them rather than the last gravel sector for ever.
     */
    var crr: Double = CompanionProtocol.DEFAULT_CRR
        private set

    /** Cumulative along-road distance the companion has reported, if any. */
    var distanceM: Double? = null
        private set

    /** Cumulative ascent the companion has reported, if any. */
    var ascentM: Double? = null
        private set

    /**
     * Take a frame, clamping anything out of bounds.
     *
     * Clamping happens here, on arrival, rather than deeper in the physics, so
     * that the value the rider sees on the HUD and the value the trainer is
     * given are the same number, and so that a companion is told about it.
     */
    fun accept(frame: WorldFrame, nowMs: Long): RoadUpdate {
        if (everAccepted && frame.seq <= lastSeq) return RoadUpdate.Stale

        val clamps = mutableListOf<Clamped>()

        val g = clamp(
            frame.gradient, CompanionProtocol.MIN_GRADIENT, CompanionProtocol.MAX_GRADIENT,
        )
        if (g != frame.gradient) clamps += Clamped("gradient", frame.gradient, g)

        val v = clamp(
            frame.speedMps, CompanionProtocol.MIN_SPEED_MPS, CompanionProtocol.MAX_SPEED_MPS,
        )
        if (v != frame.speedMps) clamps += Clamped("speedMps", frame.speedMps, v)

        val sent = frame.crr
        val c = if (sent == null) {
            CompanionProtocol.DEFAULT_CRR
        } else {
            clamp(sent, CompanionProtocol.MIN_CRR, CompanionProtocol.MAX_CRR).also {
                if (it != sent) clamps += Clamped("crr", sent, it)
            }
        }

        lastSeq = frame.seq
        acceptedAtMs = nowMs
        everAccepted = true
        gradient = g
        speedMps = v
        crr = c

        // Both are cumulative and must never go backwards. A companion that
        // resets its own counter mid-ride would otherwise hand the ride record
        // a negative distance, so the larger value wins rather than the newer.
        frame.distanceM?.let { reported ->
            val current = distanceM
            if (current == null || reported > current) distanceM = reported
        }
        frame.ascentM?.let { reported ->
            val current = ascentM
            if (current == null || reported > current) ascentM = reported
        }

        return RoadUpdate.Applied(clamps)
    }

    /** True once the lease on the last frame has expired. */
    fun isStale(nowMs: Long): Boolean =
        !everAccepted || (nowMs - acceptedAtMs) > CompanionProtocol.WORLD_TIMEOUT_MS

    /**
     * The gradient to actually ride, with the deadman applied.
     *
     * Inside the lease this is [gradient] unchanged. Past it the value decays
     * linearly to flat over [CompanionProtocol.WORLD_RAMP_MS] and stays there.
     */
    fun effectiveGradient(nowMs: Long): Double {
        if (!everAccepted) return 0.0
        return CompanionProtocol.rampedGradient(gradient, nowMs - acceptedAtMs)
    }

    /**
     * And the rolling resistance to ride, with the deadman applied: back to
     * tarmac over the same ramp the gradient flattens over.
     */
    fun effectiveCrr(nowMs: Long): Double {
        if (!everAccepted) return CompanionProtocol.DEFAULT_CRR
        return CompanionProtocol.rampedCrr(crr, nowMs - acceptedAtMs)
    }

    /**
     * Forget the road without forgetting the ride.
     *
     * Used when the rider takes back control: the recording continues, but
     * nothing off the socket is steering it any more.
     */
    fun release() {
        everAccepted = false
        gradient = 0.0
        speedMps = 0.0
        crr = CompanionProtocol.DEFAULT_CRR
        lastSeq = Long.MIN_VALUE
    }

    private fun clamp(value: Double, min: Double, max: Double): Double {
        if (value.isNaN()) return min.coerceAtLeast(0.0).coerceAtMost(max)
        return value.coerceIn(min, max)
    }
}
