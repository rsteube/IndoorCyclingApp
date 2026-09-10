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
package app.indoorbike.shared.session

import app.indoorbike.shared.Workout
import app.indoorbike.shared.ble.TrainerManager
import app.indoorbike.shared.db.TelemetryPoint
import app.indoorbike.shared.physics.VirtualSpeed
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.shared.simulation.MIN_RESUMABLE_METERS
import app.indoorbike.shared.simulation.RouteProgress
import app.indoorbike.shared.simulation.RouteProgressStore
import app.indoorbike.shared.simulation.SimulationEngine
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.companion.CompanionCourse
import app.indoorbike.shared.companion.CompanionEngine
import app.indoorbike.shared.util.getCurrentEpochMillis
import app.indoorbike.shared.workout.FREE_RIDE_STEP_WATTS
import app.indoorbike.shared.workout.clampFreeRideWatts
import app.indoorbike.shared.workout.freeRideStartWatts
import app.indoorbike.shared.workout.companionWorkout
import app.indoorbike.shared.workout.freeRideWorkout
import app.indoorbike.shared.workout.isFreeRide
import app.indoorbike.shared.workout.isCompanion
import app.indoorbike.shared.workout.isSimulation
import app.indoorbike.shared.workout.simulationWorkout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class WorkoutState {
    Idle,
    Standby,
    Countdown,
    Active,
    Paused,
    Completed,
}

/**
 * Owns the active workout session and drives the trainer in ERG mode.
 *
 * State updates are driven by wall-clock time anchored at [sessionStartTimeEpoch]
 * so we don't drift over a 60+ minute ride if the coroutine dispatcher hiccups.
 *
 * Telemetry is captured by sampling power/cadence/HR once per second from the
 * injected [TrainerManager]; samples are buffered in memory until the session
 * completes, at which point they are persisted via [HistoryRepository]. They
 * are *also* streamed to [ActiveTelemetryStore] as the ride runs, so a process
 * death doesn't take the ride's power curve with it.
 */
