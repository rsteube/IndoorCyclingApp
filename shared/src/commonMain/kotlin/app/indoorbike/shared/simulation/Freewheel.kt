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

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * The freewheel: a change in the road reaches the pedals only while the legs
 * are keeping up with the wheel.
 *
 * ## The report
 *
 * Down a descent at 70 km/h on the gravel 1x, in the hardest gear, the road
 * eased from steep to about -2 % and the pedals went heavy at once. The numbers
 * agree: at 70 km/h the air is worth 83 N at the wheel, which the steep
 * gradient had been cancelling. Take the gradient away and the road is suddenly
 * asking 72 N, the 40x10 multiplies that by 2.6, and on an abrupt change the
 * trainer is told 186 N after the soft ceiling, a 22 % wall.
 *
 * The inertia term already knew better. A bike coasting at 70 km/h on -2 %
 * slows at 0.8 m/s^2, and `m . a` cancels the road almost exactly: the model's
 * own way of saying a coasting bike asks nothing of the legs. But it learns the
 * deceleration from the speed, through a filter, so it says so most of a
 * second late, and the rider meets the whole step in the meantime. Then they
 * push against it, the world credits the push as power into a wheel their legs
 * were never connected to, the bike does not slow as it should, and the filter
 * takes seconds to catch up. Simulated with a rider, a trainer flywheel and Col
 * de Berg's own integrator, what the trainer actually applies peaks between 80
 * and 95 N and takes about five seconds to come back under 30 N.
 *
 * ## What a bicycle does
 *
 * At 70 km/h the 40x10 needs 132 rpm to reach the wheel, and a rider turning
 * 100 is not connected to it. The road easing off slows the bike, which is all
 * a road can do. Nothing reaches the pedals until the bike has slowed to the
 * speed the legs are turning out, about 53 km/h and eight seconds later.
 *
 * ## What this does
 *
 * While the legs are behind the wheel, a road getting harder, in gradient or
 * surface, is not passed to the pedals as it arrives. It is held here as
 * [absorbedNewtons] and let go over [ABSORB_SECONDS], which is how long the
 * inertia term takes to see the bike's speed answer it. The two hand over
 * without a gap: what the road adds, the bike's slowing takes away, and the
 * legs meet neither. It is let go slightly slower than the inertia term
 * arrives, so the handover errs light rather than heavy.
 *
 * Only changes are held, never a road. A steady road at any speed is exactly
 * what it was, and so is a road change that meets legs turning with the wheel:
 * those legs are connected to the bike, and a climb reaches them the way it
 * always has. Between [BEHIND] and [KEEPING_UP] the share held eases from all
 * to none, so a rider a few percent behind is not treated as a coasting one.
 * And a road getting easier is never held: legs behind the wheel should feel
 * less than the road, not more.
 *
 * ## Why "behind the wheel" is measured
 *
 * `cadence x development` is the speed the legs are turning the virtual wheel
 * only if the rider's own bike sits in the real gear the model assumes,
 * [Drivetrain.ASSUMED_REAL_DEVELOPMENT_M]. A rider whose chain is on a taller
 * real gear pushes more watts at the same cadence, rides faster for it, and
 * looks to the drivetrain as if their legs were always behind: on a 50x17,
 * turning the virtual wheel at about half the world's speed while riding
 * steadily.
 * Judged against the assumption, that rider would freewheel through every
 * climb.
 *
 * So the ratio is measured, as [coupling]: the world's speed over the
 * drivetrain's, while the rider is riding steadily and doing the work. On a
 * bike that is not accelerating, the road is taking exactly the watts the legs
 * deliver, so the legs are connected by definition and the ratio is whatever
 * this rider's trainer makes it. Until [CALIBRATION_SECONDS] of such riding has
 * been seen, nothing is held, and the ride behaves as it did before this class.
 *
 * ## After a shift
 *
 * Every downshift leaves the legs behind the new gear for a moment, until they
 * spin up to it; [VirtualDrivetrain.SHIFT_RELEASE_SECONDS] is what the rider
 * feels of that. A rider changes down as a climb starts, so a road change often
 * arrives in exactly that moment, and it has to reach them. For
 * [SHIFT_GRACE_SECONDS] after a shift nothing is held and nothing is learned.
 */
