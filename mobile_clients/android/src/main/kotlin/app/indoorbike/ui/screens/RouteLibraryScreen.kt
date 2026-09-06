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
package app.indoorbike.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.LocalAppContainer
import app.indoorbike.route.RouteSource
import app.indoorbike.shared.route.RouteEstimate
import app.indoorbike.shared.route.RouteStats
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.shared.workout.formatDurationPretty
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import app.indoorbike.shared.simulation.RouteProgress
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.rememberCollapsingHeaderState
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.DestructiveBtn
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.RouteProfileSpark
import app.indoorbike.ui.components.RouteShapeThumb
import app.indoorbike.ui.components.SecondaryBtn
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * The route selector: every road the rider has, with what each one will cost
 * them tonight.
 *
 * The estimate is the reason this screen is not just a list of names. A rider
 * choosing between routes is really choosing how long they are about to be on
 * the bike, and distance alone does not answer that - 20 km of Ventoux and
 * 20 km of valley floor are not the same evening. So each row carries the
 * duration for *this* rider, computed from their FTP and weight the same way
 * the community browser does it, using the same physics the ride will use.
 *
 * Unlike [RemoteRouteBrowserScreen], everything here is already on the device,
 * so the numbers come from the parsed [RouteTrack] rather than from a
 * publisher listing. [RouteStats.entryFor] is what keeps the two agreeing: a
 * route added from a repository and the same route imported from a file
 * describe themselves identically.
 */
