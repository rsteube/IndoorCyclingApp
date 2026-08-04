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
package app.indoorbike

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.compose.runtime.staticCompositionLocalOf
import app.indoorbike.library.RepositoryManager
import app.indoorbike.library.WorkoutImporter
import app.indoorbike.library.WorkoutLibrary
import app.indoorbike.service.RideSessionService
import app.indoorbike.session.AndroidActiveSessionStore
import app.indoorbike.session.AndroidActiveTelemetryStore
import app.indoorbike.shared.session.WorkoutState
import app.indoorbike.sync.IntervalsIcuUploader
import app.indoorbike.sync.SafSyncStorage
import app.indoorbike.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.indoorbike.settings.AndroidUserSettings
import app.indoorbike.shared.WorkoutRepository
import app.indoorbike.shared.ble.ControllerManager
import app.indoorbike.shared.ble.HeartRateMonitor
import app.indoorbike.shared.ble.TrainerManager
import app.indoorbike.shared.db.DatabaseDriverFactory
import app.indoorbike.shared.db.HistoryRepository
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.session.ActiveSessionStore
import app.indoorbike.shared.session.WorkoutSessionManager
import app.indoorbike.shared.settings.UserSettings
import app.indoorbike.shared.util.CrashHandler
import app.indoorbike.shared.util.DiagnosticsLog
import app.indoorbike.shared.util.Log
import java.io.File

/**
 * Holds all long-lived singletons. The pattern:
 *  - Constructed once by [IndoorBikeApp.onCreate]
 *  - Read in Composables via [LocalAppContainer]
 *  - Never re-created across config changes (lives on Application)
 *
 * This replaces the previous `object`-singleton pattern with explicit ownership
 * and a single dispose path.
 */
