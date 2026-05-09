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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.LocalAppContainer
import app.indoorbike.library.RepoFetchResult
import app.indoorbike.library.WorkoutSource
import app.indoorbike.shared.Workout
import app.indoorbike.shared.repo.RepositoryManifest
import app.indoorbike.shared.workout.WorkoutSummary
import app.indoorbike.shared.workout.formatDurationPretty
import app.indoorbike.shared.workout.toSummary
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.FilterChip
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.ZoneBar
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.workout.zoneSegments
import kotlinx.coroutines.launch

/**
 * Remote-repo browser. Fetches a single repository's manifest plus all of its
 * bundles on entry and renders the resulting workouts as add-able cards. Tag
 * filters mirror the Rides list. Each card shows whether the workout is
 * already in the rider's local library; tapping Add copies it into the
 * imported store, recording the source repo for attribution.
 *
 * Stateless from the persistence layer's point of view: nothing about this
 * repo lives on disk while the user browses, so closing the screen costs at
 * most the in-memory list.
 */
@Composable
fun RemoteRepoBrowserScreen(
    manifestUrl: String,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val ftp by container.userSettings.ftp.collectAsState()
    val entries by container.workoutLibrary.entries.collectAsState()
    // Imported = "in your library". Bundled is excluded so a repo workout that
    // happens to share an id with the bundled starter doesn't read as already
    // added (it isn't — adding it would create a local copy).
    val addedIds = remember(entries) {
        entries.filter { it.source is WorkoutSource.Imported }
            .mapTo(mutableSetOf()) { it.workout.id }
    }

    var fetchState by remember(manifestUrl) {
        mutableStateOf<FetchState>(FetchState.Loading)
    }
    var selectedTag by remember { mutableStateOf("All") }
    var selectedDuration by remember { mutableStateOf(DurationBand.AnyDuration) }

    // Detail overlay: when set, the screen flips from list to a full-screen
    // WorkoutDetailScreen rendering for that workout. Stays inside this nav
    // entry so back press goes list → detail → close, not detail → Library.
    var detailWorkout by remember(manifestUrl) { mutableStateOf<Workout?>(null) }

    LaunchedEffect(manifestUrl) {
        fetchState = FetchState.Loading
        fetchState = when (val result = container.repositoryManager.fetchRepoWorkouts(manifestUrl)) {
            is RepoFetchResult.Ok -> FetchState.Loaded(
                manifest = result.manifest,
                summaries = result.workouts.map { it.toSummary(ftp) }
            )
            is RepoFetchResult.Failure -> FetchState.Failed(result.message)
        }
    }

    val handleAdd: (Workout, RepositoryManifest) -> Unit = { workout, manifest ->
        scope.launch {
            container.workoutImporter.saveWorkoutFromRepo(
                workout = workout,
                repoId = manifest.id,
                repoName = manifest.name,
            )
            container.workoutLibrary.refreshImportsAwait()
            Toast.makeText(
                context,
                "Added \"${workout.name}\" to your library",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Detail overlay short-circuits the list view. The BackHandler intercepts
    // system back so the rider goes list → detail → list, then list → Library.
    val loadedManifest = (fetchState as? FetchState.Loaded)?.manifest
    if (detailWorkout != null && loadedManifest != null) {
        BackHandler { detailWorkout = null }
        WorkoutDetailScreen(
            workout = detailWorkout!!,
            onBack = { detailWorkout = null },
            mode = WorkoutDetailMode.RemoteAdd(
                isAlreadyAdded = detailWorkout!!.id in addedIds,
                // Stay on the detail screen after Add — the CTA flips to the
                // green "Added" pill so the rider sees the action took. They
                // tap back when they're done browsing the workout.
                onAdd = { handleAdd(detailWorkout!!, loadedManifest) },
            ),
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0)) {
        val title = (fetchState as? FetchState.Loaded)?.manifest?.name ?: "Loading…"
        val eyebrow = (fetchState as? FetchState.Loaded)?.manifest?.author?.uppercase()
        AppBar(
            title = title,
            eyebrow = eyebrow ?: "REMOTE REPOSITORY",
            leading = {
                IconBtn(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack
                )
            }
        )

        when (val state = fetchState) {
            FetchState.Loading -> LoadingState()
            is FetchState.Failed -> ErrorState(
                message = state.message,
                onRetry = {
                    scope.launch {
                        fetchState = FetchState.Loading
                        fetchState = when (val result = container.repositoryManager.fetchRepoWorkouts(manifestUrl)) {
                            is RepoFetchResult.Ok -> FetchState.Loaded(
                                manifest = result.manifest,
                                summaries = result.workouts.map { it.toSummary(ftp) }
                            )
                            is RepoFetchResult.Failure -> FetchState.Failed(result.message)
                        }
                    }
                }
            )
            is FetchState.Loaded -> LoadedState(
                manifest = state.manifest,
                summaries = state.summaries,
                selectedTag = selectedTag,
                onTagChange = { selectedTag = it },
                selectedDuration = selectedDuration,
                onDurationChange = { selectedDuration = it },
                addedIds = addedIds,
                onCardClick = { workout -> detailWorkout = workout },
                onAdd = { workout -> handleAdd(workout, state.manifest) }
            )
        }
    }
}

// ─── States ────────────────────────────────────────────────────────────────

private sealed class FetchState {
    data object Loading : FetchState()
    data class Loaded(
        val manifest: RepositoryManifest,
        val summaries: List<WorkoutSummary>,
    ) : FetchState()
    data class Failed(val message: String) : FetchState()
}

/**
 * Coarse duration bands for filtering. Boundaries chosen for indoor cycling:
 *  - Quick = sub-30 (recovery, short tempo, openers)
 *  - 30–60 = the modal "weekday after work" range
 *  - 60–90 = sweet-spot / threshold sessions
 *  - Long = anything 90+ (sub-threshold endurance blocks, sweetspot stacks)
 *
 * AnyDuration is the default no-filter sentinel.
 */
private enum class DurationBand(val label: String, val matches: (Int) -> Boolean) {
    AnyDuration("Any", { true }),
    Quick("Quick", { it < 30 * 60 }),
    Mid("30–60", { it in (30 * 60) until (60 * 60) }),
    LongRange("60–90", { it in (60 * 60) until (90 * 60) }),
    Epic("Long", { it >= 90 * 60 }),
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = CadenceColors.Brand500,
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(Spacing.s4))
            Text("Fetching workouts…", style = CadenceType.body, color = CadenceColors.Fg3)
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.s5),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Couldn't load this repository.", style = CadenceType.h2, color = CadenceColors.Fg1)
        Spacer(Modifier.height(Spacing.s2))
        Text(text = message, style = CadenceType.body, color = CadenceColors.Fg3)
        Spacer(Modifier.height(Spacing.s5))
        PrimaryBtn(text = "Retry", leadingIcon = null, onClick = onRetry)
    }
}