class Freewheel {

    private var logCoupling = 0.0
    private var learnedSeconds = 0.0
    private var sinceShiftSeconds = SHIFT_GRACE_SECONDS

    /**
     * The part of the road the pedals have not been asked for yet, in newtons
     * at the wheel. Positive while a road that got harder is being carried by
     * the bike's momentum. Relaxes to zero.
     */
    var absorbedNewtons: Double = 0.0
        private set

    /**
     * The world's speed over the drivetrain's while this rider rides steadily.
     * About 1 for a rider whose own bike sits in the assumed real gear, and
     * larger for one whose bike sits in a taller one.
     */
    val coupling: Double get() = exp(logCoupling)

    /** True once [coupling] has been measured for long enough to act on. */
    val calibrated: Boolean get() = learnedSeconds >= CALIBRATION_SECONDS

    /** A new ride, and perhaps a different bike on the trainer: forget everything. */
    fun reset() {
        logCoupling = 0.0
        learnedSeconds = 0.0
        sinceShiftSeconds = SHIFT_GRACE_SECONDS
        release()
    }

    /**
     * Hand back whatever is being held, at once. For a road being stated in
     * full: a start, a stop, a power target giving the control point back.
     * The measured [coupling] belongs to the rider's bike and is kept.
     */
    fun release() {
        absorbedNewtons = 0.0
    }

    /** The legs have a new gear to catch up with. See "After a shift". */
    fun onShift() {
        sinceShiftSeconds = 0.0
    }

    /**
     * How far the legs are behind the wheel, as the share of a road change
     * they are not there to feel: 0 while they keep up, 1 when they are well
     * behind. Zero whenever it cannot be known.
     *
     * [driveSpeedMps] is `cadence x development` in the current gear, or null
     * while no crank has turned.
     */
    fun slack(driveSpeedMps: Double?, worldSpeedMps: Double): Double {
        if (driveSpeedMps == null || !calibrated) return 0.0
        if (worldSpeedMps < MIN_SPEED_MPS || sinceShiftSeconds < SHIFT_GRACE_SECONDS) return 0.0
        val legs = driveSpeedMps * coupling / worldSpeedMps
        val x = ((KEEPING_UP - legs) / (KEEPING_UP - BEHIND)).coerceIn(0.0, 1.0)
        // Smoothstep, so a rider hovering near either end does not feel the
        // share move in steps.
        return x * x * (3.0 - 2.0 * x)
    }

    /**
     * The road just changed by [changeNewtons] at the wheel (gravity and
     * rolling, the parts that do not depend on speed). Hold [slack] of it.
     *
     * Only a road getting harder is held. Legs behind the wheel should be
     * feeling less than the road, never more, so a road getting easier is let
     * through at once, as it always was.
     */
    fun absorb(changeNewtons: Double, slack: Double) {
        if (slack <= 0.0 || changeNewtons <= 0.0) return
        absorbedNewtons += changeNewtons * slack
    }

