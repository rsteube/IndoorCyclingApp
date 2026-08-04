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

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.LocalAppContainer
import app.indoorbike.library.WorkoutEntry
import app.indoorbike.shared.Workout
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.settings.MAX_FTP
import app.indoorbike.shared.settings.MAX_WEIGHT_KG
import app.indoorbike.shared.settings.MIN_FTP
import app.indoorbike.shared.settings.MIN_WEIGHT_KG
import app.indoorbike.shared.settings.UNKNOWN_FTP_DEFAULT
import app.indoorbike.shared.workout.WorkoutSummary
import app.indoorbike.shared.workout.formatDurationPretty
import app.indoorbike.shared.workout.toSummary
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.FilterChip
import app.indoorbike.ui.components.HrmHeartIndicator
import app.indoorbike.ui.components.MetaPill
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.UnfinishedRideBanner
import app.indoorbike.ui.components.ZoneBar
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.workout.zoneSegments
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val STARTER_WORKOUT_ID = "starter-15min"

/** How many rides of the same workout before it counts as a habit for Quick picks. */
private const val FREQUENT_RIDE_THRESHOLD = 2
private const val QUICK_PICKS_MAX = 8

/**
 * Single-select library filter. Duration buckets come before tags because
 * "how long do I have?" is the question riders actually start from.
 */
private sealed class LibraryFilter {
    data object All : LibraryFilter()
    data object Short : LibraryFilter()      // <= 30 min
    data object Medium : LibraryFilter()     // 30-60 min
    data object Long : LibraryFilter()       // > 60 min
    data object Favourites : LibraryFilter()
    data class Tag(val name: String) : LibraryFilter()

    fun label(): String = when (this) {
        All -> "All"
        Short -> "≤ 30 min"
        Medium -> "30-60"
        Long -> "60+"
        Favourites -> "★"
        is Tag -> name
    }

    fun matches(summary: WorkoutSummary, favouriteIds: Set<String>): Boolean = when (this) {
        All -> true
        Short -> summary.totalSeconds <= 30 * 60
        Medium -> summary.totalSeconds in (30 * 60 + 1)..(60 * 60)
        Long -> summary.totalSeconds > 60 * 60
        Favourites -> summary.workout.id in favouriteIds
        is Tag -> summary.tag.equals(name, ignoreCase = true)
    }
}

/** Why a workout is sitting in the Up next hero slot. */
private data class UpNextInfo(
    val summary: WorkoutSummary,
    val eyebrow: String,
    val subtitle: String?,
)

/** A Quick-picks shelf entry: starred, a habit, or both. */
private data class QuickPick(
    val summary: WorkoutSummary,
    val starred: Boolean,
    val rideCount: Int,
)

/**
 * Home tab ("Ride"). Organized by intent, strongest signal first:
 *
 *   1. App bar: trainer status chip + QR scan (the web-editor handoff).
 *   2. Up next: one hero proposal - resume > last ridden > newest star >
 *      starter workout. Start is one tap when the trainer is live.
 *   3. Quick picks: starred + most-ridden shelf, computed from history.
 *   4. Library: search + duration-first filters over compact rows.
 */
