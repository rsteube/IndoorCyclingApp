package app.indoorbike

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Settings
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.indoorbike.navigation.*
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import app.indoorbike.ui.SettingsTab
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.indoorbike.shared.Workout
import app.indoorbike.shared.WorkoutRepository
import app.indoorbike.ui.screens.WorkoutDetailScreen
import app.indoorbike.ui.screens.WorkoutListScreen
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceTheme
import app.indoorbike.ui.theme.CadenceType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.indoorbike.shared.db.DatabaseDriverFactory
import app.indoorbike.shared.db.HistoryRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val metrics = resources.displayMetrics
        val dpWidth = metrics.widthPixels / metrics.density
        val dpHeight = metrics.heightPixels / metrics.density
        val smallestWidth = minOf(dpWidth, dpHeight)
        
        if (smallestWidth < 600) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        
        enableEdgeToEdge()
        UserSettings.init(applicationContext)
        setContent {
            CadenceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CadenceColors.Bg0
                ) {
                    var workouts by remember { mutableStateOf<List<Workout>>(emptyList()) }

                    LaunchedEffect(Unit) {
                        val parsedWorkouts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val jsonStrings = loadWorkoutJsonsFromAssets(this@MainActivity)
                            WorkoutRepository.parseWorkouts(jsonStrings)
                        }
                        workouts = parsedWorkouts
                    }
                    val historyRepository = remember {
                        HistoryRepository(DatabaseDriverFactory(applicationContext))
                    }

                    AppNavigator(workouts = workouts, historyRepository = historyRepository)
                }
            }
        }
    }

    private fun loadWorkoutJsonsFromAssets(context: Context): List<String> {
        val jsonStrings = mutableListOf<String>()
        try {
            val assetManager = context.assets
            val allAssets = assetManager.list("") ?: emptyArray()
            val workoutFiles = allAssets.filter { it.startsWith("workouts") && it.endsWith(".json") }
            for (fileName in workoutFiles) {
                val jsonString = assetManager.open(fileName).bufferedReader().use { it.readText() }
                jsonStrings.add(jsonString)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return jsonStrings
    }
}




private val TOP_LEVEL_ROUTES = mapOf<NavKey, app.indoorbike.ui.components.TabItem>(
    TabRides to app.indoorbike.ui.components.TabItem("rides", Icons.AutoMirrored.Outlined.DirectionsBike, "Ride"),
    TabHistory to app.indoorbike.ui.components.TabItem("history", Icons.Outlined.AccessTime, "History"),
    TabProgress to app.indoorbike.ui.components.TabItem("progress", Icons.Outlined.EmojiEvents, "Progress"),
    TabSettings to app.indoorbike.ui.components.TabItem("settings", Icons.Outlined.Settings, "Settings")
)

@Composable
private fun AppNavigator(workouts: List<Workout>, historyRepository: HistoryRepository) {
    val eyebrow = remember { currentDayEyebrow() }

    if (workouts.isEmpty()) {
        EmptyState()
        return
    }

    val navigationState = rememberNavigationState(
        startRoute = TabRides,
        topLevelRoutes = TOP_LEVEL_ROUTES.keys
    )

    val navigator = remember { Navigator(navigationState) }

    val entryProvider = entryProvider<NavKey> {
        entry<TabRides> { _ ->
            WorkoutListScreen(
                workouts = workouts,
                eyebrow = eyebrow,
                onPick = { workout -> navigator.navigate(RouteWorkoutDetail(workout.id)) },
                activeTab = "rides",
                onTabSelected = { tabId -> 
                    val targetTab = TOP_LEVEL_ROUTES.entries.find { (k, v) -> v.id == tabId }?.key
                    targetTab?.let { navigator.navigate(it) }
                }
            )
        }
        entry<RouteWorkoutDetail> { key: RouteWorkoutDetail ->
            val workout = workouts.find { it.id == key.workoutId }
            if (workout != null) {
                WorkoutDetailScreen(
                    workout = workout,
                    onBack = { navigator.goBack() },
                    onStart = { 
                        app.indoorbike.shared.session.WorkoutSessionManager.prepareWorkout(
                            workout = workout,
                            ftp = app.indoorbike.UserSettings.ftp.value
                        )
                        navigator.navigate(app.indoorbike.navigation.RouteRideScreen(workout.id))
                    }
                )
            } else {
                LaunchedEffect(Unit) {
                    navigator.goBack()
                }
            }
        }
        entry<TabHistory> { _ ->
            app.indoorbike.ui.HistoryTab(
                historyRepository = historyRepository,
                onNavigateToOverview = { sessionId ->
                    navigator.navigate(app.indoorbike.navigation.RouteWorkoutOverview(sessionId, fromRide = false))
                }
            )
        }
        entry<TabProgress> { _ ->
            app.indoorbike.ui.ProgressTab(historyRepository = historyRepository)
        }
        entry<TabSettings> { _ ->
            app.indoorbike.ui.SettingsTab(
                onNavigateToPairing = { navigator.navigate(app.indoorbike.navigation.RouteTrainerPairing) }
            )
        }
        entry<app.indoorbike.navigation.RouteTrainerPairing> { _ ->
            app.indoorbike.ui.TrainerPairingScreen(
                onBack = { navigator.goBack() }
            )
        }
        entry<app.indoorbike.navigation.RouteRideScreen> { _ ->
            app.indoorbike.ui.screens.RideScreen(
                onBack = { navigator.goBack() },
                onNavigateToOverview = { sessionId ->
                    navigator.replace(app.indoorbike.navigation.RouteWorkoutOverview(sessionId, fromRide = true))
                },
                historyRepository = historyRepository
            )
        }
        entry<app.indoorbike.navigation.RouteWorkoutOverview> { key: app.indoorbike.navigation.RouteWorkoutOverview ->
            app.indoorbike.ui.screens.WorkoutOverviewScreen(
                sessionId = key.sessionId,
                fromRide = key.fromRide,
                workoutList = workouts,
                historyRepository = historyRepository,
                onBack = {
                    navigator.goBack()
                },
                onDone = {
                    navigator.popToRoot()
                }
            )
        }
    }

    Scaffold(
        bottomBar = {
            // Only show bottom bar on top level routes
            if (navigationState.topLevelRoute in TOP_LEVEL_ROUTES.keys && navigationState.backStacks[navigationState.topLevelRoute]?.size == 1) {
                app.indoorbike.ui.components.TabBar(
                    active = TOP_LEVEL_ROUTES[navigationState.topLevelRoute]?.id ?: "rides",
                    tabs = TOP_LEVEL_ROUTES.values.toList(),
                    onTabSelected = { tabId ->
                        val targetTab = TOP_LEVEL_ROUTES.entries.find { (k, v) -> v.id == tabId }?.key
                        targetTab?.let { navigator.navigate(it) }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            NavDisplay(
                entries = navigationState.toDecoratedEntries(entryProvider),
                onBack = { navigator.goBack() }
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No workouts saved yet.\nDrop a workouts*.json into assets/ to start.",
            color = CadenceColors.Fg2,
            style = CadenceType.body
        )
    }
}

private fun currentDayEyebrow(): String {
    val fmt = SimpleDateFormat("EEEE", Locale.getDefault())
    return fmt.format(Date())
}
