package app.indoorbike.shared.session

import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout
import app.indoorbike.shared.ble.TrainerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import app.indoorbike.shared.db.TelemetryPoint
import app.indoorbike.shared.util.getCurrentEpochMillis

enum class WorkoutState {
    Idle,
    Standby,
    Countdown,
    Active,
    Paused,
    Completed
}

object WorkoutSessionManager {
    private val scope = CoroutineScope(Dispatchers.Default)

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
    val telemetry: List<TelemetryPoint> get() = _telemetry.toList()
    var sessionStartTimeEpoch: Long = 0

    private var ftpWatts = 200
    private var timerJob: Job? = null
    private var pedalingDetectionJob: Job? = null

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
        _telemetry.clear()
        sessionStartTimeEpoch = 0
        
        // Listen for pedaling to trigger countdown
        pedalingDetectionJob?.cancel()
        pedalingDetectionJob = TrainerManager.power.onEach { watts ->
            if (_state.value == WorkoutState.Standby && watts > 10) {
                startCountdown()
            }
        }.launchIn(scope)
    }

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

        // Initialize trainer with first segment's target power
        updateTrainerTarget()

        timerJob?.cancel()
        timerJob = scope.launch {
            while (_state.value == WorkoutState.Active) {
                delay(1000)
                advanceTime()
            }
        }
    }

    private fun advanceTime() {
        val workout = _activeWorkout.value ?: return
        if (_currentSegmentIndex.value >= workout.segments.size) {
            completeWorkout()
            return
        }

        _elapsedSeconds.value += 1
        _elapsedInSegment.value += 1
        
        _telemetry.add(
            TelemetryPoint(
                timestampEpoch = getCurrentEpochMillis(),
                power = TrainerManager.power.value.toLong(),
                cadence = TrainerManager.cadence.value.toLong(),
                heartRate = TrainerManager.heartRate.value.toLong()
            )
        )

        val currentSeg = workout.segments[_currentSegmentIndex.value]
        if (_elapsedInSegment.value >= currentSeg.lengthInSeconds) {
            // Move to next segment
            _currentSegmentIndex.value += 1
            _elapsedInSegment.value = 0
            
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
            val targetWatts = (seg.powerPercentFTP / 100.0 * ftpWatts).roundToInt()
            TrainerManager.setTargetPower(targetWatts)
        }
    }

    fun pauseWorkout() {
        if (_state.value == WorkoutState.Active) {
            _state.value = WorkoutState.Paused
            TrainerManager.setTargetPower(0) // Release resistance
        }
    }

    fun resumeWorkout() {
        if (_state.value == WorkoutState.Paused) {
            _state.value = WorkoutState.Active
            updateTrainerTarget()
            
            timerJob?.cancel()
            timerJob = scope.launch {
                while (_state.value == WorkoutState.Active) {
                    delay(1000)
                    advanceTime()
                }
            }
        }
    }

    fun stopWorkout() {
        timerJob?.cancel()
        pedalingDetectionJob?.cancel()
        TrainerManager.setTargetPower(0)
        _state.value = WorkoutState.Idle
        _activeWorkout.value = null
    }

    private fun completeWorkout() {
        timerJob?.cancel()
        TrainerManager.setTargetPower(0)
        _state.value = WorkoutState.Completed
    }
}