@Composable
fun WorkoutListScreen(
    entries: List<WorkoutEntry>,
    eyebrow: String,
    onPick: (Workout) -> Unit,
    onStartWorkout: (Workout) -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onScanQr: () -> Unit = {},
    onOpenTrainerPairing: () -> Unit = {},
) {
    val container = LocalAppContainer.current
    val ftp by container.userSettings.ftp.collectAsState()
    val favouriteIds by container.userSettings.favouriteWorkoutIds.collectAsState()
    val sessions by container.sessions.collectAsState()
    val hasCompletedOnboarding by container.userSettings.hasCompletedOnboarding.collectAsState()
    val persistedSnapshot by container.sessionManager.persistedSnapshot.collectAsState()

    val workouts = remember(entries) { entries.map { it.workout } }
    val summaries = remember(workouts, ftp) { workouts.map { it.toSummary(ftp) } }
    val summaryById = remember(summaries) { summaries.associateBy { it.workout.id } }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<LibraryFilter>(LibraryFilter.All) }

    // Trainer connection, shared by the status chip and the hero CTA.
    val connectionState by container.trainerManager.connectionState.collectAsState()
    val pairedTrainerId by container.trainerManager.pairedTrainerId.collectAsState()
    val isMockTrainer by container.trainerManager.isMockTrainer.collectAsState()
    val connectedTrainerName by container.trainerManager.connectedTrainerName.collectAsState()
    val isTrainerLive = connectionState == TrainerConnectionState.Connected || isMockTrainer

    val hrmState by container.heartRateMonitor.connectionState.collectAsState()
    val hrmSilent by container.heartRateMonitor.sensorSilent.collectAsState()

    // The unfinished-ride banner IS the up-next proposal while it's showing,
    // so the hero yields to it. The banner component itself decides its final
    // visibility (it also validates the snapshot); this only needs to know
    // whether a snapshot for a still-existing workout is on disk.
    val hasUnfinishedRide = persistedSnapshot?.let { snap ->
        summaryById.containsKey(snap.workoutId)
    } ?: false

    val upNext = remember(summaryById, sessions, favouriteIds, hasUnfinishedRide) {
        if (hasUnfinishedRide) null
        else pickUpNext(summaryById, sessions, favouriteIds)
    }

    val quickPicks = remember(summaryById, sessions, favouriteIds, upNext) {
        buildQuickPicks(
            summaryById = summaryById,
            sessions = sessions,
            favouriteIds = favouriteIds,
            excludeId = upNext?.summary?.workout?.id,
        )
    }

    val availableFilters = remember(summaries, favouriteIds) {
        buildList {
            add(LibraryFilter.All)
            add(LibraryFilter.Short)
            add(LibraryFilter.Medium)
            add(LibraryFilter.Long)
            if (favouriteIds.isNotEmpty()) add(LibraryFilter.Favourites)
            summaries.map { it.tag }.distinct().sorted().forEach { add(LibraryFilter.Tag(it)) }
        }
    }
    // Snap back to All if the active filter disappears (last favourite
    // unstarred, tag gone after a delete) so the list never strands on an
    // option that no longer has a chip.
    if (availableFilters.none { it == selectedFilter }) {
        selectedFilter = LibraryFilter.All
    }

    val visible = remember(summaries, selectedFilter, favouriteIds, searchQuery) {
        val q = searchQuery.trim()
        summaries.filter { summary ->
            selectedFilter.matches(summary, favouriteIds) &&
                (q.isEmpty() ||
                    summary.workout.name.contains(q, ignoreCase = true) ||
                    summary.tag.contains(q, ignoreCase = true))
        }
    }

    var showOnboardingSheet by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0)) {
        AppBar(
            title = "Ride",
            eyebrow = eyebrow,
            leading = {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(Radii.sm))
                        .background(CadenceColors.Brand500.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.DirectionsBike,
                        contentDescription = null,
                        tint = CadenceColors.Brand500,
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s2)
                ) {
                    HrmHeartIndicator(state = hrmState, sensorSilent = hrmSilent)
                    TrainerChip(
                        isLive = isTrainerLive,
                        isPaired = pairedTrainerId != null,
                        connectionState = connectionState,
                        deviceName = if (isMockTrainer) "Mock trainer" else connectedTrainerName,
                        onClick = onOpenTrainerPairing
                    )
                    QrScanButton(onClick = onScanQr)
                }
            }
        )

        if (workouts.isEmpty()) {
            // Skeleton placeholders while the assets parser is still loading.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.s5),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(3) { SkeletonCard() }
            }
        } else {
            // Adaptive grid: one column on phones, more as width allows on
            // tablets / unfolded foldables. Section headers, the hero and the
            // shelf span every column; the compact rows flow into the grid.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = Spacing.s5, end = Spacing.s5,
                    top = Spacing.s2, bottom = Spacing.s5
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!hasCompletedOnboarding) {
                    item(key = "welcome", span = { GridItemSpan(maxLineSpan) }) {
                        WelcomeBanner(onGetStarted = { showOnboardingSheet = true })
                    }
                }

                if (hasUnfinishedRide) {
                    item(key = "unfinished", span = { GridItemSpan(maxLineSpan) }) {
                        UnfinishedRideBanner()
                    }
                }

                if (upNext != null) {
                    item(key = "up-next", span = { GridItemSpan(maxLineSpan) }) {
                        UpNextHero(
                            info = upNext,
                            isTrainerLive = isTrainerLive,
                            onOpen = { onPick(upNext.summary.workout) },
                            onStart = { onStartWorkout(upNext.summary.workout) },
                        )
                    }
                }

                if (quickPicks.isNotEmpty()) {
                    item(key = "quick-picks", span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SectionEyebrow(
                                title = "QUICK PICKS",
                                hint = "starred + most ridden"
                            )
                            Spacer(Modifier.height(Spacing.s2))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(
                                    count = quickPicks.size,
                                    key = { "qp-${quickPicks[it].summary.workout.id}" }
                                ) { i ->
                                    QuickPickCard(
                                        pick = quickPicks[i],
                                        onClick = { onPick(quickPicks[i].summary.workout) }
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        SearchField(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                        )
                        Spacer(Modifier.height(Spacing.s3))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            availableFilters.forEach { f ->
                                FilterChip(
                                    label = f.label(),
                                    selected = f == selectedFilter,
                                    onClick = { selectedFilter = f }
                                )
                            }
                        }
                    }
                }

                if (visible.isEmpty()) {
                    item(key = "no-results", span = { GridItemSpan(maxLineSpan) }) {
                        NoResultsHint(query = searchQuery.trim())
                    }
                } else {
                    items(visible, key = { it.workout.id }) { summary ->
                        val starred = summary.workout.id in favouriteIds
                        WorkoutRow(
                            summary = summary,
                            starred = starred,
                            onClick = { onPick(summary.workout) },
                            onToggleStar = {
                                container.userSettings.setWorkoutFavourite(
                                    summary.workout.id, !starred
                                )
                            }
                        )
                    }
                }

                // Trailing "add more" card. Catches users who scrolled the
                // whole list looking for what they wanted.
                item(key = "add-more-workouts", span = { GridItemSpan(maxLineSpan) }) {
                    AddMoreWorkoutsCard(onClick = onOpenLibrary)
                }
            }
        }
    }

    if (showOnboardingSheet) {
        OnboardingSheet(
            onProfileSaved = { newFtp, newWeight ->
                // Don't mark onboarding complete yet — the permission step still
                // has to run. Persist the profile so closing the sheet mid-flow
                // doesn't lose it.
                container.userSettings.setFtp(newFtp)
                container.userSettings.setWeightKg(newWeight)
            },
            onFinish = {
                container.userSettings.setHasCompletedOnboarding(true)
                showOnboardingSheet = false
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Up next / Quick picks selection
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Hero priority: most recent ride whose workout still exists > any starred
 * workout > the bundled starter. Sessions arrive newest-first from the shared
 * StateFlow, so the first library hit is the last ride.
 */
private fun pickUpNext(
    summaryById: Map<String, WorkoutSummary>,
    sessions: List<WorkoutSession>,
    favouriteIds: Set<String>,
): UpNextInfo? {
    sessions.firstNotNullOfOrNull { session ->
        summaryById[session.workoutId]?.let { it to session }
    }?.let { (summary, session) ->
        return UpNextInfo(
            summary = summary,
            eyebrow = "UP NEXT · RIDE AGAIN",
            subtitle = "Last ridden ${relativeDayLabel(session.startTimeEpoch)}",
        )
    }
    // No history yet: fall back to a star, then the starter workout.
    favouriteIds.firstNotNullOfOrNull { summaryById[it] }?.let { summary ->
        return UpNextInfo(
            summary = summary,
            eyebrow = "UP NEXT · FROM YOUR STARS",
            subtitle = summary.workout.description?.takeIf { it.isNotBlank() },
        )
    }
    summaryById[STARTER_WORKOUT_ID]?.let { summary ->
        return UpNextInfo(
            summary = summary,
            eyebrow = "START HERE · YOUR FIRST RIDE",
            subtitle = summary.workout.description?.takeIf { it.isNotBlank() },
        )
    }
    return null
}

/**
 * Starred workouts first (library order), then habits (ridden at least
 * [FREQUENT_RIDE_THRESHOLD] times, most-ridden first). The hero's workout is
 * excluded so the same ride doesn't appear twice in a row on screen.
 */
private fun buildQuickPicks(
    summaryById: Map<String, WorkoutSummary>,
    sessions: List<WorkoutSession>,
    favouriteIds: Set<String>,
    excludeId: String?,
): List<QuickPick> {
    val rideCounts = sessions.groupingBy { it.workoutId }.eachCount()
    val picks = LinkedHashMap<String, QuickPick>()
    summaryById.values.forEach { summary ->
        val id = summary.workout.id
        if (id != excludeId && id in favouriteIds) {
            picks[id] = QuickPick(summary, starred = true, rideCount = rideCounts[id] ?: 0)
        }
    }
    rideCounts.entries
        .filter { it.value >= FREQUENT_RIDE_THRESHOLD }
        .sortedByDescending { it.value }
        .forEach { (id, count) ->
            if (id != excludeId && id !in picks) {
                summaryById[id]?.let { summary ->
                    picks[id] = QuickPick(summary, starred = false, rideCount = count)
                }
            }
        }
    return picks.values.take(QUICK_PICKS_MAX)
}

/** "today", "yesterday", "3 days ago", then "12 Jun" for anything older. */
private fun relativeDayLabel(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val dayMs = 24 * 60 * 60 * 1000L
    // Compare calendar days, not raw 24h windows, so 23:50 → 00:10 is "yesterday".
    val days = ((now / dayMs) - (epochMs / dayMs)).toInt()
    return when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        days < 7 -> "$days days ago"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(epochMs))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  App bar controls
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One-chip trainer status, replacing the stacked banner treatments. Tapping
 * always opens the pairing screen - it handles every state (scan, connect,
 * mock) itself.
 */
@Composable
private fun TrainerChip(
    isLive: Boolean,
    isPaired: Boolean,
    connectionState: TrainerConnectionState,
    deviceName: String?,
    onClick: () -> Unit,
) {
    val (dotColor, label, labelColor) = when {
        isLive -> Triple(
            CadenceColors.Success,
            (deviceName ?: "Trainer").uppercase(),
            CadenceColors.Fg2
        )
        !isPaired -> Triple(
            CadenceColors.Brand500,
            "PAIR TRAINER",
            CadenceColors.Brand500
        )
        connectionState == TrainerConnectionState.Connecting ||
            connectionState == TrainerConnectionState.Scanning -> Triple(
            CadenceColors.Warning,
            "CONNECTING",
            CadenceColors.Fg3
        )
        else -> Triple(
            CadenceColors.Fg4,
            "TRAINER OFF",
            CadenceColors.Fg3
        )
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            style = CadenceType.tick,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp)
        )
    }
}

/**
 * Top-level entry point for the web-editor handoff: build a workout at
 * indoorbike.app/editor, tap this, point the camera. Brand-tinted so it reads
 * as a primary action, not chrome.
 */
@Composable
private fun QrScanButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radii.md)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(shape)
            .background(CadenceColors.Brand500.copy(alpha = 0.16f))
            .border(1.dp, CadenceColors.Brand500.copy(alpha = 0.35f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.QrCodeScanner,
            contentDescription = "Scan workout QR",
            tint = CadenceColors.Brand500,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Up next hero
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun UpNextHero(
    info: UpNextInfo,
    isTrainerLive: Boolean,
    onOpen: () -> Unit,
    onStart: () -> Unit,
) {
    val summary = info.summary
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .background(CadenceColors.Brand500.copy(alpha = 0.05f))
            .border(1.dp, CadenceColors.Brand500.copy(alpha = 0.55f), shape)
            .clickable(onClick = onOpen)
            .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Text(
            text = info.eyebrow,
            style = CadenceType.eyebrow,
            color = CadenceColors.Brand500
        )
        Column {
            Text(
                text = summary.workout.name,
                style = CadenceType.h2,
                color = CadenceColors.Fg1
            )
            info.subtitle?.let { sub ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sub,
                    style = CadenceType.small,
                    color = CadenceColors.Fg3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        ZoneBar(segments = summary.zoneSegments(), height = 8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetaPill(text = formatDurationPretty(summary.totalSeconds).uppercase())
            MetaPill(text = "TSS ${summary.tss}")
            MetaPill(text = "IF ${"%.2f".format(summary.intensityFactor)}")
        }
        // Trainer live: start the ride in one tap. Otherwise open the detail
        // screen, which owns the pair-a-trainer CTA.
        PrimaryBtn(
            text = if (isTrainerLive) "Start workout" else "Open workout",
            leadingIcon = if (isTrainerLive) Icons.Outlined.PlayArrow else null,
            onClick = if (isTrainerLive) onStart else onOpen,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Quick picks shelf
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionEyebrow(title: String, hint: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = CadenceType.eyebrow,
            color = CadenceColors.Fg3,
            modifier = Modifier.weight(1f)
        )
        if (hint != null) {
            Text(
                text = hint,
                style = CadenceType.tick,
                color = CadenceColors.Fg4
            )
        }
    }
}

@Composable
private fun QuickPickCard(pick: QuickPick, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (pick.starred) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Starred",
                    tint = CadenceColors.Brand500,
                    modifier = Modifier.size(11.dp)
                )
                Text(
                    text = "STARRED",
                    style = CadenceType.tick.copy(fontSize = 9.sp),
                    color = CadenceColors.Fg3
                )
            } else {
                Text(
                    text = "↻ RIDDEN ${pick.rideCount}×",
                    style = CadenceType.tick.copy(fontSize = 9.sp),
                    color = CadenceColors.Fg3
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = pick.summary.workout.name,
            style = CadenceType.bodyStrong.copy(fontSize = 13.sp, lineHeight = 17.sp),
            color = CadenceColors.Fg1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${formatDurationPretty(pick.summary.totalSeconds).uppercase()} · TSS ${pick.summary.tss}",
            style = CadenceType.tick.copy(fontSize = 9.5.sp),
            color = CadenceColors.Fg3
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Search + library rows
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val shape = RoundedCornerShape(Radii.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = CadenceColors.Fg3,
            modifier = Modifier.size(16.dp)
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = CadenceType.body.copy(color = CadenceColors.Fg1),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text = "Search workouts",
                        style = CadenceType.body,
                        color = CadenceColors.Fg4
                    )
                }
                inner()
            }
        )
        if (query.isNotEmpty()) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Clear search",
                tint = CadenceColors.Fg3,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onQueryChange("") }
            )
        }
    }
}

