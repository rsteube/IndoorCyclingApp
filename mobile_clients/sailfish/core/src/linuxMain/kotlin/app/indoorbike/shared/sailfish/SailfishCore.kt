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
package app.indoorbike.shared.sailfish

import app.indoorbike.shared.WorkoutRepository
import app.indoorbike.shared.ble.ControllerEvent
import app.indoorbike.shared.ble.ControllerManager
import app.indoorbike.shared.ble.HeartRateMonitor
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.shared.ble.TrainerManager
import app.indoorbike.shared.companion.CompanionEngine
import app.indoorbike.shared.db.DatabaseDriverFactory
import app.indoorbike.shared.db.HistoryRepository
import app.indoorbike.shared.session.WorkoutSessionManager
import app.indoorbike.shared.session.WorkoutState
import app.indoorbike.shared.session.adjustEffort
import app.indoorbike.shared.simulation.Drivetrain
import app.indoorbike.shared.simulation.SimulationEngine
import app.indoorbike.shared.util.DiagnosticsLog
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.PosixFiles
import app.indoorbike.shared.util.installCrashLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The Sailfish app's graph of shared objects, built once at start: the
 * counterpart of Android's `AppContainer` and of `IosAppContainer`, wired the
 * same way. Each feature puts its topics and commands on [bus] from a file of
 * its own (Trainer.kt, Ride.kt and so on).
 *
 * Everything here runs on the core thread ([coreDispatcher]).
 */
