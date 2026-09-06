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

import kotlin.math.sqrt

/**
 * How fast a rider is moving along a simulated road, second by second.
 *
 * [VirtualSpeed] answers a different question: "for these watts, on a flat
 * road, what speed would the rider be holding once everything settled?" That
 * steady-state answer is right for a HUD readout during a workout, but it is
 * the wrong model for riding a route. A rider who crests a climb and stops
 * pedalling does not stop moving, and one who hits a 30 km/h descent does not
 * instantly arrive at terminal speed. Both of those are momentum, and momentum
 * is the thing that makes a simulated road feel like a road.
 *
 * So this integrates the equation of motion instead of solving for its fixed
 * point:
 *
 *     m_eff · dv/dt = F_drive(P, v) − F_gravity(θ) − F_rolling(θ) − F_drag(v)
 *
 * with
 *
 *     F_drive   = P·η / v          (the wheel can only push through the ground
 *                                   it is covering, so the same watts give a
 *                                   huge shove at walking pace and a gentle one
 *                                   at 50 km/h)
 *     F_gravity = m·g·sin θ        (negative downhill, which is what makes a
 *                                   descent free)
 *     F_rolling = Crr·m·g·cos θ
 *     F_drag    = ½·ρ·CdA·v²
 *
 * The road constants are [VirtualSpeed]'s, so a flat stretch of a simulated
 * route settles at exactly the speed the workout HUD would have shown for the
 * same watts. What this adds is gravity and time.
 *
 * Everything here is pure: state in, state out, no clock and no flows. The
 * caller ([app.indoorbike.shared.simulation.SimulationEngine]) owns the loop.
 */
object RideDynamics {

    /**
     * Rotating mass the wheels add to the rider's inertia.
     *
     * A wheel resists acceleration twice: once as mass to move down the road,
     * and again as a flywheel to spin up. For a road wheelset the second part
     * is worth roughly this much extra, and leaving it out makes a simulated
     * bike noticeably twitchier off the line than a real one.
     */
    const val ROTATING_MASS_KG = 1.5

    /**
     * Speed below which drive force stops growing.
     *
     * P/v goes to infinity as v goes to zero, so a standing start would launch
     * the rider at an impossible acceleration. Real cranks cannot deliver that
     * either: at a standstill the rider is limited by torque, not by power.
     * Capping the divisor at a slow walking pace is the cheap, stable version
     * of that limit, and at 200 W it gives about 130 N off the line, which is a
     * brisk but believable push.
     */
    const val MIN_DRIVE_SPEED_MPS = 1.5

    /**
     * Terminal speed on a descent. Real riders scrub speed rather than tuck
     * for a personal-best on an unfamiliar road, and an uncapped 20 % descent
     * would otherwise run the route counter forward at 100 km/h and finish the
     * ride in a couple of minutes.
     */
    const val MAX_DESCENT_SPEED_MPS = 22.0

    /** Below this the rider is stopped. Avoids an endless crawl towards zero. */
    private const val STOPPED_MPS = 0.05

    /** Sub-steps never longer than this, so a slow UI frame can't destabilise the integrator. */
    private const val MAX_SUB_STEP_SECONDS = 0.1

    /**
     * Advance [speedMps] by [dtSeconds] of riding at [powerWatts] on a road of
     * [gradient] (rise/run, so 0.06 is a 6 % climb and -0.06 a 6 % descent),
     * carrying [totalMassKg] of rider plus bike.
     *
     * Long steps are integrated in sub-steps: forward Euler on a v² drag term
     * is only stable while the step is small, and the caller's tick can stretch
     * when the app is backgrounded or a GC lands.
     */
    fun step(
        speedMps: Double,
        powerWatts: Double,
        gradient: Double,
        totalMassKg: Double,
        dtSeconds: Double,
    ): Double {
        if (dtSeconds <= 0.0 || totalMassKg <= 0.0) return speedMps
        var v = speedMps.coerceAtLeast(0.0)
        var remaining = dtSeconds.coerceAtMost(MAX_TOTAL_STEP_SECONDS)
        while (remaining > 0.0) {
            val dt = if (remaining > MAX_SUB_STEP_SECONDS) MAX_SUB_STEP_SECONDS else remaining
            v = subStep(v, powerWatts, gradient, totalMassKg, dt)
            remaining -= dt
        }
        return v
    }

