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
package app.indoorbike.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.progression.durationLabelLong
import app.indoorbike.shared.progression.totalDurationSeconds
import app.indoorbike.shared.progression.totalKjAcross
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.tabRootBottomPadding
import app.indoorbike.ui.components.rememberCollapsingHeaderState
import app.indoorbike.ui.components.CardDivider
import app.indoorbike.ui.components.ListCard
import app.indoorbike.ui.components.MetaPill
import app.indoorbike.ui.components.SlateCard
import app.indoorbike.ui.components.Stat
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.UnfinishedRideBanner
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HistoryTab(
    onNavigateToOverview: (Long) -> Unit,
    onNavigateToRides: () -> Unit = {},
    onRideAgain: (workoutId: String) -> Unit = {},
) {
    val container = LocalAppContainer.current
    val sessions by container.sessions.collectAsState()
    // Workout ids that still exist in the library - a session whose workout
    // was deleted can't offer "Ride again".
    val workouts = app.indoorbike.LocalWorkouts.current
    val libraryIds = remember(workouts) { workouts.map { it.id }.toSet() }
    val header = rememberCollapsingHeaderState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
            .nestedScroll(header.nestedScrollConnection)
    ) {
        AppBar(
            header = header,
            title = "History",
            eyebrow = "Past activity"
        )

        UnfinishedRideBanner(
            modifier = Modifier.padding(horizontal = Spacing.s5, vertical = Spacing.s2)
        )

        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.s5),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = CadenceColors.Fg3,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.s4))
                    Text(
                        text = "No rides yet",
                        style = CadenceType.h1,
                        color = CadenceColors.Fg1,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(Spacing.s2))
                    Text(
                        text = "Your past workouts will appear here\nonce you complete them.",
                        style = CadenceType.body,
                        color = CadenceColors.Fg3,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(Spacing.s5))
                    PrimaryBtn(
                        text = "Pick a workout",
                        leadingIcon = null,
                        onClick = onNavigateToRides
                    )
                }
            }
        } else {
            val totalSeconds = remember(sessions) { totalDurationSeconds(sessions) }
            val totalKj = remember(sessions) { totalKjAcross(sessions) }
            val grouped = remember(sessions) { groupByWeek(sessions) }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.s5,
                    end = Spacing.s5,
                    top = Spacing.s4,
                    bottom = tabRootBottomPadding()
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.s3)
            ) {
                item { TotalsCard(totalRides = sessions.size, totalSeconds = totalSeconds, totalKj = totalKj) }

                grouped.forEach { (label, list) ->
                    item(key = "h-$label") {
                        WeekHeader(label = label, sessions = list)
                    }
                    // A week is one card. Rides inside it are rows of that
                    // week, not seven separate objects that happen to be
                    // adjacent.
                    item(key = "w-$label") {
                        ListCard {
                            list.forEachIndexed { index, session ->
                                SessionRow(
                                    session = session,
                                    onClick = { onNavigateToOverview(session.id) },
                                    onRideAgain = if (session.workoutId in libraryIds) {
                                        { onRideAgain(session.workoutId) }
                                    } else null
                                )
                                if (index != list.lastIndex) CardDivider()
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets(0))) }
            }
        }
    }
}

@Composable
private fun TotalsCard(totalRides: Int, totalSeconds: Long, totalKj: Long) {
    SlateCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2)
        ) {
            Stat(label = "Rides", value = totalRides.toString(), modifier = Modifier.weight(1f))
            Stat(
                label = "Time",
                value = durationLabelLong(totalSeconds),
                modifier = Modifier.weight(1f)
            )
            Stat(label = "kJ", value = totalKj.toString(), modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Week group header: label on the left, that week's own totals on the right,
 * so each group answers "how much did I do that week?" without opening rides.
 */
@Composable
private fun WeekHeader(label: String, sessions: List<WorkoutSession>) {
    val rides = sessions.size
    val secs = sessions.sumOf { it.durationSeconds }
    val kj = sessions.sumOf { (it.avgPower * it.durationSeconds) / 1000L }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.s1, end = Spacing.s1, top = Spacing.s3, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = label,
            style = CadenceType.eyebrow,
            color = CadenceColors.Fg3,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$rides ride${if (rides == 1) "" else "s"} · ${durationLabelLong(secs)} · $kj kJ",
            style = CadenceType.tick,
            color = CadenceColors.Fg3
        )
    }
}

