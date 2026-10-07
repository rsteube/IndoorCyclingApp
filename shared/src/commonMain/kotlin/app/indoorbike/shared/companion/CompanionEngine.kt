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
import app.indoorbike.shared.simulation.BikeSpeed
import app.indoorbike.shared.simulation.DriveSpeed
import app.indoorbike.shared.simulation.Drivetrain
import app.indoorbike.shared.simulation.SimulationEngine
import app.indoorbike.shared.simulation.VirtualDrivetrain
import app.indoorbike.shared.util.ReentrantLock
import app.indoorbike.shared.util.getCurrentEpochMillis
import app.indoorbike.shared.util.withLock
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

/**
 * Rides a road somebody else is describing.
 *
 * [SimulationEngine] is the same loop with one term moved. There, the app reads
 * a gradient off a [app.indoorbike.shared.route.RouteTrack] and integrates the
 * rider's watts into a speed and a position. Here the companion supplies both
 * the gradient and the speed, and the app does everything downstream of them:
 *
 * 1. Take the road the companion last sent.
 * 2. Work out the force that road pushes back with at the companion's speed,
 *    multiply it by the rider's gear, and command exactly that force.
 * 3. The rider feels the hill, pushes harder or shifts, and the companion sees
 *    it in the power and gear this app reports back.
 *
 * ## Whose speed the road is evaluated at
 *
 * The companion owns the world: where the rider is, how fast their avatar is
 * moving, and every rule about drafting, surfaces and wind that produced it.
 * None of that changes.
 *
 * The road is evaluated at that speed too. The companion integrated it out of
 * power with the bike's mass behind it, so it is the speed a bike actually
 * has at the instant of a shift, and a shift changes the force at the pedals by
 * the ratio between the two gears and nothing more. See [VirtualDrivetrain]
 * for why it is not `cadence x development` any longer.
 *
 * That second speed is still computed, from the rider's cadence and gear, and
 * sent back in telemetry, because it is known on the first turn of the crank
 * and a companion with no physics of its own can use it. On a settled road the
 * two agree.
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
 * Everything that turns a road into resistance - the gears, the shift, the
 * force model, the ceiling, the freewheel floor and the pacing of every BLE
 * write - is one [VirtualDrivetrain], the same class route mode uses. Two modes
 * command the same machine, and a drivetrain with two implementations is a
 * drivetrain with two behaviours: a fix to how a shift feels would be made in
 * one and missed in the other.
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
    /**
     * Hold the trainer at a wattage. Wired to `TrainerManager::setTargetPower`,
     * the same ERG write a free ride uses.
     *
     * Only ever called while a companion holds an untracked ride at a power
     * target; the rest of the time this engine describes a road and never names
     * a number of watts. Defaulted so tests and any platform without ERG get
     * the road-only engine they had before.
     */
    private val applyPower: (Int) -> Unit = {},
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
     * The gears, the shifting and the force a gear asks for, shared with route
     * mode so a change to how shifting feels is made once. See
     * [VirtualDrivetrain]. This engine's job is the companion's road, and its
     * lease; that one's is turning a road into resistance.
     */
    private val gearing = VirtualDrivetrain(
        riderMassKg = riderMassKg,
        bikeMassKg = bikeMassKg,
        cadenceRpm = cadenceRpm,
        applyRoad = applyRoad,
    )

    /**
     * The speed the rider's legs and their chosen gear are turning out.
     *
     * The companion still owns the world; this is the drivetrain's own answer
     * to the same question, and on a settled road the two agree. Sent to the
     * companion in telemetry, because it is known on the first turn of the
     * crank. See [VirtualDrivetrain.driveSpeedMps].
     */
    val driveSpeedMps: StateFlow<Double> = gearing.driveSpeedMps

    /**
     * Whether [driveSpeedMps] is the drivetrain's answer or an echo of the
     * companion's own. False until a crank has turned; telemetry omits the
     * field in that case rather than sending a companion its own number back,
     * which would look like agreement and mean nothing.
     */
    val driveSpeedKnown: StateFlow<Boolean> = gearing.driveSpeedKnown

    /**
     * The bike's speed as the phone models it, which is what telemetry tells
     * the companion. See [BikeSpeed] for why that is not [driveSpeedMps]: a
     * companion rides its world at the number it is sent and sends it back as
     * the road's speed, and `cadence x development` turned every upshift into
     * an instant acceleration on the way round.
     */
    private val bikeSpeed = BikeSpeed()
    private val _bikeSpeedMps = MutableStateFlow(0.0)
    val bikeSpeedMps: StateFlow<Double> = _bikeSpeedMps.asStateFlow()

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

    /**
     * Where this ride began, along whatever the companion is counting.
     *
     * A session is many rides and `distanceM` is defined as cumulative, so a
     * game is perfectly entitled to keep counting across all of them. Without
     * an origin to measure from, the evening's second lap would be recorded as
     * everything ridden since the first one started.
     *
     * The origin is taken from the companion's own first report after the ride
     * begins rather than from whatever the engine happens to hold, because the
     * two disagree exactly when it matters: the engine is reset between rides
     * and the game's counter may not be.
     */
    private val _rideStartMeters = MutableStateFlow(0.0)
    val rideStartMeters: StateFlow<Double> = _rideStartMeters.asStateFlow()

    private val _rideStartElevationGainMeters = MutableStateFlow(0.0)
    val rideStartElevationGainMeters: StateFlow<Double> =
        _rideStartElevationGainMeters.asStateFlow()

    /** Set while waiting for the first progress report of a new ride. */
    private var awaitingRideOrigin = false

    /** Cumulative ascent the companion has reported. */
    private val _elevationGainMeters = MutableStateFlow(0.0)
    val elevationGainMeters: StateFlow<Double> = _elevationGainMeters.asStateFlow()

    /**
     * Whether the companion has ever reported a distance on this load. The
     * distance above starts at zero either way, and a zero the companion never
     * said must not be recorded as one it did.
     */
    private val distanceReported = MutableStateFlow(false)

    /**
     * The position the companion last reported, and when it arrived. Held in
     * flows rather than behind [lock] for the reason [sample] gives.
     */
    private val reportedPosition = MutableStateFlow<ReportedPosition?>(null)

    private class ReportedPosition(val position: CompanionPosition, val atMs: Long)

    val drivetrain: StateFlow<Drivetrain> = gearing.drivetrain

    val gearIndex: StateFlow<Int> = gearing.gearIndex

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

    /**
     * The wattage an untracked ride is being held at, or null when the rider is
     * on the road. While it is set the road is still read, still timed and
     * still published; it is simply not what the trainer is being told.
     */
    private val _powerTarget = MutableStateFlow<Int?>(null)
    val powerTarget: StateFlow<Int?> = _powerTarget.asStateFlow()

    /** What the trainer is actually being told, which the lease and the pedals both shape. */
    private val _powerCommanded = MutableStateFlow(0)
    val powerCommanded: StateFlow<Int> = _powerCommanded.asStateFlow()

    /** True while a target is set and the rider has been let go of it ([PowerHold]). */
    private val _powerReleased = MutableStateFlow(false)
    val powerReleased: StateFlow<Boolean> = _powerReleased.asStateFlow()

    private val hold = PowerHold()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Last force commanded, in newtons at the wheel. Published in telemetry. */
    val trainerForceN: StateFlow<Double> = gearing.commandedForceN

    /** The grade actually written over FTMS, which is that force against 84 kg. */
    val trainerGradePercent: StateFlow<Double> = gearing.commandedGradePercent

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

    /**
     * Fired whenever the requested power target changes, with the source the
     * caller named: the companion asked, or the ride's own state cleared it.
     *
     * Only the *requested* target. The lease decay and the release of a stopped
     * rider are not changes to what was asked for, and reporting them here
     * would tell a companion its target had been taken away when it has not.
     * Those show in [powerCommanded] and nowhere else.
     */
    var onPowerTargetChanged: ((watts: Int?, source: String) -> Unit)? = null

    private var rawGradient = 0.0
    private var rawCrr = CompanionProtocol.DEFAULT_CRR
    /** The rolling resistance being ridden, after the deadman ramp. */
    private var ridingCrr = CompanionProtocol.DEFAULT_CRR
    private var roadAtMs = 0L
    private var everReceived = false

    /** Wall clock of the last [advance], for the gearing's filters. */
    private var lastAdvanceMs = 0L
    private var advanceSeeded = false

    private var loop: Job? = null

    /**
     * Held by every entry point and by each tick.
     *
     * The engine is driven from three places at once: its own loop, the socket
     * delivering roads and shifts, and the session manager starting and
     * stopping it. Nothing serialised them. A pause stopped the engine and
     * zeroed the trainer, and a tick already under way could then write its
     * road or its wattage after the zero, leaving a paused rider on a hill or
     * in ERG with nothing running to let them go.
     */
    private val lock = ReentrantLock()

    /** The target last written, so an unchanged one is not written again. */
    private var lastSentWatts: Int? = null

    /**
     * Put a rider on a bike with no road under it yet.
     *
     * The road arrives from the companion, so unlike route mode there is
     * nothing to load. What this does set is the drivetrain, read once here
     * rather than live for the same reason route mode reads it once: changing
     * bikes mid-ride would silently change what the gear number on screen
     * means.
     */
    fun load(gearIndex: Int = USE_START_GEAR) = lock.withLock {
        stop()
        val drivetrain = drivetrainProvider()
        gearing.load(
            drivetrain = drivetrain,
            gearIndex = if (gearIndex == USE_START_GEAR) drivetrain.startIndex else gearIndex,
        )
        rawGradient = 0.0
        rawCrr = CompanionProtocol.DEFAULT_CRR
        ridingCrr = CompanionProtocol.DEFAULT_CRR
        roadAtMs = 0
        everReceived = false
        _gradient.value = 0.0
        _speedMps.value = 0.0
        _distanceMeters.value = 0.0
        _elevationGainMeters.value = 0.0
        distanceReported.value = false
        reportedPosition.value = null
        _rideStartMeters.value = 0.0
        _rideStartElevationGainMeters.value = 0.0
        awaitingRideOrigin = false
        _roadStale.value = false
        _receivingRoad.value = false
        _course.value = CompanionCourse()
        advanceSeeded = false
        lastSentWatts = null
        bikeSpeed.reset()
        _bikeSpeedMps.value = 0.0
        gearing.setRoad(gradient = 0.0, worldSpeedMps = 0.0, crr = ridingCrr)
    }

    /** Start commanding the trainer. Safe with no companion connected: that is a flat road. */
    fun start() = lock.withLock {
        loop?.cancel()
        lastSentWatts = null
        advanceSeeded = false
        gearing.onStart()
        _running.value = true
        if (hold.target != null) pushPowerToTrainer(force = true) else pushRoadToTrainer(force = true)
        loop = scope.launch {
            while (isActive) {
                // Only while running, checked under the lock: a tick that
                // began before a stop does not get to write after it.
                lock.withLock { if (_running.value) advance(getCurrentEpochMillis()) }
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
    fun stop() = lock.withLock {
        loop?.cancel()
        loop = null
        _running.value = false
        advanceSeeded = false
        gearing.onStop()
        // A ride resuming from a pause pulls away again, from the legs.
        bikeSpeed.reset()
        _bikeSpeedMps.value = 0.0
        lastSentWatts = null
        // Nothing is driving the trainer any more, so nothing is holding a
        // target either. Every path that stops this engine either pauses the
        // ride or ends it, and both leave untracked, which clears the target
        // anyway; this is the backstop that keeps the two from disagreeing.
        val wasHolding = _powerTarget.value != null
        hold.set(null, 0, getCurrentEpochMillis())
        _powerTarget.value = null
        _powerCommanded.value = 0
        _powerReleased.value = false
        if (wasHolding) onPowerTargetChanged?.invoke(null, SOURCE_STATE)
    }

    fun clear() = lock.withLock {
        stop()
        load()
    }

    /**
     * Hold the rider at [watts], or hand them back to the road with null.
     *
     * The loop keeps running either way, because the lease lives in it: a game
     * that goes quiet has to decay a power target exactly as it decays a hill,
     * and something has to be timing it. What changes is only what the loop
     * writes to the trainer.
     *
     * Returns the target actually held, after clamping.
     */
    fun setPowerTarget(
        watts: Int?,
        source: String = SOURCE_STATE,
        nowMs: Long = getCurrentEpochMillis(),
    ): Int? = lock.withLock {
        val clamped = watts?.coerceIn(
            CompanionProtocol.MIN_POWER_WATTS,
            CompanionProtocol.MAX_POWER_WATTS,
        )
        val changed = clamped != _powerTarget.value
        hold.set(clamped, powerWatts(), nowMs)
        _powerTarget.value = clamped
        _powerCommanded.value = hold.commanded
        _powerReleased.value = false
        if (changed) onPowerTargetChanged?.invoke(clamped, source)
        if (clamped == null) {
            // Back to the road, and stated at once rather than at the next
            // tick: the rider is waiting to feel something other than ERG.
            gearing.clearCommand()
            gearing.forgetLastRoad()
            if (_running.value) pushRoadToTrainer(force = true)
        } else {
            // No road is being applied any more, and telemetry says so rather
            // than leaving the last hill it described standing.
            gearing.clearCommand()
            if (_running.value) pushPowerToTrainer(force = true)
        }
        clamped
    }

    /**
     * A new road, already clamped by [CompanionRoad].
     *
     * Resets the deadman: this is the frame whose lease the next tick measures.
     */
    fun setRoad(
        gradient: Double,
        speedMps: Double,
        crr: Double = CompanionProtocol.DEFAULT_CRR,
        nowMs: Long = getCurrentEpochMillis(),
    ) = lock.withLock {
        rawGradient = gradient
        rawCrr = crr
        ridingCrr = crr
        _speedMps.value = speedMps
        roadAtMs = nowMs
        everReceived = true
        if (!_receivingRoad.value) _receivingRoad.value = true
        if (_roadStale.value) {
            _roadStale.value = false
            onRoadStaleChanged?.invoke(false)
        }
        _gradient.value = gradient
        gearing.setRoad(gradient = gradient, worldSpeedMps = speedMps, crr = crr)
        // Written now rather than at the next tick, but as a routine write: a
        // game can send a road at its own frame rate, and only the rider's own
        // hand gets to jump the pacer's floor.
        if (_running.value) pushRoadToTrainer(force = false)
    }

    /**
     * Cumulative totals for the ride record.
     *
     * Monotonic on the way in already ([CompanionRoad] keeps the larger value),
     * and guarded again here because this is what the saved ride reports and a
     * distance that goes backwards is worse than one that stalls.
     */
    fun setProgress(distanceM: Double?, ascentM: Double?) = lock.withLock {
        // Totals nobody could have ridden are a companion's bug, not the
        // rider's ride, and a running maximum keeps whatever it is given: one
        // frame claiming 1e300 m used to become the saved distance for good,
        // in history, in the export and in every upload.
        val distance = distanceM?.takeIf { it.isFinite() && it >= 0.0 && it <= MAX_PLAUSIBLE_DISTANCE_M }
        val ascent = ascentM?.takeIf { it.isFinite() && it >= 0.0 && it <= MAX_PLAUSIBLE_ASCENT_M }
        if (awaitingRideOrigin && (distance != null || ascent != null)) {
            // The first thing the companion says about position after a ride
            // starts is where that ride starts. A game that resets its counter
            // per ride reports something near zero and this costs nothing; one
            // that counts for the whole session reports a large number, and
            // this is the only chance to learn it.
            distance?.let { _rideStartMeters.value = it }
            ascent?.let { _rideStartElevationGainMeters.value = it }
            awaitingRideOrigin = false
        }
        distance?.let {
            distanceReported.value = true
            if (it > _distanceMeters.value) _distanceMeters.value = it
        }
        ascent?.let { if (it > _elevationGainMeters.value) _elevationGainMeters.value = it }
    }

    /** Where the companion says the rider is, already checked by the session. */
    fun setPosition(position: CompanionPosition, nowMs: Long = getCurrentEpochMillis()) {
        reportedPosition.value = ReportedPosition(position, nowMs)
    }

    /**
     * What the companion has said about the road, for one second of the ride
     * record taken at [nowMs].
     *
     * The distance is the companion's cumulative count whenever it has given
     * one: it is a total, true until the next report. A position is only
     * recorded while it is fresh, inside the same lease the road is ridden on,
     * so a companion that stops sending leaves a gap in the track rather than a
     * rider standing still at the last place it mentioned.
     *
     * Lock-free, like every other read the ride recorder makes of this engine:
     * the recorder calls it holding its own lock, and the engine's callbacks
     * reach out to the host while holding this one.
     */
    fun sample(nowMs: Long = getCurrentEpochMillis()): CompanionSample {
        val fresh = reportedPosition.value
            ?.takeIf { nowMs - it.atMs in 0..CompanionProtocol.WORLD_TIMEOUT_MS }
            ?.position
        return CompanionSample(
            distanceMeters = if (distanceReported.value) _distanceMeters.value else null,
            latitude = fresh?.latitude,
            longitude = fresh?.longitude,
            altitudeMeters = fresh?.altitudeM,
        )
    }

    /**
     * A ride is starting. Measure it from here.
     *
     * Called on the first move into an active ride and not on a resume: a rider
     * coming back from a pause is continuing the ride they were on, and
     * re-basing it there would throw away everything they had already done.
     */
    fun markRideStart() = lock.withLock {
        awaitingRideOrigin = true
        _rideStartMeters.value = _distanceMeters.value
        _rideStartElevationGainMeters.value = _elevationGainMeters.value
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
    fun setDrivetrain(drivetrain: Drivetrain) = lock.withLock {
        if (drivetrain.gears.isEmpty()) return
        gearing.setDrivetrain(drivetrain)
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
     *
     * [replace] is a `course` that carried a `mode`: the whole description of
     * the ride, so a free ride does not keep a length left by the course before
     * it.
     */
    fun setCourse(update: CompanionCourse, replace: Boolean = false) = lock.withLock {
        if (replace) {
            _course.value = update
            return
        }
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
    fun shiftBy(steps: Int, source: String = SOURCE_SCREEN): Int = lock.withLock {
        val before = gearing.gearIndex.value
        val next = gearing.shiftBy(steps)
        val changed = next != before
        // A shift has to be felt immediately, not on the next road update.
        if (changed && _running.value) pushRoadToTrainer(force = true)
        onGearChanged?.invoke(next, changed, source)
        next
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
    internal fun advance(nowMs: Long) = lock.withLock {
        // Both filters are fed from the tick rather than from [setRoad], so a
        // companion that goes quiet decays its own momentum away instead of
        // freezing an acceleration into the commanded force forever.
        val dt = if (advanceSeeded) (nowMs - lastAdvanceMs) / 1_000.0 else 0.0
        lastAdvanceMs = nowMs
        advanceSeeded = true
        if (everReceived) {
            val age = nowMs - roadAtMs
            val effective = CompanionProtocol.rampedGradient(rawGradient, age)
            _gradient.value = effective
            ridingCrr = CompanionProtocol.rampedCrr(rawCrr, age)
            val stale = age > CompanionProtocol.WORLD_TIMEOUT_MS
            if (stale != _roadStale.value) {
                _roadStale.value = stale
                onRoadStaleChanged?.invoke(stale)
            }
        }

        // The road the gearing turns into resistance: the companion's speed,
        // which already has the bike's mass behind it because the companion
        // integrated it, on the gradient and surface after the deadman ramp.
        gearing.setRoad(gradient = _gradient.value, worldSpeedMps = _speedMps.value, crr = ridingCrr)
        gearing.update(dt)
        val rider = riderMassKg()
        bikeSpeed.update(
            powerWatts = powerWatts().toDouble(),
            driveSpeedMps = gearing.driveSpeedMps.value.takeIf { gearing.driveSpeedKnown.value },
            gradient = _gradient.value,
            crr = ridingCrr,
            // No body weight, no ride: the same contract as the rest of the
            // physics, rather than a riderless bike at an impossible speed.
            totalMassKg = if (rider > 0f) (rider + bikeMassKg()).toDouble() else 0.0,
            dtSeconds = dt,
        )
        _bikeSpeedMps.value = bikeSpeed.mps

        // The road above was read, timed and published either way. Only the
        // write below is the target's to take over.
        if (hold.target != null) {
            hold.update(
                nowMs = nowMs,
                cadenceRpm = cadenceRpm(),
                worldAgeMs = if (everReceived) nowMs - roadAtMs else null,
            )
            _powerCommanded.value = hold.commanded
            _powerReleased.value = hold.released
            pushPowerToTrainer(force = false)
            return
        }
        pushRoadToTrainer(force = false)
    }

    /** See [VirtualDrivetrain.trainerForceNewtons]. Exposed for tests. */
    internal fun trainerForceNewtons(gearIndex: Int): Double = gearing.trainerForceNewtons(gearIndex)

    /**
     * Write the commanded wattage, through the same pacer as the road.
     *
     * The GATT queue does not care which kind of write is queued on it, so a
     * road and a target share one floor and one refresh: a trainer that
     * dropped and reconnected has forgotten its target as surely as a hill.
     */
    private fun pushPowerToTrainer(force: Boolean) {
        val now = getCurrentEpochMillis()
        val watts = hold.commanded
        val pacer = gearing.pacer
        if (!pacer.mayWrite(now, force)) return
        if (watts == lastSentWatts && !pacer.mustRestate(now, force)) return

        pacer.wrote(now, force)
        lastSentWatts = watts
        // A road written after this would be fighting the target for the
        // control point, so the next one restates itself in full.
        gearing.forgetLastRoad()
        applyPower(watts)
    }

    private fun pushRoadToTrainer(force: Boolean) {
        // P4: while a target is held the road is still read and still timed,
        // but it is not what the trainer is being told.
        if (hold.target != null) return
        gearing.writeRoad(forced = force, nowMs = getCurrentEpochMillis())
    }

    companion object {
        /** Passed to [load] when the caller has no opinion, which is every fresh ride. */
        const val USE_START_GEAR = -1

        /** Shift sources, as they appear in a `gearChanged` event. */
        const val SOURCE_SCREEN = "screen"
        const val SOURCE_CLICK = "zwiftClick"
        const val SOURCE_COMPANION = "companion"

        /** A `powerTargetChanged` the ride's own state caused, rather than a request. */
        const val SOURCE_STATE = EventFrame.SOURCE_STATE

        /** Further than any ride, including a whole day of them in one game session. */
        const val MAX_PLAUSIBLE_DISTANCE_M = 2_000_000.0

        /** More climbing than any day's riding. */
        const val MAX_PLAUSIBLE_ASCENT_M = 50_000.0
    }
}
