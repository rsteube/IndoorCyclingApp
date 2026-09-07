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
package app.indoorbike.shared.companion

import app.indoorbike.shared.ble.SimulationParameters
import app.indoorbike.shared.physics.RideDynamics
import app.indoorbike.shared.physics.VirtualSpeed
import app.indoorbike.shared.simulation.Drivetrain
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
 * 2. Work out the force that road pushes back with **at that speed**, multiply
 *    it by the rider's virtual gear, and command exactly that force.
 * 3. The rider feels the hill, pushes harder or shifts, and the companion sees
 *    it in the power and gear this app reports back.
 *
 * ## Why speed comes off the wire rather than out of [RideDynamics]
 *
 * The commanded force contains an aerodynamic term, `0.5 * rho * CdA * v^2`, so
 * the app has to *know* a speed. It does not have to *compute* one. Letting the
 * companion own it is what makes drafting, surfaces, wind and every other thing
 * a game might invent its own arithmetic, instead of four more fields on the
 * wire and four more terms in here. `RideDynamics.step` is therefore unused in
 * this mode; `roadForceNewtons` is not.
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
        lastSentForceNewtons = null
        lastSentGearIndex = -1
    }

    /** Start commanding the trainer. Safe with no companion connected: that is a flat road. */
    fun start() {
        loop?.cancel()
        lastSentForceNewtons = null
        sendOwed = false
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
        val gearFactor = _drivetrain.value.loadFactor(gearIndex)
        val totalMass = (rider + bikeMassKg()).toDouble()

        val road = RideDynamics.roadForceNewtons(
            gradient = _gradient.value,
            speedMps = _speedMps.value,
            totalMassKg = totalMass,
        )
        val floor = SimulationEngine.FREEWHEEL_DRAG_NEWTONS * gearFactor
        return SimulationEngine.softCeiling((road * gearFactor).coerceAtLeast(floor))
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