/**
 * Session row with a calendar tile anchoring the eye: weekday + day number
 * on the left, name + one compact meta line, "Ride again" when the workout
 * still exists. The date tile replaces the old date-as-text-line, which made
 * every row start with the least distinguishing information.
 */
@Composable
private fun SessionRow(
    session: WorkoutSession,
    onClick: () -> Unit,
    onRideAgain: (() -> Unit)? = null,
) {
    val cal = remember(session.startTimeEpoch) {
        Calendar.getInstance().apply { timeInMillis = session.startTimeEpoch }
    }
    val weekday = remember(session.startTimeEpoch) {
        SimpleDateFormat("EEE", Locale.getDefault()).format(Date(session.startTimeEpoch))
    }
    val timeStr = remember(session.startTimeEpoch) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(session.startTimeEpoch))
    }
    val kj = (session.avgPower * session.durationSeconds) / 1000L

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = Spacing.s5, end = Spacing.s4, top = Spacing.s3, bottom = Spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DateTile(weekday = weekday, day = cal.get(Calendar.DAY_OF_MONTH))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.workoutName,
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$timeStr · ${durationLabelLong(session.durationSeconds)} · " +
                    "${session.avgPower} W · $kj kJ",
                style = CadenceType.small,
                color = CadenceColors.Fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (onRideAgain != null) {
            RideAgainPill(onClick = onRideAgain)
        }
    }
}

@Composable
private fun DateTile(weekday: String, day: Int) {
    Column(
        modifier = Modifier
            .size(width = 44.dp, height = 48.dp)
            .clip(RoundedCornerShape(Radii.md))
            .background(CadenceColors.Bg2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = weekday.uppercase(),
            style = CadenceType.micro.copy(fontSize = 10.sp),
            color = CadenceColors.Fg3
        )
        Text(
            text = day.toString(),
            style = CadenceType.h3.copy(fontWeight = FontWeight.ExtraBold),
            color = CadenceColors.Fg1
        )
    }
}

/**
 * Habit shortcut: jumps from a past session straight to that workout's
 * detail screen (not directly into a ride - the rider still confirms via
 * the Start CTA there, which also owns trainer gating).
 */
@Composable
private fun RideAgainPill(onClick: () -> Unit) {
    MetaPill(text = "Again", icon = Icons.Outlined.Replay, onClick = onClick)
}

private fun groupByWeek(sessions: List<WorkoutSession>): List<Pair<String, List<WorkoutSession>>> {
    val now = Calendar.getInstance()
    val thisWeekStart = startOfWeek(now)
    val lastWeekStart = (thisWeekStart.clone() as Calendar).apply { add(Calendar.WEEK_OF_YEAR, -1) }
    val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())

    val buckets = LinkedHashMap<String, MutableList<WorkoutSession>>()
    for (s in sessions) {
        val cal = Calendar.getInstance().apply { timeInMillis = s.startTimeEpoch }
        val label = when {
            cal.timeInMillis >= thisWeekStart.timeInMillis -> "This week"
            cal.timeInMillis >= lastWeekStart.timeInMillis -> "Last week"
            else -> monthFmt.format(cal.time)
        }
        buckets.getOrPut(label) { mutableListOf() }.add(s)
    }
    return buckets.map { it.key to it.value.toList() }
}

private fun startOfWeek(c: Calendar): Calendar {
    val out = (c.clone() as Calendar)
    out[Calendar.HOUR_OF_DAY] = 0
    out[Calendar.MINUTE] = 0
    out[Calendar.SECOND] = 0
    out[Calendar.MILLISECOND] = 0
    val firstDow = out.firstDayOfWeek
    while (out[Calendar.DAY_OF_WEEK] != firstDow) {
        out.add(Calendar.DAY_OF_YEAR, -1)
    }
    return out
}
