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
package app.indoorbike.shared.route

import app.indoorbike.shared.physics.RideDynamics
import app.indoorbike.shared.physics.VirtualSpeed
import app.indoorbike.shared.repo.RouteEntry
import app.indoorbike.shared.repo.RouteGradientBuckets
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How long a route will take this rider, computed from the listing alone.
 *
 * The route picker has to put a duration on a card before the GPX has been
 * downloaded, so the estimate works from [RouteEntry.gradientMeters]: how many
 * metres of road run at each steepness. For every bucket it asks what speed
 * this rider would settle at on that gradient, and adds up the time.
 *
 *     t = Σ  distance_bucket / steadySpeed(P, gradient_bucket, mass)
 *
 * That is deliberately not "distance divided by average speed". Time on a
 * route is non-linear in gradient: a rider loses far more minutes on a 10 %
 * ramp than they win back on the 10 % descent that follows, so a route summed
 * to zero net elevation is still slower than a flat one of the same length.
 * Distance and total ascent cannot see that difference; a histogram can.
 *
 * The physics is [VirtualSpeed]s, extended with gravity, so the estimate and
 * the ride agree: the speed used for a 0 % bucket is exactly the number the
 * workout HUD shows for the same watts, and the speed used for a 6 % bucket is
 * the fixed point that [RideDynamics] would converge to on a 6 % climb.
 *
 * What it cannot know is momentum. A real rider carries speed into the bottom
 * of a climb and brakes before a corner, and this assumes each bucket is
 * ridden at its own steady state. On rolling terrain that makes the estimate
 * slightly pessimistic, which is the right direction for a number a rider
 * plans an evening around.
 */
object RouteEstimate {

    /**
     * The share of FTP the estimate assumes.
     *
     * A route has no duration of its own, only a duration at an effort, and
     * this is the effort most riders actually pick for a scenic hour: steady
     * endurance, conversational, sustainable for as long as the route lasts.
     * It is a constant rather than a setting because a picker that made every
     * rider choose an intensity before it could show a single number would be
     * asking a question to answer a question.
     */
    const val ESTIMATE_FTP_FRACTION = 0.65

    /**
     * Floor on estimated speed, about 4 km/h.
     *
     * Below this a rider is not riding, they are walking or unclipping, and an
     * uncapped solve turns a short 25 % wall at endurance watts into a
     * forty-minute bucket that swamps the whole estimate. The floor keeps a
     * brutal ramp expensive without letting it become absurd.
     */
    const val MIN_ESTIMATE_SPEED_MPS = 1.1

    private const val MAX_NEWTON_STEPS = 24
    private const val CONVERGED_MPS = 1e-7

    /**
     * Steady-state speed for [powerWatts] on a road of [gradient] (rise/run)
     * carrying [totalMassKg] of rider plus bike.
     *
     * The fixed point of [RideDynamics.step]: the speed at which drive force
     * exactly balances gravity, rolling resistance and drag, so
     *
     *     P·η = v·m·g·(sin θ + Crr·cos θ) + ½·ρ·CdA·v³
     *
     * Solved by Newton on `f(v) = A·v³ + B·v − W`. `f` is convex for positive
     * v, so seeding above the root guarantees the iteration walks down to it
     * and never overshoots into the region where the cubic turns back on
     * itself. On a descent steep enough that `B` goes negative the seed adds
     * the drag-free rollout term, which keeps it above the turning point at
     * `sqrt(-B/3A)` as well as above the root.
     */
    fun steadySpeedMps(
        powerWatts: Double,
        gradient: Double,
        totalMassKg: Double,
    ): Double {
        if (totalMassKg <= 0.0) return 0.0

        // sin and cos of the road angle straight from the gradient, the same
        // way RideDynamics does it, so the two models cannot drift apart.
        val hyp = sqrt(1.0 + gradient * gradient)
        val sinTheta = gradient / hyp
        val cosTheta = 1.0 / hyp

        val drag = 0.5 * VirtualSpeed.AIR_DENSITY_KG_M3 * VirtualSpeed.CDA_M2
        val resistance = totalMassKg * VirtualSpeed.GRAVITY_MS2 *
            (sinTheta + VirtualSpeed.CRR * cosTheta)
        val wheelWatts = powerWatts * VirtualSpeed.DRIVETRAIN_EFFICIENCY

        if (wheelWatts <= 0.0 && resistance >= 0.0) return 0.0

        var v = (wheelWatts.coerceAtLeast(0.0) / drag).pow(1.0 / 3.0) +
            sqrt((-resistance).coerceAtLeast(0.0) / drag)
        if (v <= 0.0) return 0.0

        repeat(MAX_NEWTON_STEPS) {
            val f = drag * v * v * v + resistance * v - wheelWatts
            val slope = 3.0 * drag * v * v + resistance
            if (slope <= 0.0) return@repeat
            val step = f / slope
            v -= step
            if (abs(step) < CONVERGED_MPS) return clampSpeed(v)
        }
        return clampSpeed(v)
    }

    private fun clampSpeed(v: Double): Double =
        v.coerceIn(MIN_ESTIMATE_SPEED_MPS, RideDynamics.MAX_DESCENT_SPEED_MPS)

    /**
     * Seconds to complete [entry] for a rider of [ftpWatts] and [riderKg] on a
     * bike of [bikeKg], riding at [ESTIMATE_FTP_FRACTION] of threshold.
     *
     * Null when the rider has not told us enough to answer. Weight in
     * particular is not guessed: [VirtualSpeed] refuses to invent a mass and
     * so does this, because a duration built on a made-up rider is worse than
     * no duration at all.
     */
    fun secondsFor(
        entry: RouteEntry,
        ftpWatts: Int,
        riderKg: Float,
        bikeKg: Float = VirtualSpeed.DEFAULT_BIKE_MASS_KG,
    ): Long? {
        if (ftpWatts <= 0 || riderKg <= 0f || entry.distanceMeters <= 0.0) return null
        val powerWatts = ftpWatts * ESTIMATE_FTP_FRACTION
        val totalMassKg = riderKg.toDouble() + bikeKg.toDouble()

        val seconds = if (entry.hasGradientHistogram) {
            secondsFromHistogram(entry.gradientMeters, powerWatts, totalMassKg)
        } else {
            // No histogram, so the only honest reading of the route is a flat
            // one. Better a slightly optimistic number than none, and the
            // maintainer tool always writes the histogram.
            val flat = steadySpeedMps(powerWatts, gradient = 0.0, totalMassKg = totalMassKg)
            if (flat <= 0.0) return null
            entry.distanceMeters / flat
        }
        if (!seconds.isFinite() || seconds <= 0.0) return null
        return seconds.toLong()
    }

    private fun secondsFromHistogram(
        gradientMeters: List<Double>,
        powerWatts: Double,
        totalMassKg: Double,
    ): Double {
        var seconds = 0.0
        for (i in 0 until RouteGradientBuckets.COUNT) {
            val metres = gradientMeters[i]
            if (metres <= 0.0) continue
            val gradient = RouteGradientBuckets.CENTRE_PERCENT[i] / 100.0
            val speed = steadySpeedMps(powerWatts, gradient, totalMassKg)
            if (speed <= 0.0) continue
            seconds += metres / speed
        }
        return seconds
    }
}
