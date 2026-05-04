package app.indoorbike

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import app.indoorbike.session.AndroidActiveSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import app.indoorbike.settings.AndroidUserSettings
import app.indoorbike.shared.WorkoutRepository
import app.indoorbike.shared.ble.HeartRateMonitor
import app.indoorbike.shared.ble.TrainerManager
import app.indoorbike.shared.db.DatabaseDriverFactory
import app.indoorbike.shared.db.HistoryRepository
import app.indoorbike.shared.session.ActiveSessionStore
import app.indoorbike.shared.session.WorkoutSessionManager
import app.indoorbike.shared.settings.UserSettings
import app.indoorbike.shared.util.Log

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
    val sessionManager: WorkoutSessionManager,
    val historyRepository: HistoryRepository,
    val userSettings: UserSettings,
    val activeSessionStore: ActiveSessionStore,
) {
    fun dispose() {
        trainerManager.dispose()
        heartRateMonitor.dispose()
        sessionManager.dispose()
        if (userSettings is AndroidUserSettings) userSettings.dispose()
    }

    companion object {
        fun create(application: Application): AppContainer {
            val trainerManager = TrainerManager()
            val heartRateMonitor = HeartRateMonitor()
            val activeSessionStore = AndroidActiveSessionStore(application)
            val sessionManager = WorkoutSessionManager(
                trainerManager = trainerManager,
                activeSessionStore = activeSessionStore,
                // Prefer the dedicated HRM when one is connected and emitting samples;
                // fall back to whatever the trainer reports (often 0 for trainer-only).
                effectiveHeartRate = {
                    val hrm = heartRateMonitor.heartRate.value
                    if (hrm > 0) hrm else trainerManager.heartRate.value
                },
            )
            val userSettings = AndroidUserSettings(application)
            val historyRepository = HistoryRepository(DatabaseDriverFactory(application))
            return AppContainer(
                trainerManager = trainerManager,
                heartRateMonitor = heartRateMonitor,
                sessionManager = sessionManager,
                historyRepository = historyRepository,
                userSettings = userSettings,
                activeSessionStore = activeSessionStore,
            )
        }
    }
}

class IndoorBikeApp : Application() {
    lateinit var container: AppContainer
        private set

    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.i("IndoorBikeApp", "App start")
        container = AppContainer.create(this)
        // Surface any unfinished ride snapshot to the UI (banner) by populating
        // the session manager's persistedSnapshot StateFlow from disk.
        initScope.launch { container.sessionManager.loadPersistedSnapshot() }
        // Silently re-connect to previously paired BLE devices so users don't
        // have to dig into settings every launch.
        initScope.launch {
            container.trainerManager.autoConnectIfPaired(container.userSettings.pairedTrainerId.value)
        }
        initScope.launch {
            container.heartRateMonitor.autoConnectIfPaired(container.userSettings.pairedHrmId.value)
        }
    }

    override fun onTerminate() {
        // Rarely called by the framework on real devices, but good hygiene for emulator runs.
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
)

val LocalRideResumer = staticCompositionLocalOf<RideResumer> {
    error("RideResumer not provided. Wrap your composables in CompositionLocalProvider(LocalRideResumer provides ...).")
}
