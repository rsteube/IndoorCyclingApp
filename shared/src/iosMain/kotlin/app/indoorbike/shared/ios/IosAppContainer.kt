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
package app.indoorbike.shared.ios

import app.indoorbike.shared.Workout
import app.indoorbike.shared.WorkoutRepository
import app.indoorbike.shared.ble.HeartRateMonitor
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.shared.ble.TrainerManager
import app.indoorbike.shared.db.DatabaseDriverFactory
import app.indoorbike.shared.db.HistoryRepository
import app.indoorbike.shared.session.WorkoutSessionManager
import app.indoorbike.shared.session.WorkoutState
import app.indoorbike.shared.session.writeRideToHistory
import app.indoorbike.shared.simulation.Drivetrain
import app.indoorbike.shared.simulation.SimulationEngine
import app.indoorbike.shared.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The iOS app's graph of shared objects, built once at launch: the iOS
 * counterpart of the Android app's `AppContainer`, wired the same way.
 *
 * Swift holds one of these for the life of the app and reads the managers off
 * it. What the Android app does in `Application.onCreate` - reconnecting the
 * paired devices, surfacing an unfinished ride - is [start].
 */
class IosAppContainer {
    val userSettings = IosUserSettings()
    val trainerManager = TrainerManager()
    val heartRateMonitor = HeartRateMonitor()
    val historyRepository = HistoryRepository(DatabaseDriverFactory())

    /** Built for route rides (milestone 5); a workout ride doesn't drive it. */
    val simulationEngine = SimulationEngine(
        powerWatts = { trainerManager.power.value },
        cadenceRpm = { trainerManager.cadence.value },
        applyRoad = trainerManager::setSimulationParameters,
        riderMassKg = { userSettings.weightKg.value },
        bikeMassKg = { userSettings.bikeWeightKg.value },
        drivetrainProvider = { Drivetrain.byId(userSettings.drivetrainId.value) },
    )

    val sessionManager = WorkoutSessionManager(
        trainerManager = trainerManager,
        activeSessionStore = IosActiveSessionStore(),
        activeTelemetryStore = IosActiveTelemetryStore(),
        routeProgressStore = IosRouteProgressStore(),
        // Prefer the dedicated HRM when one is connected and sending; fall
        // back to whatever the trainer reports (often 0 for trainer-only).
        effectiveHeartRate = {
            val hrm = heartRateMonitor.heartRate.value
            if (hrm > 0) hrm else trainerManager.heartRate.value
        },
        riderMassKg = { userSettings.weightKg.value },
        simulationEngine = simulationEngine,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** One save at a time: two at once could each look for the ride, find nothing, and each insert it. */
    private val saveLock = Mutex()

    private var foreground = false
    private var rideActive = false

    /** Call once, at launch. */
    fun start() {
        scope.launch { sessionManager.loadPersistedSnapshot() }
        // Keep retrying the devices this rider paired, so they don't have to
        // pick their trainer every time they open the app. Gated by
        // [setForeground] and the ride state, as on Android.
        trainerManager.autoConnectIfPaired(userSettings.pairedTrainerId.value)
        heartRateMonitor.autoConnectIfPaired(userSettings.pairedHrmId.value)
        // Remember a trainer the moment it connects, and never the demo one.
        trainerManager.connectionState
            .filter { it == TrainerConnectionState.Connected }
            .onEach {
                val id = trainerManager.pairedTrainerId.value
                if (id != null && id != MOCK_ID) userSettings.setPairedTrainerId(id)
            }
            .launchIn(scope)
        // A ride keeps its devices even with the app in the background.
        sessionManager.state
            .onEach { state ->
                rideActive = state != WorkoutState.Idle && state != WorkoutState.Completed
                updateScanGate()
            }
            .launchIn(scope)
    }

    /** The app came to the front or went behind; scanning for paired devices follows. */
    fun setForeground(value: Boolean) {
        foreground = value
        updateScanGate()
    }

    private fun updateScanGate() {
        if (foreground || rideActive) {
            trainerManager.resumeAutoReconnect()
            heartRateMonitor.resumeAutoReconnect()
        } else {
            trainerManager.pauseAutoReconnect()
            heartRateMonitor.pauseAutoReconnect()
        }
    }

    /** The workouts in bundled JSON files, each a list of workouts; duplicates keep the first. */
    fun parseWorkouts(jsonFiles: List<String>): List<Workout> = WorkoutRepository.parseWorkouts(jsonFiles)

    /** Get [workout] ready at the rider's FTP; it starts when they pedal. */
    fun prepareWorkout(workout: Workout) {
        sessionManager.prepareWorkout(workout, userSettings.ftp.value)
    }

    /**
     * Save the ride that just completed to history, then call [onSaved] with
     * its row id on the main thread, or with null when there was nothing to
     * save. Runs on the container's scope, so leaving the screen can't cancel
     * a save half-way.
     */
    fun saveCompletedRide(onSaved: (Long?) -> Unit) {
        val workout = sessionManager.activeWorkout.value
        if (sessionManager.state.value != WorkoutState.Completed || workout == null) {
            onSaved(null)
            return
        }
        // Read everything now, before anything else can start a new ride.
        val startTimeEpoch = sessionManager.sessionStartTimeEpoch
        val durationSeconds = sessionManager.actualRideSeconds.value
        val telemetry = sessionManager.telemetrySnapshot()
        val road = sessionManager.pendingRideRoad()
        val record = sessionManager.pendingRideRecord()
        scope.launch {
            val id = try {
                saveLock.withLock {
                    writeRideToHistory(
                        history = historyRepository,
                        sessionManager = sessionManager,
                        deviceId = userSettings.deviceId,
                        workout = workout,
                        startTimeEpoch = startTimeEpoch,
                        durationSeconds = durationSeconds,
                        telemetry = telemetry,
                        road = road,
                        record = record,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't save the ride", e)
                null
            }
            onSaved(id)
        }
    }

    private companion object {
        const val TAG = "IosAppContainer"
        /** [TrainerManager.connectMock]'s pseudo-id. */
        const val MOCK_ID = "mock"
    }
}
