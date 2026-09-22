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
     * The gears, the shifting and the force a gear asks for, shared with
     * companion mode. See [VirtualDrivetrain]: this engine's job is the road,
     * and that one's is turning a road into resistance.
     */
    private val gearing = VirtualDrivetrain(
        riderMassKg = riderMassKg,
        bikeMassKg = bikeMassKg,
        cadenceRpm = cadenceRpm,
        applyRoad = applyRoad,
    )

    /** See [VirtualDrivetrain.driveSpeedMps]. */
    val driveSpeedMps: StateFlow<Double> = gearing.driveSpeedMps

    /** See [VirtualDrivetrain.driveSpeedKnown]. */
    val driveSpeedKnown: StateFlow<Boolean> = gearing.driveSpeedKnown

    /** Smoothed road gradient under the rider, as a fraction (0.064 = 6.4 %). */
    private val _gradient = MutableStateFlow(0.0)
    val gradient: StateFlow<Double> = _gradient.asStateFlow()

    private val _position = MutableStateFlow<RoutePosition?>(null)
    val position: StateFlow<RoutePosition?> = _position.asStateFlow()

    val drivetrain: StateFlow<Drivetrain> = gearing.drivetrain

    val gearIndex: StateFlow<Int> = gearing.gearIndex

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
        gearing.load(
            drivetrain = drivetrain,
            gearIndex = if (gearIndex == USE_START_GEAR) drivetrain.startIndex else gearIndex,
        )
        _route.value = route
        _distanceMeters.value = startDistanceMeters.coerceIn(0.0, route.totalDistanceMeters)
        _startedAtMeters.value = startedAtMeters.coerceIn(0.0, _distanceMeters.value)
        _startedAtElevationGainMeters.value =
            startedAtElevationGainMeters.coerceIn(0.0, elevationGainMeters.coerceAtLeast(0.0))
        _elevationGainMeters.value = elevationGainMeters.coerceAtLeast(0.0)
        _speedMps.value = 0.0
        _finished.value = false
        _gradient.value = route.gradientAt(_distanceMeters.value)
        _position.value = route.positionAt(_distanceMeters.value)
        gearing.setRoad(gradient = _gradient.value, worldSpeedMps = 0.0)
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
        gearing.onStart()
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
        gearing.onStop()
    }

    /** One step towards a harder gear. */
    fun shiftUp() = shiftBy(+1)

    /** One step towards an easier gear. */
    fun shiftDown() = shiftBy(-1)

    fun shiftBy(steps: Int) {
        if (_route.value == null) return
        val before = gearing.gearIndex.value
        if (gearing.shiftBy(steps) == before) return
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

        // Climbing is measured off the smoothed profile between the two
        // positions, so it agrees with the gradient the rider was made to feel
        // rather than with the raw GPS altitude they were not.
        val climbed = route.elevationAt(distance) - route.elevationAt(previousDistance)
        if (climbed > 0.0) _elevationGainMeters.value += climbed

        _speedMps.value = speed
        _distanceMeters.value = distance
        _gradient.value = route.gradientAt(distance)
        _position.value = route.positionAt(distance)

        // The road the gearing turns into resistance: this engine's own speed,
        // integrated out of power with the bike's mass behind it, on the
        // gradient the rider has just reached.
        gearing.setRoad(gradient = _gradient.value, worldSpeedMps = speed)
        gearing.update(dt)

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

    /** Hand the road to the trainer through the shared gearing and its pacer. */
    private fun pushRoadToTrainer(force: Boolean) {
        gearing.writeRoad(forced = force, nowMs = getCurrentEpochMillis())
    }

    /** See [VirtualDrivetrain.trainerForceNewtons]. Exposed for tests. */
    internal fun trainerForceNewtons(gearIndex: Int): Double = gearing.trainerForceNewtons(gearIndex)

    companion object {
        private const val TAG = "SimulationEngine"

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
    }
}
