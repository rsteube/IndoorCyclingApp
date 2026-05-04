package app.indoorbike

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
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
import app.indoorbike.navigation.RouteHrmPairing
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
import app.indoorbike.ui.HrmPairingScreen
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CadenceColors.Bg0
                ) {
                    // null = still loading from assets; emptyList() = loaded but no
                    // workouts (genuine empty state). Distinguishing the two prevents
                    // the "no workouts saved yet" message from flashing during launch.
                    var workouts by remember { mutableStateOf<List<Workout>?>(null) }
                    LaunchedEffect(Unit) {
                        workouts = withContext(Dispatchers.IO) {
                            val jsonStrings = loadWorkoutJsonsFromAssets(this@MainActivity)
                            WorkoutRepository.parseWorkouts(jsonStrings)
                        }
                    }
                    CompositionLocalProvider(
                        LocalAppContainer provides container,
                        LocalWorkouts provides (workouts ?: emptyList()),
                    ) {
                        when (val w = workouts) {
                            null -> LoadingState()
                            else -> AppNavigator(workouts = w)
                        }
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

    val resumer = remember(navigator) {
        RideResumer(
            continueRide = { workout, snapshot ->
                container.sessionManager.restoreFromSnapshot(snapshot, workout)
                navigator.continueRide(workout.id)
            },
            abandonRide = { container.sessionManager.abandonUnfinishedRide() }
        )
    }

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
                    },
                    onNavigateToPairing = { navigator.navigate(RouteTrainerPairing) }
                )
            } else {
                LaunchedEffect(Unit) { navigator.goBack() }
            }
        }
        entry<TabHistory> { _ ->
            HistoryTab(
                onNavigateToOverview = { sessionId ->
                    navigator.navigate(RouteWorkoutOverview(sessionId, fromRide = false))
                },
                onNavigateToRides = { navigator.navigate(TabRides) }
            )
        }
        entry<TabProgress> { _ ->
            ProgressTab(
                onNavigateToRides = { navigator.navigate(TabRides) }
            )
        }
        entry<TabSettings> { _ ->
            SettingsTab(
                onNavigateToPairing = { navigator.navigate(RouteTrainerPairing) },
                onNavigateToHrmPairing = { navigator.navigate(RouteHrmPairing) }
            )
        }
        entry<RouteTrainerPairing> { _ ->
            TrainerPairingScreen(onBack = { navigator.goBack() })
        }
        entry<RouteHrmPairing> { _ ->
            HrmPairingScreen(onBack = { navigator.goBack() })
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

    CompositionLocalProvider(LocalRideResumer provides resumer) {
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

@Composable
private fun LoadingState() {
    val transition = rememberInfiniteTransition(label = "splashPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "splashPulseAlpha"
    )
    Box(
        modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.DirectionsBike,
            contentDescription = null,
            tint = CadenceColors.Brand500,
            modifier = Modifier
                .alpha(pulse)
                .size(56.dp)
        )
    }
}

private fun currentDayEyebrow(): String {
    val fmt = SimpleDateFormat("EEEE", Locale.getDefault())
    return fmt.format(Date())
}