class WorkoutSessionManager(
    private val trainerManager: TrainerManager,
    private val activeSessionStore: ActiveSessionStore? = null,
    /**
     * Durable log of samples captured so far. Paired with [activeSessionStore]:
     * that one remembers *where* the rider was, this one remembers *what they
     * did* to get there. Null disables crash-safe telemetry (tests, and any
     * platform that hasn't wired a store yet).
     */
    private val activeTelemetryStore: ActiveTelemetryStore? = null,
    /**
     * Where a partly-ridden route is remembered so the rider can carry on next
     * time. Null leaves every route starting at its trailhead, which is what a
     * platform that has not wired a store should do.
     */
    private val routeProgressStore: RouteProgressStore? = null,
    /**
     * How to get the user's current heart rate at sample time. The default
     * reads from the trainer's HR characteristic; a [HeartRateMonitor]-aware
     * caller (AppContainer) overrides this to prefer the external HRM.
     */
    private val effectiveHeartRate: () -> Int = { trainerManager.heartRate.value },
    /**
     * The rider's body mass in kg, read at sample time rather than captured at
     * the start so a weight corrected mid-ride takes effect from there on.
     * 0 means it isn't set, which leaves the distance estimate at zero.
     */
    private val riderMassKg: () -> Float = { 0f },
    /**
     * Rides a route when one is loaded. Null on platforms and in tests that
     * don't offer simulation, in which case [prepareSimulation] is a no-op and
     * every other mode behaves exactly as it did before the mode existed.
     */
    private val simulationEngine: SimulationEngine? = null,
    /**
     * Drives the trainer in companion mode. Null on platforms and in tests with
     * no companion socket, in which case [prepareCompanion] is a no-op - the
     * same contract [simulationEngine] has for route rides.
     */
    private val companionEngine: CompanionEngine? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Single-thread dispatcher for snapshot persistence. Both write and clear
     * operations go through this so they execute strictly in submission order
     * (FIFO). Without this, a stale write queued just before [completeWorkout]
     * could land on disk *after* the clear, leaving an "unfinished ride"
     * banner for a workout that's actually done.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val snapshotDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val _state = MutableStateFlow(WorkoutState.Idle)
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    private val _activeWorkout = MutableStateFlow<Workout?>(null)
    val activeWorkout: StateFlow<Workout?> = _activeWorkout.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    /**
     * Wall-clock seconds the rider has actually spent pedaling in
     * [WorkoutState.Active]. Distinct from [elapsedSeconds] in two ways:
     *  - skipping a segment fast-forwards [elapsedSeconds] to keep the planned
     *    timeline aligned but does NOT advance this counter, and
     *  - pauses don't count (same as [elapsedSeconds]).
     *
     * Used when persisting the finished session so the history reflects how
     * long the ride actually took, not the planned workout length.
     */
    private val _actualRideSeconds = MutableStateFlow(0)
    val actualRideSeconds: StateFlow<Int> = _actualRideSeconds.asStateFlow()

    /**
     * Ground covered so far, estimated from power by [VirtualSpeed]. A trainer
     * has no wheel on a road to measure, so this is the distance the rider's
     * watts would have carried them on a flat one. Stays at zero until a body
     * weight is set, since the estimate needs a mass to move.
     */
    private val _virtualDistanceMeters = MutableStateFlow(0.0)
    val virtualDistanceMeters: StateFlow<Double> = _virtualDistanceMeters.asStateFlow()

    private val _countdownSeconds = MutableStateFlow(3)
    val countdownSeconds: StateFlow<Int> = _countdownSeconds.asStateFlow()

    private val _currentSegmentIndex = MutableStateFlow(0)
    val currentSegmentIndex: StateFlow<Int> = _currentSegmentIndex.asStateFlow()

    private val _elapsedInSegment = MutableStateFlow(0)
    val elapsedInSegment: StateFlow<Int> = _elapsedInSegment.asStateFlow()

    /**
     * Set when the platform-side foreground service can't start (e.g. Android
     * 14+ refuses a `connectedDevice` FGS without a runtime BLE permission,
     * or a background-start restriction kicks in). Non-null = an active ride
     * is no longer protected from being killed by the OS when backgrounded.
     *
     * The platform layer (the FGS) calls [notifyForegroundServiceFailure];
     * the UI observes this and surfaces a banner so the rider can react
     * (grant permission and try again, or finish their current ride before
     * backgrounding the app). Cleared via [clearForegroundServiceFailure]
     * when the rider dismisses the banner or the next FGS start succeeds.
     */
    private val _foregroundServiceFailure = MutableStateFlow<String?>(null)
    val foregroundServiceFailure: StateFlow<String?> = _foregroundServiceFailure.asStateFlow()

    private val _telemetry = mutableListOf<TelemetryPoint>()
    /**
     * Snapshot of telemetry buffered so far. Reads only from the timer coroutine's
     * dispatcher — callers should marshal through scope.launch if invoking from
     * outside; for the common UI case (end-of-ride save) we guarantee the writer
     * is paused (state == Completed) before this is read.
     */
    fun telemetrySnapshot(): List<TelemetryPoint> = _telemetry.toList()

    /**
     * How many entries of [_telemetry] have already been written to
     * [activeTelemetryStore]. Everything past this index is pending. Seeded to
     * the restored sample count on resume so replayed samples aren't appended
     * to the log a second time.
     */
    private var flushedTelemetryCount = 0

    /**
     * Samples recovered from the durable log at startup, held until the rider
     * decides whether to resume. Loaded in [loadPersistedSnapshot] rather than
     * in [restoreFromSnapshot] because the resume entry point is a plain
     * (non-suspend) UI callback: doing the read up front means the samples are
     * already in hand when the rider taps Continue, so the timer can never
     * start racing an in-flight disk read.
     */
    private var recoveredTelemetry: List<TelemetryPoint> = emptyList()

    /**
     * Samples belonging to the ride [persistedSnapshot] describes.
     *
     * Two sources, because a finished-but-unsaved ride can be offered back to
     * the rider either in the process that rode it (they backed out of the
     * completion screen without tapping through) or in a fresh one (the app
     * died first). In-process the live buffer is authoritative; otherwise it's
     * whatever [loadPersistedSnapshot] recovered from the durable log.
     */
    fun pendingRideTelemetry(snapshot: ActiveSessionSnapshot): List<TelemetryPoint> {
        val rideIsStillLoaded = _activeWorkout.value?.id == snapshot.workoutId &&
            _state.value == WorkoutState.Completed
        return if (rideIsStillLoaded) _telemetry.toList() else recoveredTelemetry
    }

    var sessionStartTimeEpoch: Long = 0
        private set

    private val _targetBiasPercent = MutableStateFlow(0)
    val targetBiasPercent: StateFlow<Int> = _targetBiasPercent.asStateFlow()

    /**
     * The ERG target a "just ride" session is holding, in watts.
     *
     * A free ride has no plan to bias, so the rider's ± taps move an absolute
     * wattage rather than a percentage of a segment that doesn't exist. Zero
     * while a structured workout is loaded; that path keeps using
     * [targetBiasPercent] and the segment's own FTP percentage.
     */
    private val _freeRideTargetWatts = MutableStateFlow(0)
    val freeRideTargetWatts: StateFlow<Int> = _freeRideTargetWatts.asStateFlow()

    /**
     * The most recently persisted snapshot of an in-flight ride, or null if
     * nothing is unfinished. Updated whenever we write or clear the on-disk
     * snapshot. UI observes this to render the "unfinished ride" banner.
     */
    private val _persistedSnapshot = MutableStateFlow<ActiveSessionSnapshot?>(null)
    val persistedSnapshot: StateFlow<ActiveSessionSnapshot?> = _persistedSnapshot.asStateFlow()

    private var ftpWatts = 200
    private var timerJob: Job? = null
    private var pedalingDetectionJob: Job? = null

    // Wall-clock anchors — updated on resume so accumulated paused time doesn't count.
    private var activePhaseStartEpochMs: Long = 0
    private var elapsedAtPhaseStart: Int = 0
    private var elapsedInSegAtPhaseStart: Int = 0
    private var actualRideAtPhaseStart: Int = 0

    fun prepareWorkout(workout: Workout, ftp: Int) {
        if (_state.value != WorkoutState.Idle && _state.value != WorkoutState.Completed) {
            stopWorkout()
        }
        // Drop the outgoing ride's samples *before* wiping its persisted log.
        // The stopped timer's exit handler may still be racing us on another
        // thread; ordering it this way means it either finds an empty buffer
        // and does nothing, or enqueues an append that the clear below is
        // guaranteed to land after (both go through snapshotDispatcher in FIFO
        // order). Either way the previous ride can't bleed into this one.
        _telemetry.clear()
        flushedTelemetryCount = 0
        _virtualDistanceMeters.value = 0.0
        // Starting a fresh workout — discard any stale unfinished ride. The
        // user implicitly abandons the prior ride by starting a new one.
        clearPersistedRide()
        _activeWorkout.value = workout
        // Any road left over from a previous ride goes now. [prepareSimulation]
        // and [prepareCompanion] load the new one straight after this returns,
        // so neither engine-driven mode ever sees the gap.
        simulationEngine?.clear()
        companionEngine?.clear()
        ftpWatts = ftp
        _state.value = WorkoutState.Standby
        _elapsedSeconds.value = 0
        _actualRideSeconds.value = 0
        _currentSegmentIndex.value = 0
        _elapsedInSegment.value = 0
        _targetBiasPercent.value = 0
        // Meaningful only for a free ride; [prepareFreeRide] sets it right
        // after this returns. Cleared here so a structured workout started
        // after one can't inherit a stale target.
        _freeRideTargetWatts.value = 0
        sessionStartTimeEpoch = 0
        startPedalingDetection()
    }

    /**
     * Start an open-ended "just ride" session: no workout, no plan, no end.
     *
     * Runs against the synthetic [freeRideWorkout] so the timer, telemetry,
     * crash recovery and save-to-history paths are exactly the ones a
     * structured ride uses. The trainer opens at [freeRideStartWatts] of [ftp]
     * and holds whatever [adjustFreeRideTarget] moves it to until the rider
     * ends the ride.
     */
    fun prepareFreeRide(ftp: Int) {
        prepareWorkout(workout = freeRideWorkout(), ftp = ftp)
        _freeRideTargetWatts.value = freeRideStartWatts(ftp)
    }

    /**
     * Start a simulated ride down [route].
     *
     * Like a free ride this runs against a synthetic workout, so the timer,
     * telemetry, crash recovery and save-to-history paths are the ones every
     * other ride uses. Unlike every other mode the trainer is never told a
     * wattage: [SimulationEngine] puts it into FTMS simulation mode and keeps
     * it there, describing the road under the rider as they move along it.
     *
     * The ride finishes when the route does. That signal comes from the engine
     * rather than from the clock, which is why the callback is wired here.
     */
    fun prepareSimulation(
        route: RouteTrack,
        ftp: Int,
        /**
         * Where on the road to begin. Non-zero when the rider chose to carry on
         * from a previous sitting; see [RouteProgress].
         */
        startDistanceMeters: Double = 0.0,
        /** Climbing already done, so a resumed ride's total covers the whole route. */
        startElevationGainMeters: Double = 0.0,
    ) {
        val engine = simulationEngine ?: return
        prepareWorkout(
            workout = simulationWorkout(routeId = route.id, routeName = route.name),
            ftp = ftp,
        )
        engine.load(
            route = route,
            startDistanceMeters = startDistanceMeters,
            elevationGainMeters = startElevationGainMeters,
        )
        engine.onRouteFinished = { completeWorkout() }
    }

    /**
     * Start a ride whose road comes from a companion on another device.
     *
     * The third engine-driven mode, and the only one where the app does not
     * know what is being ridden. Like a route ride the trainer is put into FTMS
     * simulation mode and never told a wattage; unlike a route ride there is no
     * course to load, because the gradient and the speed arrive frame by frame
     * over the socket (see `docs/COMPANION_API.md`).
     *
     * Nothing here ends the ride. A route ride finishes when the road runs out,
     * but a companion ride has no road to run out: it ends when the companion
     * sends `finish` or the rider stops, both of which arrive through
     * [endWorkoutEarly].
     */
    fun prepareCompanion(ftp: Int) {
        val engine = companionEngine ?: return
        prepareWorkout(workout = companionWorkout(), ftp = ftp)
        engine.load()
    }

    /**
     * Restore a previously persisted snapshot. Sets state to Standby so the
     * rider has to pedal to resume, mirroring the start-of-ride flow.
     *
     * Telemetry recovered from the durable log ([loadPersistedSnapshot]) is
     * replayed into the in-memory buffer, so the ride the rider eventually
     * saves covers the whole session and not just the part after the crash.
     * Any in-memory samples from *this* process are dropped: the snapshot we're
     * restoring is the authoritative position, and the log already holds
     * everything recorded up to it.
     */
    fun restoreFromSnapshot(
        snapshot: ActiveSessionSnapshot,
        workout: Workout,
        /**
         * The route a simulated ride was on. Loaded by the caller from the
         * platform's route catalog, since the snapshot names it but cannot
         * carry it. Ignored for every other mode; a simulated ride whose route
         * has since gone missing resumes as a plain ride on a road with no
         * hills rather than refusing to resume at all.
         */
        route: RouteTrack? = null,
    ) {
        if (snapshot.workoutId != workout.id) return
        // Tear down any active timing without clearing the snapshot we're restoring.
        timerJob?.cancel()
        pedalingDetectionJob?.cancel()
        trainerManager.setTargetPower(0)

        _activeWorkout.value = workout
        ftpWatts = snapshot.ftp
        _elapsedSeconds.value = snapshot.elapsedSeconds
        _actualRideSeconds.value = snapshot.actualRideSeconds
        _currentSegmentIndex.value = snapshot.currentSegmentIndex
        _elapsedInSegment.value = snapshot.elapsedInSegment
        _targetBiasPercent.value = snapshot.targetBiasPercent
        // A free ride resumes on the wattage it was holding. Snapshots written
        // before the mode existed - and every structured ride - carry null, in
        // which case the field stays unused at 0.
        _freeRideTargetWatts.value = when {
            !workout.isFreeRide -> 0
            snapshot.freeRideTargetWatts != null -> clampFreeRideWatts(snapshot.freeRideTargetWatts)
            else -> freeRideStartWatts(snapshot.ftp)
        }
        if (workout.isSimulation && route != null) {
            simulationEngine?.load(
                route = route,
                startDistanceMeters = snapshot.simulationDistanceMeters ?: 0.0,
                gearIndex = snapshot.simulationGearIndex ?: SimulationEngine.USE_START_GEAR,
                elevationGainMeters = snapshot.simulationElevationGainMeters ?: 0.0,
                // The ride being restored is the same ride, so it began
                // wherever it began. Letting the origin default to the crash
                // point would quietly stop counting everything ridden before
                // the process died. Snapshots written before these fields
                // existed carry null and fall back to the old behaviour.
                startedAtMeters = snapshot.simulationStartMeters
                    ?: snapshot.simulationDistanceMeters ?: 0.0,
                startedAtElevationGainMeters = snapshot.simulationStartElevationGainMeters
                    ?: snapshot.simulationElevationGainMeters ?: 0.0,
            )
            simulationEngine?.onRouteFinished = { completeWorkout() }
            companionEngine?.clear()
        } else if (workout.isCompanion) {
            // The gear survives a crash; the road does not, and should not. A
            // resumed companion ride sits on a flat road until the companion
            // reconnects and says otherwise, which is the honest state: the app
            // has no idea what hill it was on.
            simulationEngine?.clear()
            companionEngine?.load(
                gearIndex = snapshot.simulationGearIndex ?: CompanionEngine.USE_START_GEAR,
            )
            companionEngine?.setProgress(
                distanceM = snapshot.simulationDistanceMeters,
                ascentM = snapshot.simulationElevationGainMeters,
            )
            // The road does not come back, but what the companion said *about*
            // it does. Nothing else has a copy: the catalogue never had this
            // course and the socket did not survive either, so without this a
            // recovered ride saves as numbers with no shape while the same
            // ride finished normally saves with one.
            snapshot.companionCourse?.let { companionEngine?.setCourse(it) }
        } else {
            simulationEngine?.clear()
            companionEngine?.clear()
        }
        _telemetry.clear()
        _telemetry.addAll(recoveredTelemetry)
        // Rebuilt from the recovered samples rather than carried in the
        // snapshot, so a ride resumed after a crash reports the same distance
        // it would have had the app never died. A simulated ride's distance is
        // real ground on a real route, so it comes from the snapshot instead.
        _virtualDistanceMeters.value = if (workout.isSimulation || workout.isCompanion) {
            snapshot.simulationDistanceMeters ?: 0.0
        } else {
            VirtualSpeed.distanceMeters(_telemetry.map { it.power }, riderMassKg())
        }
        // Already on disk — the next flush must only append what comes after.
        flushedTelemetryCount = _telemetry.size
        sessionStartTimeEpoch = snapshot.sessionStartTimeEpochMs
        _state.value = WorkoutState.Standby
        startPedalingDetection()
    }

    /**
     * Background loop that bridges trainer activity to session state. Runs for
     * the lifetime of the workout (created in [prepareWorkout] / [restoreFromSnapshot],
     * cancelled in [stopWorkout] / [completeWorkout]).
     *
     * Two transitions, both based on the same "pedaling" signal (cadence > 5 rpm
     * OR power > 10 W — either suffices, so a single foot resting on a pedal at
     * idle doesn't count, and a hard coast at top of an interval doesn't either):
     *
     *   - **Standby → Countdown**: rider starts pedaling at the start of a ride
     *     OR after an auto-pause. Existing 3-2-1-GO behaviour.
     *   - **Active → Standby**: rider stops pedaling for [AUTO_PAUSE_AFTER_MS],
     *     freezing the timer + zeroing the trainer target. Resumes naturally via
     *     the first transition above when pedaling restarts.
     *
     * On a simulated route there is a third thing that counts as riding: still
     * moving. A rider who stops pedalling over the top of a climb is
     * descending, not resting, and pausing the ride under them would stop the
     * road, freeze the clock and throw away the descent they just earned. So
     * while the bike is rolling faster than [COASTING_MPS] the session stays
     * active, and auto-pause only arrives once they have actually stopped.
     *
     * Polled every [DETECTION_TICK_MS] because the trainer's StateFlows are
     * "distinct values only" — when power drops to 0 and stays there, no new
     * events fire, so we can't react purely on edges.
     */
    private fun startPedalingDetection() {
        pedalingDetectionJob?.cancel()
        pedalingDetectionJob = scope.launch {
            var lastPedalingEpochMs = getCurrentEpochMillis()
            while (isActive) {
                val state = _state.value
                if (state == WorkoutState.Idle || state == WorkoutState.Completed) break

                val cadence = trainerManager.cadence.value
                val power = trainerManager.power.value
                // Coasting down a hill is riding. Only meaningful while a
                // route is loaded and moving; every other mode has no ground
                // under it and this term is always false.
                val isRolling = when {
                    _activeWorkout.value?.isSimulation == true ->
                        (simulationEngine?.speedMps?.value ?: 0.0) > COASTING_MPS
                    // The companion owns speed in its mode, so the same rule
                    // reads it from there. A rider freewheeling down the game's
                    // descent is riding, exactly as they are on a route.
                    _activeWorkout.value?.isCompanion == true ->
                        (companionEngine?.speedMps?.value ?: 0.0) > COASTING_MPS
                    else -> false
                }
                val isPedaling = cadence > 5 || power > 10 || isRolling
                val now = getCurrentEpochMillis()
                if (isPedaling) lastPedalingEpochMs = now

                when (state) {
                    WorkoutState.Standby -> if (isPedaling) startCountdown()
                    WorkoutState.Active -> {
                        if (now - lastPedalingEpochMs >= AUTO_PAUSE_AFTER_MS) {
                            autoPauseToStandby()
                            // Reset so we don't immediately re-fire if the rider
                            // bumps the pedal a moment later — the Standby branch
                            // above will pick up the resume.
                            lastPedalingEpochMs = now
                        }
                    }
                    else -> { /* Countdown / Paused — leave alone */ }
                }
                delay(DETECTION_TICK_MS)
            }
        }
    }

    /**
     * Stop-pedaling auto-pause. Mirrors [pauseWorkout] but lands in [WorkoutState.Standby]
     * instead of [WorkoutState.Paused] so the existing pedaling-detection loop
     * auto-resumes (via Countdown) the moment the rider starts pedaling again.
     */
    private fun autoPauseToStandby() {
        if (_state.value != WorkoutState.Active) return
        timerJob?.cancel()
        // Order matters: the engine stops driving the trainer before the zero
        // target lands, so the two can't race over the control point.
        simulationEngine?.stop()
        companionEngine?.stop()
        trainerManager.setTargetPower(0)
        _state.value = WorkoutState.Standby
        // The timer's exit handler flushes the tail to the durable log.
    }

    fun adjustDifficulty(deltaPercent: Int) {
        val newBias = (_targetBiasPercent.value + deltaPercent).coerceIn(-30, 30)
        _targetBiasPercent.value = newBias
        if (_state.value == WorkoutState.Active) updateTrainerTarget()
    }

    /**
     * Move a free ride's ERG target by [deltaWatts] (one stepper tap is
     * [FREE_RIDE_STEP_WATTS]), clamped to the range a trainer will actually
     * track. No-op unless a free ride is loaded.
     *
     * Unlike [adjustDifficulty] this takes effect in every state, not only
     * [WorkoutState.Active]: the rider sets their opening wattage while the HUD
     * is still in "pedal to start" standby, and the trainer picks it up when
     * the ride goes active.
     */
    fun adjustFreeRideTarget(deltaWatts: Int) {
        if (_activeWorkout.value?.isFreeRide != true) return
        _freeRideTargetWatts.value = clampFreeRideWatts(_freeRideTargetWatts.value + deltaWatts)
        if (_state.value == WorkoutState.Active) updateTrainerTarget()
    }

    fun skipSegment() {
        val workout = _activeWorkout.value ?: return
        if (_state.value != WorkoutState.Active && _state.value != WorkoutState.Paused) return
        val idx = _currentSegmentIndex.value
        if (idx >= workout.segments.size) return

        val skippedSegLength = workout.segments[idx].lengthInSeconds
        // Roll elapsed forward to segment boundary so the timeline stays consistent.
        val rollForward = (skippedSegLength - _elapsedInSegment.value).coerceAtLeast(0)
        _elapsedSeconds.value += rollForward
        _elapsedInSegment.value = 0
        _currentSegmentIndex.value = idx + 1
        // Re-anchor wall-clock so the resumed timer doesn't double-count the skip.
        if (_state.value == WorkoutState.Active) anchorActivePhase()

        if (_currentSegmentIndex.value >= workout.segments.size) {
            completeWorkout()
        } else {
            updateTrainerTarget()
        }
    }

    fun pauseWorkout() {
        if (_state.value != WorkoutState.Active) return
        _state.value = WorkoutState.Paused
        simulationEngine?.stop()
        companionEngine?.stop()
        trainerManager.setTargetPower(0)
        // Cancelling the loop runs its exit handler, which flushes the tail —
        // a paused ride can sit backgrounded for a long time and is a prime
        // candidate for being killed.
        timerJob?.cancel()
    }

    /**
     * Called by the platform foreground service when it can't keep the ride
     * alive in the background (BLE permission missing, background-start
     * refused on Android 12+, etc.). Pauses the active ride immediately so
     * the rider doesn't lose progress to a silent kill, and exposes the
     * message via [foregroundServiceFailure] for the UI to surface.
     *
     * No-op for non-active states — if the service can't start while the
     * rider is on the workout-list screen, there's nothing to pause and the
     * snapshot doesn't need writing.
     */
    fun notifyForegroundServiceFailure(message: String) {
        _foregroundServiceFailure.value = message
        if (_state.value == WorkoutState.Active || _state.value == WorkoutState.Countdown) {
            pauseWorkout()
        }
    }

    /** Clears the FGS failure flag — call after the rider acks the banner or the next start succeeds. */
    fun clearForegroundServiceFailure() {
        _foregroundServiceFailure.value = null
    }

    fun resumeWorkout() {
        if (_state.value != WorkoutState.Paused) return
        _state.value = WorkoutState.Active
        anchorActivePhase()
        startModeControl()
        startTimerLoop()
    }

    /**
     * Tears down the in-memory active session but **leaves the persisted
     * snapshot intact** so the user can resume from the workout list banner.
     * Use [abandonUnfinishedRide] to also discard the snapshot.
     */
    fun stopWorkout() {
        // Before the engine is stopped and its distance is gone. Backing out of
        // a ride does not save it to history, but the rider still rode the
        // road, and being asked to ride it again from the start is the thing
        // this exists to prevent.
        recordRouteProgress()
        timerJob?.cancel()
        pedalingDetectionJob?.cancel()
        simulationEngine?.stop()
        companionEngine?.stop()
        trainerManager.setTargetPower(0)
        _state.value = WorkoutState.Idle
        _activeWorkout.value = null
    }

    /**
     * End the ride now and mark it [WorkoutState.Completed] so the accumulated
     * telemetry can be saved to history, exactly like a naturally-finished
     * workout. Invoked when the rider explicitly chooses "End & save" from a
     * paused ride — either a manual [WorkoutState.Paused] or the stop-pedaling
     * auto-pause ([WorkoutState.Standby]).
     *
     * No-op if the ride never actually started (nothing worth saving) or is
     * already finished/torn down.
     */
    fun endWorkoutEarly() {
        val state = _state.value
        if (state == WorkoutState.Idle || state == WorkoutState.Completed) return
        // Never got past the initial "pedal to start" standby — no ride to save.
        if (sessionStartTimeEpoch == 0L) return
        completeWorkout()
    }

    /**
     * Remember how far along the route this ride got, so a course too long for
     * one sitting can be finished across several.
     *
     * Reaching the end clears the mark instead of moving it: a rider who picks
     * a finished route again means to ride it, not to be put back at the line
     * they already crossed. So does stopping in the first few hundred metres,
     * where an offer to continue reads as the app having lost track of the
     * ride rather than remembered it.
     */
    private fun recordRouteProgress() {
        val store = routeProgressStore ?: return
        val engine = simulationEngine ?: return
        val route = engine.route.value ?: return
        val finished = engine.finished.value
        val distance = engine.distanceMeters.value
        val climbed = engine.elevationGainMeters.value
        val seconds = _elapsedSeconds.value
        scope.launch(snapshotDispatcher) {
            if (finished || distance < MIN_RESUMABLE_METERS) {
                store.clear(route.id)
            } else {
                store.save(
                    RouteProgress(
                        routeId = route.id,
                        distanceMeters = distance,
                        elevationGainMeters = climbed,
                        elapsedSeconds = seconds,
                        savedAtEpochMs = getCurrentEpochMillis(),
                    )
                )
            }
        }
    }

    /**
     * Discard the persisted snapshot — invoked when the user explicitly taps
     * "Abandon" on the unfinished-ride banner.
     */
    fun abandonUnfinishedRide() {
        if (_state.value != WorkoutState.Idle) stopWorkout()
        simulationEngine?.clear()
        companionEngine?.clear()
        clearPersistedRide()
    }

    /**
     * One-time loader, called from app startup so the banner lights up if we
     * crashed mid-ride. Also recovers the durable telemetry log up front so
     * [restoreFromSnapshot] has the samples in hand the moment the rider taps
     * Continue.
     */
    suspend fun loadPersistedSnapshot() {
        val store = activeSessionStore ?: return
        val snapshot = store.read()
        recoveredTelemetry = if (snapshot != null) {
            activeTelemetryStore?.readAll().orEmpty()
        } else {
            // No unfinished ride to resume, so any log left on disk belongs to a
            // session that already ended. Reap it now rather than let it bleed
            // into the next ride that gets resumed.
            activeTelemetryStore?.clear()
            emptyList()
        }
        // Published last, once the samples are in place: the banner reads them
        // via [pendingRideTelemetry] the moment a snapshot appears, and uses
        // whether they exist to decide what to offer.
        _persistedSnapshot.value = snapshot
    }

    fun dispose() {
        scope.cancel()
    }

    // Internals ──────────────────────────────────────────────────────────────

    private fun startCountdown() {
        // The detection loop keeps running — it's already a no-op while in
        // Countdown / Active and we need it alive for the auto-pause path.
        _state.value = WorkoutState.Countdown
        _countdownSeconds.value = 3

        scope.launch {
            while (_countdownSeconds.value > 0) {
                delay(1000)
                _countdownSeconds.value -= 1
            }
            // Hold "GO!" on screen so the impact lands before the ride UI takes over.
            delay(2000)
            startActiveWorkout()
        }
    }

    private fun startActiveWorkout() {
        _state.value = WorkoutState.Active
        if (sessionStartTimeEpoch == 0L) {
            sessionStartTimeEpoch = getCurrentEpochMillis()
        }
        anchorActivePhase()
        startModeControl()
        startTimerLoop()
    }

    /**
     * Hand the trainer to whichever thing owns it in this mode: the simulation
     * engine on a route, [updateTrainerTarget]'s ERG write everywhere else.
     * Called on every transition into [WorkoutState.Active].
     */
    private fun startModeControl() {
        val workout = _activeWorkout.value
        when {
            workout?.isSimulation == true -> simulationEngine?.start()
            workout?.isCompanion == true -> companionEngine?.start()
            else -> updateTrainerTarget()
        }
    }

    private fun anchorActivePhase() {
        activePhaseStartEpochMs = getCurrentEpochMillis()
        elapsedAtPhaseStart = _elapsedSeconds.value
        elapsedInSegAtPhaseStart = _elapsedInSegment.value
        actualRideAtPhaseStart = _actualRideSeconds.value
    }

    private fun startTimerLoop() {
        timerJob?.cancel()
        timerJob = scope.launch {
            try {
                while (_state.value == WorkoutState.Active) {
                    tick()
                    delay(1000)
                }
            } finally {
                // The loop owns [_telemetry] — every add happens here — so this
                // is the only place a flush can copy the buffer without racing
                // a writer. Running it on the way out covers every path that
                // stops the timer (manual pause, stop-pedaling auto-pause,
                // backing out of a ride, completion) so the last few seconds
                // don't sit unflushed while the app is a kill candidate.
                //
                // Safe under cancellation: flushTelemetry only copies the slice
                // and hands it to the manager's own scope, which is a sibling of
                // this job rather than a child, so it isn't cancelled with us.
                flushTelemetry()
            }
        }
    }

    private var lastSnapshotEpoch: Long = 0

    /**
     * Hand every not-yet-written sample to the durable log.
     *
     * The pending slice is copied on the caller's thread (the timer coroutine,
     * the only writer to [_telemetry]) and the immutable copy is what crosses
     * onto [snapshotDispatcher], so the appending coroutine never reads a list
     * that's being mutated underneath it.
     */
    private fun flushTelemetry() {
        val store = activeTelemetryStore ?: return
        if (flushedTelemetryCount >= _telemetry.size) return
        val pending = _telemetry.subList(flushedTelemetryCount, _telemetry.size).toList()
        flushedTelemetryCount += pending.size
        scope.launch(snapshotDispatcher) {
            try {
                store.append(pending)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to append active telemetry", e)
            }
        }
    }

    private fun maybePersistSnapshot(now: Long) {
        if (now - lastSnapshotEpoch < SNAPSHOT_INTERVAL_MS) return
        // Don't persist for finished or not-yet-started states. Without this
        // guard, a final tick could write a snapshot for a ride that's already
        // marked Completed, racing against the completion clear.
        val state = _state.value
        if (state == WorkoutState.Idle || state == WorkoutState.Completed) return
        lastSnapshotEpoch = now
        // Telemetry first, position second, both FIFO on snapshotDispatcher. A
        // crash between the two therefore leaves samples slightly *ahead* of the
        // restored position rather than behind it, which loses nothing: the
        // extra samples are real riding, and the snapshot only decides which
        // interval we drop the rider back into.
        flushTelemetry()
        val store = activeSessionStore ?: return
        val workout = _activeWorkout.value ?: return
        val snapshot = ActiveSessionSnapshot(
            workoutId = workout.id,
            sessionStartTimeEpochMs = sessionStartTimeEpoch,
            ftp = ftpWatts,
            targetBiasPercent = _targetBiasPercent.value,
            elapsedSeconds = _elapsedSeconds.value,
            currentSegmentIndex = _currentSegmentIndex.value,
            elapsedInSegment = _elapsedInSegment.value,
            state = state,
            actualRideSeconds = _actualRideSeconds.value,
            freeRideTargetWatts = _freeRideTargetWatts.value.takeIf { workout.isFreeRide },
            simulationDistanceMeters = engineDistanceMeters(workout),
            simulationGearIndex = engineGearIndex(workout),
            simulationElevationGainMeters = engineElevationGainMeters(workout),
            simulationStartMeters = engineStartMeters(workout),
            simulationStartElevationGainMeters = engineStartElevationGainMeters(workout),
            companionCourse = engineCourse(workout),
        )
        _persistedSnapshot.value = snapshot
        scope.launch(snapshotDispatcher) {
            // Re-check inside the dispatcher: by the time this lambda runs the
            // workout may have completed, in which case we'd be writing a
            // snapshot that the upcoming clear is supposed to delete.
            val now2 = _state.value
            if (now2 == WorkoutState.Idle || now2 == WorkoutState.Completed) return@launch
            store.write(snapshot)
        }
    }

    /**
     * Drop everything persisted for the ride: position *and* telemetry.
     *
     * Only for paths where the ride is genuinely going away - the rider
     * abandoned it, replaced it by starting another, or it reached history and
     * no longer needs a recovery copy. Completion deliberately does *not* come
     * through here; see [persistCompletedRide].
     */
    private fun clearPersistedRide() {
        _persistedSnapshot.value = null
        recoveredTelemetry = emptyList()
        // FIFO with the writes above — any still queued are skipped by the
        // state guard inside their lambda, then these clears run and remove
        // whatever's on disk.
        activeSessionStore?.let { store ->
            scope.launch(snapshotDispatcher) { store.clear() }
        }
        activeTelemetryStore?.let { store ->
            scope.launch(snapshotDispatcher) {
                try {
                    store.clear()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear active telemetry", e)
                }
            }
        }
    }

    /**
     * Compute elapsed from wall-clock so we don't drift; sample telemetry
     * once per second; advance segments when the wall-clock crosses a
     * boundary.
     */
    private fun tick() {
        val workout = _activeWorkout.value ?: return
        val now = getCurrentEpochMillis()
        val deltaSeconds = ((now - activePhaseStartEpochMs) / 1000).toInt().coerceAtLeast(0)
        _elapsedSeconds.value = elapsedAtPhaseStart + deltaSeconds
        _elapsedInSegment.value = elapsedInSegAtPhaseStart + deltaSeconds
        // actualRideSeconds shares the same wall-clock anchor as elapsedSeconds
        // during a single Active phase, but skipSegment never bumps its phase
        // anchor — so skipped time is never added.
        _actualRideSeconds.value = actualRideAtPhaseStart + deltaSeconds

        val sample = TelemetryPoint(
            timestampEpoch = now,
            power = trainerManager.power.value.toLong(),
            cadence = trainerManager.cadence.value.toLong(),
            heartRate = effectiveHeartRate().toLong(),
        )
        _telemetry.add(sample)
        val engineDistance = engineDistanceMeters(workout)
        if (engineDistance != null) {
            // On a route the distance isn't an estimate at all: it's where the
            // rider has got to on a real road, which the engine already knows.
            // In companion mode it is the total the companion reported, which is
            // ground covered rather than a position, and equally not a guess.
            _virtualDistanceMeters.value = engineDistance
        } else {
            // One sample is one second of riding, so the sample's speed is the
            // metres it covered. Integrating sample by sample rather than from an
            // average matters because speed goes as the cube root of power: a
            // ride that alternates hard and soft covers less ground than a steady
            // one at the same average watts, and this is what shows that.
            _virtualDistanceMeters.value +=
                VirtualSpeed.speedMps(sample.power.toDouble(), riderMassKg())
        }
        maybePersistSnapshot(now)

        val idx = _currentSegmentIndex.value
        if (idx >= workout.segments.size) {
            completeWorkout()
            return
        }
        val currentSeg = workout.segments[idx]
        if (_elapsedInSegment.value >= currentSeg.lengthInSeconds) {
            _currentSegmentIndex.value = idx + 1
            _elapsedInSegment.value = 0
            elapsedInSegAtPhaseStart = 0
            elapsedAtPhaseStart = _elapsedSeconds.value
            actualRideAtPhaseStart = _actualRideSeconds.value
            activePhaseStartEpochMs = now

            if (_currentSegmentIndex.value >= workout.segments.size) {
                completeWorkout()
            } else {
                updateTrainerTarget()
            }
        }
    }

    /**
     * Distance, gear and climbing for whichever engine is driving, or null when
     * none is.
     *
     * The three snapshot columns are named for simulation because route mode
     * got there first, but what they hold is generic - ground covered, the gear
     * the rider is in, and metres climbed - so a companion ride reuses them
     * rather than costing a migration for three columns that already exist.
     */
    private fun engineDistanceMeters(workout: Workout): Double? = when {
        workout.isSimulation -> simulationEngine?.distanceMeters?.value
        workout.isCompanion -> companionEngine?.distanceMeters?.value
        else -> null
    }

    private fun engineGearIndex(workout: Workout): Int? = when {
        workout.isSimulation -> simulationEngine?.gearIndex?.value
        workout.isCompanion -> companionEngine?.gearIndex?.value
        else -> null
    }

    private fun engineElevationGainMeters(workout: Workout): Double? = when {
        workout.isSimulation -> simulationEngine?.elevationGainMeters?.value
        workout.isCompanion -> companionEngine?.elevationGainMeters?.value
        else -> null
    }

    /**
     * Where this ride began. Always zero for a companion ride: its totals are
     * this session's, because the companion is the thing counting them.
     */
    private fun engineStartMeters(workout: Workout): Double? =
        if (workout.isSimulation) simulationEngine?.startedAtMeters?.value else null

    private fun engineStartElevationGainMeters(workout: Workout): Double? =
        if (workout.isSimulation) {
            simulationEngine?.startedAtElevationGainMeters?.value
        } else {
            null
        }

    private fun engineCourse(workout: Workout): CompanionCourse? =
        if (workout.isCompanion) {
            companionEngine?.course?.value?.takeUnless { it.isEmpty }
        } else {
            null
        }

    /**
     * What the ride did on its road, from whichever source still has it.
     *
     * The mirror of [pendingRideTelemetry], and it exists for the same reason.
     * A ride tapped through from the completion screen still has its engines
     * loaded and every number can be read off them. A ride saved from the
     * unfinished-ride banner has nothing but the snapshot: the app has just
     * started and the engines are empty, so reading them returned zeroes and a
     * recovered route ride saved with no distance and no climbing against a
     * road it had obviously ridden.
     *
     * [snapshot] is the one the caller is saving, or null when there is no
     * recovery involved.
     */
    fun pendingRideRoad(snapshot: ActiveSessionSnapshot? = null): RideRoad {
        val workout = _activeWorkout.value
        val live = workout != null &&
            (snapshot == null || workout.id == snapshot.workoutId)
        if (live && workout != null) {
            return RideRoad(
                distanceMeters = engineDistanceMeters(workout) ?: 0.0,
                elevationGainMeters = engineElevationGainMeters(workout) ?: 0.0,
                startedAtMeters = engineStartMeters(workout) ?: 0.0,
                startedAtElevationGainMeters = engineStartElevationGainMeters(workout) ?: 0.0,
                course = engineCourse(workout),
            )
        }
        if (snapshot == null) return RideRoad.NONE
        return RideRoad(
            distanceMeters = snapshot.simulationDistanceMeters ?: 0.0,
            elevationGainMeters = snapshot.simulationElevationGainMeters ?: 0.0,
            startedAtMeters = snapshot.simulationStartMeters ?: 0.0,
            startedAtElevationGainMeters = snapshot.simulationStartElevationGainMeters ?: 0.0,
            course = snapshot.companionCourse,
        )
    }

    private fun updateTrainerTarget() {
        val workout = _activeWorkout.value ?: return
        // A simulated ride has no target: the engine owns the trainer and
        // describes a road to it. Writing a wattage here would drop the machine
        // out of simulation mode and flatten the route's hills.
        if (workout.isSimulation || workout.isCompanion) return
        if (workout.isFreeRide) {
            // The synthetic segment's FTP percentage is only the opening
            // suggestion; what the rider has dialled in is authoritative.
            trainerManager.setTargetPower(_freeRideTargetWatts.value.coerceAtLeast(0))
            return
        }
        val idx = _currentSegmentIndex.value
        if (idx < workout.segments.size) {
            val seg = workout.segments[idx]
            val biasMultiplier = 1.0 + (_targetBiasPercent.value / 100.0)
            val targetWatts = (seg.powerPercentFTP / 100.0 * ftpWatts * biasMultiplier).roundToInt()
            trainerManager.setTargetPower(targetWatts.coerceAtLeast(0))
        }
    }

    private fun completeWorkout() {
        recordRouteProgress()
        // Flip state first so the timer loop's while-condition fails and it
        // unwinds through its exit handler, flushing the ride's last samples.
        _state.value = WorkoutState.Completed
        timerJob?.cancel()
        pedalingDetectionJob?.cancel()
        simulationEngine?.stop()
        companionEngine?.stop()
        trainerManager.setTargetPower(0)
        persistCompletedRide()
    }

    /**
     * Record the finished ride as still-pending-save rather than dropping it.
     *
     * Completion used to clear the snapshot outright, which left the ride
     * living only in this process's memory from the moment the trophy appeared
     * until the rider tapped through to the summary and the UI wrote it to
     * history. That gap is as long as the rider takes to catch their breath,
     * and anything that killed the app inside it took the whole session with
     * it. Backing out of the completion screen lost it just as silently.
     *
     * The snapshot goes down with [WorkoutState.Completed] so recovery knows to
     * offer a save rather than putting the rider back on the bike. Both copies
     * are dropped by [markRideSaved] once the ride is durable in history.
     */
    private fun persistCompletedRide() {
        // Never actually started: no ride time, nothing worth recovering.
        if (sessionStartTimeEpoch == 0L) {
            clearPersistedRide()
            return
        }
        val workout = _activeWorkout.value ?: return
        val snapshot = ActiveSessionSnapshot(
            workoutId = workout.id,
            sessionStartTimeEpochMs = sessionStartTimeEpoch,
            ftp = ftpWatts,
            targetBiasPercent = _targetBiasPercent.value,
            elapsedSeconds = _elapsedSeconds.value,
            currentSegmentIndex = _currentSegmentIndex.value,
            elapsedInSegment = _elapsedInSegment.value,
            state = WorkoutState.Completed,
            actualRideSeconds = _actualRideSeconds.value,
            freeRideTargetWatts = _freeRideTargetWatts.value.takeIf { workout.isFreeRide },
            simulationDistanceMeters = engineDistanceMeters(workout),
            simulationGearIndex = engineGearIndex(workout),
            simulationElevationGainMeters = engineElevationGainMeters(workout),
            simulationStartMeters = engineStartMeters(workout),
            simulationStartElevationGainMeters = engineStartElevationGainMeters(workout),
            companionCourse = engineCourse(workout),
        )
        _persistedSnapshot.value = snapshot
        val store = activeSessionStore ?: return
        // FIFO behind any periodic write still queued from the final ticks, so
        // this Completed snapshot is what ends up on disk.
        scope.launch(snapshotDispatcher) { store.write(snapshot) }
    }

    /**
     * The finished ride is now in history, so the crash-recovery copies can go.
     * Called by the UI once the insert has returned, which is the first moment
     * the ride is durable somewhere other than these two files.
     */
    fun markRideSaved() {
        clearPersistedRide()
    }

    companion object {
        private const val TAG = "WorkoutSessionManager"
        private const val SNAPSHOT_INTERVAL_MS = 5_000L
        /** Detection-loop cadence — small enough to catch the 3-second gap with sub-second slack. */
        private const val DETECTION_TICK_MS = 500L
        /** Stop-pedaling threshold before the active ride auto-pauses to Standby. */
        private const val AUTO_PAUSE_AFTER_MS = 3_000L

        /**
         * Above this speed a simulated ride counts as under way even with the
         * pedals still. Walking pace: fast enough that a bike rolling to a
         * halt does eventually pause, slow enough that no real descent trips
         * it.
         */
        private const val COASTING_MPS = 1.0
    }
}