@Composable
fun RouteLibraryScreen(
    onBack: () -> Unit,
    /**
     * Ride [RouteTrack], carrying on from [RouteProgress] when the rider chose
     * to. Null means from the trailhead.
     */
    onStartRoute: (RouteTrack, RouteProgress?) -> Unit,
    onFindMore: () -> Unit,
    onPairTrainer: () -> Unit = {},
) {
    val container = LocalAppContainer.current
    val routes by container.routeCatalog.routes.collectAsState()
    val initialised by container.routeCatalog.initialised.collectAsState()
    val ftp by container.userSettings.ftp.collectAsState()
    val riderKg by container.userSettings.weightKg.collectAsState()
    val bikeKg by container.userSettings.bikeWeightKg.collectAsState()

    // Simulation is trainer-gated for the same reason the rides list gates it:
    // with nothing to feel the gradient through, the mode is a map that
    // scrolls on its own. Tapping a route without a trainer goes to pairing
    // rather than into a ride that cannot do anything.
    val connectionState by container.trainerManager.connectionState.collectAsState()
    val isMockTrainer by container.trainerManager.isMockTrainer.collectAsState()
    val isTrainerLive = connectionState == TrainerConnectionState.Connected || isMockTrainer

    // Deriving a listing entry per route is a walk of the gradient profile, so
    // it is remembered against the catalogue rather than redone on every
    // recomposition of a scrolling list.
    val entries = remember(routes) {
        routes.associate { it.id to RouteStats.entryFor(it, gpxUrl = "local") }
    }

    // Where the rider stopped last time, per route. Read once on entry rather
    // than watched: nothing writes it while this screen is up, because writing
    // it is what ending a ride does.
    var progress by remember { mutableStateOf<Map<String, RouteProgress>>(emptyMap()) }
    LaunchedEffect(routes) { progress = container.routeProgressStore.read() }

    // Set when a route with something to carry on from is tapped. The choice is
    // always the rider's: a long road ridden in pieces and the same road ridden
    // again from the start are both ordinary things to want.
    var choosingStart by remember { mutableStateOf<Pair<RouteTrack, RouteProgress>?>(null) }

    // Set by a long press. Only ever an imported route: a bundled one has no
    // file of its own to remove.
    var removing by remember { mutableStateOf<RouteTrack?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    removing?.let { route ->
        RemoveRouteDialog(
            route = route,
            hasProgress = progress.containsKey(route.id),
            onDismiss = { removing = null },
            onRemove = {
                removing = null
                scope.launch {
                    val ok = container.routeImporter.deleteUserRoute(route.id)
                    // The place on the road goes with the road. Left behind, it
                    // would silently resume a re-added route part-way along a
                    // ride the rider had decided to be rid of.
                    container.routeProgressStore.clear(route.id)
                    progress = progress - route.id
                    container.routeCatalog.refresh()
                    if (!ok) {
                        Toast.makeText(
                            context,
                            "Couldn't remove \"${route.name}\"",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            },
        )
    }

    choosingStart?.let { (route, saved) ->
        ResumeRouteDialog(
            route = route,
            progress = saved,
            onDismiss = { choosingStart = null },
            onFromStart = {
                choosingStart = null
                onStartRoute(route, null)
            },
            onContinue = {
                choosingStart = null
                onStartRoute(route, saved)
            },
        )
    }

    val header = rememberCollapsingHeaderState()
    Column(
        Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
            .nestedScroll(header.nestedScrollConnection)
    ) {
        AppBar(
            header = header,
            title = "Routes",
            eyebrow = "Simulation",
            leading = { IconBtn(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack) },
        )

        if (routes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(Spacing.s6), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (initialised) "No routes yet." else "Loading routes…",
                        style = CadenceType.bodyStrong,
                        color = CadenceColors.Fg1,
                    )
                    if (initialised) {
                        Spacer(Modifier.height(Spacing.s2))
                        Text(
                            "Add a road from a community repository and the trainer will ride its hills.",
                            style = CadenceType.small,
                            color = CadenceColors.Fg3,
                        )
                        Spacer(Modifier.height(Spacing.s4))
                        SecondaryBtn("Find routes", onClick = onFindMore)
                    }
                }
            }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            items(routes, key = { it.id }) { route ->
                val entry = entries[route.id]
                InstalledRouteCard(
                    route = route,
                    estimateSeconds = entry?.let {
                        RouteEstimate.secondsFor(it, ftp, riderKg, bikeKg)
                    },
                    profile = entry?.profile.orEmpty(),
                    shape = entry?.shape,
                    source = container.routeCatalog.sourceOf(route.id),
                    isTrainerLive = isTrainerLive,
                    progress = progress[route.id],
                    onLongClick = {
                        // A bundled route lives in the APK, so there is nothing
                        // to delete. Saying so beats the same gesture doing
                        // nothing on one card and working on the one above it.
                        if (container.routeCatalog.sourceOf(route.id) is RouteSource.Bundled) {
                            Toast.makeText(
                                context,
                                "\"${route.name}\" comes with the app and can't be removed.",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            removing = route
                        }
                    },
                    onClick = {
                        val saved = progress[route.id]
                        when {
                            !isTrainerLive -> onPairTrainer()
                            saved != null -> choosingStart = route to saved
                            else -> onStartRoute(route, null)
                        }
                    },
                )
            }
            item(key = "find-more") {
                Spacer(Modifier.height(Spacing.s2))
                SecondaryBtn("Find more routes", onClick = onFindMore, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InstalledRouteCard(
    route: RouteTrack,
    estimateSeconds: Long?,
    profile: List<Double>,
    shape: String?,
    source: RouteSource,
    isTrainerLive: Boolean,
    /** Set when this road was left part-ridden; see [RouteProgress]. */
    progress: RouteProgress?,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val cardShape = RoundedCornerShape(Radii.lg)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(CadenceColors.Bg1)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(Spacing.s4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RouteShapeThumb(
                shape,
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(Radii.sm))
                    .background(CadenceColors.Bg2),
            )
            Spacer(Modifier.width(Spacing.s3))
            Column(Modifier.weight(1f)) {
                Text(
                    source.label,
                    style = CadenceType.eyebrow,
                    color = CadenceColors.Fg4,
                    fontSize = 9.sp,
                )
                Text(
                    route.name,
                    style = CadenceType.bodyStrong,
                    color = CadenceColors.Fg1,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text("›", style = CadenceType.h2, color = CadenceColors.Fg1)
        }

        if (profile.size >= 2) {
            Spacer(Modifier.height(Spacing.s3))
            RouteProfileSpark(profile, Modifier.fillMaxWidth().height(40.dp))
        }

        Spacer(Modifier.height(Spacing.s3))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s5)) {
            RouteLibraryStat("Distance", formatKm(route.totalDistanceMeters))
            RouteLibraryStat("Climbing", "${route.totalAscentMeters.roundToInt()} m")
            RouteLibraryStat(
                "Time",
                estimateSeconds?.let { formatDurationPretty(it.toInt()) } ?: "--",
            )
        }

        progress?.let { saved ->
            val total = route.totalDistanceMeters
            val done = saved.distanceMeters.coerceIn(0.0, total)
            Spacer(Modifier.height(Spacing.s3))
            RouteProgressBar(fraction = if (total > 0) (done / total).toFloat() else 0f)
            Spacer(Modifier.height(Spacing.s2))
            Text(
                "${formatKm(done)} ridden - tap to carry on or start again",
                style = CadenceType.tick,
                color = CadenceColors.Fg2,
            )
        }

        if (!isTrainerLive) {
            Spacer(Modifier.height(Spacing.s2))
            Text(
                "Pair a trainer to feel this route's hills.",
                style = CadenceType.tick,
                color = CadenceColors.Fg3,
            )
        }

        // A licensed route has to keep showing its credit wherever it appears,
        // not only in the browser it was added from.
        source.creditLine?.let {
            Spacer(Modifier.height(Spacing.s2))
            Text(it, style = CadenceType.micro, color = CadenceColors.Fg4)
        }
    }
}

@Composable
private fun RouteLibraryStat(label: String, value: String) {
    Column {
        Text(label, style = CadenceType.eyebrow, color = CadenceColors.Fg4, fontSize = 9.sp)
        Text(value, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
    }
}

/**
 * Where to start a route the rider has already been part-way along.
 *
 * Continue leads because it is why the mark was kept, but neither is the
 * dangerous choice: starting again loses only a saved position, and the ride
 * that earned it is already in the rider's history either way.
 */
@Composable
private fun ResumeRouteDialog(
    route: RouteTrack,
    progress: RouteProgress,
    onDismiss: () -> Unit,
    onFromStart: () -> Unit,
    onContinue: () -> Unit,
) {
    val total = route.totalDistanceMeters
    val done = progress.distanceMeters.coerceIn(0.0, total)
    val remaining = (total - done).coerceAtLeast(0.0)
    val percent = if (total > 0) ((done / total) * 100).roundToInt() else 0

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(Radii.lg))
                .background(CadenceColors.Bg1)
                .padding(Spacing.s6),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4),
        ) {
            Text("Pick up where you left off?", style = CadenceType.h2, color = CadenceColors.Fg1)
            Text(
                "You rode ${formatKm(done)} of ${route.name} last time, " +
                    "and ${formatKm(remaining)} is left.",
                style = CadenceType.body,
                color = CadenceColors.Fg2,
            )
            // The bar is the sentence again as a picture: on a road this long
            // the difference between a third and two thirds is the decision.
            RouteProgressBar(fraction = if (total > 0) (done / total).toFloat() else 0f)
            // Stacked, not side by side. These are two equal ways to ride the
            // road rather than a cancel and a confirm, and the pill buttons
            // carry 28 dp of padding a side, which at half a dialog's width
            // leaves too little room for the labels.
            PrimaryBtn(
                text = "Continue",
                leadingIcon = null,
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryBtn(
                text = "From the start",
                onClick = onFromStart,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Continuing starts you at $percent % along the road. Either way, " +
                    "the ride you already did stays in your history.",
                style = CadenceType.micro,
                color = CadenceColors.Fg4,
            )
        }
    }
}

/** The strip under the numbers. Bg2 track, Fg1 fill: the ZoneBar's language without its colours. */
@Composable
private fun RouteProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(CadenceColors.Bg2),
    ) {
        val safe = fraction.coerceIn(0f, 1f)
        if (safe > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(safe)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(CadenceColors.Fg1),
            )
        }
    }
}