@OptIn(ExperimentalUuidApi::class)
internal class SailfishCore(
    /** Where the package's own read-only files are: /usr/share/harbour-indoorbike. */
    val packageDir: String,
    /** The app's version, as a ride file records it and a companion is told it. */
    val appVersion: String,
    /** A debug build, which offers the demo trainer. */
    val debug: Boolean,
    send: (topic: String, payload: JsonElement) -> Unit,
) {
    val scope = CoroutineScope(SupervisorJob() + coreDispatcher)
    val bus = Bus(scope, send)

    val userSettings = LinuxUserSettings(
        defaults = JsonPrefs(SailfishPaths.config("settings.json")),
        secrets = JsonPrefs(SailfishPaths.config("secrets.json"), private = true),
        defaultDeviceLabel = deviceName(),
    )

    /** The radio: BlueZ, driven from the Qt side. See [BridgedCentral]. */
    val central = BridgedCentral(bus)
    val trainerManager = TrainerManager(central)
    val heartRateMonitor = HeartRateMonitor(central)
    val controllerManager = ControllerManager(central)
    val historyRepository = HistoryRepository(DatabaseDriverFactory())
    val routeProgressStore = LinuxRouteProgressStore()

    val simulationEngine = SimulationEngine(
        powerWatts = { trainerManager.power.value },
        cadenceRpm = { trainerManager.cadence.value },
        applyRoad = trainerManager::setSimulationParameters,
        riderMassKg = { userSettings.weightKg.value },
        bikeMassKg = { userSettings.bikeWeightKg.value },
        drivetrainProvider = { Drivetrain.byId(userSettings.drivetrainId.value) },
    )

    /** Drives the trainer from a companion's road, as the simulation engine does from a route's. */
    val companionEngine = CompanionEngine(
        powerWatts = { trainerManager.power.value },
        cadenceRpm = { trainerManager.cadence.value },
        applyRoad = trainerManager::setSimulationParameters,
        applyPower = trainerManager::setTargetPower,
        riderMassKg = { userSettings.weightKg.value },
        bikeMassKg = { userSettings.bikeWeightKg.value },
        drivetrainProvider = { Drivetrain.byId(userSettings.drivetrainId.value) },
    )

    val sessionManager = WorkoutSessionManager(
        trainerManager = trainerManager,
        activeSessionStore = LinuxActiveSessionStore(),
        activeTelemetryStore = LinuxActiveTelemetryStore(),
        routeProgressStore = routeProgressStore,
        // The strap when one is connected and sending; the trainer's own
        // reading otherwise, often 0. As on Android.
        effectiveHeartRate = { effectiveHeartRate() },
        riderMassKg = { userSettings.weightKg.value },
        simulationEngine = simulationEngine,
        companionEngine = companionEngine,
    )

    /** Every workout the rider can ride here: their own, then the bundled ones. */
    val workoutLibrary = LinuxWorkoutLibrary(scope, WorkoutRepository.parseWorkouts(listOfNotNull(bundledWorkouts())))

    /** One save at a time: two at once could each look for the ride, find nothing, and each insert it. */
    val saveLock = Mutex()

    private var foreground = true
    var rideActive = false
        private set

    fun effectiveHeartRate(): Int {
        val strap = heartRateMonitor.heartRate.value
        return if (strap > 0) strap else trainerManager.heartRate.value
    }

    fun start() {
        // Before the first log line, so the last run's log is rotated aside
        // where a bug report can find it.
        DiagnosticsLog.attachStorage()
        installCrashLogging()
        Log.i(TAG, "Core start, version $appVersion${if (debug) " (debug)" else ""}")
        central.install()
        installApp()
        installSettings()
        installTrainer()
        installSensors()
        installWorkouts()
        installRide()

        workoutLibrary.start()
        // Rides from before external ids existed get one; then the folder sync,
        // once it exists here (milestone 8).
        scope.launch {
            val deviceId = userSettings.deviceId
            runCatching { historyRepository.backfillExternalIds { "$deviceId:${Uuid.random()}" } }
        }
        scope.launch { sessionManager.loadPersistedSnapshot() }

        // Keep retrying the devices this rider paired, so they need not pick
        // them every time they open the app. Gated by [setForeground] and the
        // ride, as on Android.
        trainerManager.autoConnectIfPaired(userSettings.pairedTrainerId.value)
        heartRateMonitor.autoConnectIfPaired(userSettings.pairedHrmId.value)
        controllerManager.autoConnectIfPaired(userSettings.pairedControllerId.value)
        // Remember each device the moment it connects, never the demo trainer.
        trainerManager.connectionState
            .filter { it == TrainerConnectionState.Connected }
            .onEach {
                val id = trainerManager.pairedTrainerId.value
                if (id != null && id != MOCK_ID) userSettings.setPairedTrainerId(id)
            }
            .launchIn(scope)
        heartRateMonitor.connectionState
            .filter { it == TrainerConnectionState.Connected }
            .onEach { heartRateMonitor.pairedDeviceId.value?.let { id -> userSettings.setPairedHrmId(id) } }
            .launchIn(scope)
        controllerManager.connectionState
            .filter { it == TrainerConnectionState.Connected }
            .onEach { controllerManager.pairedDeviceId.value?.let { id -> userSettings.setPairedControllerId(id) } }
            .launchIn(scope)
        // A controller's buttons step the ride in progress, as the ride
        // screen's buttons do. Outside a ride they do nothing.
        controllerManager.events
            .onEach { event ->
                if (!rideActive) return@onEach
                stepEffort(if (event == ControllerEvent.Plus) 1 else -1)
            }
            .launchIn(scope)
        // A ride keeps its devices even with the app in the background.
        sessionManager.state
            .onEach { state ->
                rideActive = state != WorkoutState.Idle && state != WorkoutState.Completed
                updateScanGate()
            }
            .launchIn(scope)

        bus.command("app.foreground") { args ->
            foreground = args.bool("active") ?: true
            updateScanGate()
            JsonNull
        }
    }

    /** One step harder (+1) or easier (-1), as the ride in progress understands it. */
    fun stepEffort(direction: Int) {
        adjustEffort(direction, sessionManager.activeWorkout.value, sessionManager, simulationEngine, companionEngine)
    }

    private fun updateScanGate() {
        if (foreground || rideActive) {
            trainerManager.resumeAutoReconnect()
            heartRateMonitor.resumeAutoReconnect()
            controllerManager.resumeAutoReconnect()
        } else {
            trainerManager.pauseAutoReconnect()
            heartRateMonitor.pauseAutoReconnect()
            controllerManager.pauseAutoReconnect()
        }
    }

    /** The workout files shipped in the package: the same JSON the Android app bundles. */
    private fun bundledWorkouts(): String? = PosixFiles.readText("${packageDir.trimEnd('/')}/data/workouts_sample.json")
        .also { if (it == null) Log.w(TAG, "No bundled workouts in $packageDir/data") }

    fun stop() {
        trainerManager.dispose()
        heartRateMonitor.dispose()
        controllerManager.dispose()
        sessionManager.dispose()
        scope.cancel()
    }

    companion object {
        private const val TAG = "SailfishCore"

        /** [TrainerManager.connectMock]'s pseudo-id. */
        const val MOCK_ID = "mock"

        /** The phone's model, from Sailfish's hardware release file, for naming it in a backup folder. */
        fun deviceName(): String {
            val release = PosixFiles.readText("/etc/hw-release") ?: return "Sailfish OS"
            val name = release.lineSequence()
                .firstOrNull { it.startsWith("NAME=") }
                ?.substringAfter('=')?.trim()?.trim('"')
            return name?.takeIf { it.isNotEmpty() } ?: "Sailfish OS"
        }
    }
}
