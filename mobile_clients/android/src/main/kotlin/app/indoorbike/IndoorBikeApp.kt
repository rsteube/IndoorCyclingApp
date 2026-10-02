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
package app.indoorbike

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import app.indoorbike.companion.NsdCompanionAdvertiser
import app.indoorbike.companion.UdpCompanionBeacon
import app.indoorbike.shared.companion.BrandSink
import app.indoorbike.shared.companion.ScreenshotSink
import app.indoorbike.ride.saveRideToHistory
import app.indoorbike.sync.AndroidHttpClient
import app.indoorbike.sync.AndroidSyncLibrary
import app.indoorbike.sync.SharedPreferencesStore
import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.compose.runtime.staticCompositionLocalOf
import app.indoorbike.library.RepositoryManager
import app.indoorbike.library.AndroidTextStore
import app.indoorbike.shared.repo.FollowedRepositories
import app.indoorbike.route.RouteCatalog
import app.indoorbike.route.RouteImporter
import app.indoorbike.library.WorkoutImporter
import app.indoorbike.library.WorkoutLibrary
import app.indoorbike.service.RideSessionService
import app.indoorbike.route.AndroidRouteProgressStore
import app.indoorbike.session.AndroidActiveSessionStore
import app.indoorbike.session.AndroidActiveTelemetryStore
import app.indoorbike.shared.session.WorkoutState
import app.indoorbike.shared.sync.FitTrackeeUploader
import app.indoorbike.shared.sync.IntervalsIcuUploader
import app.indoorbike.sync.SafSyncStorage
import app.indoorbike.shared.sync.RideUploads
import app.indoorbike.shared.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
import app.indoorbike.shared.simulation.RouteProgressStore
import app.indoorbike.shared.session.WorkoutSessionManager
import app.indoorbike.companion.AndroidPairedCompanionStore
import app.indoorbike.shared.companion.CompanionHost
import app.indoorbike.shared.companion.CompanionRideRecorder
import app.indoorbike.ride.RideScreenshotStore
import app.indoorbike.ride.RideBrandStore
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.shared.companion.CompanionEngine
import app.indoorbike.shared.simulation.Drivetrain
import app.indoorbike.shared.simulation.SimulationEngine
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
    /** Drives a simulated route ride: position, speed, gears, road resistance. */
    val simulationEngine: SimulationEngine,
    val companionEngine: CompanionEngine,
    /**
     * The companion socket. Started by the pairing screen and stopped when the
     * ride ends, so nothing is listening outside a companion ride.
     */
    val companionHost: CompanionHost,
    /** Every route the rider can simulate. */
    val routeCatalog: RouteCatalog,
    /** Downloads and stores the GPX behind a route the rider adds. */
    val routeImporter: RouteImporter,
    val historyRepository: HistoryRepository,
    /** Pictures a companion sent, kept on the phone with the ride they were taken on. */
    val rideScreenshots: RideScreenshotStore,
    /** Which companion drove a ride, kept with the ride for the share card to credit. */
    val rideBrands: RideBrandStore,
    val userSettings: UserSettings,
    val activeSessionStore: ActiveSessionStore,
    val routeProgressStore: RouteProgressStore,
    val workoutImporter: WorkoutImporter,
    val repositoryManager: RepositoryManager,
    /** Repositories the rider follows, and what each has published since they looked. */
    val followedRepositories: FollowedRepositories,
    val workoutLibrary: WorkoutLibrary,
    val intervalsIcuUploader: IntervalsIcuUploader,
    val fitTrackeeUploader: FitTrackeeUploader,
    val syncEngine: SyncEngine,
) {
    private val containerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * For work a screen starts but must not end: saving a ride, syncing,
     * uploading, finishing a sign-in. A screen's own coroutine scope is
     * cancelled the moment the rider navigates away or the activity is
     * recreated, and work launched there used to die half done with it: a
     * restore cut off by a tab switch, an upload whose success was never
     * recorded, a sign-in code that could then never be redeemed.
     */
    val appScope: CoroutineScope get() = containerScope

    /** Uploads to Intervals.icu and FitTrackee, run and recorded on the app's scope. */
    val rideUploads = RideUploads(
        scope = containerScope,
        history = historyRepository,
        settings = userSettings,
        intervals = intervalsIcuUploader,
        fitTrackee = fitTrackeeUploader,
    )

    /**
     * The unfinished ride the rider has waved away this session, identified by
     * its start time.
     *
     * Deliberately not persisted and deliberately not a boolean. Not
     * persisted, because dismissing is "not now" rather than "never", and the
     * banner should be back next launch while the ride is still on disk. Keyed
     * to the ride rather than a flag, because a rider who dismisses one
     * unfinished ride and then abandons a second one mid-session must still be
     * told about the second.
     */
    val dismissedUnfinishedRideStart = MutableStateFlow<Long?>(null)

    /**
     * Throw away the unfinished ride, and the screenshots taken on it.
     *
     * The one way to abandon a ride, so its pictures cannot outlive it by being
     * forgotten at one of the places a rider can abandon from.
     */
    fun abandonUnfinishedRide() {
        val start = sessionManager.persistedSnapshot.value?.sessionStartTimeEpochMs
            ?: sessionManager.sessionStartTimeEpoch
        sessionManager.abandonUnfinishedRide()
        containerScope.launch {
            rideScreenshots.deleteRide(start)
            rideBrands.deleteRide(start)
        }
    }

    /**
     * Delete the screenshots of rides that no longer exist.
     *
     * After the unfinished-ride snapshot has loaded, so a ride the app was
     * killed in the middle of keeps its pictures until the rider decides what
     * to do with it.
     */
    suspend fun pruneRideScreenshots() {
        val keep = historyRepository.getAllSessions().first().mapTo(mutableSetOf()) { it.startTimeEpoch }
        sessionManager.persistedSnapshot.value?.let { keep += it.sessionStartTimeEpochMs }
        sessionManager.sessionStartTimeEpoch.takeIf { it != 0L }?.let { keep += it }
        val pruned = rideScreenshots.prune(keep)
        if (pruned > 0) Log.i("IndoorBikeApp", "Deleted the screenshots of $pruned ride(s) no longer in history")
        rideBrands.prune(keep)
    }

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
        simulationEngine.dispose()
        routeCatalog.dispose()
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
            val routeProgressStore = AndroidRouteProgressStore(application)
            // Built before the session manager so the manager can read the
            // rider's weight for its virtual-distance estimate.
            val userSettings = AndroidUserSettings(application)
            // Built before the session manager, which hands it the trainer
            // whenever a simulated ride goes active.
            val simulationEngine = SimulationEngine(
                powerWatts = { trainerManager.power.value },
                // The virtual gear is a kinematic constraint, so the physics
                // needs a cadence to constrain. Zero from a trainer that
                // cannot count one, which the model handles.
                cadenceRpm = { trainerManager.cadence.value },
                applyRoad = trainerManager::setSimulationParameters,
                riderMassKg = { userSettings.weightKg.value },
                bikeMassKg = { userSettings.bikeWeightKg.value },
                drivetrainProvider = { Drivetrain.byId(userSettings.drivetrainId.value) },
            )
            // The companion's engine is built the same way and from the same
            // settings, because it drives the same trainer through the same
            // force model. What differs is only where the road comes from.
            val companionEngine = CompanionEngine(
                powerWatts = { trainerManager.power.value },
                cadenceRpm = { trainerManager.cadence.value },
                applyRoad = trainerManager::setSimulationParameters,
                // The ERG write a free ride already uses, for the one mode
                // where a companion holds the rider at a wattage instead of on
                // a road.
                applyPower = trainerManager::setTargetPower,
                riderMassKg = { userSettings.weightKg.value },
                bikeMassKg = { userSettings.bikeWeightKg.value },
                drivetrainProvider = { Drivetrain.byId(userSettings.drivetrainId.value) },
            )
            // One importer, shared with the catalogue: the route browser adds
            // through it and then asks the catalogue to refresh, and both have
            // to be looking at the same directory for that to mean anything.
            val routeImporter = RouteImporter(application)
            val routeCatalog = RouteCatalog(application, routeImporter)
            val sessionManager = WorkoutSessionManager(
                trainerManager = trainerManager,
                activeSessionStore = activeSessionStore,
                activeTelemetryStore = activeTelemetryStore,
                routeProgressStore = routeProgressStore,
                // Prefer the dedicated HRM when one is connected and emitting samples;
                // fall back to whatever the trainer reports (often 0 for trainer-only).
                effectiveHeartRate = {
                    val hrm = heartRateMonitor.heartRate.value
                    if (hrm > 0) hrm else trainerManager.heartRate.value
                },
                riderMassKg = { userSettings.weightKg.value },
                simulationEngine = simulationEngine,
                companionEngine = companionEngine,
            )
            val rideScreenshots = RideScreenshotStore(application.filesDir)
            val rideBrands = RideBrandStore(application.filesDir)
            val companionHost = CompanionHost(
                engine = companionEngine,
                sessionManager = sessionManager,
                appVersion = BuildConfig.VERSION_NAME,
                ftp = { userSettings.ftp.value },
                riderMassKg = { userSettings.weightKg.value },
                bikeMassKg = { userSettings.bikeWeightKg.value },
                powerWatts = { trainerManager.power.value },
                cadenceRpm = { trainerManager.cadence.value },
                heartRateBpm = {
                    val hrm = heartRateMonitor.heartRate.value
                    if (hrm > 0) hrm else trainerManager.heartRate.value
                },
                // The same stable id the backup layer namespaces sessions with.
                // Not a secret: it says which phone, and the proof says whether
                // the companion actually knows it.
                deviceId = { userSettings.deviceId },
                pairedStore = AndroidPairedCompanionStore(application),
                screenshots = ScreenshotSink { rideStart, image, takenAt, rideSeconds ->
                    rideScreenshots.save(rideStart, image, takenAt, rideSeconds)
                },
                brands = BrandSink { rideStart, name, iconPng -> rideBrands.save(rideStart, name, iconPng) },
                advertiser = NsdCompanionAdvertiser(application),
                beacon = UdpCompanionBeacon(CoroutineScope(SupervisorJob() + Dispatchers.IO)),
                isDebug = BuildConfig.DEBUG,
                decodeIcon = { png -> BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() },
                trainerName = { trainerManager.connectedTrainerName.value },
                trainerConnected = {
                    trainerManager.connectionState.value == TrainerConnectionState.Connected ||
                        trainerManager.isMockTrainer.value
                },
                trainerAcceptsPower = { trainerManager.acceptsTargetPower },
            )
            val historyRepository = HistoryRepository(DatabaseDriverFactory(application))
            val workoutImporter = WorkoutImporter(application)
            val repositoryManager = RepositoryManager(
                context = application,
                registryUrl = BuildConfig.REGISTRY_URL,
            )
            val followedRepositories = FollowedRepositories(
                store = AndroidTextStore(File(application.filesDir, "followed")),
                fetcher = repositoryManager.fetcher,
            )
            val workoutLibrary = WorkoutLibrary(
                context = application,
                importer = workoutImporter,
            )
            val fitTrackeeUploader = FitTrackeeUploader(
                http = AndroidHttpClient,
                historyRepository = historyRepository,
                userSettings = userSettings,
                appVersion = BuildConfig.VERSION_NAME,
                rideBrandName = { rideStart -> rideBrands.find(rideStart)?.name },
                pending = SharedPreferencesStore(application, "fittrackee_oauth"),
            )
            val intervalsIcuUploader = IntervalsIcuUploader(
                http = AndroidHttpClient,
                historyRepository = historyRepository,
                userSettings = userSettings,
                appVersion = BuildConfig.VERSION_NAME,
                rideBrandName = { rideStart -> rideBrands.find(rideStart)?.name },
                pending = SharedPreferencesStore(application, "intervals_oauth"),
                clientId = BuildConfig.INTERVALS_CLIENT_ID,
                clientSecret = BuildConfig.INTERVALS_CLIENT_SECRET,
                // Never in a debug build: the code comes back through a
                // verified app link, and indoorbike.app vouches for the release
                // key alone.
                signInAllowed = !BuildConfig.DEBUG,
            )
            val syncEngine = SyncEngine(
                userSettings = userSettings,
                historyRepository = historyRepository,
                workoutLibrary = AndroidSyncLibrary(workoutImporter, workoutLibrary),
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
            val container = AppContainer(
                trainerManager = trainerManager,
                heartRateMonitor = heartRateMonitor,
                controllerManager = controllerManager,
                sessionManager = sessionManager,
                simulationEngine = simulationEngine,
                companionEngine = companionEngine,
                companionHost = companionHost,
                routeCatalog = routeCatalog,
                routeImporter = routeImporter,
                historyRepository = historyRepository,
                rideScreenshots = rideScreenshots,
                rideBrands = rideBrands,
                userSettings = userSettings,
                activeSessionStore = activeSessionStore,
                routeProgressStore = routeProgressStore,
                workoutImporter = workoutImporter,
                repositoryManager = repositoryManager,
                followedRepositories = followedRepositories,
                workoutLibrary = workoutLibrary,
                intervalsIcuUploader = intervalsIcuUploader,
                fitTrackeeUploader = fitTrackeeUploader,
                syncEngine = syncEngine,
            )
            // After the container, because writing a ride needs the history
            // repository and the host is built before it. A companion ride is
            // saved when its game says the ride is over rather than when a
            // rider taps through a summary, since in this mode nobody does.
            companionHost.rideRecorder = CompanionRideRecorder(
                history = historyRepository,
                sessions = sessionManager,
            ) { workout, startTimeEpoch, durationSeconds, telemetry, road, record ->
                saveRideToHistory(container, workout, startTimeEpoch, durationSeconds, telemetry, road, record)
            }
            return container
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
    //
    // Both fields, and the gate, belong to the main thread: the activity
    // callbacks run there, and so does the ride-state collector below. It used
    // to collect on the IO pool, and a stale read of one field from another
    // thread could pause scanning mid-ride or leave it on while idle.
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
        container.routeCatalog.start()
        // Surface any unfinished ride snapshot to the UI (banner) by populating
        // the session manager's persistedSnapshot StateFlow from disk.
        initScope.launch {
            container.sessionManager.loadPersistedSnapshot()
            container.pruneRideScreenshots()
        }
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
        initScope.launch(Dispatchers.Main.immediate) {
            // StateFlow is already conflated, so a bare collect is enough:
            // distinctUntilChanged on StateFlow is a no-op and the compiler
            // flags it.
            //
            // Only the edges are acted on. A ride is not one state but a
            // sequence of them, and auto-pause alone flips it every time the
            // rider stops turning the pedals at a light. Calling `start` on each
            // of those is not free: every call is another
            // `startForegroundService`, another few-second promise Android
            // holds the app to, and hundreds of them over a long ride are
            // hundreds of chances for one to land badly and take the process
            // down. The service wants to know when riding begins and ends, and
            // nothing in between.
            var serviceRunning = false
            container.sessionManager.state.collect { state ->
                // A ride in flight keeps device scanning alive even if the app
                // is backgrounded (screen off mid-workout), so a dropped trainer
                // is recovered without the rider reopening the app.
                val riding = when (state) {
                    WorkoutState.Standby,
                    WorkoutState.Countdown,
                    WorkoutState.Active,
                    WorkoutState.Paused,
                    // Untracked riding is riding: the trainer is being driven
                    // and the screen must survive being backgrounded, even
                    // though none of it is being written down.
                    WorkoutState.Untracked -> true
                    WorkoutState.Idle,
                    WorkoutState.Completed -> false
                }
                if (riding != serviceRunning) {
                    serviceRunning = riding
                    if (riding) RideSessionService.start(this@IndoorBikeApp)
                    else RideSessionService.stop(this@IndoorBikeApp)
                }
                rideActive = riding
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
