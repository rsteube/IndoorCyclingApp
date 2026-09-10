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
import app.indoorbike.shared.route.RoutePosition
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.getCurrentEpochMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Rides a [RouteTrack]: turns the rider's watts into ground covered, and the
 * ground covered back into resistance on the trainer.
 *
 * The loop is a closed one, and it is the whole mode:
 *
 * 1. Read the power the trainer reports.
 * 2. Integrate it against the road's gradient ([RideDynamics]) to get speed,
 *    and speed against time to get a position on the route.
 * 3. Work out the force the road is pushing back with at that speed and
 *    gradient, multiply it by the rider's virtual gear ([Drivetrain]), and
 *    command exactly that force to the trainer.
 * 4. The rider feels the hill, pushes harder or shifts down, and step 1 sees
 *    it.
 *
 * The rider's effort is the only input. Nothing here holds a wattage, which is
 * exactly what separates this mode from ERG: a workout tells the rider what to
 * produce, a route tells them what they are up against.
 *
 * ## Why it runs at [TICK_MS] rather than the session's 1 Hz
 *
 * [app.indoorbike.shared.session.WorkoutSessionManager] ticks once a second,
 * which is the right rate for a clock and for telemetry. It is too slow for
 * this: at 1 Hz a rider descending at 60 km/h jumps 17 metres per frame, the
 * map marker stutters, and gradient changes reach the trainer up to a second
 * late. Four times a second is smooth to the eye and still nowhere near enough
 * traffic to bother the BLE stack, which is separately rate-limited (see
 * [pushRoadToTrainer]).
 */
