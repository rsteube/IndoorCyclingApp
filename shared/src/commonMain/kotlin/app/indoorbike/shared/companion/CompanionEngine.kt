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

import app.indoorbike.shared.ble.SimulationParameters
import app.indoorbike.shared.physics.RideDynamics
import app.indoorbike.shared.physics.VirtualSpeed
import app.indoorbike.shared.simulation.DriveSpeed
import app.indoorbike.shared.simulation.Drivetrain
import app.indoorbike.shared.simulation.LaunchAssist
import app.indoorbike.shared.simulation.RoadAcceleration
import app.indoorbike.shared.simulation.SimulationEngine
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

/**
 * Rides a road somebody else is describing.
 *
 * [SimulationEngine] is the same loop with one term moved. There, the app reads
 * a gradient off a [app.indoorbike.shared.route.RouteTrack] and integrates the
 * rider's watts into a speed and a position. Here the companion supplies both
 * the gradient and the speed, and the app does everything downstream of them:
 *
 * 1. Take the road the companion last sent.
 * 2. Work out the force that road pushes back with at the speed the rider's
 *    own gear is turning out, multiply it by that gear, and command exactly
 *    that force.
 * 3. The rider feels the hill, pushes harder or shifts, and the companion sees
 *    it in the power and gear this app reports back.
 *
 * ## Whose speed the road is evaluated at
 *
 * The companion owns the world: where the rider is, how fast their avatar is
 * moving, and every rule about drafting, surfaces and wind that produced it.
 * None of that changes.
 *
 * What the *drivetrain* is evaluated at is a different speed, and it has to
 * be: `v = cadence x development`. A chain does not slip, so the gear decides
 * how fast the road goes past for a given cadence, and a virtual gear that
 * does not decide that is not a gear at all - it is a multiplier on a number
 * somebody else chose, worth a few per cent a shift, which is precisely the
 * "I can't feel my gears" report this model was rebuilt to answer. See
 * [DriveSpeed].
 *
 * The two speeds are not rivals. A settled rider solves `P = F_road(v) . v`
 * from either end and lands on the same v, so they agree wherever the road is
 * steady and part company only where a bicycle would: mid-acceleration, while
 * coasting, and when a gear cannot turn the road out fast enough. A trainer
 * that cannot report cadence falls back to the companion's speed and behaves
 * exactly as this mode did before.
 *
 * `RideDynamics.step` is still unused here - the companion integrates, not
 * this - but `roadForceNewtons` and `inertiaForceNewtons` are not.
 *
 * ## What this deliberately does not know
 *
 * Where the rider is. It is told a gradient, a speed, and cumulative totals for
 * the ride record. None of those is a position, and nothing here could
 * reconstruct one.
 *
 * ## Shared with route mode on purpose
 *
 * The force ceiling ([SimulationEngine.softCeiling]), the assumed trainer mass,
 * the freewheel floor and every BLE pacing constant are the shipped ones rather
 * than copies. Two modes command the same machine through the same envelope,
 * and an envelope with two implementations is an envelope with two behaviours.
 */