class AppContainer(
    val trainerManager: TrainerManager,
    val heartRateMonitor: HeartRateMonitor,
    val controllerManager: ControllerManager,
    val sessionManager: WorkoutSessionManager,
    val historyRepository: HistoryRepository,
    val userSettings: UserSettings,
    val activeSessionStore: ActiveSessionStore,
    val workoutImporter: WorkoutImporter,
    val repositoryManager: RepositoryManager,
    val workoutLibrary: WorkoutLibrary,
    val intervalsIcuUploader: IntervalsIcuUploader,
    val syncEngine: SyncEngine,
) {
    private val containerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Single shared subscription to the history table. Three separate screens
     * (rides, history, progress) used to each open their own SQLDelight Query
     * collector, which meant N full table reads per insert at steady state.
     * Hoisting to one StateFlow with `SharingStarted.WhileSubscribed` collapses
     * that to one shared listener.
     */
    val sessions: StateFlow<List<WorkoutSession>> =
        historyRepository.getAllSessions().stateIn(
            scope = containerScope,
            // 5 s grace so a quick screen swap doesn't tear down + rebuild the
            // upstream Query.AsFlow(). Replays the last value when a new
            // collector arrives, so screens hydrate without flashing empty.
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun dispose() {
        trainerManager.dispose()
        heartRateMonitor.dispose()
        controllerManager.dispose()
        sessionManager.dispose()
        workoutLibrary.dispose()
        containerScope.cancel()
        if (userSettings is AndroidUserSettings) userSettings.dispose()
    }

    companion object {
        fun create(application: Application): AppContainer {
            val trainerManager = TrainerManager()
            val heartRateMonitor = HeartRateMonitor()
            val controllerManager = ControllerManager()
            val activeSessionStore = AndroidActiveSessionStore(application)
            val activeTelemetryStore = AndroidActiveTelemetryStore(application)
            val sessionManager = WorkoutSessionManager(
                trainerManager = trainerManager,
                activeSessionStore = activeSessionStore,
                activeTelemetryStore = activeTelemetryStore,
                // Prefer the dedicated HRM when one is connected and emitting samples;
                // fall back to whatever the trainer reports (often 0 for trainer-only).
                effectiveHeartRate = {
                    val hrm = heartRateMonitor.heartRate.value
                    if (hrm > 0) hrm else trainerManager.heartRate.value
                },
            )
            val userSettings = AndroidUserSettings(application)
            val historyRepository = HistoryRepository(DatabaseDriverFactory(application))
            val workoutImporter = WorkoutImporter(application)
            val repositoryManager = RepositoryManager(
                context = application,
                registryUrl = BuildConfig.REGISTRY_URL,
            )
            val workoutLibrary = WorkoutLibrary(
                context = application,
                importer = workoutImporter,
            )
            val intervalsIcuUploader = IntervalsIcuUploader(
                historyRepository = historyRepository,
                userSettings = userSettings,
            )
            val syncEngine = SyncEngine(
                userSettings = userSettings,
                historyRepository = historyRepository,
                workoutImporter = workoutImporter,
                workoutLibrary = workoutLibrary,
                // Re-evaluated on each syncNow() so a freshly-picked folder is
                // honored without rebuilding the engine.
                storageProvider = {
                    userSettings.syncFolderUri.value?.let { raw ->
                        SafSyncStorage(
                            context = application,
                            treeUri = android.net.Uri.parse(raw),
                        )
                    }
                },
            )
            return AppContainer(
                trainerManager = trainerManager,
                heartRateMonitor = heartRateMonitor,
                controllerManager = controllerManager,
                sessionManager = sessionManager,
                historyRepository = historyRepository,
                userSettings = userSettings,
                activeSessionStore = activeSessionStore,
                workoutImporter = workoutImporter,
                repositoryManager = repositoryManager,
                workoutLibrary = workoutLibrary,
                intervalsIcuUploader = intervalsIcuUploader,
                syncEngine = syncEngine,
            )
        }
    }
}

class IndoorBikeApp : Application() {
    lateinit var container: AppContainer
        private set

    private companion object {
        /** App-private subdirectory holding the durable diagnostic log files. */
        const val DIAGNOSTICS_DIR = "diagnostics"
    }

    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Device auto-reconnect scanning is gated to "app in use": scan while the UI
    // is foregrounded, or while a ride is in progress (the rider may have the
    // screen off mid-workout and we still want to recover a dropped trainer).
    // Established connections are never dropped by this gate — only scanning is
    // paused — so a backgrounded idle app isn't draining the BLE radio.
    private var startedActivityCount = 0
    private var rideActive = false

    private fun updateDeviceScanGate() {
        val shouldScan = startedActivityCount > 0 || rideActive
        if (shouldScan) {
            container.trainerManager.resumeAutoReconnect()
            container.heartRateMonitor.resumeAutoReconnect()
            container.controllerManager.resumeAutoReconnect()
        } else {
            container.trainerManager.pauseAutoReconnect()
            container.heartRateMonitor.pauseAutoReconnect()
            container.controllerManager.pauseAutoReconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Before the first log line, so "App start" itself is captured, and so
        // the previous run's log is rotated aside where a bug report can find
        // it. A crash report drafted after a restart is only worth anything if
        // the lines from before the restart survived.
        DiagnosticsLog.attachStorage(File(filesDir, DIAGNOSTICS_DIR))
        CrashHandler.install()
        Log.i("IndoorBikeApp", "App start")
        container = AppContainer.create(this)
        container.workoutLibrary.start()
        // Surface any unfinished ride snapshot to the UI (banner) by populating
        // the session manager's persistedSnapshot StateFlow from disk.
        initScope.launch { container.sessionManager.loadPersistedSnapshot() }
        // One-shot backfill for the v1 → v2 history schema: any session that
        // predates the externalId column gets a fresh `$deviceId:${UUID}`.
        // Idempotent — fast no-op on fresh installs and after the first run.
        initScope.launch {
            val deviceId = container.userSettings.deviceId
            val n = container.historyRepository.backfillExternalIds {
                "$deviceId:${java.util.UUID.randomUUID()}"
            }
            if (n > 0) Log.i("IndoorBikeApp", "Backfilled externalId for $n session(s)")
            // Best-effort auto-sync after backfill so any rides we just gave
            // external ids to participate in this round-trip. NotConfigured
            // (no folder picked) is a silent no-op; failures surface in the
            // Backup & Sync card via SyncEngine.status. The mutex inside the
            // engine serialises against any user-triggered "Sync now" tap.
            container.syncEngine.syncNow()
        }
        // Arm persistent auto-reconnect for previously paired BLE devices so
        // users don't have to dig into settings every launch. The managers keep
        // retrying (gated by updateDeviceScanGate) until each device is found.
        initScope.launch {
            container.trainerManager.autoConnectIfPaired(container.userSettings.pairedTrainerId.value)
        }
        initScope.launch {
            container.heartRateMonitor.autoConnectIfPaired(container.userSettings.pairedHrmId.value)
        }
        initScope.launch {
            container.controllerManager.autoConnectIfPaired(container.userSettings.pairedControllerId.value)
        }

        // Foreground tracking: scan only while at least one Activity is started.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                updateDeviceScanGate()
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                updateDeviceScanGate()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Drive the foreground service from the session state instead of the
        // ride screen's composition. Previously a config change (rotation,
        // etc.) tore down RideScreen → ran its `onDispose { stop }` → killed
        // the FGS mid-ride. Now the service mirrors whether a workout is in
        // flight, regardless of which UI happens to be on screen.
        initScope.launch {
            // StateFlow is already conflated, so a bare collect is enough —
            // distinctUntilChanged on StateFlow is a no-op and the compiler
            // flags it.
            container.sessionManager.state.collect { state ->
                when (state) {
                    WorkoutState.Standby,
                    WorkoutState.Countdown,
                    WorkoutState.Active,
                    WorkoutState.Paused -> RideSessionService.start(this@IndoorBikeApp)
                    WorkoutState.Idle,
                    WorkoutState.Completed -> RideSessionService.stop(this@IndoorBikeApp)
                }
                // A ride in flight keeps device scanning alive even if the app
                // is backgrounded (screen off mid-workout), so a dropped trainer
                // is recovered without the rider reopening the app.
                rideActive = when (state) {
                    WorkoutState.Standby,
                    WorkoutState.Countdown,
                    WorkoutState.Active,
                    WorkoutState.Paused -> true
                    WorkoutState.Idle,
                    WorkoutState.Completed -> false
                }
                updateDeviceScanGate()
            }
        }
    }

    override fun onTerminate() {
        // Rarely called by the framework on real devices, but good hygiene for
        // emulator runs (and any future test harness that calls onTerminate
        // explicitly). Cancel both scopes — initScope runs the auto-connect
        // / backfill / first-sync coroutines that would otherwise outlive
        // the container.
        initScope.cancel()
        container.dispose()
        super.onTerminate()
    }
}

/**
 * CompositionLocal for the AppContainer. Set once in MainActivity's setContent;
 * any Composable can read it without parameter drilling.
 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided. Wrap your composables in CompositionLocalProvider(LocalAppContainer provides container).")
}

/** Loaded workouts from JSON assets — exposed so the unfinished-ride banner can resolve workoutId → Workout. */
val LocalWorkouts = staticCompositionLocalOf<List<app.indoorbike.shared.Workout>> { emptyList() }

/** UI handle for the unfinished-ride banner: continue and abandon callbacks wired to the navigator. */
data class RideResumer(
    val continueRide: (app.indoorbike.shared.Workout, app.indoorbike.shared.session.ActiveSessionSnapshot) -> Unit,
    val abandonRide: () -> Unit,
    /**
     * Write a ride that finished but never reached history - the app died (or
     * the rider backed out) between the trophy screen and the summary. Saves it
     * exactly as the completion screen would have, then opens that summary.
     */
    val saveFinishedRide: (app.indoorbike.shared.Workout, app.indoorbike.shared.session.ActiveSessionSnapshot) -> Unit,
)

val LocalRideResumer = staticCompositionLocalOf<RideResumer> {
    error("RideResumer not provided. Wrap your composables in CompositionLocalProvider(LocalRideResumer provides ...).")
}
