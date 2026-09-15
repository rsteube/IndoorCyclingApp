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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.runtime.NavEntry
import kotlin.math.sign
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import app.indoorbike.ui.components.LocalTabBarHeight
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.navigationevent.NavigationEvent
import app.indoorbike.ui.theme.SlateMotion
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.indoorbike.navigation.RouteConnections
import app.indoorbike.navigation.RouteControllerPairing
import app.indoorbike.navigation.RouteHrmPairing
import app.indoorbike.navigation.RouteRideScreen
import app.indoorbike.navigation.RouteSimulationSettings
import app.indoorbike.navigation.RouteTrainerPairing
import app.indoorbike.navigation.RouteWorkoutDetail
import app.indoorbike.navigation.RouteWorkoutOverview
import app.indoorbike.ride.saveRideToHistory
import app.indoorbike.navigation.TabHistory
import app.indoorbike.navigation.TabProgress
import app.indoorbike.navigation.TabRides
import app.indoorbike.navigation.TabSettings
import app.indoorbike.navigation.Navigator
import app.indoorbike.navigation.rememberNavigationState
import app.indoorbike.library.WorkoutEntry
import app.indoorbike.library.WorkoutSource
import app.indoorbike.shared.Workout
import app.indoorbike.shared.WorkoutRepository
import app.indoorbike.navigation.RouteCompanion
import app.indoorbike.shared.workout.COMPANION_WORKOUT_ID
import app.indoorbike.shared.workout.FREE_RIDE_WORKOUT_ID
import app.indoorbike.ui.screens.companion.CompanionScreen
import app.indoorbike.shared.workout.simulationRouteId
import app.indoorbike.shared.workout.simulationWorkoutId
import app.indoorbike.ui.ControllerPairingScreen
import app.indoorbike.sync.FitTrackeeUploader
import app.indoorbike.ui.screens.ConnectionsScreen
import app.indoorbike.ui.screens.SimulationSettingsScreen
import app.indoorbike.ui.screens.LibraryScreen
import app.indoorbike.ui.screens.QrScannerScreen
import app.indoorbike.ui.screens.RemoteRepoBrowserScreen
import app.indoorbike.ui.screens.RemoteRouteBrowserScreen
import app.indoorbike.ui.screens.HomeScreen
import app.indoorbike.ui.screens.RepositoriesScreen
import app.indoorbike.ui.screens.RouteLibraryScreen
import app.indoorbike.ui.screens.WorkoutPickerScreen
import app.indoorbike.ui.screens.STARTER_WORKOUT_ID
import app.indoorbike.navigation.RouteLibrary
import app.indoorbike.navigation.RouteQrScanner
import app.indoorbike.navigation.RouteRemoteRepo
import app.indoorbike.navigation.RouteRemoteRoutes
import app.indoorbike.navigation.RouteRepositories
import app.indoorbike.navigation.RouteRouteLibrary
import app.indoorbike.navigation.RouteWorkoutPicker
import app.indoorbike.ui.HistoryTab
import app.indoorbike.ui.HrmPairingScreen
import app.indoorbike.ui.ProgressTab
import app.indoorbike.ui.SettingsTab
import app.indoorbike.ui.TrainerPairingScreen
import app.indoorbike.ui.components.ConnectionToastHost
import app.indoorbike.ui.components.TabBar
import app.indoorbike.ui.components.TabItem
import app.indoorbike.ui.screens.RideScreen
import app.indoorbike.ui.screens.WorkoutDetailScreen
import app.indoorbike.ui.screens.WorkoutOverviewScreen
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceTheme
import app.indoorbike.ui.theme.CadenceType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast

class MainActivity : ComponentActivity() {

