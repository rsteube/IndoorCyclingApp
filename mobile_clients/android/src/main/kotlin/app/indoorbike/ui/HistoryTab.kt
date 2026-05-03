package app.indoorbike.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.progression.durationLabelLong
import app.indoorbike.shared.progression.totalDurationSeconds
import app.indoorbike.shared.progression.totalKjAcross
import app.indoorbike.ui.components.AppBar
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
    onNavigateToOverview: (Long) -> Unit
) {
    val container = LocalAppContainer.current
    val sessions by container.historyRepository.getAllSessions().collectAsState(initial = emptyList())
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
    ) {
        AppBar(
            title = "Ride History",
            eyebrow = "Past Activity"
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
                        tint = CadenceColors.Brand500.copy(alpha = 0.5f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(Spacing.s4))
                    Text(
                        text = "No rides yet",
                        style = CadenceType.h2,
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
                }
            }
        } else {
            val totalSeconds = remember(sessions) { totalDurationSeconds(sessions) }
            val totalKj = remember(sessions) { totalKjAcross(sessions) }
            val grouped = remember(sessions) { groupByWeek(sessions) }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.s5, vertical = Spacing.s4),
                verticalArrangement = Arrangement.spacedBy(Spacing.s3)
            ) {
                item { TotalsCard(totalRides = sessions.size, totalSeconds = totalSeconds, totalKj = totalKj) }

                grouped.forEach { (label, list) ->
                    item(key = "h-$label") {
                        Text(
                            text = label.uppercase(),
                            style = CadenceType.eyebrow,
                            color = CadenceColors.Fg3,
                            modifier = Modifier.padding(top = Spacing.s2, bottom = Spacing.s1)
                        )
                    }
                    items(list, key = { it.id }) { session ->
                        SessionRow(session = session, onClick = { onNavigateToOverview(session.id) })
                    }
                }
                item { Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets(0))) }
            }
        }
    }
}

@Composable
private fun TotalsCard(totalRides: Int, totalSeconds: Long, totalKj: Long) {
    val shape = RoundedCornerShape(Radii.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .padding(Spacing.s4),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TotalCell(label = "RIDES", value = totalRides.toString())
        TotalCell(label = "TIME", value = durationLabelLong(totalSeconds))
        TotalCell(label = "kJ", value = totalKj.toString())
    }
}

@Composable
private fun TotalCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = CadenceType.eyebrow, color = CadenceColors.Fg3)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, style = CadenceType.h3, color = CadenceColors.Fg1)
    }
}

@Composable
private fun SessionRow(session: WorkoutSession, onClick: () -> Unit) {
    val fmt = remember { SimpleDateFormat("EEE d MMM · h:mm a", Locale.getDefault()) }
    val dateStr = fmt.format(Date(session.startTimeEpoch))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line1, RoundedCornerShape(Radii.md))
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Text(text = dateStr, style = CadenceType.small, color = CadenceColors.Brand500)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = session.workoutName, style = CadenceType.h3, color = CadenceColors.Fg1)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = durationLabelLong(session.durationSeconds),
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg2
            )
            Text(text = "${session.avgPower} W", style = CadenceType.bodyStrong, color = CadenceColors.Fg2)
            Text(
                text = "${(session.avgPower * session.durationSeconds) / 1000L} kJ",
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg2
            )
        }
    }
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
