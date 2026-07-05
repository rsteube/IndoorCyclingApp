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
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.getCurrentEpochMillis
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
 * completes, at which point they are persisted via [HistoryRepository].
 */
class WorkoutSessionManager(
    private val trainerManager: TrainerManager,
    private val activeSessionStore: ActiveSessionStore? = null,
    /**
     * How to get the user's current heart rate at sample time. The default
     * reads from the trainer's HR characteristic; a [HeartRateMonitor]-aware
     * caller (AppContainer) overrides this to prefer the external HRM.
     */
    private val effectiveHeartRate: () -> Int = { trainerManager.heartRate.value },
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

    var sessionStartTimeEpoch: Long = 0
        private set

    private val _targetBiasPercent = MutableStateFlow(0)
    val targetBiasPercent: StateFlow<Int> = _targetBiasPercent.asStateFlow()

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
        // Starting a fresh workout — discard any stale unfinished snapshot. The
        // user implicitly abandons the prior ride by starting a new one.
        clearPersistedSnapshot()
        _activeWorkout.value = workout
        ftpWatts = ftp
        _state.value = WorkoutState.Standby
        _elapsedSeconds.value = 0
        _actualRideSeconds.value = 0
        _currentSegmentIndex.value = 0
        _elapsedInSegment.value = 0
        _targetBiasPercent.value = 0
        _telemetry.clear()
        sessionStartTimeEpoch = 0
        startPedalingDetection()
    }

    /**
     * Restore a previously persisted snapshot. Sets state to Standby so the
     * rider has to pedal to resume, mirroring the start-of-ride flow. Existing
     * in-memory telemetry from this process is discarded — only post-resume
     * samples will contribute to the saved session's averages.
     */
    fun restoreFromSnapshot(snapshot: ActiveSessionSnapshot, workout: Workout) {
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
        _telemetry.clear()
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
                val isPedaling = cadence > 5 || power > 10
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
        trainerManager.setTargetPower(0)
        _state.value = WorkoutState.Standby
    }

    fun adjustDifficulty(deltaPercent: Int) {
        val newBias = (_targetBiasPercent.value + deltaPercent).coerceIn(-30, 30)
        _targetBiasPercent.value = newBias
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
        trainerManager.setTargetPower(0)
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
        updateTrainerTarget()
        startTimerLoop()
    }

    /**
     * Tears down the in-memory active session but **leaves the persisted
     * snapshot intact** so the user can resume from the workout list banner.
     * Use [abandonUnfinishedRide] to also discard the snapshot.
     */
    fun stopWorkout() {
        timerJob?.cancel()
        pedalingDetectionJob?.cancel()
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
     * Discard the persisted snapshot — invoked when the user explicitly taps
     * "Abandon" on the unfinished-ride banner.
     */
    fun abandonUnfinishedRide() {
        if (_state.value != WorkoutState.Idle) stopWorkout()
        clearPersistedSnapshot()
    }

    /** One-time loader, called from app startup so the banner lights up if we crashed mid-ride. */
    suspend fun loadPersistedSnapshot() {
        val store = activeSessionStore ?: return
        _persistedSnapshot.value = store.read()
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
        updateTrainerTarget()
        startTimerLoop()
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
            while (_state.value == WorkoutState.Active) {
                tick()
                delay(1000)
            }
        }
    }

    private var lastSnapshotEpoch: Long = 0

    private fun maybePersistSnapshot(now: Long) {
        val store = activeSessionStore ?: return
        if (now - lastSnapshotEpoch < SNAPSHOT_INTERVAL_MS) return
        // Don't persist for finished or not-yet-started states. Without this
        // guard, a final tick could write a snapshot for a ride that's already
        // marked Completed, racing against the completion clear.
        val state = _state.value
        if (state == WorkoutState.Idle || state == WorkoutState.Completed) return
        lastSnapshotEpoch = now
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

    private fun clearPersistedSnapshot() {
        _persistedSnapshot.value = null
        val store = activeSessionStore ?: return
        // FIFO with the writes above — if any are still queued, they're
        // skipped by the state guard inside their lambda, then this clear
        // runs and removes whatever's on disk.
        scope.launch(snapshotDispatcher) { store.clear() }
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

    private fun updateTrainerTarget() {
        val workout = _activeWorkout.value ?: return
        val idx = _currentSegmentIndex.value
        if (idx < workout.segments.size) {
            val seg = workout.segments[idx]
            val biasMultiplier = 1.0 + (_targetBiasPercent.value / 100.0)
            val targetWatts = (seg.powerPercentFTP / 100.0 * ftpWatts * biasMultiplier).roundToInt()
            trainerManager.setTargetPower(targetWatts.coerceAtLeast(0))
        }
    }

    private fun completeWorkout() {
        timerJob?.cancel()
        pedalingDetectionJob?.cancel()
        trainerManager.setTargetPower(0)
        _state.value = WorkoutState.Completed
        clearPersistedSnapshot()
    }

    companion object {
        private const val TAG = "WorkoutSessionManager"
        private const val SNAPSHOT_INTERVAL_MS = 5_000L
        /** Detection-loop cadence — small enough to catch the 3-second gap with sub-second slack. */
        private const val DETECTION_TICK_MS = 500L
        /** Stop-pedaling threshold before the active ride auto-pauses to Standby. */
        private const val AUTO_PAUSE_AFTER_MS = 3_000L
    }
}

