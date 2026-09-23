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
import app.indoorbike.shared.library.ImportResult
import app.indoorbike.shared.repo.FollowedRepo
import app.indoorbike.shared.repo.FollowedRepositories
import app.indoorbike.shared.repo.RegistryEntry
import app.indoorbike.shared.repo.RepoFetchResult
import app.indoorbike.shared.repo.WorkoutLinkParser
import app.indoorbike.shared.util.getCurrentEpochMillis
import app.indoorbike.shared.net.queryParameters
import app.indoorbike.shared.sync.FitTrackeeUploader
import app.indoorbike.shared.sync.IntervalsIcuUploader
import app.indoorbike.shared.sync.RideUploads
import app.indoorbike.shared.sync.SyncEngine
import app.indoorbike.shared.sync.SyncWorkoutLibrary
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import app.indoorbike.shared.companion.CompanionEngine
import app.indoorbike.shared.companion.CompanionHost
import app.indoorbike.shared.companion.CompanionRideRecorder
import app.indoorbike.shared.companion.JsonPairedCompanionStore
import platform.UIKit.UIImage
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
@OptIn(ExperimentalUuidApi::class)
class IosAppContainer(
    /** The workout files shipped in the app: the same JSON the Android app bundles. */
    bundledWorkoutJson: List<String>,
    /** The Keychain, for the upload services' credentials. */
    secrets: SecretStore,
    /** The app's version, as a companion is told it. */
    appVersion: String,
    /** A debug build: the companion pairing code is always the same. */
    isDebug: Boolean,
) {
    val userSettings = IosUserSettings(secrets)
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
        companionEngine = companionEngine,
    )

    val screenshots = IosScreenshotStore()
    val brands = IosBrandStore()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Every route the rider can ride here: bundled and added. */
    val routeLibrary = IosRouteLibrary(scope)

    /** Every workout the rider can ride here: their own, then the bundled ones. */
    val workoutLibrary = IosWorkoutLibrary(scope, WorkoutRepository.parseWorkouts(bundledWorkoutJson))

    /**
     * Intervals.icu by athlete id and API key. Signing in needs the verified
     * https redirect Android gets from indoorbike.app, which an iOS build
     * cannot receive without Universal Links, and a client this build doesn't
     * carry; see docs/IOS_PORT.md.
     */
    val intervalsIcuUploader = IntervalsIcuUploader(
        http = IosHttpClient,
        historyRepository = historyRepository,
        userSettings = userSettings,
        pending = DefaultsStore("intervals_oauth"),
        clientId = "",
        clientSecret = "",
        signInAllowed = false,
    )

    /** FitTrackee, signed in through the browser and `indoorbike://oauth`. */
    val fitTrackeeUploader = FitTrackeeUploader(
        http = IosHttpClient,
        historyRepository = historyRepository,
        userSettings = userSettings,
        pending = DefaultsStore("fittrackee_oauth"),
    )

    /** Uploads as the app's work, once per ride and service; see [RideUploads]. */
    val rideUploads = RideUploads(scope, historyRepository, userSettings, intervalsIcuUploader, fitTrackeeUploader)

    /** The backup folder: each device's slice, merged; see [SyncEngine]. */
    val syncEngine = SyncEngine(
        userSettings = userSettings,
        historyRepository = historyRepository,
        workoutLibrary = object : SyncWorkoutLibrary {
            override fun loadUserWorkouts() = workoutLibrary.core.loadUserWorkouts()
            override fun loadImportMeta(workoutId: String) = workoutLibrary.core.loadImportMeta(workoutId)
            override suspend fun saveWorkout(workout: Workout, sourceRepoId: String?, sourceRepoName: String?, addedEpoch: Long) =
                withContext(Dispatchers.IO) { workoutLibrary.core.saveWorkout(workout, sourceRepoId, sourceRepoName, addedEpoch) }
            override suspend fun deleteUserWorkout(workoutId: String) =
                withContext(Dispatchers.IO) { workoutLibrary.core.deleteUserWorkout(workoutId) }
            override suspend fun refresh() = workoutLibrary.rebuild()
        },
        storageProvider = { userSettings.syncFolderUri.value?.let { IosSyncStorage(it) } },
    )

    /**
     * The companion socket: the same host the Android app runs, found by
     * Bonjour and the UDP beacon. The beacon only gets out with Apple's
     * multicast entitlement; see [IosCompanionBeacon].
     */
    val companionHost = CompanionHost(
        engine = companionEngine,
        sessionManager = sessionManager,
        appVersion = appVersion,
        ftp = { userSettings.ftp.value },
        riderMassKg = { userSettings.weightKg.value },
        bikeMassKg = { userSettings.bikeWeightKg.value },
        powerWatts = { trainerManager.power.value },
        cadenceRpm = { trainerManager.cadence.value },
        heartRateBpm = {
            val hrm = heartRateMonitor.heartRate.value
            if (hrm > 0) hrm else trainerManager.heartRate.value
        },
        trainerName = { trainerManager.connectedTrainerName.value },
        trainerConnected = {
            trainerManager.connectionState.value == TrainerConnectionState.Connected || trainerManager.isMockTrainer.value
        },
        trainerAcceptsPower = { trainerManager.acceptsTargetPower },
        deviceId = { userSettings.deviceId },
        pairedStore = JsonPairedCompanionStore(IosTextFile("paired_companions.json")),
        screenshots = screenshots,
        brands = brands,
        advertiser = NetServiceAdvertiser(),
        beacon = IosCompanionBeacon(CoroutineScope(SupervisorJob() + Dispatchers.IO)),
        isDebug = isDebug,
        decodeIcon = { png -> UIImage.imageWithData(png.toNSData()) },
    ).also { host ->
        host.rideRecorder = CompanionRideRecorder(historyRepository, sessionManager) { workout, start, duration, telemetry, road, record ->
            saveNow(workout, start, duration, telemetry, road, record)
                ?: throw IllegalStateException("The companion ride couldn't be saved")
        }
    }

    /** The community repositories the rider follows, and what each has that they haven't seen. */
    val followedRepositories = FollowedRepositories(IosFolder("followed").asTextStore(), IosHttp.textFetcher)


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
        workoutLibrary.start()
        // Give rides from before external ids existed one, then sync. With no
        // folder picked this does nothing at all.
        scope.launch {
            val deviceId = userSettings.deviceId
            runCatching { historyRepository.backfillExternalIds { "$deviceId:${Uuid.random()}" } }
            syncEngine.syncNow()
        }
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
                adjustEffort(direction, sessionManager.activeWorkout.value, sessionManager, simulationEngine, companionEngine)
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
        adjustEffort(direction, sessionManager.activeWorkout.value, sessionManager, simulationEngine, companionEngine)
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

    // ─── Workout library ─────────────────────────────────────────────────

    /**
     * Import a workout (or a list, or a repository manifest) from JSON text.
     * An import that would replace one of the rider's workouts first asks
     * through [IosWorkoutLibrary.questions].
     */
    fun importWorkoutJson(text: String, onDone: (ImportResult) -> Unit) {
        scope.launch { onDone(workoutLibrary.importJsonText(text)) }
    }

    /** Import a file the rider picked or shared; no bigger than a workout could be. */
    fun importWorkoutFile(data: NSData, onDone: (ImportResult) -> Unit) {
        if (data.length.toLong() > IosHttp.MAX_BODY_BYTES) {
            onDone(ImportResult.Failure("That file is too large to be a workout."))
            return
        }
        importWorkoutJson(data.toByteArray().decodeToString(), onDone)
    }

    /**
     * Import from a link: a share link or `indoorbike://import` carrying the
     * workout itself, or one pointing at an HTTPS file to fetch. What a QR
     * code holds, what a tapped link opens, and what the rider pastes.
     */
    fun importWorkoutLink(link: String, onDone: (ImportResult) -> Unit) {
        scope.launch {
            val result = try {
                val embedded = WorkoutLinkParser.extractEmbeddedJson(link)
                if (embedded != null) {
                    workoutLibrary.importJsonText(embedded)
                } else {
                    val url = if (link.trim().startsWith("indoorbike://", ignoreCase = true)) {
                        link.substringAfter("url=", "").substringBefore('&').trim()
                    } else {
                        link.trim()
                    }
                    when {
                        url.isEmpty() -> ImportResult.Failure("Missing ?url= or ?data= in indoorbike://import link")
                        !url.startsWith("https://", ignoreCase = true) ->
                            ImportResult.Failure("That link didn't contain a workout or an HTTPS URL")
                        else -> workoutLibrary.importJsonText(IosHttp.getText(url))
                    }
                }
            } catch (t: Throwable) {
                ImportResult.Failure("Couldn't fetch: ${t.message}")
            }
            onDone(result)
        }
    }

    fun fetchRepoWorkouts(manifestUrl: String, onLoaded: (RepoFetchResult) -> Unit) {
        scope.launch { onLoaded(RepoClient.fetchRepoWorkouts(manifestUrl, IosHttp.textFetcher)) }
    }

    fun addWorkoutFromRepo(workout: Workout, repoId: String, repoName: String, onDone: () -> Unit) {
        scope.launch {
            workoutLibrary.addFromRepo(workout, repoId, repoName)
            onDone()
        }
    }

    fun deleteWorkout(workoutId: String, onDone: (Boolean) -> Unit) {
        scope.launch { onDone(workoutLibrary.delete(workoutId)) }
    }

    fun renameWorkout(workoutId: String, name: String, onDone: (Workout?) -> Unit) {
        scope.launch { onDone(workoutLibrary.rename(workoutId, name)) }
    }

    // ─── Followed repositories ───────────────────────────────────────────

    /** Check the followed repositories, unless they were checked lately and [force] is false. */
    fun refreshFollowed(force: Boolean) {
        scope.launch { followedRepositories.refresh(force) }
    }

    fun follow(entry: RegistryEntry) {
        followedRepositories.follow(entry)
        refreshFollowed(force = true)
    }

    /**
     * Follow a repository by its manifest's address. [onDone] gets null on
     * success, or what went wrong in words for the rider.
     */
    fun followByUrl(url: String, onDone: (String?) -> Unit) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) {
            onDone("Repository URLs have to be https://")
            return
        }
        scope.launch {
            val error = try {
                val manifest = RepoClient.fetchManifest(trimmed, IosHttp.textFetcher)
                if (followedRepositories.isFollowed(manifest.id)) {
                    "You already follow \"${manifest.name}\"."
                } else {
                    followedRepositories.follow(
                        FollowedRepo(
                            id = manifest.id,
                            name = manifest.name,
                            manifestUrl = trimmed,
                            author = manifest.author,
                            homepage = manifest.homepage,
                            addedEpoch = getCurrentEpochMillis(),
                        )
                    )
                    followedRepositories.refresh(force = true)
                    null
                }
            } catch (t: Throwable) {
                t.message ?: "Could not read that repository"
            }
            onDone(error)
        }
    }

    // ─── Uploads and sync ────────────────────────────────────────────────

    /** Finish a FitTrackee sign-in from the `indoorbike://oauth` redirect. */
    fun completeFitTrackeeSignIn(redirect: String, onDone: (String?) -> Unit) {
        scope.launch {
            onDone(
                when (val result = fitTrackeeUploader.completeAuthorization(queryParameters(redirect))) {
                    is FitTrackeeUploader.SignIn.Success -> null
                    is FitTrackeeUploader.SignIn.Failure -> result.message
                }
            )
        }
    }

    fun fitTrackeeSports(onLoaded: (List<FitTrackeeUploader.Sport>) -> Unit) {
        scope.launch { onLoaded(fitTrackeeUploader.sports()) }
    }

    fun disconnectIntervals(onDone: (Boolean) -> Unit) {
        scope.launch { onDone(intervalsIcuUploader.disconnect()) }
    }

    /** Upload a saved ride to Intervals.icu, and say how it went in words for the rider. */
    fun uploadToIntervals(sessionId: Long, library: List<Workout>, onDone: (String) -> Unit) {
        scope.launch {
            val session = historyRepository.getSessionById(sessionId) ?: return@launch onDone("That ride is gone.")
            val workout = library.firstOrNull { it.id == session.workoutId }
            onDone(
                when (val outcome = rideUploads.uploadIntervals(session, workout, session.ftp.toInt().takeIf { it > 0 } ?: userSettings.ftp.value)) {
                    is IntervalsIcuUploader.Outcome.Success -> "Uploaded to Intervals.icu"
                    IntervalsIcuUploader.Outcome.NotConfigured -> "Connect Intervals.icu in Settings → Connections first"
                    IntervalsIcuUploader.Outcome.Unauthorized ->
                        "Intervals.icu no longer accepts this connection. Connect again in Settings → Connections."
                    is IntervalsIcuUploader.Outcome.Failure -> outcome.message
                }
            )
        }
    }

    /** Upload a saved ride to FitTrackee, and say how it went in words for the rider. */
    fun uploadToFitTrackee(sessionId: Long, library: List<Workout>, onDone: (String) -> Unit) {
        scope.launch {
            val session = historyRepository.getSessionById(sessionId) ?: return@launch onDone("That ride is gone.")
            val workout = library.firstOrNull { it.id == session.workoutId }
            onDone(
                when (val outcome = rideUploads.uploadFitTrackee(session, workout, session.ftp.toInt().takeIf { it > 0 } ?: userSettings.ftp.value)) {
                    is FitTrackeeUploader.Outcome.Success -> if (outcome.withStreams) "Uploaded to FitTrackee"
                        else "Uploaded to FitTrackee as a summary (it can't take indoor files)"
                    FitTrackeeUploader.Outcome.NotConfigured -> "Connect FitTrackee in Settings → Connections first"
                    FitTrackeeUploader.Outcome.Unauthorized ->
                        "FitTrackee sign-in has expired. Sign in again in Settings → Connections."
                    is FitTrackeeUploader.Outcome.Failure -> outcome.message
                }
            )
        }
    }

    /** Run a sync round now: a tap on Sync now, or a folder just picked. */
    fun syncNow() {
        scope.launch { syncEngine.syncNow() }
    }

    fun restoreFromBackup() {
        scope.launch { syncEngine.resolveRestoreFromBackup() }
    }

    fun keepThisDevicesData() {
        scope.launch { syncEngine.resolveKeepLocal() }
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
        scope.launch { onSaved(saveNow(workout, startTimeEpoch, durationSeconds, telemetry, road, record)) }
    }

    /** Write a ride to history under the save lock and start its uploads; null if it failed. */
    private suspend fun saveNow(
        workout: Workout,
        startTimeEpoch: Long,
        durationSeconds: Int,
        telemetry: List<TelemetryPoint>,
        road: RideRoad,
        record: RideRecord,
    ): Long? {
        run {
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
            // Every way a ride reaches history comes through here, so this is
            // where automatic upload starts, as on Android.
            if (id != null) {
                rideUploads.autoUpload(id, workout, record.ftp.takeIf { it > 0 } ?: userSettings.ftp.value)
            }
            return id
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