@Composable
private fun LoadedState(
    manifest: RepositoryManifest,
    summaries: List<WorkoutSummary>,
    selectedTag: String,
    onTagChange: (String) -> Unit,
    selectedDuration: DurationBand,
    onDurationChange: (DurationBand) -> Unit,
    addedIds: Set<String>,
    onCardClick: (Workout) -> Unit,
    onAdd: (Workout) -> Unit,
) {
    val availableTags = remember(summaries) {
        listOf("All") + summaries.map { it.tag }.distinct().sorted()
    }
    val visible = remember(summaries, selectedTag, selectedDuration) {
        summaries
            .filter { selectedTag == "All" || it.tag.equals(selectedTag, ignoreCase = true) }
            .filter { selectedDuration.matches(it.totalSeconds) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Compact, expand-on-tap description. Default to 2 lines so the long
        // "covers recovery, endurance, tempo, threshold, …" blurbs don't push
        // the workout list off-screen on small phones; tapping the line
        // reveals the full text for riders who want more context.
        if (!manifest.description.isNullOrBlank()) {
            var descExpanded by remember(manifest.id) { mutableStateOf(false) }
            Text(
                text = manifest.description!!,
                style = CadenceType.small,
                color = CadenceColors.Fg3,
                maxLines = if (descExpanded) Int.MAX_VALUE else 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { descExpanded = !descExpanded }
                    .padding(horizontal = Spacing.s5, vertical = Spacing.s2)
            )
        }

        // Two filter rows: duration bands + tags. Both are AND'd, both single-
        // select. Duration leads because it's the cheaper decision (rider
        // typically knows how much time they have before they know which
        // training stress they want).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s5, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DurationBand.entries.forEach { band ->
                FilterChip(
                    label = band.label,
                    selected = band == selectedDuration,
                    onClick = { onDurationChange(band) }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s5, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            availableTags.forEach { f ->
                FilterChip(label = f, selected = f == selectedTag, onClick = { onTagChange(f) })
            }
        }

        if (summaries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(Spacing.s5),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "This repository has no readable workouts yet.",
                    style = CadenceType.body,
                    color = CadenceColors.Fg3
                )
            }
            return
        }

        if (visible.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(Spacing.s5),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No workouts match these filters. Try widening the duration or tag.",
                    style = CadenceType.body,
                    color = CadenceColors.Fg3,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.s5, end = Spacing.s5,
                top = Spacing.s2, bottom = Spacing.s5
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(visible, key = { it.workout.id }) { summary ->
                RemoteWorkoutCard(
                    summary = summary,
                    isAdded = summary.workout.id in addedIds,
                    onClick = { onCardClick(summary.workout) },
                    onAdd = { onAdd(summary.workout) }
                )
            }
        }
    }
}