    private fun subStep(
        speedMps: Double,
        powerWatts: Double,
        gradient: Double,
        totalMassKg: Double,
        dt: Double,
    ): Double {
        // sin and cos of the road angle straight from the gradient, no trig:
        // for a slope of rise/run = g, sin = g/sqrt(1+g²) and cos = 1/sqrt(1+g²).
        val hyp = sqrt(1.0 + gradient * gradient)
        val sinTheta = gradient / hyp
        val cosTheta = 1.0 / hyp

        val v = speedMps.coerceAtLeast(0.0)
        val driveSpeed = if (v < MIN_DRIVE_SPEED_MPS) MIN_DRIVE_SPEED_MPS else v
        val driveForce = if (powerWatts > 0.0) {
            powerWatts * VirtualSpeed.DRIVETRAIN_EFFICIENCY / driveSpeed
        } else {
            0.0
        }

        val weight = totalMassKg * VirtualSpeed.GRAVITY_MS2
        val gravityForce = weight * sinTheta
        val rollingForce = VirtualSpeed.CRR * weight * cosTheta
        val dragForce = 0.5 * VirtualSpeed.AIR_DENSITY_KG_M3 * VirtualSpeed.CDA_M2 * v * v

        // Rolling resistance and drag always oppose motion, so they only apply
        // to a bike that is actually moving. Without this guard a stopped rider
        // on the flat would be pushed backwards by their own tyres.
        val resistive = if (v > STOPPED_MPS) rollingForce + dragForce else 0.0
        val net = driveForce - gravityForce - resistive

        val effectiveMass = totalMassKg + ROTATING_MASS_KG
        var next = v + (net / effectiveMass) * dt

        if (next <= STOPPED_MPS) {
            // Stopped, or being dragged backwards by a climb the rider is no
            // longer pedalling up. Either way the simulation holds them still
            // rather than rolling them back down the route.
            next = 0.0
        }
        return next.coerceAtMost(MAX_DESCENT_SPEED_MPS)
    }

    /**
     * The total force the road pushes back with, in newtons, for a rider of
     * [totalMassKg] travelling at [speedMps] on a road of [gradient].
     *
     * Gravity, rolling resistance and air, added up. Negative on a descent
     * steep enough that gravity outruns the other two, which is the physical
     * statement that the road is doing the work.
     *
     * This is the number a trainer in simulation mode ultimately has to
     * produce, so [app.indoorbike.shared.simulation.SimulationEngine] asks for
     * it directly rather than describing a road and hoping the machine derives
     * the same answer from a flywheel speed that belongs to a different gear.
     */
    fun roadForceNewtons(
        gradient: Double,
        speedMps: Double,
        totalMassKg: Double,
    ): Double {
        if (totalMassKg <= 0.0) return 0.0
        val hyp = sqrt(1.0 + gradient * gradient)
        val weight = totalMassKg * VirtualSpeed.GRAVITY_MS2
        val gravity = weight * (gradient / hyp)
        val rolling = VirtualSpeed.CRR * weight * (1.0 / hyp)
        val v = speedMps.coerceAtLeast(0.0)
        val drag = 0.5 * VirtualSpeed.AIR_DENSITY_KG_M3 * VirtualSpeed.CDA_M2 * v * v
        return gravity + rolling + drag
    }

    /**
     * Steady-state speed for [powerWatts] on a road of [gradient]: the speed
     * [step] converges to if nothing changes. Not used by the ride loop, which
     * integrates instead, but it is what tells a HUD what the current effort is
     * "worth" and what makes the physics testable against hand-checked numbers.
     *
     * Bisection rather than Newton, because on a descent the linear coefficient
     * turns negative and the cubic is no longer convex from every seed. There
     * is still exactly one non-negative root (one sign change in
     * `drag·v³ + slope·v − P`), and the bracket below always contains it.
     */
    fun steadyStateSpeedMps(
        powerWatts: Double,
        gradient: Double,
        totalMassKg: Double,
    ): Double {
        if (totalMassKg <= 0.0) return 0.0
        val hyp = sqrt(1.0 + gradient * gradient)
        val weight = totalMassKg * VirtualSpeed.GRAVITY_MS2
        val slope = weight * (gradient / hyp) + VirtualSpeed.CRR * weight * (1.0 / hyp)
        val drag = 0.5 * VirtualSpeed.AIR_DENSITY_KG_M3 * VirtualSpeed.CDA_M2
        val wheelWatts = powerWatts.coerceAtLeast(0.0) * VirtualSpeed.DRIVETRAIN_EFFICIENCY

        // No power and a road that is flat or uphill: the rider stops.
        if (wheelWatts <= 0.0 && slope >= 0.0) return 0.0

        var lo = 0.0
        var hi = MAX_DESCENT_SPEED_MPS
        // f is increasing wherever it matters above the root, so if the whole
        // bracket is still negative at the top the answer is the cap.
        if (drag * hi * hi * hi + slope * hi - wheelWatts < 0.0) return hi
        repeat(BISECTION_STEPS) {
            val mid = (lo + hi) / 2.0
            if (drag * mid * mid * mid + slope * mid - wheelWatts < 0.0) lo = mid else hi = mid
        }
        return ((lo + hi) / 2.0).coerceIn(0.0, MAX_DESCENT_SPEED_MPS)
    }

    /**
     * Longest single call [step] will integrate. A ride resumed after the
     * process was suspended for an hour must not fast-forward the rider down
     * the route; the loop re-anchors instead.
     */
    private const val MAX_TOTAL_STEP_SECONDS = 2.0

    /** 40 halvings of a 22 m/s bracket lands well inside floating-point noise. */
    private const val BISECTION_STEPS = 40
}
