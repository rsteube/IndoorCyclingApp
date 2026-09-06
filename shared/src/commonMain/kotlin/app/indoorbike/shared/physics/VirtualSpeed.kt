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
package app.indoorbike.shared.physics

import kotlin.math.abs
import kotlin.math.pow

/**
 * Speed and distance for an indoor ride, estimated from power.
 *
 * A trainer has no road under it, so there is no speed to measure. What the
 * head units and trainer apps show instead is the speed the rider *would* be
 * holding outdoors for the watts they are actually producing, on a flat road
 * in still air. Given power and the mass being moved, that is a physics
 * question with one answer:
 *
 *     P·η = Crr·m·g·v + ½·ρ·CdA·v³
 *            └ rolling ┘  └── aerodynamic drag ──┘
 *
 * The unknown is v, and the cubic has exactly one real root for any positive
 * power (the left side is strictly increasing in v from zero). [speedMps]
 * solves it; distance is that speed integrated over the ride.
 *
 * The road is a modelled one, so its parameters are fixed rather than asked
 * for: no gradient, no wind, [CDA_M2] for a rider on the hoods, [CRR] for good
 * clinchers on smooth asphalt, sea-level air. Only mass comes from the rider,
 * because it is the term they can feel: a heavier rider covers less ground for
 * the same watts. Two riders holding 200 W therefore see nearly the same
 * number, which is the point - it is a fair, repeatable readout of effort, not
 * a claim about a particular road.
 *
 * Calibration: these values put 200 W at about 32 km/h for a 75 kg rider and
 * 250 W at about 35 km/h for a 70 kg one, which is where the outdoor rules of
 * thumb and the other trainer apps sit.
 *
 * Without a body weight in Settings there is no mass and so no estimate; every
 * entry point returns zero rather than guessing one.
 */
object VirtualSpeed {

    /** Bike + kit, added to the rider's body mass. A road bike with pedals and bottles. */
    const val DEFAULT_BIKE_MASS_KG: Float = 9f

    // The road model's calibration. Public because [RideDynamics] simulates the
    // same road with a gradient on it, and the two readouts have to agree: a
    // rider who sees 32 km/h for 200 W on the flat here must see the same
    // number on a 0 % stretch of a simulated route.
    const val GRAVITY_MS2 = 9.8067
    /** Dry air at 15 °C, sea level. */
    const val AIR_DENSITY_KG_M3 = 1.225
    /** Rolling resistance coefficient: road tyres on tarmac. */
    const val CRR = 0.005
    /** Drag area in m², a rider on the hoods. */
    const val CDA_M2 = 0.36
    /** Chain losses between the crank and the road. */
    const val DRIVETRAIN_EFFICIENCY = 0.97

    private const val MAX_NEWTON_STEPS = 12
    private const val CONVERGED_MPS = 1e-7

    /**
     * Speed in metres per second for [powerWatts] held by a rider of
     * [riderKg] plus [bikeKg] of bike. Zero for zero (or negative) power, and
     * zero when the rider's weight is unknown.
     */
    fun speedMps(
        powerWatts: Double,
        riderKg: Float,
        bikeKg: Float = DEFAULT_BIKE_MASS_KG,
    ): Double {
        if (powerWatts <= 0.0 || riderKg <= 0f) return 0.0

        val massKg = riderKg.toDouble() + bikeKg.toDouble()
        val dragTerm = 0.5 * AIR_DENSITY_KG_M3 * CDA_M2
        val rollingTerm = CRR * massKg * GRAVITY_MS2
        val wheelWatts = powerWatts * DRIVETRAIN_EFFICIENCY

        // Newton on f(v) = drag·v³ + rolling·v − wheelWatts. Seeded from the
        // drag-only solution, which ignores rolling resistance and so always
        // overshoots the root. f is increasing and convex above zero, so from
        // an overshoot every step lands closer and still above: the iteration
        // can't jump past the answer into a region with no root.
        var v = (wheelWatts / dragTerm).pow(1.0 / 3.0)
        repeat(MAX_NEWTON_STEPS) {
            val f = dragTerm * v * v * v + rollingTerm * v - wheelWatts
            val slope = 3.0 * dragTerm * v * v + rollingTerm
            val step = f / slope
            v -= step
            if (abs(step) < CONVERGED_MPS) return v
        }
        return v.coerceAtLeast(0.0)
    }

    /** [speedMps] in the unit the HUD shows. */
    fun speedKmh(
        powerWatts: Int,
        riderKg: Float,
        bikeKg: Float = DEFAULT_BIKE_MASS_KG,
    ): Double = speedMps(powerWatts.toDouble(), riderKg, bikeKg) * 3.6

    /**
     * Ground covered by a run of power samples, each held for
     * [sampleSeconds]. Telemetry is recorded at 1 Hz, so the default is the
     * usual case.
     */
    fun distanceMeters(
        powerSamples: List<Long>,
        riderKg: Float,
        sampleSeconds: Double = 1.0,
        bikeKg: Float = DEFAULT_BIKE_MASS_KG,
    ): Double {
        if (riderKg <= 0f || powerSamples.isEmpty()) return 0.0
        var metres = 0.0
        for (watts in powerSamples) {
            metres += speedMps(watts.toDouble(), riderKg, bikeKg) * sampleSeconds
        }
        return metres
    }

    /** Average speed in km/h over [durationSeconds] of riding covering [distanceMeters]. */
    fun averageSpeedKmh(distanceMeters: Double, durationSeconds: Long): Double =
        if (durationSeconds <= 0L || distanceMeters <= 0.0) 0.0
        else distanceMeters / durationSeconds * 3.6
}