    /**
     * One tick of [dtSeconds]: let go of some of what is held, and learn the
     * [coupling] if the rider is riding steadily.
     *
     * [roadNewtons] and [inertiaNewtons] are the road's force at the world's
     * speed and the bike's mass changing speed. [settled] is the drivetrain's
     * own say: a road in force on the trainer, no launch assist, no shift
     * release running.
     */
    fun update(
        dtSeconds: Double,
        driveSpeedMps: Double?,
        cadenceRpm: Double,
        worldSpeedMps: Double,
        roadNewtons: Double,
        inertiaNewtons: Double,
        settled: Boolean,
    ) {
        if (dtSeconds <= 0.0) return
        sinceShiftSeconds = (sinceShiftSeconds + dtSeconds).coerceAtMost(SHIFT_GRACE_SECONDS)
        absorbedNewtons *= exp(-dtSeconds / ABSORB_SECONDS)
        if (abs(absorbedNewtons) < LET_GO_NEWTONS) absorbedNewtons = 0.0

        if (driveSpeedMps == null || driveSpeedMps <= 0.0) return
        val steady = settled &&
            cadenceRpm >= MIN_CADENCE_RPM &&
            worldSpeedMps >= MIN_SPEED_MPS &&
            sinceShiftSeconds >= SHIFT_GRACE_SECONDS &&
            absorbedNewtons == 0.0 &&
            roadNewtons >= WORKING_ROAD_NEWTONS &&
            abs(inertiaNewtons) <= STEADY_SHARE * roadNewtons
        if (!steady) return

        val sample = ln(worldSpeedMps / driveSpeedMps).coerceIn(ln(MIN_COUPLING), ln(MAX_COUPLING))
        // A long gap is not a long observation: it is one sample.
        val step = dtSeconds.coerceAtMost(MAX_LEARNING_STEP_SECONDS)
        logCoupling = if (learnedSeconds == 0.0) {
            sample
        } else {
            logCoupling + (sample - logCoupling) * (1.0 - exp(-step / LEARNING_SECONDS))
        }
        learnedSeconds += step
    }

    companion object {
        /**
         * Legs turning the wheel this slowly or slower, as a share of the
         * world's speed, are behind it: a road change is carried whole by the
         * bike's momentum. Well clear of a pedal stroke's worth of cadence
         * wobble.
         */
        const val BEHIND = 0.80

        /**
         * Legs this close to the world's speed or closer are keeping up, and a
         * road change reaches them whole. The gap to 1 is the measurement's
         * own uncertainty: a rider really turning with the wheel must never
         * read as behind it.
         */
        const val KEEPING_UP = 0.95

        /**
         * How long what is held takes to be let go, as a time constant.
         * Slightly longer than [RoadAcceleration.SMOOTHING_SECONDS], the time
         * the inertia term takes to see the bike's speed answer the same
         * change, so the handover undershoots rather than overshoots. At the
         * inertia term's own 0.7 s the let-go ran a little ahead of it, by the
         * delay in sampling the speed, and the gap came back as a small bump.
         */
        const val ABSORB_SECONDS = 1.0

        /** Below this, what is left of a held change is nothing. */
        private const val LET_GO_NEWTONS = 0.01

        /**
         * How long after a shift the legs are given to catch up with it before
         * they count as behind. The shift release is over in about a second;
         * the cadence reading, filtered at both ends, takes about another.
         */
        const val SHIFT_GRACE_SECONDS = 2.0

        /**
         * Nothing is held below this speed. Pulling away and grinding up a
         * wall are all force and no momentum, and there the ratio of two small
         * speeds says nothing either.
         */
        const val MIN_SPEED_MPS = 3.0

        // Measuring the coupling ───────────────────────────────────────────

        /**
         * Seconds of steady riding before [coupling] is believed. Any ride
         * reaches it within its first few minutes, and until then this class
         * does nothing at all.
         */
        const val CALIBRATION_SECONDS = 10.0

        /**
         * Time constant of the running measurement: long enough to average a
         * rider's cadence wandering, short enough to follow them if they move
         * their own chain mid-ride.
         */
        const val LEARNING_SECONDS = 10.0

        /** No single tick counts as more steady riding than this. */
        private const val MAX_LEARNING_STEP_SECONDS = 0.5

        /**
         * The road has to be asking for real work, so the rider is delivering
         * it: about an easy 15 km/h on the flat.
         */
        const val WORKING_ROAD_NEWTONS = 8.0

        /**
         * And the bike steady: its change of speed worth no more than this
         * share of the road. Tight on purpose, because an accelerating bike is
         * one the legs are ahead of and the ratio is biased by exactly this
         * share.
         */
        const val STEADY_SHARE = 0.10

        /** Below this cadence the legs are not turning steadily enough to measure. */
        const val MIN_CADENCE_RPM = 40.0

        /**
         * Bounds on [coupling]: a real gear from half the assumed one to twice
         * it, a 34x42 to a 50x15. Past either, a measurement is a bad one.
         */
        const val MIN_COUPLING = 0.5
        const val MAX_COUPLING = 2.0
    }
}