@Composable
private fun RemoteWorkoutCard(
    summary: WorkoutSummary,
    isAdded: Boolean,
    onClick: () -> Unit,
    onAdd: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .clickable(onClick = onClick)
            .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s2)
    ) {
        Text(
            text = summary.tag.uppercase(),
            style = CadenceType.eyebrow,
            color = CadenceColors.Fg3
        )
        Text(
            text = summary.workout.name,
            style = CadenceType.h3.copy(fontSize = 18.sp),
            color = CadenceColors.Fg1
        )
        if (!summary.workout.description.isNullOrBlank()) {
            Text(
                text = summary.workout.description!!,
                style = CadenceType.small,
                color = CadenceColors.Fg3,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        ZoneBar(segments = summary.zoneSegments(), modifier = Modifier.fillMaxWidth())
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
        ) {
            // weight(1f) + maxLines/ellipsis so the metadata never starves the
            // Add pill of width — long stat strings (e.g. "1 h 24 min · TSS
            // 68 · IF 0.70") were otherwise pushing the pill to a 12-dp column
            // and the word "Add" wrapped letter-by-letter.
            Text(
                text = "${formatDurationPretty(summary.totalSeconds)} · TSS ${summary.tss} · IF %.2f".format(summary.intensityFactor),
                style = CadenceType.tick,
                color = CadenceColors.Fg3,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            AddButton(isAdded = isAdded, onAdd = onAdd)
        }
    }
}

@Composable
private fun AddButton(isAdded: Boolean, onAdd: () -> Unit) {
    if (isAdded) {
        // "Added" is a non-interactive status pill. We deliberately don't let
        // the user re-tap it — re-adding would clobber any local edits they
        // may have made (e.g. via Rename in the workout detail screen).
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(CadenceColors.Success.copy(alpha = 0.18f))
                .padding(horizontal = Spacing.s3, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = CadenceColors.Success,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Added",
                style = CadenceType.bodyStrong.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                softWrap = false,
                color = CadenceColors.Success
            )
        }
    } else {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(CadenceColors.Brand500.copy(alpha = 0.18f))
                .clickable(onClick = onAdd)
                .padding(horizontal = Spacing.s3, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudDownload,
                contentDescription = null,
                tint = CadenceColors.Brand500,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Add",
                style = CadenceType.bodyStrong.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                softWrap = false,
                color = CadenceColors.Brand500
            )
        }
    }
}