class SimulationEngine(
    /**
     * The rider's current power in watts, read from the trainer. A lambda
     * rather than the [app.indoorbike.shared.ble.TrainerManager] itself, in
     * the same style as the session manager's own collaborators: the loop
     * needs two facts about the trainer, and taking them as functions keeps
     * the physics testable without a BLE stack under it.
     */
    private val powerWatts: () -> Int,
    /**
     * The rider's cadence in rpm, read from the trainer.
     *
     * Not decoration: a gear is a kinematic constraint, and without a cadence
     * there is nothing for it to constrain. See [DriveSpeed]. A source that
     * cannot report one is fine, and falls back to the route's own speed.
     */
    private val cadenceRpm: () -> Int = { 0 },
    /** Hand a road to the trainer. Wired to `TrainerManager::setSimulationParameters`. */
    private val applyRoad: (SimulationParameters) -> Unit,
    /** The rider's body mass in kg, read live so a correction mid-ride takes effect. */
    private val riderMassKg: () -> Float,
    private val bikeMassKg: () -> Float = { VirtualSpeed.DEFAULT_BIKE_MASS_KG },
    /**
     * The bike the rider is on. Read once per [load] rather than live: changing
     * drivetrain mid-ride would silently change what the gear number the rider
     * is looking at means.
     */
    private val drivetrainProvider: () -> Drivetrain = { Drivetrain.DEFAULT },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _route = MutableStateFlow<RouteTrack?>(null)
    val route: StateFlow<RouteTrack?> = _route.asStateFlow()

    /** Distance along the route, in metres. Never exceeds the route's length. */
    private val _distanceMeters = MutableStateFlow(0.0)
    val distanceMeters: StateFlow<Double> = _distanceMeters.asStateFlow()

    /**
     * Where on the road this ride began, which is only non-zero when the rider
     * chose to carry on from a previous sitting.
     *
     * [distanceMeters] is a position on the route, not a distance covered, and
     * the two are the same number only for a ride that started at the
     * trailhead. Anything reporting what *this* ride did has to subtract this,
     * or a rider who resumes at 42 km and rides 6 is told they rode 48.
     */
    private val _startedAtMeters = MutableStateFlow(0.0)
    val startedAtMeters: StateFlow<Double> = _startedAtMeters.asStateFlow()

    /** The same for climbing: what was already on the clock when this ride began. */
    private val _startedAtElevationGainMeters = MutableStateFlow(0.0)
    val startedAtElevationGainMeters: StateFlow<Double> =
        _startedAtElevationGainMeters.asStateFlow()

    private val _speedMps = MutableStateFlow(0.0)
    val speedMps: StateFlow<Double> = _speedMps.asStateFlow()

    /**
     * The speed the rider's legs and their chosen gear are actually turning
     * out, which is what the road force is evaluated at.
     *
     * Published because it is the number that says whether the drivetrain
     * model is working. It should track [speedMps] closely on a settled road -
     * the two solve the same equation - and diverge exactly when the rider is
     * in the wrong gear, coasting, or spun out. A permanent offset between
     * them is [Drivetrain.ASSUMED_REAL_DEVELOPMENT_M] being wrong for this
     * rider's bike, and nothing else.
     */
    private val _driveSpeedMps = MutableStateFlow(0.0)
    val driveSpeedMps: StateFlow<Double> = _driveSpeedMps.asStateFlow()

    /**
     * Whether [driveSpeedMps] is the drivetrain's answer or an echo of the
     * route's own, which is what it falls back to until a crank has turned.
     */
    private val _driveSpeedKnown = MutableStateFlow(false)
    val driveSpeedKnown: StateFlow<Boolean> = _driveSpeedKnown.asStateFlow()

    /** Smoothed road gradient under the rider, as a fraction (0.064 = 6.4 %). */
    private val _gradient = MutableStateFlow(0.0)
    val gradient: StateFlow<Double> = _gradient.asStateFlow()

    private val _position = MutableStateFlow<RoutePosition?>(null)
    val position: StateFlow<RoutePosition?> = _position.asStateFlow()

    private val _drivetrain = MutableStateFlow(Drivetrain.DEFAULT)
    val drivetrain: StateFlow<Drivetrain> = _drivetrain.asStateFlow()

    private val _gearIndex = MutableStateFlow(Drivetrain.DEFAULT.startIndex)
    val gearIndex: StateFlow<Int> = _gearIndex.asStateFlow()

    /** Metres climbed so far, summed off the smoothed profile as the rider passes. */
    private val _elevationGainMeters = MutableStateFlow(0.0)
    val elevationGainMeters: StateFlow<Double> = _elevationGainMeters.asStateFlow()

    /** True once the rider has reached the end of the route. */
    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * Called once when the route runs out, on the engine's own dispatcher.
     * The session manager uses it to finish the ride the same way a workout's
     * final segment does.
     */
    var onRouteFinished: (() -> Unit)? = null

    private var loop: Job? = null
    private var lastTickEpochMs: Long = 0

    /** Cadence into road speed, through whatever gear the rider is in. */
    private val driveSpeed = DriveSpeed()

    /** A hand off the line, fading to nothing once the rider is moving. */
    private val launchAssist = LaunchAssist()

    /**
     * The acceleration term of the road load, taken off the route's own
     * integrated speed. Exact here rather than estimated: [advance] has just
     * finished computing the very number this is the derivative of.
     */
    private val roadAcceleration = RoadAcceleration()

    // What was last actually written to the trainer, so we only write again
    // when the road has meaningfully changed. Null means nothing is in force.
    private var lastSentForceNewtons: Double? = null
    private var lastSentGearIndex: Int = -1
    private var lastSentEpochMs: Long = 0

    /**
     * A road change that had to be written but arrived inside the rate
     * limiter's floor, so the next tick owes it.
     *
     * Without this a rider working the shifter quickly would have shifts
     * silently dropped rather than coalesced: the write is refused, the gear
     * still moves, and the trainer keeps applying the previous gear's load
     * until something else happens to trigger a send. Coalescing is fine
     * (only the latest gear matters); losing the change is not.
     */
    private var sendOwed = false

    /**
     * Put a route under the rider. Resets progress unless [startDistanceMeters]
     * says otherwise, which is how a crash-recovered ride picks up where it
     * stopped rather than at the trailhead.
     */
    fun load(
        route: RouteTrack,
        startDistanceMeters: Double = 0.0,
        /** [USE_START_GEAR] picks the drivetrain's own opening gear. */
        gearIndex: Int = USE_START_GEAR,
        elevationGainMeters: Double = 0.0,
        /**
         * Where *this ride* began, when that is not where it is being placed.
         *
         * Defaults to [startDistanceMeters], which is right for a rider
         * choosing to carry on down a route they part-rode last week: that is
         * a new ride, and it starts here. It is wrong for crash recovery,
         * where the ride being restored is the same ride and began wherever it
         * began. Passing the origin separately is what keeps
         * [startedAtMeters] a statement about the ride rather than about the
         * moment it was reloaded.
         */
        startedAtMeters: Double = startDistanceMeters,
        startedAtElevationGainMeters: Double = elevationGainMeters,
    ) {
        stop()
        val drivetrain = drivetrainProvider()
        _drivetrain.value = drivetrain
        _route.value = route
        _distanceMeters.value = startDistanceMeters.coerceIn(0.0, route.totalDistanceMeters)
        _startedAtMeters.value = startedAtMeters.coerceIn(0.0, _distanceMeters.value)
        _startedAtElevationGainMeters.value =
            startedAtElevationGainMeters.coerceIn(0.0, elevationGainMeters.coerceAtLeast(0.0))
        _gearIndex.value =
            if (gearIndex == USE_START_GEAR) drivetrain.startIndex else drivetrain.clampIndex(gearIndex)
        _elevationGainMeters.value = elevationGainMeters.coerceAtLeast(0.0)
        _speedMps.value = 0.0
        _finished.value = false
        _gradient.value = route.gradientAt(_distanceMeters.value)
        _position.value = route.positionAt(_distanceMeters.value)
        driveSpeed.reset()
        roadAcceleration.reset()
        launchAssist.reset()
        _driveSpeedMps.value = 0.0
        _driveSpeedKnown.value = false
        lastSentForceNewtons = null
        lastSentGearIndex = -1
    }

    /** Forget the route entirely. Called when the ride is torn down. */
    fun clear() {
        stop()
        _route.value = null
        _position.value = null
        _distanceMeters.value = 0.0
        _startedAtMeters.value = 0.0
        _startedAtElevationGainMeters.value = 0.0
        _speedMps.value = 0.0
        _gradient.value = 0.0
        _elevationGainMeters.value = 0.0
        _finished.value = false
    }

    /**
     * Start (or resume) simulating. Re-anchors the clock, so time spent paused
     * or backgrounded never turns into distance the rider did not cover, and
     * force-sends the road because a pause puts the trainer back into ERG.
     */
    fun start() {
        val route = _route.value ?: return
        if (route.isEmpty || _finished.value) return
        loop?.cancel()
        lastTickEpochMs = getCurrentEpochMillis()
        lastSentForceNewtons = null
        sendOwed = false
        // A pause leaves stale momentum behind. Resuming must not bill the
        // rider for an acceleration that happened while they were stopped, and
        // a ride resuming from a standstill is pulling away like any other.
        roadAcceleration.reset()
        launchAssist.reset()
        _running.value = true
        pushRoadToTrainer(force = true)
        loop = scope.launch {
            while (isActive) {
                val now = getCurrentEpochMillis()
                val dt = ((now - lastTickEpochMs) / 1000.0).coerceIn(0.0, MAX_TICK_SECONDS)
                lastTickEpochMs = now
                advance(dt)
                delay(TICK_MS)
            }
        }
    }

    /**
     * Stop simulating and let the bike coast to a standstill.
     *
     * The trainer is deliberately left alone here. Every caller that pauses a
     * ride already zeroes it through the session manager, and a second write
     * racing that one would only fight it.
     */
    fun stop() {
        loop?.cancel()
        loop = null
        _running.value = false
        _speedMps.value = 0.0
        _driveSpeedMps.value = 0.0
        roadAcceleration.reset()
        lastSentForceNewtons = null
        sendOwed = false
    }

    /** One step towards a harder gear. */
    fun shiftUp() = shiftBy(+1)

    /** One step towards an easier gear. */
    fun shiftDown() = shiftBy(-1)

    fun shiftBy(steps: Int) {
        if (_route.value == null) return
        val next = _drivetrain.value.clampIndex(_gearIndex.value + steps)
        if (next == _gearIndex.value) return
        _gearIndex.value = next
        // A shift has to be felt immediately, not on the next gradient change:
        // the whole point of the button is that the resistance responds to it.
        if (_running.value) pushRoadToTrainer(force = true)
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    // Internals ──────────────────────────────────────────────────────────────

    /**
     * One step of the loop: [dtSeconds] of riding at whatever the rider is
     * currently producing.
     *
     * Internal rather than private so tests can drive the closed loop with a
     * clock of their own. The running loop measures the real elapsed time and
     * calls this; nothing else should.
     */
    internal fun advance(dtSeconds: Double) {
        val route = _route.value ?: return
        val dt = dtSeconds
        if (dt <= 0.0) return

        // No body weight, no ride. The road model needs a mass, and the bike's
        // own 9 kg is not it: without this guard an unset weight would send a
        // riderless bike down the route at an impossible speed. Same contract
        // as [VirtualSpeed], which returns zero rather than guessing a rider.
        val rider = riderMassKg()
        if (rider <= 0f) return
        val totalMass = (rider + bikeMassKg()).toDouble()
        if (totalMass <= 0.0) return

        val previousDistance = _distanceMeters.value
        val gradient = route.gradientAt(previousDistance)
        val power = powerWatts().toDouble()

        val speed = RideDynamics.step(
            speedMps = _speedMps.value,
            powerWatts = power,
            gradient = gradient,
            totalMassKg = totalMass,
            dtSeconds = dt,
        )
        val distance = (previousDistance + speed * dt).coerceAtMost(route.totalDistanceMeters)

        // The two halves of the drivetrain model, both refreshed before any
        // force is computed from them: how fast the gear is turning the road
        // out, and how hard the bike is changing speed.
        driveSpeed.update(cadenceRpm(), dt)
        roadAcceleration.update(speed, dt)
        launchAssist.update(speed, dt)
        _driveSpeedMps.value = driveSpeed.speedMps(
            developmentMeters = _drivetrain.value.developmentMeters(_gearIndex.value),
            fallbackMps = speed,
        )
        _driveSpeedKnown.value = driveSpeed.available

        // Climbing is measured off the smoothed profile between the two
        // positions, so it agrees with the gradient the rider was made to feel
        // rather than with the raw GPS altitude they were not.
        val climbed = route.elevationAt(distance) - route.elevationAt(previousDistance)
        if (climbed > 0.0) _elevationGainMeters.value += climbed

        _speedMps.value = speed
        _distanceMeters.value = distance
        _gradient.value = route.gradientAt(distance)
        _position.value = route.positionAt(distance)

        if (distance >= route.totalDistanceMeters - FINISH_TOLERANCE_METERS) {
            finishRoute()
            return
        }
        pushRoadToTrainer(force = false)
    }

    private fun finishRoute() {
        if (_finished.value) return
        _finished.value = true
        stop()
        Log.i(TAG, "Route finished")
        onRouteFinished?.invoke()
    }

    /**
     * Tell the trainer exactly how hard to push back.
     *
     * ## Why this commands a force rather than a road
     *
     * The obvious implementation of simulation mode is to pass the route's
     * gradient straight through to the trainer and let its own road model do
     * the rest. That is what this did first, and on a real bike it is wrong in
     * two ways the rider feels immediately:
     *
     *  - **Shifting on the flat did nothing.** The gradient is zero there, so
     *    scaling it by the gear scaled zero. All that was left were the rolling
     *    and aerodynamic terms, worth a few newtons between the easiest gear
     *    and the hardest. A shift has to be felt at once or it is not a shift.
     *  - **The trainer's aerodynamic term comes from its own flywheel speed**,
     *    which reflects the real gear the bike is parked in, not the virtual
     *    one. Once the rider is shifting virtually, the machine's idea of how
     *    fast it is going has nothing to do with how fast they are travelling
     *    on the route, so any resistance derived from it is wrong. On a descent
     *    in a small real gear the flywheel barely turns, that term collapses,
     *    and the rider spins out against nothing.
     *
     * So the road model lives here instead, where the *virtual* speed is known.
     * This computes the force the road is really pushing back with, multiplies
     * it by the gear, and sends that force. The trainer is asked for a number
     * of newtons and nothing else: rolling resistance and the wind coefficient
     * go out as zero, so the machine's own speed cannot get into the answer.
     *
     * A force is expressed as a gradient because FTMS has no force field. Grade
     * is the one term that produces a speed-independent force, and it does so
     * against whatever mass the trainer assumes ([ASSUMED_TRAINER_MASS_KG]).
     * The rider's real mass is already inside [RideDynamics.roadForceNewtons], so it must
     * not be applied twice.
     *
     * Rate-limited two ways: never more often than [MIN_SEND_INTERVAL_MS], and
     * in between only when the force has moved by [FORCE_EPSILON_NEWTONS] or
     * the rider has shifted, with an unconditional restatement every
     * [REFRESH_INTERVAL_MS] because a trainer that dropped and reconnected has
     * forgotten it. A control-point write shares the GATT queue with the
     * telemetry notifications, and flooding it is what makes cadence drop out.
     */
    private fun pushRoadToTrainer(force: Boolean) {
        val now = getCurrentEpochMillis()
        val gearIndex = _gearIndex.value
        val targetForce = trainerForceNewtons(gearIndex)

        val elapsed = now - lastSentEpochMs
        val mustSend = force || sendOwed
        // A shift is allowed to jump ahead of the loop's routine traffic.
        val floorMs = if (mustSend) MIN_SHIFT_INTERVAL_MS else MIN_SEND_INTERVAL_MS
        if (elapsed < floorMs) {
            // Inside the floor. A forced send is remembered rather than
            // dropped, so holding the shift button coalesces into one write
            // instead of either flooding the control point or losing shifts.
            if (mustSend) sendOwed = true
            return
        }
        if (!mustSend) {
            val previous = lastSentForceNewtons
            val unchanged = previous != null &&
                gearIndex == lastSentGearIndex &&
                abs(targetForce - previous) < FORCE_EPSILON_NEWTONS
            if (unchanged && elapsed < REFRESH_INTERVAL_MS) return
        }

        sendOwed = false
        lastSentEpochMs = now
        lastSentForceNewtons = targetForce
        lastSentGearIndex = gearIndex

        applyRoad(
            SimulationParameters(
                windSpeedMps = 0.0,
                gradePercent =
                    targetForce / (ASSUMED_TRAINER_MASS_KG * VirtualSpeed.GRAVITY_MS2) * 100.0,
                // Deliberately zero: everything the rider should feel is
                // already in the grade above, and anything the trainer added
                // from here would be computed against its own flywheel speed.
                rollingResistance = 0.0,
                windResistanceKgPerM = 0.0,
            )
        )
    }

    /**
     * The force the trainer should apply, in newtons at its wheel: the road's
     * own resistance scaled by the virtual gear.
     */
    internal fun trainerForceNewtons(gearIndex: Int): Double {
        val rider = riderMassKg()
        if (rider <= 0f) return 0.0
        val drivetrain = _drivetrain.value
        val gearFactor = drivetrain.loadFactor(gearIndex)
        val totalMass = (rider + bikeMassKg()).toDouble()

        // The road is evaluated at the speed *this gear* is turning out, not at
        // the speed the rider happens to be travelling. That is what makes a
        // shift a shift: it moves the aerodynamic term with the square of the
        // ratio instead of leaving it fixed while a multiplier slides past it.
        // The two agree wherever the rider is settled, because both solve
        // P = F_road(v) . v, and they are meant to disagree everywhere else.
        val roadSpeed = driveSpeed.speedMps(
            developmentMeters = drivetrain.developmentMeters(gearIndex),
            fallbackMps = _speedMps.value,
        )
        val road = RideDynamics.roadForceNewtons(
            gradient = _gradient.value,
            speedMps = roadSpeed,
            totalMassKg = totalMass,
        ) + RideDynamics.inertiaForceNewtons(
            // The fourth force a road pushes back with. [advance] integrated
            // the speed against this same mass; commanding only the other
            // three was the model telling the rider their bike weighs nothing
            // the moment it changes speed.
            accelerationMps2 = roadAcceleration.mps2,
            totalMassKg = totalMass,
        )
        // A descent can pull harder than the road resists, which is a negative
        // force the trainer has no way to apply. What is left in that case is
        // the drivetrain's own drag, which a real bike has too and which is
        // what keeps a freewheeling rider's legs from spinning against
        // literally nothing. It scales with the gear, so choosing a big gear on
        // a descent still leaves something to push against.
        // The floor is not assisted: it is already a few newtons, and a rider
        // pulling away should still feel a chain rather than nothing at all.
        val floor = FREEWHEEL_DRAG_NEWTONS * gearFactor
        val commanded = road * gearFactor * launchAssist.factor
        return softCeiling(commanded.coerceAtLeast(floor))
    }

    /**
     * Hold the commanded force under [MAX_TRAINER_FORCE_NEWTONS] without ever
     * flattening out, so a shift always changes something.
     *
     * A hard clamp here was a real bug, and the case that exposed it is the one
     * that matters most: arriving at a climb off a fast descent, in a big gear.
     * Gravity and a large aerodynamic term stack up, the gear multiplies both,
     * and the result lands far above anything a trainer will apply. Clipped
     * flat, the first six downshifts then commanded exactly the same force -
     * the rider works the shifter in the one moment they most need it to
     * respond and feels nothing at all.
     *
     * Above [SOFT_KNEE_NEWTONS] the curve compresses instead of clipping. It is
     * strictly increasing, so every gear still maps to its own force and each
     * shift is felt, while the output still approaches the ceiling rather than
     * crossing it. Below the knee, which is all ordinary riding, nothing is
     * changed at all.
     *
     * Lives on the companion object rather than the instance because companion
     * mode commands the same trainer through the same envelope
     * ([app.indoorbike.shared.companion.CompanionEngine]), and a force ceiling
     * with two implementations is a force ceiling with two behaviours.
     */
    companion object {
        private const val TAG = "SimulationEngine"

        internal fun softCeiling(force: Double): Double {
            if (force <= SOFT_KNEE_NEWTONS) return force
            val headroom = MAX_TRAINER_FORCE_NEWTONS - SOFT_KNEE_NEWTONS
            return SOFT_KNEE_NEWTONS + headroom * tanh((force - SOFT_KNEE_NEWTONS) / headroom)
        }

        /**
         * Passed to [load] as the gear when the caller has no opinion,
         * which is every fresh ride. A resumed one passes the gear it
         * was actually in.
         */
        const val USE_START_GEAR = -1

        /** 4 Hz: smooth on the map, cheap on the battery. */
        internal const val TICK_MS = 250L

        /**
         * Longest gap a single tick will integrate. A ride whose process was
         * frozen must not wake up and teleport the rider down the route.
         */
        private const val MAX_TICK_SECONDS = 1.5

        /** Within this of the end, the route counts as ridden. */
        private const val FINISH_TOLERANCE_METERS = 1.0

        /**
         * Floor on the routine traffic the 4 Hz loop generates, so gradient
         * changes cannot saturate the GATT queue.
         */
        internal const val MIN_SEND_INTERVAL_MS = 200L

        /**
         * Floor on a shift, which jumps that queue.
         *
         * On a descent the force is changing every tick, so routine sends run
         * at the floor above continuously and a shift arriving between them
         * used to wait for the next slot: a fifth of a second before the write
         * even started, on top of the BLE round trip. That is exactly long
         * enough to feel like the shifter is not working. A shift is a
         * deliberate act a human performs a few times a second at most, so it
         * gets its own, far shorter floor - long enough to coalesce a
         * double-tap, short enough to be indistinguishable from immediate.
         */
        internal const val MIN_SHIFT_INTERVAL_MS = 60L

        /** Restate an unchanged road at least this often. */
        internal const val REFRESH_INTERVAL_MS = 5_000L

        /**
         * Change in commanded force worth a write, in newtons. Below roughly
         * this the rider cannot tell, and every write costs GATT bandwidth the
         * telemetry notifications also need.
         */
        internal const val FORCE_EPSILON_NEWTONS = 1.5

        /**
         * The total mass a trainer is assumed to apply simulation parameters
         * against when the app has not told it otherwise. Around a 75 kg rider
         * on a 9 kg bike, which is the industry's usual default. Used only to
         * turn a force back into the grade that produces it.
         */
        internal const val ASSUMED_TRAINER_MASS_KG = 84.0

        /**
         * What a freewheeling drivetrain still costs, before the gear
         * multiplier. Small: enough that the pedals are never completely dead
         * on a descent, not so much that coasting becomes work.
         */
        internal const val FREEWHEEL_DRAG_NEWTONS = 4.0

        /**
         * Where the commanded force starts being compressed rather than taken
         * at face value. Chosen above anything an ordinary stretch of road
         * produces, so the compression only ever engages where the alternative
         * was clipping.
         */
        internal const val SOFT_KNEE_NEWTONS = 140.0

        /**
         * Ceiling the commanded force approaches but never reaches. About a
         * 32 % grade at the assumed mass: past anything rideable, and past
         * where trainers apply their own limit, which is the point - the last
         * word on what is too hard belongs to the machine, not to a clamp here
         * that would take the rider's gears away first.
         */
        internal const val MAX_TRAINER_FORCE_NEWTONS = 265.0
    }
}
