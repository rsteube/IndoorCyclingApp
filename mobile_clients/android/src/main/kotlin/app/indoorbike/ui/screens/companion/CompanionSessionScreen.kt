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
package app.indoorbike.ui.screens.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SlateCard
import app.indoorbike.ui.components.listBottomPadding
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * What a companion session produced, once the game has gone.
 *
 * ## Why a session needs a screen of its own
 *
 * Every other mode ends one ride at a time, and the post-ride summary is the
 * whole ending. A companion session is a sitting rather than a ride: the rider
 * stays on the bike, the game runs lap after lap, and each lap was already
 * written to history the moment it finished. By the time the game closes there
 * may be four of them, and no single summary is the answer to "what did I just
 * do".
 *
 * So this lists them. It is deliberately not a summary of the whole session
 * added together: the rides are separate rides, they are separate rows in
 * history, and presenting a total here would invent a thing that does not
 * exist anywhere else in the app.
 *
 * A session with exactly one ride never reaches this screen. The caller sends
 * the rider to that ride's own summary instead, which is the ending every other
 * mode gives them and is strictly more informative than a list of one.
 */
@Composable
fun CompanionSessionScreen(
    sessionIds: List<Long>,
    onOpenRide: (Long) -> Unit,
    onDone: () -> Unit,
) {
    val container = LocalAppContainer.current
    var rides by remember { mutableStateOf<List<WorkoutSession>>(emptyList()) }

    // Read back from history rather than trusting what was passed in. These
    // rows have been through the database already, and a course that arrived
    // late means the name and the distance on this screen should be the ones
    // that were actually stored, not the ones that were stored first.
    LaunchedEffect(sessionIds) {
        rides = sessionIds.mapNotNull { container.historyRepository.getSessionById(it) }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        AppBar(
            title = if (rides.size == 1) "Your ride" else "Your rides",
            eyebrow = "Companion session",
            leading = {
                IconBtn(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Done",
                    onClick = onDone,
                )
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).weight(1f),
            contentPadding = PaddingValues(
                start = Spacing.s4,
                end = Spacing.s4,
                bottom = listBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.s3),
        ) {
            item(key = "intro") {
                Text(
                    text = if (rides.isEmpty()) {
                        "The companion disconnected. Nothing was recorded this time: a ride is " +
                            "kept once the game has started one and there is something to keep."
                    } else {
                        "Saved to your history as you rode them. They are in History with " +
                            "everything else, so there is nothing left to do here."
                    },
                    style = CadenceType.body,
                    color = CadenceColors.Fg2,
                    modifier = Modifier.padding(vertical = Spacing.s2),
                )
            }
            items(rides, key = { it.id }) { ride ->
                SessionRideRow(ride = ride, onClick = { onOpenRide(ride.id) })
            }
        }
        Column(modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(Spacing.s4)) {
            PrimaryBtn(
                text = "Done",
                leadingIcon = null,
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SessionRideRow(ride: WorkoutSession, onClick: () -> Unit) {
    SlateCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = ride.workoutName,
                    style = CadenceType.h3,
                    color = CadenceColors.Fg1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.s1))
                Text(
                    text = rideSummaryLine(ride),
                    style = CadenceType.small,
                    color = CadenceColors.Fg3,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = CadenceColors.Fg3,
            )
        }
    }
}

/** Duration always, then whatever else this ride actually has. */
private fun rideSummaryLine(ride: WorkoutSession): String {
    val parts = mutableListOf(formatDuration(ride.durationSeconds.toInt()))
    val ridden = ride.routeDistanceMeters - ride.routeStartMeters
    if (ridden > 1.0) {
        parts += String.format("%.1f km", ridden / 1000.0)
    }
    if (ride.avgPower > 0) parts += ride.avgPower.toString() + " W"
    val climbed = ride.routeElevationGainMeters - ride.routeStartElevationGainMeters
    if (climbed >= 1.0) parts += climbed.roundToInt().toString() + " m up"
    return parts.joinToString(" · ")
}

private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    return when {
        minutes < 1 -> "<1 min"
        minutes < 60 -> "$minutes min"
        else -> (minutes / 60).toString() + " h " + (minutes % 60) + " min"
    }
}
