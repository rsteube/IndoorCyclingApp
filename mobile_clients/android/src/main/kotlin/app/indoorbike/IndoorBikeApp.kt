package app.indoorbike

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import app.indoorbike.session.AndroidActiveSessionStore
import app.indoorbike.settings.AndroidUserSettings
import app.indoorbike.shared.WorkoutRepository
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
    val sessionManager: WorkoutSessionManager,
    val historyRepository: HistoryRepository,
    val userSettings: UserSettings,
    val activeSessionStore: ActiveSessionStore,
) {
    fun dispose() {
        trainerManager.dispose()
        sessionManager.dispose()
        if (userSettings is AndroidUserSettings) userSettings.dispose()
    }

    companion object {
        fun create(application: Application): AppContainer {
            val trainerManager = TrainerManager()
            val activeSessionStore = AndroidActiveSessionStore(application)
            val sessionManager = WorkoutSessionManager(
                trainerManager = trainerManager,
                activeSessionStore = activeSessionStore,
            )
            val userSettings = AndroidUserSettings(application)
            val historyRepository = HistoryRepository(DatabaseDriverFactory(application))
            return AppContainer(
                trainerManager = trainerManager,
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

    override fun onCreate() {
        super.onCreate()
        Log.i("IndoorBikeApp", "App start")
        container = AppContainer.create(this)
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