class CompanionEngine(
    private val powerWatts: () -> Int,
    /**
     * The rider's cadence in rpm, read from the trainer.
     *
     * Route mode takes it for the same reason: the virtual gear is a
     * kinematic constraint and needs a cadence to constrain. See [DriveSpeed].
     */
    private val cadenceRpm: () -> Int = { 0 },
    /** Hand a road to the trainer. Wired to `TrainerManager::setSimulationParameters`. */
    private val applyRoad: (SimulationParameters) -> Unit,
    private val riderMassKg: () -> Float,
    private val bikeMassKg: () -> Float = { VirtualSpeed.DEFAULT_BIKE_MASS_KG },
    private val drivetrainProvider: () -> Drivetrain = { Drivetrain.DEFAULT },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The gradient actually being ridden, after clamping and the deadman ramp. */
    private val _gradient = MutableStateFlow(0.0)
    val gradient: StateFlow<Double> = _gradient.asStateFlow()

    /** Virtual speed as the companion last reported it. */
    private val _speedMps = MutableStateFlow(0.0)
    val speedMps: StateFlow<Double> = _speedMps.asStateFlow()

    /**
     * The speed the rider's legs and their chosen gear are turning out, which
     * is what the road force is evaluated at.
     *
     * The companion still owns the world. This is the drivetrain's own answer
     * to the same question, and the two are meant to agree: both solve
     * `P = F_road(v) . v`, so a settled rider lands on the same speed from
     * either end. Where they part company is where a bicycle parts company
     * too - mid-acceleration, coasting, spun out on a descent, or riding a
     * gear that cannot turn the road out fast enough.
     */
    private val _driveSpeedMps = MutableStateFlow(0.0)
    val driveSpeedMps: StateFlow<Double> = _driveSpeedMps.asStateFlow()

    /**
     * Whether [driveSpeedMps] is the drivetrain's answer or an echo of the
     * companion's own.
     *
     * False until a crank has turned, because a power source that cannot count
     * cadence has no drivetrain speed to give and [DriveSpeed] falls back to
     * whatever it was handed. Telemetry omits the field in that case rather
     * than sending a companion its own number back, which would look like
     * agreement and mean nothing.
     */
    private val _driveSpeedKnown = MutableStateFlow(false)
    val driveSpeedKnown: StateFlow<Boolean> = _driveSpeedKnown.asStateFlow()

    /**
     * The road the companion says the rider is on, merged from every `course`
     * frame and from a `finish` summary.
     *
     * Held here rather than on the host because this is where the rest of the
     * ride record already lives: the cumulative distance and ascent that
     * [setProgress] collects are read off this engine when the ride is saved,
     * and the course is read from the same place at the same moment.
     */
    private val _course = MutableStateFlow(CompanionCourse())
    val course: StateFlow<CompanionCourse> = _course.asStateFlow()

    /** Cumulative along-road distance the companion has reported. */
    private val _distanceMeters = MutableStateFlow(0.0)
    val distanceMeters: StateFlow<Double> = _distanceMeters.asStateFlow()

    /** Cumulative ascent the companion has reported. */
    private val _elevationGainMeters = MutableStateFlow(0.0)
    val elevationGainMeters: StateFlow<Double> = _elevationGainMeters.asStateFlow()

    private val _drivetrain = MutableStateFlow(Drivetrain.DEFAULT)
    val drivetrain: StateFlow<Drivetrain> = _drivetrain.asStateFlow()

    private val _gearIndex = MutableStateFlow(Drivetrain.DEFAULT.startIndex)
    val gearIndex: StateFlow<Int> = _gearIndex.asStateFlow()

    /**
     * True once the companion has gone quiet for longer than its lease.
     *
     * The HUD says so rather than pretending, because a rider whose pedals just
     * went light deserves to know it was the game that stopped, not them.
     */
    private val _roadStale = MutableStateFlow(false)
    val roadStale: StateFlow<Boolean> = _roadStale.asStateFlow()

    /** True once any road has ever arrived: the difference between "waiting" and "riding". */
    private val _receivingRoad = MutableStateFlow(false)
    val receivingRoad: StateFlow<Boolean> = _receivingRoad.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Last force commanded, in newtons at the wheel. Published in telemetry. */
    private val _trainerForceN = MutableStateFlow(0.0)
    val trainerForceN: StateFlow<Double> = _trainerForceN.asStateFlow()

    /** The grade actually written over FTMS, which is that force against 84 kg. */
    private val _trainerGradePercent = MutableStateFlow(0.0)
    val trainerGradePercent: StateFlow<Double> = _trainerGradePercent.asStateFlow()

    /** Fired when the lease expires and again when a road comes back. */
    var onRoadStaleChanged: ((stale: Boolean) -> Unit)? = null

    /**
     * Fired on every shift, including one that changed nothing.
     *
     * The host forwards it as a `gearChanged` event. Telemetry also carries the
     * gear, but a shift sound played up to a tick after the button reads as lag
     * in the companion rather than in the protocol.
     */
    var onGearChanged: ((index: Int, changed: Boolean, source: String) -> Unit)? = null

    private var rawGradient = 0.0
    private var roadAtMs = 0L
    private var everReceived = false

    /** Cadence into road speed, through whatever gear the rider is in. */
    private val driveSpeed = DriveSpeed()

    /**
     * The acceleration term of the road load, differentiated off the
     * companion's own speed rather than off the rider's cadence.
     *
     * Deliberately the world's signal and not the legs'. The companion's speed
     * is already the output of an integration, so it moves smoothly and it
     * moves for reasons the rider will recognise. Differentiating cadence
     * instead would close a fast loop from the rider's legs, through a
     * control-point write that cannot go out more than five times a second,
     * and back to their legs, which is the shape of an oscillation rather than
     * of a bicycle.
     */
    private val roadAcceleration = RoadAcceleration()

    /** A hand off the line, fading to nothing once the rider is moving. */
    private val launchAssist = LaunchAssist()

    /** Wall clock of the last [advance], for the filters above. */
    private var lastAdvanceMs = 0L
    private var advanceSeeded = false

    private var loop: Job? = null

    private var lastSentForceNewtons: Double? = null
    private var lastSentGearIndex: Int = -1
    private var lastSentEpochMs: Long = 0
    private var sendOwed = false

    /**
     * Put a rider on a bike with no road under it yet.
     *
     * The road arrives from the companion, so unlike route mode there is
     * nothing to load. What this does set is the drivetrain, read once here
     * rather than live for the same reason route mode reads it once: changing
     * bikes mid-ride would silently change what the gear number on screen
     * means.
     */
    fun load(gearIndex: Int = USE_START_GEAR) {
        stop()
        val drivetrain = drivetrainProvider()
        _drivetrain.value = drivetrain
        _gearIndex.value =
            if (gearIndex == USE_START_GEAR) drivetrain.startIndex else drivetrain.clampIndex(gearIndex)
        rawGradient = 0.0
        roadAtMs = 0
        everReceived = false
        _gradient.value = 0.0
        _speedMps.value = 0.0
        _distanceMeters.value = 0.0
        _elevationGainMeters.value = 0.0
        _roadStale.value = false
        _receivingRoad.value = false
        _trainerForceN.value = 0.0
        _trainerGradePercent.value = 0.0
        _driveSpeedMps.value = 0.0
        _driveSpeedKnown.value = false
        _course.value = CompanionCourse()
        launchAssist.reset()
        driveSpeed.reset()
        roadAcceleration.reset()
        advanceSeeded = false
        lastSentForceNewtons = null
        lastSentGearIndex = -1
    }

    /** Start commanding the trainer. Safe with no companion connected: that is a flat road. */
    fun start() {
        loop?.cancel()
        lastSentForceNewtons = null
        sendOwed = false
        // A pause leaves stale momentum behind, and resuming must not bill the
        // rider for an acceleration that happened while they were stopped.
        roadAcceleration.reset()
        advanceSeeded = false
        // A ride that is starting, or restarting after a pause, is pulling away
        // from a standstill either way.
        launchAssist.reset()
        _running.value = true
        pushRoadToTrainer(force = true)
        loop = scope.launch {
            while (isActive) {
                advance(getCurrentEpochMillis())
                delay(SimulationEngine.TICK_MS)
            }
        }
    }

    /**
     * Stop commanding the trainer.
     *
     * Deliberately leaves the machine alone, exactly as route mode does: every
     * caller that pauses a ride already zeroes it through the session manager,
     * and a second write racing that one would only fight it.
     */
    fun stop() {
        loop?.cancel()
        loop = null
        _running.value = false
        roadAcceleration.reset()
        advanceSeeded = false
        // Nothing is measuring the drivetrain while the ride is paused, so
        // telemetry says so rather than repeating whatever the last tick
        // happened to see. Resetting the cadence filter as well means the first
        // crank after a resume is adopted whole, which is the difference
        // between speed reappearing at once and creeping back over a second.
        driveSpeed.reset()
        _driveSpeedMps.value = 0.0
        _driveSpeedKnown.value = false
        lastSentForceNewtons = null
        sendOwed = false
    }

    fun clear() {
        stop()
        load()
    }

    /**
     * A new road, already clamped by [CompanionRoad].
     *
     * Resets the deadman: this is the frame whose lease the next tick measures.
     */
    fun setRoad(gradient: Double, speedMps: Double, nowMs: Long = getCurrentEpochMillis()) {
        rawGradient = gradient
        _speedMps.value = speedMps
        roadAtMs = nowMs
        everReceived = true
        if (!_receivingRoad.value) _receivingRoad.value = true
        if (_roadStale.value) {
            _roadStale.value = false
            onRoadStaleChanged?.invoke(false)
        }
        _gradient.value = gradient
        // A road change is felt, so it jumps the routine queue the same way a
        // shift does rather than waiting up to a tick.
        if (_running.value) pushRoadToTrainer(force = false)
    }

    /**
     * Cumulative totals for the ride record.
     *
     * Monotonic on the way in already ([CompanionRoad] keeps the larger value),
     * and guarded again here because this is what the saved ride reports and a
     * distance that goes backwards is worse than one that stalls.
     */
    fun setProgress(distanceM: Double?, ascentM: Double?) {
        distanceM?.let { if (it > _distanceMeters.value) _distanceMeters.value = it }
        ascentM?.let { if (it > _elevationGainMeters.value) _elevationGainMeters.value = it }
    }

    /**
     * Put the rider on a different bike.
     *
     * The gear index is carried across by *development* rather than by
     * position: a rider in the eleventh of twenty gears is not asking to be in
     * the eleventh of twelve, they are asking to keep turning the ground over
     * at the rate they were. Nearest development is the closest thing to
     * leaving them where they are, and on a fresh ride it lands on a sensible
     * opener because the gear it measures against is already one.
     *
     * Announced by the host afterwards, because the ladder the rider ends up
     * with is not the one the companion described: near-identical ratios are
     * dropped on the way in.
     */
    fun setDrivetrain(drivetrain: Drivetrain) {
        if (drivetrain.gears.isEmpty()) return
        val wanted = _drivetrain.value.developmentMeters(_gearIndex.value)
        _drivetrain.value = drivetrain
        _gearIndex.value = drivetrain.gears.indices.minByOrNull {
            abs(drivetrain.gears[it].developmentMeters - wanted)
        } ?: drivetrain.startIndex
        // The gear decides the force, so the trainer needs telling at once
        // rather than at the next road update.
        if (_running.value) pushRoadToTrainer(force = true)
    }

    /**
     * The road this ride is on, laid over whatever was already known.
     *
     * Merged rather than replaced so a companion can name the course while the
     * ride is happening and hand over its shape at the end, which is the only
     * order a free-roaming game can manage: it does not know what road the
     * rider took until they have taken it.
     */
    fun setCourse(update: CompanionCourse) {
        if (update.isEmpty) return
        _course.value = _course.value.mergedWith(update)
    }

    /** One step towards a harder gear. */
    fun shiftUp(source: String = SOURCE_SCREEN): Int = shiftBy(+1, source)

    /** One step towards an easier gear. */
    fun shiftDown(source: String = SOURCE_SCREEN): Int = shiftBy(-1, source)

    /**
     * Shift, and report where the drivetrain actually ended up.
     *
     * The returned index is the resulting one whether or not it moved, and
     * [onGearChanged] fires either way, so a companion that asked for the shift
     * can tell "shifted" from "already in the hardest gear" without modelling
     * the drivetrain itself. [source] travels with the event because a
     * companion showing a gear display needs to know whether the rider reached
     * for the handlebar or whether its own request landed.
     */
    fun shiftBy(steps: Int, source: String = SOURCE_SCREEN): Int {
        val next = _drivetrain.value.clampIndex(_gearIndex.value + steps)
        val changed = next != _gearIndex.value
        if (changed) {
            _gearIndex.value = next
            // A shift has to be felt immediately, not on the next road update.
            if (_running.value) pushRoadToTrainer(force = true)
        }
        onGearChanged?.invoke(next, changed, source)
        return next
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    // Internals ──────────────────────────────────────────────────────────────

    /**
     * One tick: apply the deadman, then restate the road if it has moved.
     *
     * Internal rather than private so tests can drive the loop with a clock of
     * their own, the same contract [SimulationEngine.advance] offers.
     */
    internal fun advance(nowMs: Long) {
        // Both filters are fed from the tick rather than from [setRoad], so a
        // companion that goes quiet decays its own momentum away instead of
        // freezing an acceleration into the commanded force forever.
        val dt = if (advanceSeeded) (nowMs - lastAdvanceMs) / 1_000.0 else 0.0
        lastAdvanceMs = nowMs
        advanceSeeded = true
        driveSpeed.update(cadenceRpm(), dt)
        roadAcceleration.update(_speedMps.value, dt)
        launchAssist.update(_speedMps.value, dt)
        _driveSpeedMps.value = driveSpeed.speedMps(
            developmentMeters = _drivetrain.value.developmentMeters(_gearIndex.value),
            fallbackMps = _speedMps.value,
        )
        _driveSpeedKnown.value = driveSpeed.available

        if (everReceived) {
            val age = nowMs - roadAtMs
            val effective = CompanionProtocol.rampedGradient(rawGradient, age)
            _gradient.value = effective
            val stale = age > CompanionProtocol.WORLD_TIMEOUT_MS
            if (stale != _roadStale.value) {
                _roadStale.value = stale
                onRoadStaleChanged?.invoke(stale)
            }
        }
        pushRoadToTrainer(force = false)
    }

    /**
     * The force the trainer should apply: the road's own resistance at the
     * companion's speed, scaled by the virtual gear.
     *
     * Identical in shape to route mode's, and deliberately so. The only
     * difference is where `gradient` and `speedMps` came from.
     */
    internal fun trainerForceNewtons(gearIndex: Int): Double {
        val rider = riderMassKg()
        if (rider <= 0f) return 0.0
        val drivetrain = _drivetrain.value
        val gearFactor = drivetrain.loadFactor(gearIndex)
        val totalMass = (rider + bikeMassKg()).toDouble()

        // Evaluated at the speed this gear is turning out rather than at the
        // one the companion reported. Route mode does the same thing and for
        // the same reason: it is the only way a shift reaches the aerodynamic
        // term, and the aerodynamic term is where a shift is felt.
        val roadSpeed = driveSpeed.speedMps(
            developmentMeters = drivetrain.developmentMeters(gearIndex),
            fallbackMps = _speedMps.value,
        )
        val road = RideDynamics.roadForceNewtons(
            gradient = _gradient.value,
            speedMps = roadSpeed,
            totalMassKg = totalMass,
        ) + RideDynamics.inertiaForceNewtons(
            // The companion integrates m_eff . dv/dt = P/v - F_road to get the
            // speed it sends. Commanding only F_road was commanding one half
            // of an equation somebody else was solving in full.
            accelerationMps2 = roadAcceleration.mps2,
            totalMassKg = totalMass,
        )
        // The floor is not assisted: it is already a few newtons, and a rider
        // pulling away should still feel a chain rather than nothing at all.
        val floor = SimulationEngine.FREEWHEEL_DRAG_NEWTONS * gearFactor
        val commanded = road * gearFactor * launchAssist.factor
        return SimulationEngine.softCeiling(commanded.coerceAtLeast(floor))
    }

    /**
     * Write the road, rate-limited exactly as route mode is.
     *
     * The GATT queue is the scarce resource in this whole design, not the
     * network: a control-point write shares it with the telemetry
     * notifications, and flooding it is what makes cadence drop out. So the
     * same three rules apply - never more often than
     * [SimulationEngine.MIN_SEND_INTERVAL_MS], a shorter floor for a shift
     * because a shifter that lags a fifth of a second feels broken, and an
     * unconditional restatement every [SimulationEngine.REFRESH_INTERVAL_MS]
     * because a trainer that dropped and reconnected has forgotten.
     */
    private fun pushRoadToTrainer(force: Boolean) {
        val now = getCurrentEpochMillis()
        val gearIndex = _gearIndex.value
        val targetForce = trainerForceNewtons(gearIndex)

        val elapsed = now - lastSentEpochMs
        val mustSend = force || sendOwed
        val floorMs =
            if (mustSend) SimulationEngine.MIN_SHIFT_INTERVAL_MS
            else SimulationEngine.MIN_SEND_INTERVAL_MS
        if (elapsed < floorMs) {
            if (mustSend) sendOwed = true
            return
        }
        if (!mustSend) {
            val previous = lastSentForceNewtons
            val unchanged = previous != null &&
                gearIndex == lastSentGearIndex &&
                abs(targetForce - previous) < SimulationEngine.FORCE_EPSILON_NEWTONS
            if (unchanged && elapsed < SimulationEngine.REFRESH_INTERVAL_MS) return
        }

        sendOwed = false
        lastSentEpochMs = now
        lastSentForceNewtons = targetForce
        lastSentGearIndex = gearIndex

        val gradePercent =
            targetForce / (SimulationEngine.ASSUMED_TRAINER_MASS_KG * VirtualSpeed.GRAVITY_MS2) * 100.0
        _trainerForceN.value = targetForce
        _trainerGradePercent.value = gradePercent

        applyRoad(
            SimulationParameters(
                windSpeedMps = 0.0,
                gradePercent = gradePercent,
                // Zero for the same reason route mode zeroes them: everything
                // the rider should feel is already in the grade above, and
                // anything the trainer added from here would be computed
                // against its own flywheel speed, which has nothing to do with
                // how fast the rider is travelling in the companion's world.
                rollingResistance = 0.0,
                windResistanceKgPerM = 0.0,
            )
        )
    }

    companion object {
        /** Passed to [load] when the caller has no opinion, which is every fresh ride. */
        const val USE_START_GEAR = -1

        /** Shift sources, as they appear in a `gearChanged` event. */
        const val SOURCE_SCREEN = "screen"
        const val SOURCE_CLICK = "zwiftClick"
        const val SOURCE_COMPANION = "companion"
    }
}
