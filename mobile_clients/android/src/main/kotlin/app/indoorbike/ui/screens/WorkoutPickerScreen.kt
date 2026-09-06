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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Terrain
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
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
import app.indoorbike.shared.workout.FREE_RIDE_START_PERCENT_FTP
import app.indoorbike.shared.workout.FREE_RIDE_WORKOUT_ID
import app.indoorbike.shared.workout.WorkoutSummary
import app.indoorbike.shared.workout.formatDurationPretty
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.shared.workout.freeRideStartWatts
import app.indoorbike.shared.workout.simulationRouteId
import app.indoorbike.shared.workout.toSummary
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.rememberCollapsingHeaderState
import app.indoorbike.ui.components.listBottomPadding
import app.indoorbike.ui.components.CardDivider
import app.indoorbike.ui.components.FilterChip
import app.indoorbike.ui.components.ListCard
import app.indoorbike.ui.components.ZoneBar
import app.indoorbike.ui.components.dashedOutline
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.workout.zoneSegments
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import app.indoorbike.ui.components.IconBtn

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

/** The bundled workout a brand-new rider is pointed at first. */
internal const val STARTER_WORKOUT_ID = "starter-15min"

/**
 * Choose a workout.
 *
 * This is what is left of the old home screen once the home screen stopped
 * being a list. Search, the duration and tag filters, and the compact rows all
 * survive unchanged, because the problem they solve, finding one session among
 * a few hundred, did not go away; it just stopped being the first question the
 * app asks.
 *
 * What went is everything that was there to make a list serve as a home
 * screen: the up-next hero, the quick-pick shelf, the mode cards and the
 * trainer chip. Choosing the kind of ride now happens before this screen, so a
 * hero proposing one particular workout would be answering a question the
 * rider has already answered.
 */
@Composable
fun WorkoutPickerScreen(
    entries: List<WorkoutEntry>,
    onBack: () -> Unit,
    onPick: (Workout) -> Unit,
    onOpenLibrary: () -> Unit,
    onScanQr: () -> Unit,
) {
    val container = LocalAppContainer.current
    val ftp by container.userSettings.ftp.collectAsState()
    val favouriteIds by container.userSettings.favouriteWorkoutIds.collectAsState()

    val workouts = remember(entries) { entries.map { it.workout } }
    val summaries = remember(workouts, ftp) { workouts.map { it.toSummary(ftp) } }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<LibraryFilter>(LibraryFilter.All) }

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

    val visible = remember(summaries, searchQuery, selectedFilter, favouriteIds) {
        val q = searchQuery.trim()
        summaries.filter { s ->
            val matchesQuery = q.isEmpty() ||
                s.workout.name.contains(q, ignoreCase = true) ||
                s.tag.contains(q, ignoreCase = true)
            matchesQuery && selectedFilter.matches(s, favouriteIds)
        }
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
            title = "Workouts",
            eyebrow = "Choose a ride",
            leading = {
                IconBtn(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack)
            },
            // The QR handoff carries a workout out of the web editor, so it
            // belongs where workouts are chosen. On the home screen it was an
            // action for one of the three ride kinds sitting above all of them.
            trailing = {
                IconBtn(
                    Icons.Outlined.QrCodeScanner,
                    "Scan a workout QR code",
                    onClick = onScanQr,
                )
            },
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = Spacing.s5, end = Spacing.s5,
                top = Spacing.s4, bottom = listBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "search") {
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
                item(key = "no-results") {
                    NoResultsHint(query = searchQuery.trim())
                }
            } else {
                // One card for the whole list, rows divided rather than
                // separated. A card per row turned a library of forty workouts
                // into forty floating objects; this way the list is one object
                // and the rows are its contents.
                item(key = "workout-list") {
                    ListCard {
                        visible.forEachIndexed { index, summary ->
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
                            if (index != visible.lastIndex) CardDivider()
                        }
                    }
                }
            }

            // Trailing "add more" card. Catches riders who scrolled the whole
            // list looking for something that is not there yet.
            item(key = "add-more-workouts") {
                AddMoreWorkoutsCard(onClick = onOpenLibrary)
            }
        }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(CadenceColors.Bg1)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = CadenceColors.Fg3,
            modifier = Modifier.size(20.dp)
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = CadenceType.body.copy(color = CadenceColors.Fg1),
            cursorBrush = SolidColor(CadenceColors.Fg1),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text = "Search workouts",
                        style = CadenceType.body,
                        color = CadenceColors.Fg3
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
                    .size(20.dp)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = Spacing.s5, end = Spacing.s3, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.workout.name,
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(Spacing.s2))
            ZoneBar(segments = summary.zoneSegments(), height = 6.dp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatDurationPretty(summary.totalSeconds),
                style = CadenceType.small.copy(fontWeight = FontWeight.Bold),
                color = CadenceColors.Fg1
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
                tint = if (starred) CadenceColors.Fg1 else CadenceColors.Fg3,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Banners
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Not a card: a dashed outline on the canvas, the shape the app uses for
 * anything that is a gap rather than a thing. There is nothing here yet, and
 * the outline says so without pretending to be content.
 */
@Composable
private fun AddMoreWorkoutsCard(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.lg))
            .dashedOutline()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.s5, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s4)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.LibraryBooks,
            contentDescription = null,
            tint = CadenceColors.Fg2,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Want more workouts?",
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Community library, QR code, or a file.",
                style = CadenceType.small,
                color = CadenceColors.Fg3
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = CadenceColors.Fg3,
            modifier = Modifier.size(22.dp)
        )
    }
}
