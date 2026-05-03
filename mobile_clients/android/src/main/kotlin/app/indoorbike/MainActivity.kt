package app.indoorbike

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.indoorbike.navigation.RouteRideScreen
import app.indoorbike.navigation.RouteTrainerPairing
import app.indoorbike.navigation.RouteWorkoutDetail
import app.indoorbike.navigation.RouteWorkoutOverview
import app.indoorbike.navigation.TabHistory
import app.indoorbike.navigation.TabProgress
import app.indoorbike.navigation.TabRides
import app.indoorbike.navigation.TabSettings
import app.indoorbike.navigation.Navigator
import app.indoorbike.navigation.rememberNavigationState
import app.indoorbike.shared.Workout
import app.indoorbike.shared.WorkoutRepository
import app.indoorbike.ui.HistoryTab
import app.indoorbike.ui.ProgressTab
import app.indoorbike.ui.SettingsTab
import app.indoorbike.ui.TrainerPairingScreen
import app.indoorbike.ui.components.TabBar
import app.indoorbike.ui.components.TabItem
import app.indoorbike.ui.screens.RideScreen
import app.indoorbike.ui.screens.WorkoutDetailScreen
import app.indoorbike.ui.screens.WorkoutListScreen
import app.indoorbike.ui.screens.WorkoutOverviewScreen
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceTheme
import app.indoorbike.ui.theme.CadenceType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val metrics = resources.displayMetrics
        val dpWidth = metrics.widthPixels / metrics.density
        val dpHeight = metrics.heightPixels / metrics.density
        val smallestWidth = minOf(dpWidth, dpHeight)
        if (smallestWidth < 600) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        enableEdgeToEdge()

        val container = (application as IndoorBikeApp).container

        setContent {
            CadenceTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = CadenceColors.Bg0
                    ) {
                        var workouts by remember { mutableStateOf<List<Workout>>(emptyList()) }
                        LaunchedEffect(Unit) {
                            workouts = withContext(Dispatchers.IO) {
                                val jsonStrings = loadWorkoutJsonsFromAssets(this@MainActivity)
                                WorkoutRepository.parseWorkouts(jsonStrings)
                            }
                        }
                        AppNavigator(workouts = workouts)
                    }
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
            app.indoorbike.shared.util.Log.e("MainActivity", "Asset load failed", e)
        }
        return jsonStrings
    }
}


private val TOP_LEVEL_ROUTES = mapOf<NavKey, TabItem>(
    TabRides to TabItem("rides", Icons.AutoMirrored.Outlined.DirectionsBike, "Ride"),
    TabHistory to TabItem("history", Icons.Outlined.AccessTime, "History"),
    TabProgress to TabItem("progress", Icons.Outlined.EmojiEvents, "Progress"),
    TabSettings to TabItem("settings", Icons.Outlined.Settings, "Settings")
)

@Composable
private fun AppNavigator(workouts: List<Workout>) {
    val container = LocalAppContainer.current
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
                    val targetTab = TOP_LEVEL_ROUTES.entries.find { (_, v) -> v.id == tabId }?.key
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
                        container.sessionManager.prepareWorkout(
                            workout = workout,
                            ftp = container.userSettings.ftp.value
                        )
                        navigator.navigate(RouteRideScreen(workout.id))
                    }
                )
            } else {
                LaunchedEffect(Unit) { navigator.goBack() }
            }
        }
        entry<TabHistory> { _ ->
            HistoryTab(
                onNavigateToOverview = { sessionId ->
                    navigator.navigate(RouteWorkoutOverview(sessionId, fromRide = false))
                }
            )
        }
        entry<TabProgress> { _ ->
            ProgressTab()
        }
        entry<TabSettings> { _ ->
            SettingsTab(
                onNavigateToPairing = { navigator.navigate(RouteTrainerPairing) }
            )
        }
        entry<RouteTrainerPairing> { _ ->
            TrainerPairingScreen(onBack = { navigator.goBack() })
        }
        entry<RouteRideScreen> { _ ->
            RideScreen(
                onBack = { navigator.goBack() },
                onNavigateToOverview = { sessionId ->
                    navigator.replace(RouteWorkoutOverview(sessionId, fromRide = true))
                }
            )
        }
        entry<RouteWorkoutOverview> { key: RouteWorkoutOverview ->
            WorkoutOverviewScreen(
                sessionId = key.sessionId,
                fromRide = key.fromRide,
                workoutList = workouts,
                onBack = { navigator.goBack() },
                onDone = { navigator.popToRoot() }
            )
        }
    }

    Scaffold(
        bottomBar = {
            // Only show bottom bar on tab roots (depth == 1).
            if (navigationState.topLevelRoute in TOP_LEVEL_ROUTES.keys &&
                navigationState.backStacks[navigationState.topLevelRoute]?.size == 1
            ) {
                TabBar(
                    active = TOP_LEVEL_ROUTES[navigationState.topLevelRoute]?.id ?: "rides",
                    tabs = TOP_LEVEL_ROUTES.values.toList(),
                    onTabSelected = { tabId ->
                        val targetTab = TOP_LEVEL_ROUTES.entries.find { (_, v) -> v.id == tabId }?.key
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