/**
 * Confirmation for a long press.
 *
 * A dialog rather than the delete-and-undo the editor prefers, because this is
 * not a form being cleared: the file only comes back over the network, on
 * whatever connection the rider is on, and it takes their place on the road
 * with it. The long press is a deliberate gesture, so the question is not in
 * the way of ordinary use.
 */
@Composable
private fun RemoveRouteDialog(
    route: RouteTrack,
    hasProgress: Boolean,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(Radii.lg))
                .background(CadenceColors.Bg1)
                .padding(Spacing.s6),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4),
        ) {
            Text("Remove this route?", style = CadenceType.h2, color = CadenceColors.Fg1)
            Text(
                buildString {
                    append("\"")
                    append(route.name)
                    append("\" will be deleted from this device. ")
                    if (hasProgress) append("Where you got to on it goes too. ")
                    append("You can add it again from the repository it came from.")
                },
                style = CadenceType.body,
                color = CadenceColors.Fg2,
            )
            DestructiveBtn(text = "Remove", onClick = onRemove, modifier = Modifier.fillMaxWidth())
            SecondaryBtn(text = "Keep it", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            // Rides already saved are history, and history is not the route's
            // to take with it. Worth saying: the two are easy to conflate.
            Text(
                "Rides you have already done on it stay in your history.",
                style = CadenceType.micro,
                color = CadenceColors.Fg4,
            )
        }
    }
}
