package app.indoorbike.shared.session

import app.indoorbike.shared.Workout
import app.indoorbike.shared.ble.TrainerManager
import app.indoorbike.shared.db.TelemetryPoint
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(WorkoutState.Idle)
    val state: StateFlow<WorkoutState> = _state.asStateFlow()

    private val _activeWorkout = MutableStateFlow<Workout?>(null)
    val activeWorkout: StateFlow<Workout?> = _activeWorkout.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    private val _countdownSeconds = MutableStateFlow(3)
    val countdownSeconds: StateFlow<Int> = _countdownSeconds.asStateFlow()

    private val _currentSegmentIndex = MutableStateFlow(0)
    val currentSegmentIndex: StateFlow<Int> = _currentSegmentIndex.asStateFlow()

    private val _elapsedInSegment = MutableStateFlow(0)
    val elapsedInSegment: StateFlow<Int> = _elapsedInSegment.asStateFlow()

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

    private var ftpWatts = 200
    private var timerJob: Job? = null
    private var pedalingDetectionJob: Job? = null

    // Wall-clock anchors — updated on resume so accumulated paused time doesn't count.
    private var activePhaseStartEpochMs: Long = 0
    private var elapsedAtPhaseStart: Int = 0
    private var elapsedInSegAtPhaseStart: Int = 0

    fun prepareWorkout(workout: Workout, ftp: Int) {
        if (_state.value != WorkoutState.Idle && _state.value != WorkoutState.Completed) {
            stopWorkout()
        }
        _activeWorkout.value = workout
        ftpWatts = ftp
        _state.value = WorkoutState.Standby
        _elapsedSeconds.value = 0
        _currentSegmentIndex.value = 0
        _elapsedInSegment.value = 0
        _targetBiasPercent.value = 0
        _telemetry.clear()
        sessionStartTimeEpoch = 0

        pedalingDetectionJob?.cancel()
        pedalingDetectionJob = trainerManager.power.onEach { watts ->
            if (_state.value == WorkoutState.Standby && watts > 10) {
                startCountdown()
            }
        }.launchIn(scope)
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

    fun resumeWorkout() {
        if (_state.value != WorkoutState.Paused) return
        _state.value = WorkoutState.Active
        anchorActivePhase()
        updateTrainerTarget()
        startTimerLoop()
    }

    fun stopWorkout() {
        timerJob?.cancel()
        pedalingDetectionJob?.cancel()
        trainerManager.setTargetPower(0)
        _state.value = WorkoutState.Idle
        _activeWorkout.value = null
        clearPersistedSnapshot()
    }

    fun dispose() {
        scope.cancel()
    }

    // Internals ──────────────────────────────────────────────────────────────

    private fun startCountdown() {
        pedalingDetectionJob?.cancel()
        _state.value = WorkoutState.Countdown
        _countdownSeconds.value = 3

        scope.launch {
            while (_countdownSeconds.value > 0) {
                delay(1000)
                _countdownSeconds.value -= 1
            }
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
            state = _state.value,
        )
        scope.launch { store.write(snapshot) }
    }

    private fun clearPersistedSnapshot() {
        val store = activeSessionStore ?: return
        scope.launch { store.clear() }
    }

    /**
     * Read any persisted snapshot left from a previous process. Returns the
     * snapshot if one exists; the caller (typically the App init path) decides
     * how to surface a "Resume ride?" affordance.
     */
    suspend fun readPersistedSnapshot(): ActiveSessionSnapshot? = activeSessionStore?.read()

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

        val sample = TelemetryPoint(
            timestampEpoch = now,
            power = trainerManager.power.value.toLong(),
            cadence = trainerManager.cadence.value.toLong(),
            heartRate = trainerManager.heartRate.value.toLong(),
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
        trainerManager.setTargetPower(0)
        _state.value = WorkoutState.Completed
        clearPersistedSnapshot()
    }

    companion object {
        private const val TAG = "WorkoutSessionManager"
        private const val SNAPSHOT_INTERVAL_MS = 5_000L
    }
}

