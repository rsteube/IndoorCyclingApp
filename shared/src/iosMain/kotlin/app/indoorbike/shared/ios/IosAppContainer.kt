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

import app.indoorbike.shared.repo.ManifestParser
import app.indoorbike.shared.repo.RegistryIndex
import app.indoorbike.shared.repo.RepoClient
import app.indoorbike.shared.repo.RouteEntry
import app.indoorbike.shared.repo.RouteFetchResult
import app.indoorbike.shared.route.RouteEstimate
import app.indoorbike.shared.route.RouteStats
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.shared.route.rideRoute
import app.indoorbike.shared.simulation.RouteProgress
import app.indoorbike.shared.workout.simulationRouteId
import platform.Foundation.NSData
import app.indoorbike.shared.Workout
import app.indoorbike.shared.activity.TcxBuilder
import app.indoorbike.shared.activity.exportFileName
import app.indoorbike.shared.db.TelemetryPoint
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.fit.RideFitBuilder
import app.indoorbike.shared.progression.RideStats
import app.indoorbike.shared.progression.computeRideStats
import app.indoorbike.shared.progression.timeInZoneSeconds
import app.indoorbike.shared.session.ActiveSessionSnapshot
import app.indoorbike.shared.session.RideRecord
import app.indoorbike.shared.session.RideRoad
import app.indoorbike.shared.session.UnfinishedRide
import app.indoorbike.shared.session.describeUnfinishedRide
import app.indoorbike.shared.workout.isCompanion
import app.indoorbike.shared.workout.isFreeRide
import app.indoorbike.shared.workout.isSimulation
import app.indoorbike.shared.workout.resolveRideWorkout
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSTemporaryDirectory
import app.indoorbike.shared.WorkoutRepository
import app.indoorbike.shared.ble.ControllerEvent
import app.indoorbike.shared.ble.ControllerManager
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
import app.indoorbike.shared.feedback.bugReportText
import app.indoorbike.shared.session.adjustEffort
import app.indoorbike.shared.util.DiagnosticsLog
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.installCrashLogging
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
    val controllerManager = ControllerManager()
    val historyRepository = HistoryRepository(DatabaseDriverFactory())
    private val routeProgressStore = IosRouteProgressStore()

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
        routeProgressStore = routeProgressStore,
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

    /** Every route the rider can ride here: bundled and added. */
    val routeLibrary = IosRouteLibrary(scope)


    /** One save at a time: two at once could each look for the ride, find nothing, and each insert it. */
    private val saveLock = Mutex()

    private var foreground = false
    private var rideActive = false

    /** Call once, at launch. */
    fun start() {
        // Before the first log line, so the previous run's log is rotated aside
        // where a bug report can find it.
        DiagnosticsLog.attachStorage()
        installCrashLogging()
        Log.i(TAG, "App start")
        routeLibrary.start()
        scope.launch { sessionManager.loadPersistedSnapshot() }
        // Keep retrying the devices this rider paired, so they don't have to
        // pick them every time they open the app. Gated by [setForeground] and
        // the ride state, as on Android.
        trainerManager.autoConnectIfPaired(userSettings.pairedTrainerId.value)
        heartRateMonitor.autoConnectIfPaired(userSettings.pairedHrmId.value)
        controllerManager.autoConnectIfPaired(userSettings.pairedControllerId.value)
        // Remember each device the moment it connects, and never the demo trainer.
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
        // screen's ± buttons do. Outside a ride they do nothing.
        controllerManager.events
            .onEach { event ->
                if (!rideActive) return@onEach
                val direction = if (event == ControllerEvent.Plus) 1 else -1
                adjustEffort(direction, sessionManager.activeWorkout.value, sessionManager, simulationEngine, null)
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
            controllerManager.resumeAutoReconnect()
        } else {
            trainerManager.pauseAutoReconnect()
            heartRateMonitor.pauseAutoReconnect()
            controllerManager.pauseAutoReconnect()
        }
    }

    // ─── Devices ─────────────────────────────────────────────────────────

    /** Stop reconnecting to the trainer, and forget it was ever paired. */
    fun forgetTrainer() {
        trainerManager.forgetTrainer()
        userSettings.setPairedTrainerId(null)
    }

    fun forgetHeartRateMonitor() {
        heartRateMonitor.forgetDevice()
        userSettings.setPairedHrmId(null)
    }

    fun forgetController() {
        controllerManager.forgetDevice()
        userSettings.setPairedControllerId(null)
    }

    /** One step harder (+1) or easier (-1), as the ride in progress understands it. */
    fun stepEffort(direction: Int) {
        adjustEffort(direction, sessionManager.activeWorkout.value, sessionManager, simulationEngine, null)
    }

    // ─── Other ways to ride ──────────────────────────────────────────────

    /** No plan: the trainer opens at a share of FTP and the rider moves it. */
    fun prepareFreeRide() {
        sessionManager.prepareFreeRide(userSettings.ftp.value)
    }

    /** Get [route] ready to ride, from the start or from [resumeFrom]; it starts when they pedal. */
    fun prepareRoute(route: RouteTrack, resumeFrom: RouteProgress?) {
        sessionManager.prepareSimulation(
            route = route,
            ftp = userSettings.ftp.value,
            startDistanceMeters = resumeFrom?.distanceMeters ?: 0.0,
            startElevationGainMeters = resumeFrom?.elevationGainMeters ?: 0.0,
        )
    }

    /** Where the rider got to on each route they left part-way, by route id. */
    fun loadRouteProgress(onLoaded: (Map<String, RouteProgress>) -> Unit) {
        scope.launch { onLoaded(runCatching { routeProgressStore.read() }.getOrDefault(emptyMap())) }
    }

    /** A route's card numbers - shape, profile, distance, climbing - as a repository lists them. */
    fun routeEntry(route: RouteTrack): RouteEntry = RouteStats.entryFor(route, gpxUrl = "local")

    /** How long a route should take this rider, or null without an FTP and weight. */
    fun routeEstimateSeconds(entry: RouteEntry): Long? = RouteEstimate.secondsFor(
        entry, userSettings.ftp.value, userSettings.weightKg.value, userSettings.bikeWeightKg.value,
    )

    /** Remove an added route, and where the rider got to on it. */
    fun deleteRoute(routeId: String, onDone: (Boolean) -> Unit) {
        scope.launch {
            val removed = routeLibrary.delete(routeId)
            if (removed) runCatching { routeProgressStore.clear(routeId) }
            onDone(removed)
        }
    }

    /** Add a GPX the rider picked in Files. */
    fun importRouteFile(fileName: String, data: NSData, onDone: (RouteImportResult) -> Unit) {
        scope.launch { onDone(routeLibrary.importFile(fileName, data.toByteArray())) }
    }

    // ─── Community repositories ──────────────────────────────────────────

    /**
     * The registry of community repositories, fetched fresh, or the copy kept
     * from last time when that fails. [onLoaded] gets the index (null if there
     * has never been one) and the error, if the fetch failed.
     */
    fun loadRegistry(onLoaded: (RegistryIndex?, String?) -> Unit) {
        scope.launch {
            val cache = IosFiles.path(REGISTRY_CACHE)
            try {
                val text = IosHttp.getText(REGISTRY_URL)
                val index = ManifestParser.parseRegistryIndex(text)
                runCatching { IosFiles.writeText(cache, text) }
                onLoaded(index, null)
            } catch (t: Throwable) {
                Log.w(TAG, "Registry refresh failed: ${t.message}")
                val cached = runCatching { IosFiles.readText(cache)?.let(ManifestParser::parseRegistryIndex) }.getOrNull()
                onLoaded(cached, t.message ?: "Couldn't reach registry")
            }
        }
    }

    fun fetchRepoRoutes(manifestUrl: String, onLoaded: (RouteFetchResult) -> Unit) {
        scope.launch { onLoaded(RepoClient.fetchRepoRoutes(manifestUrl, IosHttp.textFetcher)) }
    }

    fun addRouteFromRepo(entry: RouteEntry, repoId: String?, repoName: String?, onDone: (RouteImportResult) -> Unit) {
        scope.launch { onDone(routeLibrary.addFromRepo(entry, repoId, repoName)) }
    }

    // ─── Feedback ────────────────────────────────────────────────────────

    /**
     * The text of a bug report, laid out as the Android app lays it out, with
     * [deviceInfo] - the app version and the device - supplied by Swift.
     */
    fun bugReport(deviceInfo: String, errorContext: String?): String = bugReportText(
        errorContext = errorContext,
        deviceInfo = deviceInfo,
        exitsHeading = null,
        exits = null,
        previousRunLog = DiagnosticsLog.previousRunLog(),
        recentLog = DiagnosticsLog.snapshot(),
    )

    /** The workouts in bundled JSON files, each a list of workouts; duplicates keep the first. */
    fun parseWorkouts(jsonFiles: List<String>): List<Workout> = WorkoutRepository.parseWorkouts(jsonFiles)

    /** Get [workout] ready at the rider's FTP; it starts when they pedal. */
    fun prepareWorkout(workout: Workout) {
        sessionManager.prepareWorkout(workout, userSettings.ftp.value)
    }

    /**
     * Save the ride that just completed to history, then call [onSaved] with
     * its row id on the main thread, or with null when there was nothing to
     * save or the save failed. Runs on the container's scope, so leaving the
     * screen can't cancel a save half-way.
     */
    fun saveCompletedRide(onSaved: (Long?) -> Unit) {
        val workout = sessionManager.activeWorkout.value
        if (sessionManager.state.value != WorkoutState.Completed || workout == null) {
            onSaved(null)
            return
        }
        // Read everything now, before anything else can start a new ride.
        save(
            workout = workout,
            startTimeEpoch = sessionManager.sessionStartTimeEpoch,
            durationSeconds = sessionManager.actualRideSeconds.value,
            telemetry = sessionManager.telemetrySnapshot(),
            road = sessionManager.pendingRideRoad(),
            record = sessionManager.pendingRideRecord(),
            onSaved = onSaved,
        )
    }

    private fun save(
        workout: Workout,
        startTimeEpoch: Long,
        durationSeconds: Int,
        telemetry: List<TelemetryPoint>,
        road: RideRoad,
        record: RideRecord,
        onSaved: (Long?) -> Unit,
    ) {
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

    // ─── A ride the app was stopped in the middle of ───────────────────────

    /**
     * The ride left behind by a previous run, if there is one worth showing,
     * or null. A finished ride with no samples to keep is discarded here
     * rather than offered: saving it would only add an empty row to history.
     */
    fun unfinishedRide(snapshot: ActiveSessionSnapshot?, library: List<Workout>): UnfinishedRide? {
        if (snapshot == null) return null
        val workout = resolveRideWorkout(snapshot.workoutId, library, routeLibrary.routeNames()) ?: return null
        val ride = describeUnfinishedRide(snapshot, workout)
        if (ride.isFinished && sessionManager.pendingRideTelemetry(snapshot).isEmpty()) {
            sessionManager.abandonUnfinishedRide()
            return null
        }
        return ride
    }

    /** Pick the ride up where it stopped; the ride screen takes it from there. */
    fun continueUnfinishedRide(ride: UnfinishedRide) {
        val route = simulationRouteId(ride.workout.id)?.let { routeLibrary.find(it) }
        sessionManager.restoreFromSnapshot(ride.snapshot, ride.workout, route)
    }

    /** Keep a finished ride that never reached history. */
    fun saveUnfinishedRide(ride: UnfinishedRide, onSaved: (Long?) -> Unit) {
        val snapshot = ride.snapshot
        save(
            workout = ride.workout,
            startTimeEpoch = snapshot.sessionStartTimeEpochMs,
            durationSeconds = ride.rideSeconds,
            telemetry = sessionManager.pendingRideTelemetry(snapshot),
            road = sessionManager.pendingRideRoad(snapshot),
            record = sessionManager.pendingRideRecord(snapshot),
            onSaved = onSaved,
        )
    }

    fun abandonUnfinishedRide() {
        sessionManager.abandonUnfinishedRide()
    }

    // ─── History ─────────────────────────────────────────────────────────

    /** A saved ride and everything its detail screen shows. */
    class RideDetail(
        val session: WorkoutSession,
        /** The planned workout, when the ride had one and it is still in the library. */
        val workout: Workout?,
        /** One power reading per sample, for the chart. */
        val powerWatts: List<Int>,
        val stats: RideStats,
        /** Seconds in each of the seven power zones, Z1 first. */
        val timeInZoneSeconds: List<Int>,
        /** Whether there is telemetry to export. */
        val canExport: Boolean,
        /** The road it was on, for a route or companion ride; see [rideRoute]. */
        val route: RouteTrack?,
    )

    /**
     * Load one ride for its detail screen, and call [onLoaded] on the main
     * thread with it, or with null when it no longer exists. The stats are
     * worked out against the rider's current FTP and weight, as on Android.
     */
    fun loadRide(sessionId: Long, library: List<Workout>, onLoaded: (RideDetail?) -> Unit) {
        scope.launch {
            val detail = try {
                val session = historyRepository.getSessionById(sessionId)
                if (session == null) null else {
                    val telemetry = historyRepository.getTelemetryForSession(sessionId)
                    val watts = telemetry.map { it.power }
                    val ftp = userSettings.ftp.value
                    val workout = library.firstOrNull { it.id == session.workoutId }
                        ?.takeIf { !it.isFreeRide && !it.isSimulation && !it.isCompanion }
                    RideDetail(
                        session = session,
                        workout = workout,
                        powerWatts = watts.map { it.toInt() },
                        stats = computeRideStats(watts, session.durationSeconds, ftp, userSettings.weightKg.value),
                        timeInZoneSeconds = timeInZoneSeconds(watts, ftp).toList(),
                        canExport = telemetry.isNotEmpty(),
                        route = rideRoute(session, routeLibrary::find),
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't load ride $sessionId", e)
                null
            }
            onLoaded(detail)
        }
    }

    fun deleteRide(sessionId: Long, onDone: () -> Unit) {
        scope.launch {
            try {
                historyRepository.deleteSession(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't delete ride $sessionId", e)
            }
            onDone()
        }
    }

    /**
     * Write a ride as a FIT (or, when [fit] is false, TCX) file to the temporary
     * directory for the share sheet, and call [onWritten] with its path, or
     * null if it couldn't be built.
     */
    fun exportRide(sessionId: Long, fit: Boolean, library: List<Workout>, onWritten: (String?) -> Unit) {
        scope.launch {
            val path = try {
                withContext(Dispatchers.IO) {
                    val session = historyRepository.getSessionById(sessionId) ?: return@withContext null
                    val telemetry = historyRepository.getTelemetryForSession(sessionId)
                    if (telemetry.isEmpty()) return@withContext null
                    val workout = library.firstOrNull { it.id == session.workoutId }
                    val ftp = userSettings.ftp.value
                    val kg = userSettings.weightKg.value
                    val path = "${NSTemporaryDirectory().trimEnd('/')}/${exportFileName(session, if (fit) "fit" else "tcx")}"
                    if (fit) {
                        IosFiles.writeBytes(path, RideFitBuilder.build(session, telemetry, workout, ftp, kg))
                    } else {
                        IosFiles.writeText(path, TcxBuilder.build(session, telemetry, workout, ftp, kg))
                    }
                    path
                }
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't export ride $sessionId", e)
                null
            }
            onWritten(path)
        }
    }

    private companion object {
        const val TAG = "IosAppContainer"
        /** [TrainerManager.connectMock]'s pseudo-id. */
        const val MOCK_ID = "mock"

        /** The community registry, as the Android build points at it. */
        const val REGISTRY_URL = "https://indoorbike.app/registry.json"
        const val REGISTRY_CACHE = "registry.json"
    }
}
