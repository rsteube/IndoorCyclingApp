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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.IconBtn
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
    onStartRoute: (RouteTrack) -> Unit,
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

    Column(Modifier.fillMaxSize().background(CadenceColors.Bg0)) {
        AppBar(
            title = "Routes",
            eyebrow = "SIMULATION",
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
                    onClick = { if (isTrainerLive) onStartRoute(route) else onPairTrainer() },
                )
            }
            item(key = "find-more") {
                Spacer(Modifier.height(Spacing.s2))
                SecondaryBtn("Find more routes", onClick = onFindMore, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun InstalledRouteCard(
    route: RouteTrack,
    estimateSeconds: Long?,
    profile: List<Double>,
    shape: String?,
    source: RouteSource,
    isTrainerLive: Boolean,
    onClick: () -> Unit,
) {
    val cardShape = RoundedCornerShape(Radii.lg)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, cardShape)
            .clickable(onClick = onClick)
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
            Text("›", style = CadenceType.h2, color = CadenceColors.Brand500)
        }

        if (profile.size >= 2) {
            Spacer(Modifier.height(Spacing.s3))
            RouteProfileSpark(profile, Modifier.fillMaxWidth().height(40.dp))
        }

        Spacer(Modifier.height(Spacing.s3))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s5)) {
            RouteLibraryStat("DISTANCE", formatKm(route.totalDistanceMeters))
            RouteLibraryStat("CLIMBING", "${route.totalAscentMeters.roundToInt()} m")
            RouteLibraryStat(
                "TIME",
                estimateSeconds?.let { formatDurationPretty(it.toInt()) } ?: "--",
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