    // Activity-scoped — cancelled in onDestroy. Previously a member with no
    // teardown, which leaked the Activity reference on rotation while imports
    // were in flight (the coroutine outlived the dead Activity, posted Toasts
    // against it, and the next onCreate created a fresh leaking scope).
    private val intentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onDestroy() {
        intentScope.cancel()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Orientation is intentionally unlocked: riders can use portrait or
        // landscape on any device. The ride HUD and the rest of the UI adapt to
        // the available space (see RideScreen's BoxWithConstraints HUD), and the
        // activity declares configChanges for orientation so a rotation never
        // tears down or interrupts an in-progress ride.

        enableEdgeToEdge()
        // enableEdgeToEdge turns contrast enforcement on, which paints a
        // translucent scrim behind a three-button navigation bar. The canvas is
        // already dark and the tab bar is a floating pill that deliberately
        // stops short of the bottom edge, so that scrim shows up as a lighter
        // band across the bottom of every screen rather than as protection for
        // anything.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val container = (application as IndoorBikeApp).container

        // Workouts from any external app — file open, share-sheet, deep link —
        // route through the same import pipeline as the in-app file picker.
        handleImportIntent(intent)

        setContent {
            CadenceTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CadenceColors.Bg0
                ) {
                    val initialised by container.workoutLibrary.initialised.collectAsState()
                    val entries by container.workoutLibrary.entries.collectAsState()
                    val workouts = remember(entries) { entries.map { it.workout } }

                    CompositionLocalProvider(
                        LocalAppContainer provides container,
                        LocalWorkouts provides workouts,
                    ) {
                        if (!initialised) {
                            LoadingState()
                        } else {
                            AppNavigator(entries = entries)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleImportIntent(intent)
    }

    /**
     * Reacts to:
     *  - ACTION_VIEW with content:// or file:// URI of application/json
     *  - ACTION_SEND with EXTRA_STREAM
     *  - ACTION_VIEW with `indoorbike://import?url=…` deep link
     *
     * On success surfaces a Toast; on failure the user keeps the app open and
     * sees a Toast with the error reason. We deliberately don't auto-route to
     * the Library screen — the app's just been launched/foregrounded and the
     * user can find the new workout via its name in the regular Rides list.
     */
    private fun handleImportIntent(intent: Intent?) {
        intent ?: return
        val container = (application as IndoorBikeApp).container

        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            else -> null
        }
        // Clear so a config change / process recreation doesn't re-trigger.
        intent.data = null

        if (uri == null) return

        // indoorbike://oauth — the browser handing back a FitTrackee
        // authorization code. Handled here rather than in the Connections
        // screen because the browser may well have outlived it: the app can be
        // killed while the consent page is in front of it, and the code has to
        // be redeemable on a cold start.
        if (uri.scheme.equals("indoorbike", ignoreCase = true) &&
            uri.host.equals("oauth", ignoreCase = true)
        ) {
            intentScope.launch {
                val outcome = container.fitTrackeeUploader.completeAuthorization(uri)
                val msg = when (outcome) {
                    is FitTrackeeUploader.SignIn.Success -> "Connected to FitTrackee"
                    is FitTrackeeUploader.SignIn.Failure -> outcome.message
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
            return
        }

        // indoorbike://import deep link.
        if (uri.scheme.equals("indoorbike", ignoreCase = true) &&
            uri.host.equals("import", ignoreCase = true)
        ) {
            // First try to decode an embedded payload (?data=… form, or `#w=…`
            // tucked inside the URI).
            val embedded = app.indoorbike.library.WorkoutLinkParser
                .extractEmbeddedJson(uri.toString())
            if (embedded != null) {
                intentScope.launch {
                    val result = container.workoutImporter.importJsonText(embedded)
                    onImportResolved(result)
                }
                return
            }

            // Fall back to ?url=… HTTP fetch.
            val remoteUrl = uri.getQueryParameter("url")
            if (remoteUrl.isNullOrBlank()) {
                Toast.makeText(this, "Missing ?url= or ?data= in indoorbike://import link", Toast.LENGTH_SHORT).show()
                return
            }
            intentScope.launch {
                try {
                    // The fetched URL itself may be a share-page URL with the
                    // workout in its hash — try the embedded parser on it too
                    // before doing an actual GET. This makes the editor's QR
                    // and "Show link" outputs work even if a user pastes the
                    // whole share-URL into ?url=…
                    val maybeEmbedded = app.indoorbike.library.WorkoutLinkParser
                        .extractEmbeddedJson(remoteUrl)
                    val text = maybeEmbedded
                        ?: app.indoorbike.library.HttpFetcher.getString(remoteUrl)
                    val result = container.workoutImporter.importJsonText(text)
                    onImportResolved(result)
                } catch (t: Throwable) {
                    Toast.makeText(this@MainActivity, "Couldn't fetch: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        // Local content:// or file:// URI from a share-sheet or file open.
        intentScope.launch {
            val result = container.workoutImporter.importFromUri(uri)
            onImportResolved(result)
        }
    }

    private suspend fun onImportResolved(result: app.indoorbike.library.ImportResult) {
        val container = (application as IndoorBikeApp).container
        when (result) {
            is app.indoorbike.library.ImportResult.Workouts -> {
                container.workoutLibrary.refreshImports()
                val n = result.workouts.size
                Toast.makeText(
                    this,
                    if (n == 1) "Imported \"${result.workouts.first().name}\"" else "Imported $n workouts",
                    Toast.LENGTH_SHORT
                ).show()
            }
            is app.indoorbike.library.ImportResult.Manifest -> {
                // Repos aren't auto-added under the explicit-add model. Point
                // the user at More workouts → Browse so they can pick what
                // they want from the repo's content.
                Toast.makeText(
                    this,
                    "\"${result.manifest.name}\" is a community repository. " +
                        "Open More workouts and browse it to add individual workouts.",
                    Toast.LENGTH_LONG
                ).show()
            }
            is app.indoorbike.library.ImportResult.Failure -> Toast.makeText(
                this, result.message, Toast.LENGTH_LONG
            ).show()
        }
    }
}


/**
 * Switching tabs is a move between peers, not a move deeper, so the tab roots
 * do not push. They do slide, a little, in the direction the rider moved along
 * the bar: choosing a tab to the right brings the new screen in from the
 * right. It is the smallest hint that the four tabs sit side by side, and it
 * agrees with the pill that just slid the same way underneath them.
 *
 * Anything that is not a tab-to-tab move - a detail screen popping back to a
 * tab root - falls through to the ordinary push and pop.
 */
private val TabTransition: Map<String, Any> =
    NavDisplay.transitionSpec { tabTransform(pop = false) } +
        NavDisplay.popTransitionSpec { tabTransform(pop = true) } +
        NavDisplay.predictivePopTransitionSpec { tabTransform(pop = true) }

/**
 * Which tab, if any, an entry is. NavEntry keeps its key to itself and exposes
 * a content key, which by default is the key or its string form, so both are
 * accepted.
 */
private fun tabIndex(entry: Any?): Int {
    val contentKey = (entry as? NavEntry<*>)?.contentKey ?: return -1
    return TOP_LEVEL_ROUTES.keys.indexOfFirst { it == contentKey || it.toString() == contentKey.toString() }
}

private fun AnimatedContentTransitionScope<*>.tabTransform(pop: Boolean): ContentTransform {
    val from = tabIndex(initialState)
    val to = tabIndex(targetState)
    if (from < 0 || to < 0) return if (pop) popTransform() else pushTransform()
    val dir = (to - from).sign
    val slide = tween<IntOffset>(SlateMotion.Base, easing = SlateMotion.Easing)
    val fade = tween<Float>(SlateMotion.Base, easing = SlateMotion.Easing)
    return (slideInHorizontally(slide) { dir * it / 14 } + fadeIn(fade)) togetherWith
        (slideOutHorizontally(slide) { -dir * it / 14 } +
            fadeOut(tween(SlateMotion.Fast, easing = SlateMotion.Easing)))
}

/**
 * Going deeper pushes the new screen in from the trailing edge while the one
 * underneath slips a little the other way and dims; coming back reverses it,
 * the screen underneath returning from where it went. The two offsets are
 * unequal on purpose - the screen on top travels further than the one below -
 * which is what makes the pair read as layers rather than as two panels on a
 * belt. The default was a cross-fade in both directions, which said that the
 * screen changed but not whether the rider went in or came out.
 */
private fun pushTransform(): ContentTransform {
    val slide = tween<IntOffset>(SlateMotion.Base, easing = SlateMotion.Easing)
    val fade = tween<Float>(SlateMotion.Base, easing = SlateMotion.Easing)
    return (slideInHorizontally(slide) { it / 6 } + fadeIn(fade)) togetherWith
        (slideOutHorizontally(slide) { -it / 10 } +
            fadeOut(tween(SlateMotion.Fast, easing = SlateMotion.Easing)))
}

/**
 * [edge] is set when a back gesture is driving the pop frame by frame: the
 * screen then follows the finger off whichever edge it started from, so the
 * rider sees what they are going back to before they commit.
 */
private fun popTransform(edge: Int? = null): ContentTransform {
    val slide = tween<IntOffset>(SlateMotion.Base, easing = SlateMotion.Easing)
    val fade = tween<Float>(SlateMotion.Base, easing = SlateMotion.Easing)
    val towardsLeft = edge == NavigationEvent.EDGE_RIGHT
    return (slideInHorizontally(slide) { if (towardsLeft) it / 10 else -it / 10 } + fadeIn(fade)) togetherWith
        (slideOutHorizontally(slide) {
            when {
                edge == null -> it / 6
                towardsLeft -> -it / 4
                else -> it / 4
            }
        } + fadeOut(fade))
}

private val TOP_LEVEL_ROUTES = mapOf<NavKey, TabItem>(
    TabRides to TabItem("rides", Icons.AutoMirrored.Outlined.DirectionsBike, "Ride"),
    TabHistory to TabItem("history", Icons.Outlined.AccessTime, "History"),
    TabProgress to TabItem("progress", Icons.AutoMirrored.Outlined.TrendingUp, "Progress"),
    TabSettings to TabItem("settings", Icons.Outlined.Settings, "Settings")
)

@OptIn(ExperimentalMaterial3AdaptiveNavigationSuiteApi::class)
@Composable
private fun AppNavigator(entries: List<WorkoutEntry>) {
    val container = LocalAppContainer.current
    val eyebrow = remember { currentDayEyebrow() }
    val workouts = remember(entries) { entries.map { it.workout } }

    if (entries.isEmpty()) {
        EmptyState()
        return
    }

    val navigationState = rememberNavigationState(
        startRoute = TabRides,
        topLevelRoutes = TOP_LEVEL_ROUTES.keys
    )
    val navigator = remember { Navigator(navigationState) }

    val resumerScope = rememberCoroutineScope()
    val resumer = remember(navigator, resumerScope) {
        RideResumer(
            continueRide = { workout, snapshot ->
                // A simulated ride names its route in the workout id but can't
                // carry the route itself through a process death, so it's
                // fetched back from the catalog here. A route that has since
                // gone missing resumes without one, which the session manager
                // handles rather than refusing the resume.
                val route = simulationRouteId(workout.id)
                    ?.let { container.routeCatalog.find(it) }
                container.sessionManager.restoreFromSnapshot(snapshot, workout, route)
                navigator.continueRide(workout.id)
            },
            abandonRide = { container.abandonUnfinishedRide() },
            saveFinishedRide = { workout, snapshot ->
                resumerScope.launch {
                    val sessionId = saveRideToHistory(
                        container = container,
                        workout = workout,
                        startTimeEpoch = snapshot.sessionStartTimeEpochMs,
                        // Older snapshots predate actualRideSeconds and default
                        // it to 0; the planned elapsed is the best stand-in.
                        durationSeconds = snapshot.actualRideSeconds
                            .takeIf { it > 0 } ?: snapshot.elapsedSeconds,
                        telemetry = container.sessionManager.pendingRideTelemetry(snapshot),
                        // From the snapshot when the ride is no longer loaded,
                        // which on this path is the usual case: the app has
                        // just started and the engines are empty.
                        road = container.sessionManager.pendingRideRoad(snapshot),
                    )
                    navigator.navigate(RouteWorkoutOverview(sessionId, fromRide = true))
                }
            },
        )
    }

    val entryProvider = entryProvider<NavKey> {
        entry<TabRides>(metadata = TabTransition) { _ ->
            HomeScreen(
                eyebrow = eyebrow,
                onChooseWorkout = { navigator.navigate(RouteWorkoutPicker) },
                onChooseRoute = { navigator.navigate(RouteRouteLibrary) },
                onStartFreeRide = {
                    // No workout to prepare: the session manager synthesises
                    // one and opens the trainer at 40 % of the rider's FTP.
                    container.sessionManager.prepareFreeRide(
                        ftp = container.userSettings.ftp.value
                    )
                    navigator.navigate(RouteRideScreen(FREE_RIDE_WORKOUT_ID))
                },
                onChooseCompanion = { navigator.navigate(RouteCompanion) },
                onOpenRepositories = { navigator.navigate(RouteRepositories) },
                onOpenRepo = { manifestUrl -> navigator.navigate(RouteRemoteRepo(manifestUrl)) },
                onOpenTrainerPairing = { navigator.navigate(RouteTrainerPairing) },
                onOpenHrmPairing = { navigator.navigate(RouteHrmPairing) },
                onOpenControllerPairing = { navigator.navigate(RouteControllerPairing) },
                onStartFirstRide = {
                    val starter = workouts.firstOrNull { it.id == STARTER_WORKOUT_ID }
                    if (starter != null) {
                        navigator.navigate(RouteWorkoutDetail(starter.id))
                    } else {
                        // The bundled starter can be deleted like any other
                        // workout, so fall back to the picker rather than
                        // making the card a dead tap.
                        navigator.navigate(RouteWorkoutPicker)
                    }
                },
            )
        }
        entry<RouteWorkoutPicker> { _ ->
            WorkoutPickerScreen(
                entries = entries,
                onBack = { navigator.goBack() },
                onPick = { workout -> navigator.navigate(RouteWorkoutDetail(workout.id)) },
                onOpenLibrary = { navigator.navigate(RouteLibrary) },
                onScanQr = { navigator.navigate(RouteQrScanner) },
            )
        }
        entry<RouteRepositories> { _ ->
            RepositoriesScreen(
                onBack = { navigator.goBack() },
                onOpenRepo = { manifestUrl -> navigator.navigate(RouteRemoteRepo(manifestUrl)) },
            )
        }
        entry<RouteLibrary> { _ ->
            LibraryScreen(
                onBack = { navigator.goBack() },
                onScanQr = { navigator.navigate(RouteQrScanner) },
                onOpenRepo = { manifestUrl ->
                    navigator.navigate(RouteRemoteRepo(manifestUrl))
                },
                onOpenRoutes = { manifestUrl ->
                    navigator.navigate(RouteRemoteRoutes(manifestUrl))
                }
            )
        }
        entry<RouteRemoteRepo> { key: RouteRemoteRepo ->
            RemoteRepoBrowserScreen(
                manifestUrl = key.manifestUrl,
                onBack = { navigator.goBack() },
                // replace, not navigate: the two halves of a repository are one
                // stop, so back leaves the repository however many times the
                // rider crossed between them.
                onBrowseRoutes = { navigator.replace(RouteRemoteRoutes(key.manifestUrl)) }
            )
        }
        entry<RouteRemoteRoutes> { key: RouteRemoteRoutes ->
            RemoteRouteBrowserScreen(
                manifestUrl = key.manifestUrl,
                onBack = { navigator.goBack() },
                onBrowseWorkouts = { navigator.replace(RouteRemoteRepo(key.manifestUrl)) }
            )
        }
        entry<RouteRouteLibrary> { _ ->
            RouteLibraryScreen(
                onBack = { navigator.goBack() },
                onStartRoute = { route, resumeFrom ->
                    container.sessionManager.prepareSimulation(
                        route = route,
                        ftp = container.userSettings.ftp.value,
                        // Null when the rider chose the trailhead, or had no
                        // choice to make: either way the road starts at zero.
                        startDistanceMeters = resumeFrom?.distanceMeters ?: 0.0,
                        startElevationGainMeters = resumeFrom?.elevationGainMeters ?: 0.0
                    )
                    navigator.navigate(RouteRideScreen(simulationWorkoutId(route.id)))
                },
                // Routes are found through the same community browse workouts
                // use, so this lands on the repo list rather than a second,
                // routes-only registry the rider would have to learn about.
                onFindMore = { navigator.navigate(RouteLibrary) },
                onPairTrainer = { navigator.navigate(RouteTrainerPairing) }
            )
        }
        entry<RouteQrScanner> { _ ->
            QrScannerScreen(
                onBack = { navigator.goBack() },
                onNavigateToWorkout = { workoutId ->
                    // Replace, not push: pressing Back from the detail page
                    // should land on Library, not bring the camera back.
                    navigator.replace(RouteWorkoutDetail(workoutId))
                }
            )
        }
        entry<RouteWorkoutDetail> { key: RouteWorkoutDetail ->
            val entry = entries.find { it.workout.id == key.workoutId }
            val workout = entry?.workout
            if (entry != null && workout != null) {
                val scope = rememberCoroutineScope()
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
                    onNavigateToPairing = { navigator.navigate(RouteTrainerPairing) },
                    isImported = entry.source is WorkoutSource.Imported,
                    onRename = { newName ->
                        scope.launch {
                            container.workoutImporter.renameUserWorkout(workout.id, newName)
                            container.workoutLibrary.refreshImportsAwait()
                        }
                    },
                    onDelete = {
                        scope.launch {
                            container.workoutImporter.deleteUserWorkout(workout.id)
                            container.workoutLibrary.refreshImportsAwait()
                            navigator.goBack()
                        }
                    }
                )
            } else {
                LaunchedEffect(Unit) { navigator.goBack() }
            }
        }
        entry<TabHistory>(metadata = TabTransition) { _ ->
            HistoryTab(
                onNavigateToOverview = { sessionId ->
                    navigator.navigate(RouteWorkoutOverview(sessionId, fromRide = false))
                },
                onNavigateToRides = { navigator.navigate(TabRides) },
                onRideAgain = { workoutId ->
                    navigator.navigate(RouteWorkoutDetail(workoutId))
                }
            )
        }
        entry<TabProgress>(metadata = TabTransition) { _ ->
            ProgressTab(
                onNavigateToRides = { navigator.navigate(TabRides) }
            )
        }
        entry<TabSettings>(metadata = TabTransition) { _ ->
            SettingsTab(
                onNavigateToPairing = { navigator.navigate(RouteTrainerPairing) },
                onNavigateToHrmPairing = { navigator.navigate(RouteHrmPairing) },
                onNavigateToControllerPairing = { navigator.navigate(RouteControllerPairing) },
                onNavigateToConnections = { navigator.navigate(RouteConnections) },
                onNavigateToSimulationSettings = { navigator.navigate(RouteSimulationSettings) }
            )
        }
        entry<RouteConnections> { _ ->
            ConnectionsScreen(onBack = { navigator.goBack() })
        }
        entry<RouteSimulationSettings> { _ ->
            SimulationSettingsScreen(onBack = { navigator.goBack() })
        }
        entry<RouteTrainerPairing> { _ ->
            TrainerPairingScreen(onBack = { navigator.goBack() })
        }
        entry<RouteHrmPairing> { _ ->
            HrmPairingScreen(onBack = { navigator.goBack() })
        }
        entry<RouteControllerPairing> { _ ->
            ControllerPairingScreen(onBack = { navigator.goBack() })
        }
        entry<RouteCompanion> { _ ->
            CompanionScreen(
                onBack = { navigator.goBack() },
                // `replace` rather than `navigate`: once the companion is
                // driving, backing out to a pairing screen for a ride already
                // under way would be a dead end.
                onRideReady = {
                    navigator.replace(RouteRideScreen(COMPANION_WORKOUT_ID))
                },
            )
        }
        entry<RouteRideScreen> { key: RouteRideScreen ->
            val wasCompanion = key.workoutId == COMPANION_WORKOUT_ID
            RideScreen(
                // Backing out of a companion ride is leaving the ride, not
                // leaving companion mode. `replace` rather than a push, because
                // the pairing screen handed this one its place on the stack and
                // is taking it back; pressing back again still reaches home.
                onBack = {
                    if (wasCompanion) navigator.replace(RouteCompanion) else navigator.goBack()
                },
                onNavigateToOverview = { sessionId ->
                    navigator.replace(
                        RouteWorkoutOverview(
                            sessionId,
                            fromRide = true,
                            fromCompanion = wasCompanion,
                        )
                    )
                },
                // The session's ending was shown on the ride screen itself, and
                // its rides are already in history. What the rider wants next is
                // the pairing screen, ready for the game to come back.
                onCompanionSessionDone = { navigator.replace(RouteCompanion) },
            )
        }
        entry<RouteWorkoutOverview> { key: RouteWorkoutOverview ->
            WorkoutOverviewScreen(
                sessionId = key.sessionId,
                fromRide = key.fromRide,
                workoutList = workouts,
                onBack = { navigator.goBack() },
                onDone = {
                    // Back to the root first either way, so the summary never
                    // stays on the stack: a rider who rides again and presses
                    // back should reach the home screen, not the last ride's
                    // numbers.
                    navigator.popToRoot()
                    // A companion ride ends with a game still running on the
                    // other device. Landing on the home screen would make the
                    // rider get off the bike to tap Companion again before that
                    // game could start anything, so go and wait for it instead.
                    if (key.fromCompanion) navigator.navigate(RouteCompanion)
                }
            )
        }
    }

    CompositionLocalProvider(LocalRideResumer provides resumer) {
        // The navigation area is only shown on tab roots (depth == 1); detail
        // screens stay full-screen, same as before. On a tab root it adapts to
        // window width: a bottom bar on phones, a side rail on tablets /
        // unfolded foldables (>= 600dp). NavigationSuiteScaffold picks the
        // chrome; we just feed it the layout type.
        val atTabRoot = navigationState.topLevelRoute in TOP_LEVEL_ROUTES.keys &&
            navigationState.backStacks[navigationState.topLevelRoute]?.size == 1
        val widthDp = LocalConfiguration.current.screenWidthDp
        // Slate's tab bar is a floating pill inset from the screen edges, which
        // is not a shape a Material NavigationBar can take, so on phones we lay
        // it out ourselves under the content and tell the suite there is no
        // navigation chrome. The rail is still Material: a vertical pill would
        // be a different component, not the same one turned sideways.
        val useSlateTabBar = atTabRoot && widthDp < 600
        val navLayoutType = when {
            !atTabRoot || useSlateTabBar -> NavigationSuiteType.None
            else -> NavigationSuiteType.NavigationRail
        }
        val activeTabId = TOP_LEVEL_ROUTES[navigationState.topLevelRoute]?.id ?: "rides"

        // Color computations are @Composable, so they must be hoisted out of the
        // (non-composable) navigationSuiteItems builder lambda below.
        val navItemColors = NavigationSuiteDefaults.itemColors(
            navigationRailItemColors = NavigationRailItemDefaults.colors(
                selectedIconColor = CadenceColors.OnBrand,
                selectedTextColor = CadenceColors.Fg1,
                unselectedIconColor = CadenceColors.Fg2,
                unselectedTextColor = CadenceColors.Fg2,
                indicatorColor = CadenceColors.Fg1,
            ),
        )
        val navSuiteColors = NavigationSuiteDefaults.colors(
            navigationRailContainerColor = CadenceColors.Bg0,
        )

        NavigationSuiteScaffold(
            layoutType = navLayoutType,
            containerColor = CadenceColors.Bg0,
            navigationSuiteColors = navSuiteColors,
            navigationSuiteItems = {
                TOP_LEVEL_ROUTES.forEach { (route, tab) ->
                    item(
                        selected = activeTabId == tab.id,
                        onClick = { navigator.navigate(route) },
                        icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = navItemColors,
                    )
                }
            },
        ) {
            // The tab bar floats over the content rather than sitting beside it
            // in a column. A pill inset from three edges only reads as floating
            // if the list runs underneath it; boxed off above it, it is just a
            // wall with rounded corners and a strip of dead canvas below.
            var tabBarHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            Box(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalTabBarHeight provides tabBarHeight) {
                    NavDisplay(
                        entries = navigationState.toDecoratedEntries(entryProvider),
                        // Push, pop and the gesture-driven pop: see the three
                        // transforms above the navigator.
                        transitionSpec = { pushTransform() },
                        popTransitionSpec = { popTransform() },
                        predictivePopTransitionSpec = { edge -> popTransform(edge) },
                        onBack = { navigator.goBack() }
                    )
                }
                // Transient "device connected" banner, overlaid at the top so
                // it shows on any screen (including silent auto-reconnects).
                ConnectionToastHost(modifier = Modifier.align(Alignment.TopCenter))
                // Now that it floats, it leaves the way it arrived - downward,
                // off the bottom edge - rather than collapsing a row that no
                // longer exists.
                AnimatedVisibility(
                    visible = useSlateTabBar,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(
                        animationSpec = tween(SlateMotion.Base, easing = SlateMotion.Easing),
                        initialOffsetY = { it }
                    ) + fadeIn(tween(SlateMotion.Base, easing = SlateMotion.Easing)),
                    exit = slideOutVertically(
                        animationSpec = tween(SlateMotion.Base, easing = SlateMotion.Easing),
                        targetOffsetY = { it }
                    ) + fadeOut(tween(SlateMotion.Base, easing = SlateMotion.Easing)),
                ) {
                    TabBar(
                        active = activeTabId,
                        tabs = TOP_LEVEL_ROUTES.values.toList(),
                        onTabSelected = { id ->
                            TOP_LEVEL_ROUTES.entries
                                .firstOrNull { it.value.id == id }
                                ?.let { navigator.navigate(it.key) }
                        },
                        // Published so a tab root's list knows how much of its
                        // own tail the bar is covering.
                        modifier = Modifier.onSizeChanged {
                            val measured = with(density) { it.height.toDp() }
                            if (measured > tabBarHeight) tabBarHeight = measured
                        }
                    )
                }
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
