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

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.LocalAppContainer
import app.indoorbike.library.RouteFetchResult
import app.indoorbike.route.RouteImportResult
import app.indoorbike.shared.repo.RepositoryManifest
import app.indoorbike.shared.repo.RouteEntry
import app.indoorbike.shared.route.RouteEstimate
import app.indoorbike.shared.workout.formatDurationPretty
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.FilterChip
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.RouteProfileSpark
import app.indoorbike.ui.components.RouteShapeThumb
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Browses one repository's routes, the route-side twin of
 * [RemoteRepoBrowserScreen].
 *
 * The important difference is what a card is made of. A workout card renders
 * the workout itself, because the browse already downloaded it. A route card
 * renders a *listing*: nothing here has touched a GPX, and the whole screen
 * costs one manifest plus one bundle however many routes it holds. Geometry is
 * downloaded once, for the one route the rider actually adds.
 *
 * The duration on each card is this rider's, computed from their FTP and
 * weight against the route's gradient histogram. See [RouteEstimate] for why
 * that needs more than distance and total ascent.
 */
@Composable
fun RemoteRouteBrowserScreen(
    manifestUrl: String,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val ftp by container.userSettings.ftp.collectAsState()
    val riderKg by container.userSettings.weightKg.collectAsState()
    val bikeKg by container.userSettings.bikeWeightKg.collectAsState()
    val installed by container.routeCatalog.routes.collectAsState()

    var fetchState by remember(manifestUrl) { mutableStateOf<RouteFetchState>(RouteFetchState.Loading) }
    var selectedTag by remember { mutableStateOf(ALL_TAGS) }
    // Ids added during this browse. The catalogue is the real answer, but it
    // reloads asynchronously, and a card that keeps offering Add for a second
    // after the rider tapped it reads as a failure.
    var justAdded by remember(manifestUrl) { mutableStateOf(setOf<String>()) }
    var addingId by remember(manifestUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(manifestUrl) {
        fetchState = RouteFetchState.Loading
        fetchState = when (val result = container.repositoryManager.fetchRepoRoutes(manifestUrl)) {
            is RouteFetchResult.Ok -> {
                // Opening the repo is what "looking" means, so this is the one
                // place a new badge is cleared. A refresh never does it, or
                // nothing would stay new long enough to be noticed.
                container.followedRepositories.markSeen(result.manifest.id)
                RouteFetchState.Loaded(result.manifest, result.routes)
            }
            is RouteFetchResult.Failure -> RouteFetchState.Failed(result.message)
        }
    }

    val installedIds = remember(installed, justAdded) {
        installed.mapTo(mutableSetOf()) { it.id } + justAdded
    }

    Column(Modifier.fillMaxSize().background(CadenceColors.Bg0)) {
        AppBar(
            title = (fetchState as? RouteFetchState.Loaded)?.manifest?.name ?: "Routes",
            eyebrow = "COMMUNITY",
            leading = {
                IconBtn(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack)
            },
        )

        when (val state = fetchState) {
            is RouteFetchState.Loading -> CentredMessage {
                CircularProgressIndicator(color = CadenceColors.Brand500)
            }

            is RouteFetchState.Failed -> CentredMessage {
                Text(
                    state.message,
                    style = CadenceType.body,
                    color = CadenceColors.Fg2,
                )
            }

            is RouteFetchState.Loaded -> {
                if (state.routes.isEmpty()) {
                    CentredMessage {
                        Text(
                            "This repository does not publish any routes yet.",
                            style = CadenceType.body,
                            color = CadenceColors.Fg2,
                        )
                    }
                    return@Column
                }

                val tags = remember(state.routes) {
                    listOf(ALL_TAGS) + state.routes.flatMap { it.tags }.distinct().sorted()
                }
                if (tags.none { it == selectedTag }) selectedTag = ALL_TAGS

                val visible = remember(state.routes, selectedTag) {
                    if (selectedTag == ALL_TAGS) state.routes
                    else state.routes.filter { selectedTag in it.tags }
                }

                if (tags.size > 1) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                    ) {
                        tags.forEach { tag ->
                            FilterChip(tag, tag == selectedTag) { selectedTag = tag }
                        }
                    }
                }

                LazyColumn(
                    contentPadding = PaddingValues(Spacing.s4),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s3),
                ) {
                    items(visible, key = { it.id }) { entry ->
                        RouteEntryCard(
                            entry = entry,
                            estimateSeconds = RouteEstimate.secondsFor(entry, ftp, riderKg, bikeKg),
                            added = entry.id in installedIds,
                            adding = addingId == entry.id,
                            onAdd = {
                                addingId = entry.id
                                scope.launch {
                                    val result = container.routeImporter.addFromRepo(
                                        entry = entry,
                                        repoId = state.manifest.id,
                                        repoName = state.manifest.name,
                                    )
                                    addingId = null
                                    when (result) {
                                        is RouteImportResult.Added -> {
                                            justAdded = justAdded + entry.id
                                            container.routeCatalog.refresh()
                                            Toast.makeText(
                                                context,
                                                "Added ${result.track.name}",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                        is RouteImportResult.Failure -> Toast.makeText(
                                            context,
                                            result.message,
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteEntryCard(
    entry: RouteEntry,
    estimateSeconds: Long?,
    added: Boolean,
    adding: Boolean,
    onAdd: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.lg))
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.lg))
            .padding(Spacing.s4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RouteShapeThumb(
                entry.shape,
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(Radii.sm))
                    .background(CadenceColors.Bg2),
            )
            Spacer(Modifier.width(Spacing.s3))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = CadenceType.bodyStrong,
                    color = CadenceColors.Fg1,
                    fontWeight = FontWeight.SemiBold,
                )
                entry.place?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = CadenceType.small, color = CadenceColors.Fg3)
                }
            }
            Spacer(Modifier.width(Spacing.s2))
            when {
                adding -> CircularProgressIndicator(
                    color = CadenceColors.Brand500,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                added -> Icon(
                    Icons.Outlined.Check,
                    contentDescription = "Added",
                    tint = CadenceColors.Success,
                    modifier = Modifier.size(22.dp),
                )
                else -> IconBtn(
                    Icons.Outlined.CloudDownload,
                    contentDescription = "Add ${entry.name}",
                    onClick = onAdd,
                )
            }
        }

        if (entry.profile.size >= 2) {
            Spacer(Modifier.height(Spacing.s3))
            RouteProfileSpark(entry.profile, Modifier.fillMaxWidth().height(44.dp))
        }

        Spacer(Modifier.height(Spacing.s3))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s5)) {
            RouteStat("DISTANCE", formatKm(entry.distanceMeters))
            RouteStat("CLIMBING", "${entry.ascentMeters.roundToInt()} m")
            // No weight or FTP in Settings means no honest answer, and the
            // stat is dropped rather than filled with a guessed rider.
            RouteStat("TIME", estimateSeconds?.let { formatDurationPretty(it.toInt()) } ?: "--")
        }

        entry.description?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(Spacing.s3))
            Text(it, style = CadenceType.small, color = CadenceColors.Fg2)
        }

        // Route data is usually licensed, and the credit is a condition of
        // using it rather than a detail. It goes on the card, not behind a tap.
        creditLine(entry)?.let {
            Spacer(Modifier.height(Spacing.s2))
            Text(it, style = CadenceType.micro, color = CadenceColors.Fg4)
        }
    }
}

@Composable
private fun RouteStat(label: String, value: String) {
    Column {
        Text(label, style = CadenceType.eyebrow, color = CadenceColors.Fg4, fontSize = 9.sp)
        Text(value, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
    }
}

@Composable
private fun CentredMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(Spacing.s6), contentAlignment = Alignment.Center) {
        content()
    }
}

private fun creditLine(entry: RouteEntry): String? {
    val attribution = entry.attribution ?: return null
    val source = attribution.source?.takeIf { it.isNotBlank() }
    val licence = attribution.licence?.takeIf { it.isNotBlank() }
    return when {
        source != null && licence != null -> "$source ($licence)"
        source != null -> source
        licence != null -> licence
        else -> null
    }
}

internal fun formatKm(meters: Double): String {
    val km = meters / 1000.0
    return if (km >= 100) "${km.roundToInt()} km" else "${(km * 10).roundToInt() / 10.0} km"
}

private const val ALL_TAGS = "All"

private sealed class RouteFetchState {
    data object Loading : RouteFetchState()
    data class Loaded(val manifest: RepositoryManifest, val routes: List<RouteEntry>) : RouteFetchState()
    data class Failed(val message: String) : RouteFetchState()
}
