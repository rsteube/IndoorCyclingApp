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

import app.indoorbike.shared.ble.SimulationParameters
import app.indoorbike.shared.physics.RideDynamics
import app.indoorbike.shared.physics.VirtualSpeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/**
 * The virtual drivetrain between a described road and a real trainer: the
 * gears, the shifting, the force a gear asks for, and the paced writes that
 * carry it to the machine.
 *
 * One implementation for every mode that rides a road through virtual gears.
 * Route mode ([SimulationEngine]) integrates its own road out of power;
 * companion mode ([app.indoorbike.shared.companion.CompanionEngine]) is handed
 * one by a game. Where the road comes from is the only difference between
 * them, so everything that turns a road into resistance lives here, and a
 * change to how shifting feels is made once and ridden in both.
 *
 * ## What a shift does
 *
 * A gear is leverage. At the instant of a shift the bike is still doing the
 * speed it was doing - it has 85 kg of momentum and a shift adds no energy -
 * so the force at the wheel has not moved, and the rider meets it through a
 * different ratio. The force at the pedals changes by exactly the ratio between
 * the two gears, on the flat and on a climb alike, and the cadence changes by its
 * inverse. What happens next is the rider's doing: push harder in the harder
 * gear and the bike gathers speed over the next seconds, and the air, which
 * grows with the square of it, grows with it.
 *
 * So the road is evaluated at the world's speed, the one with the bike's mass
 * behind it, and the gear scales it:
 *
 *     force at the trainer = (road force at the bike's speed + m . a)
 *                            x (virtual development / real development)
 *
 * It used to be evaluated at `cadence x development` instead, the speed the
 * *new* gear would turn out at the cadence the legs had not yet had time to
 * change. That is the speed the bike would be doing after accelerating into the
 * new gear, reached in no time and for no energy, and on the flat it put the
 * cube of the ratio on the legs rather than the ratio: one sprocket at 30 km/h
 * on a 1x gravel cassette asked for 45 % more force where a bicycle asks for
 * 15 %. The two models agree once the rider has settled into a gear, because a
 * settled bike does travel at cadence x development; they differ in the
 * seconds after a shift, which is when a rider judges one.
 *
 * ## What a downshift adds
 *
 * On a road bike the legs spin up the instant the chain drops to a bigger
 * sprocket, because the bike's momentum drives them. On a trainer nothing
 * drives them: the pedals lighten by the ratio and the legs speed up only as
 * fast as they can accelerate the flywheel, which a rider notices as a
 * downshift that is late. A trainer's own smoothing of a new grade adds to it.
 * So a downshift lets go of a little more than its ratio for a moment
 * ([SHIFT_RELEASE_SECONDS]), and the extra relaxes away as the legs catch up.
 * An upshift gets no such overshoot: the pedals firming by the ratio is exactly
 * what a bicycle does, and anything more is a wall.
 *
 * ## The contract
 *
 * The owner describes the road with [setRoad] as it changes, calls [update]
 * once per tick, and calls [writeRoad] when the trainer should hear about it.
 * The road's speed is the *world's* speed: the bike's own, with its mass
 * behind it, as integrated by the owner or reported by a game.
 *
 * Called from the owner's loop and from whichever thread a shift or a road
 * frame arrives on. The gear changes atomically, so two shifters reached for at
 * once cannot lose a shift; the writes reach the trainer in the order they were
 * made through its single control-point queue, so a shift cannot be overtaken by
 * an older road.
 */