@Composable
private fun NoResultsHint(query: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.s6),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (query.isEmpty()) "No workouts match this filter."
            else "No workouts match \"$query\".",
            style = CadenceType.body,
            color = CadenceColors.Fg3,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Compact library row: the list is a browse surface now, not the whole
 * screen, so each entry carries just enough to recognise and compare -
 * name, zone shape, duration + TSS - plus an inline star so favouriting
 * doesn't require a round-trip through the detail screen.
 */
@Composable
private fun WorkoutRow(
    summary: WorkoutSummary,
    starred: Boolean,
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .clickable(onClick = onClick)
            .padding(start = Spacing.s4, end = Spacing.s2, top = Spacing.s3, bottom = Spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.workout.name,
                style = CadenceType.bodyStrong.copy(fontSize = 14.sp),
                color = CadenceColors.Fg1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(7.dp))
            ZoneBar(segments = summary.zoneSegments(), height = 5.dp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatDurationPretty(summary.totalSeconds).uppercase(),
                style = CadenceType.tick,
                color = CadenceColors.Fg2
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "TSS ${summary.tss}",
                style = CadenceType.tick,
                color = CadenceColors.Fg3
            )
        }
        // 36dp hit target; the row's own clickable is not triggered because
        // the inner clickable consumes the tap.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggleStar),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (starred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (starred) "Remove from favourites" else "Add to favourites",
                tint = if (starred) CadenceColors.Brand500 else CadenceColors.Fg4,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Skeleton / banners
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SkeletonCard() {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line1, shape)
            .padding(Spacing.s4),
        verticalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Box(
            modifier = Modifier
                .height(14.dp)
                .fillMaxWidth(0.4f)
                .clip(RoundedCornerShape(4.dp))
                .background(CadenceColors.Bg2)
        )
        Box(
            modifier = Modifier
                .height(20.dp)
                .fillMaxWidth(0.7f)
                .clip(RoundedCornerShape(4.dp))
                .background(CadenceColors.Bg2)
        )
        Box(
            modifier = Modifier
                .height(8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(CadenceColors.Bg2)
        )
    }
}

@Composable
private fun WelcomeBanner(onGetStarted: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radii.lg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Brand500.copy(alpha = 0.12f))
            .border(1.dp, CadenceColors.Brand500.copy(alpha = 0.6f), shape)
            .padding(Spacing.s5)
    ) {
        Text(
            text = "WELCOME",
            style = CadenceType.eyebrow,
            color = CadenceColors.Brand500
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Welcome to Indoor Bike",
            style = CadenceType.h2,
            color = CadenceColors.Fg1
        )
        Spacer(Modifier.height(Spacing.s2))
        Text(
            text = "Power-zone workouts for your smart trainer. Set your FTP and weight so the targets match you - takes about 30 seconds.",
            style = CadenceType.body,
            color = CadenceColors.Fg2
        )
        Spacer(Modifier.height(Spacing.s4))
        PrimaryBtn(
            text = "Get Started",
            leadingIcon = null,
            onClick = onGetStarted,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * "Want more workouts?" card slotted at the end of the workout list. Picks
 * up users who used the scroll-to-end reflex; visually distinct from a
 * regular WorkoutRow (brand-tinted call-to-action label) so it doesn't read
 * like another workout to ride.
 */
@Composable
private fun AddMoreWorkoutsCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1.copy(alpha = 0.5f))
            .border(1.dp, CadenceColors.Brand500.copy(alpha = 0.4f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CadenceColors.Brand500.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.LibraryBooks,
                contentDescription = null,
                tint = CadenceColors.Brand500,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Want more workouts?",
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1
            )
            Text(
                text = "Browse the community library, scan a QR, or import a file.",
                style = CadenceType.tick,
                color = CadenceColors.Fg3
            )
        }
        Text(text = "›", style = CadenceType.h2, color = CadenceColors.Brand500)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Onboarding
// ─────────────────────────────────────────────────────────────────────────────

private enum class OnboardingStep { Profile, Permissions }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingSheet(
    onProfileSaved: (Int, Float) -> Unit,
    onFinish: () -> Unit,
) {
    var step by remember { mutableStateOf(OnboardingStep.Profile) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        // Sheet dismiss (swipe-down, scrim tap, back) on either step counts as
        // finishing onboarding so the user isn't pestered next launch — the
        // RideScreen permission request is the safety net.
        onDismissRequest = onFinish,
        sheetState = sheetState,
        containerColor = CadenceColors.Bg1,
        contentColor = CadenceColors.Fg1,
    ) {
        when (step) {
            OnboardingStep.Profile -> ProfileStep(
                onSaved = { ftp, weight ->
                    onProfileSaved(ftp, weight)
                    step = OnboardingStep.Permissions
                }
            )
            OnboardingStep.Permissions -> PermissionsStep(onDone = onFinish)
        }
    }
}

@Composable
private fun ProfileStep(onSaved: (Int, Float) -> Unit) {
    val container = LocalAppContainer.current
    val currentFtp by container.userSettings.ftp.collectAsState()
    val currentWeight by container.userSettings.weightKg.collectAsState()

    var ftpInput by remember { mutableStateOf(currentFtp.toString()) }
    var weightInput by remember { mutableStateOf(formatWeightForInput(currentWeight)) }

    val parsedFtp = ftpInput.toIntOrNull()
    val parsedWeight = weightInput.replace(',', '.').toFloatOrNull()
    val ftpValid = parsedFtp != null && parsedFtp in MIN_FTP..MAX_FTP
    val weightValid = parsedWeight != null && parsedWeight in MIN_WEIGHT_KG..MAX_WEIGHT_KG
    val canSave = ftpValid && weightValid

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s5)
            .padding(bottom = Spacing.s5)
    ) {
        StepHeader(eyebrow = "STEP 1 OF 2 · ABOUT YOU", title = "Set your FTP and weight")
        Spacer(Modifier.height(Spacing.s2))
        Text(
            text = "We use these to scale every workout to your fitness. You can change them anytime in Settings.",
            style = CadenceType.body,
            color = CadenceColors.Fg3
        )
        Spacer(Modifier.height(Spacing.s5))

        OnboardingNumberField(
            label = "Functional Threshold Power",
            sublabel = "Your sustainable hour-long power",
            value = ftpInput,
            onValueChange = { v -> ftpInput = v.filter { it.isDigit() }.take(4) },
            unit = "W",
            keyboardType = KeyboardType.Number,
        )
        Spacer(Modifier.height(Spacing.s2))
        Text(
            text = "Don't know your FTP? Start at ${UNKNOWN_FTP_DEFAULT}W",
            style = CadenceType.small,
            color = CadenceColors.Brand500,
            modifier = Modifier
                .clickable { ftpInput = UNKNOWN_FTP_DEFAULT.toString() }
                .padding(vertical = 4.dp)
        )

        Spacer(Modifier.height(Spacing.s4))

        OnboardingNumberField(
            label = "Body Weight",
            sublabel = "Used for W/kg and XP",
            value = weightInput,
            onValueChange = { v ->
                val filtered = buildString {
                    var seenDot = false
                    for (c in v.take(6)) {
                        if (c.isDigit()) append(c)
                        else if ((c == '.' || c == ',') && !seenDot) {
                            append('.'); seenDot = true
                        }
                    }
                }
                weightInput = filtered
            },
            unit = "kg",
            keyboardType = KeyboardType.Decimal,
        )

        Spacer(Modifier.height(Spacing.s5))

        PrimaryBtn(
            text = "Continue",
            leadingIcon = null,
            onClick = { if (canSave) onSaved(parsedFtp!!, parsedWeight!!) },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Pre-flight rationale for Bluetooth (and POST_NOTIFICATIONS on T+). Showing
 * this BEFORE the system dialog is Google's recommended pattern: it gives the
 * user the "why" so the system prompt isn't a blind yes/no. We launch the
 * system request the moment they tap "Allow access" — outcome doesn't matter
 * for finishing onboarding (the in-RideScreen request is the safety net).
 */
@Composable
private fun PermissionsStep(onDone: () -> Unit) {
    val needsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val bluetoothPerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val allPerms = remember(needsNotifications) {
        if (needsNotifications) bluetoothPerms + Manifest.permission.POST_NOTIFICATIONS
        else bluetoothPerms
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Whether granted or not, we move on. RideScreen will re-prompt on
        // first ride if Bluetooth was denied here.
        onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s5)
            .padding(bottom = Spacing.s5)
    ) {
        StepHeader(eyebrow = "STEP 2 OF 2 · CONNECT", title = "Allow trainer access")
        Spacer(Modifier.height(Spacing.s2))
        Text(
            text = "Indoor Bike talks to your smart trainer over Bluetooth. No internet, no account, no data leaves your phone.",
            style = CadenceType.body,
            color = CadenceColors.Fg3
        )

        Spacer(Modifier.height(Spacing.s5))

        PermissionRow(
            icon = Icons.Outlined.Bluetooth,
            title = "Bluetooth",
            body = "Find and pair your smart trainer, heart-rate strap, and Zwift Click controller."
        )
        if (needsNotifications) {
            Spacer(Modifier.height(Spacing.s3))
            PermissionRow(
                icon = Icons.Outlined.Notifications,
                title = "Notifications",
                body = "Show the active-ride strip so Android keeps the app alive while you ride."
            )
        }

        Spacer(Modifier.height(Spacing.s5))

        PrimaryBtn(
            text = "Allow access",
            leadingIcon = null,
            onClick = { launcher.launch(allPerms) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.s2))
        Text(
            text = "Not now",
            style = CadenceType.bodyStrong,
            color = CadenceColors.Fg3,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDone)
                .padding(vertical = 12.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StepHeader(eyebrow: String, title: String) {
    Text(text = eyebrow, style = CadenceType.eyebrow, color = CadenceColors.Brand500)
    Spacer(Modifier.height(6.dp))
    Text(text = title, style = CadenceType.h2, color = CadenceColors.Fg1)
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(CadenceColors.Bg2)
            .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.md))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CadenceColors.Brand500.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = CadenceColors.Brand500,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
            Spacer(Modifier.height(2.dp))
            Text(text = body, style = CadenceType.small, color = CadenceColors.Fg3)
        }
    }
}

@Composable
private fun OnboardingNumberField(
    label: String,
    sublabel: String,
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    keyboardType: KeyboardType,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(CadenceColors.Bg2)
            .border(1.dp, CadenceColors.Line2, RoundedCornerShape(Radii.md))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = CadenceType.bodyStrong, color = CadenceColors.Fg1)
                Spacer(Modifier.height(4.dp))
                Text(text = sublabel, style = CadenceType.small, color = CadenceColors.Fg3)
            }
            Spacer(Modifier.width(16.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = CadenceType.h2.copy(color = CadenceColors.Brand500, textAlign = TextAlign.End),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(),
                singleLine = true,
                modifier = Modifier
                    .width(110.dp)
                    .onFocusChanged { },
                decorationBox = { inner ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radii.sm))
                            .background(CadenceColors.Bg0)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { inner() }
                        Spacer(Modifier.width(4.dp))
                        Text(unit, style = CadenceType.bodyStrong, color = CadenceColors.Fg3)
                    }
                }
            )
        }
    }
}

private fun formatWeightForInput(kg: Float): String {
    val rounded = kotlin.math.round(kg * 10f) / 10f
    return if (rounded == kotlin.math.floor(rounded)) rounded.toInt().toString() else rounded.toString()
}