class VirtualDrivetrain(
    private val riderMassKg: () -> Float,
    private val bikeMassKg: () -> Float,
    /** The rider's cadence in rpm, as the trainer reports it. Zero when it cannot. */
    private val cadenceRpm: () -> Int,
    /** Hand a road to the trainer. Wired to `TrainerManager::setSimulationParameters`. */
    private val applyRoad: (SimulationParameters) -> Unit,
    /**
     * The pace of every write to this trainer. Shared with any other writer to
     * the same control point, so the two cannot flood it between them.
     */
    val pacer: TrainerWritePacer = TrainerWritePacer(),
) {
    private val _drivetrain = MutableStateFlow(Drivetrain.DEFAULT)
    val drivetrain: StateFlow<Drivetrain> = _drivetrain.asStateFlow()

    private val _gearIndex = MutableStateFlow(Drivetrain.DEFAULT.startIndex)
    val gearIndex: StateFlow<Int> = _gearIndex.asStateFlow()

    /**
     * The speed the rider's legs and their chosen gear are turning out.
     *
     * Published because it is the number that says whether the drivetrain is
     * settled: on a steady road it tracks the world's speed, and it diverges
     * exactly when the rider is in the wrong gear, coasting, or spun out.
     */
    private val _driveSpeedMps = MutableStateFlow(0.0)
    val driveSpeedMps: StateFlow<Double> = _driveSpeedMps.asStateFlow()

    /** Whether [driveSpeedMps] is the drivetrain's answer or an echo of the world's. */
    private val _driveSpeedKnown = MutableStateFlow(false)
    val driveSpeedKnown: StateFlow<Boolean> = _driveSpeedKnown.asStateFlow()

    /** The force last written to the trainer, in newtons. Zero before the first. */
    private val _commandedForceN = MutableStateFlow(0.0)
    val commandedForceN: StateFlow<Double> = _commandedForceN.asStateFlow()

    /** [commandedForceN] as the grade it was sent as, in percent. */
    private val _commandedGradePercent = MutableStateFlow(0.0)
    val commandedGradePercent: StateFlow<Double> = _commandedGradePercent.asStateFlow()

    /** Cadence into road speed, through whatever gear the rider is in. */
    private val driveSpeed = DriveSpeed()

    /** The fourth force of the road: the bike's mass changing speed. */
    private val roadAcceleration = RoadAcceleration()

    /** A hand off the line, fading to nothing once the rider is moving. */
    private val launchAssist = LaunchAssist()

    // The road as its owner last described it.
    private var gradient = 0.0
    private var crr = VirtualSpeed.CRR
    private var worldSpeedMps = 0.0

    // What was last actually written, so an unchanged road is not written again.
    private var lastSentForceNewtons: Double? = null
    private var lastSentGearIndex = -1

    /**
     * What a downshift is still letting go of: a multiplier under 1 on the
     * current gear's force, relaxing back to 1. See [SHIFT_RELEASE_SECONDS].
     */
    private var shiftRelease = 1.0

    /** Put the rider on [drivetrain] in [gearIndex], with nothing measured yet. */
    fun load(drivetrain: Drivetrain, gearIndex: Int) {
        _drivetrain.value = drivetrain
        _gearIndex.value = drivetrain.clampIndex(gearIndex)
        gradient = 0.0
        crr = VirtualSpeed.CRR
        worldSpeedMps = 0.0
        driveSpeed.reset()
        roadAcceleration.reset()
        launchAssist.reset()
        _driveSpeedMps.value = 0.0
        _driveSpeedKnown.value = false
        _commandedForceN.value = 0.0
        _commandedGradePercent.value = 0.0
        shiftRelease = 1.0
        forgetLastRoad()
        pacer.reset()
    }

    /**
     * The ride is starting, or resuming after a pause.
     *
     * A pause leaves stale momentum behind, and resuming must not bill the
     * rider for an acceleration that happened while they were stopped. A ride
     * resuming from a standstill is pulling away like any other, so the launch
     * assist is re-armed.
     */
    fun onStart() {
        roadAcceleration.reset()
        launchAssist.reset()
        forgetLastRoad()
        pacer.reset()
    }

    /**
     * The ride has stopped. Nothing is measuring the drivetrain, so it says so
     * rather than repeating whatever the last tick saw, and the first crank
     * after a resume is adopted whole rather than filtered up from the pause.
     */
    fun onStop() {
        roadAcceleration.reset()
        shiftRelease = 1.0
        driveSpeed.reset()
        _driveSpeedMps.value = 0.0
        _driveSpeedKnown.value = false
        forgetLastRoad()
        pacer.reset()
    }

    /**
     * Put the rider on a different bike, keeping them as near as possible to
     * the ground they were turning over.
     *
     * The gear is carried across by development rather than by position: a
     * rider in the eleventh of twenty gears is not asking to be in the
     * eleventh of twelve.
     */
    fun setDrivetrain(drivetrain: Drivetrain) {
        if (drivetrain.gears.isEmpty()) return
        val wanted = _drivetrain.value.developmentMeters(_gearIndex.value)
        _drivetrain.value = drivetrain
        _gearIndex.value = drivetrain.gears.indices.minByOrNull {
            abs(drivetrain.gears[it].developmentMeters - wanted)
        } ?: drivetrain.startIndex
        // A different bike is not a shift, and nothing is owed to the old one.
        shiftRelease = 1.0
    }

    /**
     * Move [steps] gears, harder for positive. Returns the gear the drivetrain
     * ended up in, which is the one it was in when the ladder has run out.
     *
     * Atomic on the gear itself, so two shifters reached for at once - the
     * screen and a handlebar remote - cannot lose a shift between them.
     */
    fun shiftBy(steps: Int): Int {
        var from = 0
        val to = _gearIndex.updateAndGet {
            from = it
            _drivetrain.value.clampIndex(it + steps)
        }
        when {
            to < from -> {
                // Compounded, so dumping three gears lets go of more than one,
                // and floored, so no number of them lets go of the road.
                val ratio = _drivetrain.value.developmentMeters(to) /
                    _drivetrain.value.developmentMeters(from)
                shiftRelease = (shiftRelease * ratio).coerceAtLeast(MIN_SHIFT_RELEASE)
            }
            // A harder gear ends any release still running: an upshift in the
            // middle of one is a rider who wants the road back, not half of it.
            to > from -> shiftRelease = 1.0
        }
        return to
    }

    /** The road under the rider, as the world now describes it. */
    fun setRoad(gradient: Double, worldSpeedMps: Double, crr: Double = VirtualSpeed.CRR) {
        this.gradient = gradient
        this.worldSpeedMps = worldSpeedMps
        this.crr = crr
    }

    /**
     * One tick: [dtSeconds] since the last. Refreshes the three filters the
     * force is built from - the cadence, the bike's acceleration and the launch
     * assist - from the road last described.
     */
    fun update(dtSeconds: Double) {
        if (shiftRelease < 1.0 && dtSeconds > 0.0) {
            shiftRelease = 1.0 - (1.0 - shiftRelease) * exp(-dtSeconds / SHIFT_RELEASE_SECONDS)
            if (shiftRelease > RELEASE_DONE) shiftRelease = 1.0
        }
        driveSpeed.update(cadenceRpm(), dtSeconds)
        roadAcceleration.update(worldSpeedMps, dtSeconds)
        launchAssist.update(worldSpeedMps, dtSeconds)
        _driveSpeedMps.value = driveSpeed.speedMps(
            developmentMeters = _drivetrain.value.developmentMeters(_gearIndex.value),
            fallbackMps = worldSpeedMps,
        )
        _driveSpeedKnown.value = driveSpeed.available
    }

    /**
     * Tell the trainer how hard to push back, if it needs telling.
     *
     * [forced] is a write the rider caused, a shift or a new bike, which
     * jumps the routine queue. An unforced one is skipped when neither the
     * gear nor the force has moved enough to feel, until the periodic
     * restatement falls due.
     *
     * ## Why this commands a force rather than a road
     *
     * Passing the road's gradient through to the trainer and letting its own
     * road model do the rest is wrong in two ways a rider feels at once.
     * Shifting on the flat does nothing, because scaling a zero gradient by a
     * gear scales zero. And the trainer's aerodynamic term comes from its own
     * flywheel speed, which reflects the real gear the bike is parked in rather
     * than the virtual one, so on a descent in a small real gear it collapses
     * and the rider spins out against nothing.
     *
     * So the road model lives here, where the virtual speed is known. This
     * computes the force the road is really pushing back with, multiplies it by
     * the gear, and sends that force as a grade, because FTMS has no force field
     * and grade is the one term that produces a speed-independent one. Rolling
     * resistance and wind go out as zero, so the machine's own speed cannot get
     * into the answer.
     *
     * Returns whether a write went out.
     */
    fun writeRoad(forced: Boolean, nowMs: Long): Boolean {
        if (!pacer.mayWrite(nowMs, forced)) return false
        val gear = _gearIndex.value
        val targetForce = trainerForceNewtons(gear)
        val previous = lastSentForceNewtons
        val unchanged = previous != null &&
            gear == lastSentGearIndex &&
            abs(targetForce - previous) < FORCE_EPSILON_NEWTONS
        if (unchanged && !pacer.mustRestate(nowMs, forced)) return false

        pacer.wrote(nowMs, forced)
        lastSentForceNewtons = targetForce
        lastSentGearIndex = gear
        val gradePercent = targetForce / (ASSUMED_TRAINER_MASS_KG * VirtualSpeed.GRAVITY_MS2) * 100.0
        _commandedForceN.value = targetForce
        _commandedGradePercent.value = gradePercent
        applyRoad(
            SimulationParameters(
                windSpeedMps = 0.0,
                gradePercent = gradePercent,
                // Deliberately zero: everything the rider should feel is
                // already in the grade above, and anything the trainer added
                // from here would be computed against its own flywheel speed.
                rollingResistance = 0.0,
                windResistanceKgPerM = 0.0,
            )
        )
        return true
    }

    /**
     * Forget what was last written, so the next road restates itself in full.
     * Called when something other than a road took the control point, such as
     * a power target, and on every start and stop.
     */
    fun forgetLastRoad() {
        lastSentForceNewtons = null
        lastSentGearIndex = -1
    }

    /** Clear the published command, when a power target has taken over. */
    fun clearCommand() {
        _commandedForceN.value = 0.0
        _commandedGradePercent.value = 0.0
    }

    /**
     * The force the trainer should apply in gear [gearIndex], in newtons at its
     * wheel: the road's own resistance scaled by the virtual gear.
     */
    fun trainerForceNewtons(gearIndex: Int): Double {
        val rider = riderMassKg()
        if (rider <= 0f) return 0.0
        val drivetrain = _drivetrain.value
        val gearFactor = drivetrain.loadFactor(gearIndex)
        val totalMass = (rider + bikeMassKg()).toDouble()

        val road = RideDynamics.roadForceNewtons(
            gradient = gradient,
            // The bike's speed, not the one the gear would turn out at the
            // legs' current cadence. See "What a shift does" above.
            speedMps = worldSpeedMps,
            totalMassKg = totalMass,
            crr = crr,
        ) + RideDynamics.inertiaForceNewtons(
            // The fourth force a road pushes back with. Commanding only the
            // other three tells the rider their bike weighs nothing the moment
            // it changes speed.
            accelerationMps2 = roadAcceleration.mps2,
            totalMassKg = totalMass,
        )
        // A descent can pull harder than the road resists, which is a negative
        // force the trainer has no way to apply. What is left is the
        // drivetrain's own drag, which a real bike has too. It scales with the
        // gear, so a big gear on a descent still leaves something to push
        // against. Not assisted: it is already a few newtons, and a rider
        // pulling away should still feel a chain.
        val floor = FREEWHEEL_DRAG_NEWTONS * gearFactor
        // Only the gear the rider is in has a downshift behind it. Asking what
        // another gear would cost is asking about that gear, settled.
        val release = if (gearIndex == _gearIndex.value) shiftRelease else 1.0
        val commanded = road * gearFactor * launchAssist.factor * release
        return softCeiling(commanded.coerceAtLeast(floor))
    }

    companion object {
        /**
         * How long a downshift takes to let go of its extra, as the time
         * constant of its relaxation back to the gear's own force.
         *
         * The release starts at the gear's ratio again, so the pedals at first
         * lighten by about twice the ratio, and it is two-thirds gone after
         * this long. Chosen for a flywheel, not a feeling: it is about how long
         * a rider's legs take to spin a direct-drive trainer up to a cadence
         * one sprocket higher, which is the thing the release is standing in
         * for. The number to tune if a downshift ever reads as a lurch.
         */
        const val SHIFT_RELEASE_SECONDS = 0.5

        /**
         * The most any run of downshifts lets go of the road at once, as a
         * multiplier: dumping five gears on a wall still leaves the rider
         * something to push against while their legs catch up.
         */
        const val MIN_SHIFT_RELEASE = 0.55

        /** Close enough to 1 that the release is over. */
        private const val RELEASE_DONE = 0.995

        /**
         * Change in commanded force worth a write, in newtons. Below roughly
         * this the rider cannot tell, and every write costs GATT bandwidth the
         * telemetry notifications also need.
         */
        const val FORCE_EPSILON_NEWTONS = 1.5

        /**
         * The total mass a trainer is assumed to apply simulation parameters
         * against when the app has not told it otherwise. Around a 75 kg rider
         * on a 9 kg bike, the industry's usual default. Used only to turn a
         * force back into the grade that produces it.
         */
        const val ASSUMED_TRAINER_MASS_KG = 84.0

        /**
         * What a freewheeling drivetrain still costs, before the gear
         * multiplier. Enough that the pedals are never completely dead on a
         * descent, not so much that coasting becomes work.
         */
        const val FREEWHEEL_DRAG_NEWTONS = 4.0

        /**
         * Where the commanded force starts being compressed rather than taken
         * at face value. Above anything an ordinary stretch of road produces, so
         * the compression only engages where the alternative was clipping.
         */
        const val SOFT_KNEE_NEWTONS = 140.0

        /**
         * Ceiling the commanded force approaches but never reaches. About a
         * 32 % grade at the assumed mass: past anything rideable and past where
         * trainers apply their own limit, so the last word on too hard belongs
         * to the machine rather than to a clamp that would take gears away first.
         */
        const val MAX_TRAINER_FORCE_NEWTONS = 265.0

        /**
         * Hold the commanded force under [MAX_TRAINER_FORCE_NEWTONS] without
         * ever flattening out, so a shift always changes something.
         *
         * A hard clamp here was a real bug, exposed arriving at a climb off a
         * fast descent in a big gear: gravity and a large air term stack up, the
         * gear multiplies both, and the first six downshifts all commanded the
         * clamp. Above [SOFT_KNEE_NEWTONS] the curve compresses instead. It is
         * strictly increasing, so every gear maps to its own force, and below
         * the knee, which is all ordinary riding, nothing changes at all.
         */
        fun softCeiling(force: Double): Double {
            if (force <= SOFT_KNEE_NEWTONS) return force
            val headroom = MAX_TRAINER_FORCE_NEWTONS - SOFT_KNEE_NEWTONS
            return SOFT_KNEE_NEWTONS + headroom * tanh((force - SOFT_KNEE_NEWTONS) / headroom)
        }
    }
}
