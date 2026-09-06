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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.LocalAppContainer
import app.indoorbike.library.RepoStatus
import app.indoorbike.shared.ble.TrainerConnectionState
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.DeviceStatusCard
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.UnfinishedRideBanner
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing

/**
 * Home. The question it answers is "what am I riding today?", and nothing else.
 *
 * It used to be the workout library: a list of every workout, with the up-next
 * hero and quick-pick shelves needed to make that list navigable. That made
 * sense when a workout was the only thing a ride could be. It stopped making
 * sense once a ride could also be a road, because a list of workouts is then
 * an answer to a question the rider has not been asked yet.
 *
 * So the shape is now: pick a kind of ride, and choose the specific one on the
 * screen after. Workouts and routes both have their own picker, which is where
 * search, filtering and per-rider estimates belong. Just ride needs no picker
 * and starts from here.
 *
 * Everything below the chooser is pre-ride readiness rather than navigation:
 * what is connected, and whether the repositories the rider follows have
 * published anything since they looked. Both are things you want to know
 * *before* you are clipped in, which is the one moment this screen is
 * reliably on screen.
 */
@Composable
fun HomeScreen(
    eyebrow: String,
    onChooseWorkout: () -> Unit,
    onChooseRoute: () -> Unit,
    onStartFreeRide: () -> Unit,
    onOpenRepositories: () -> Unit,
    onOpenRepo: (manifestUrl: String) -> Unit,
    onOpenTrainerPairing: () -> Unit,
    onOpenHrmPairing: () -> Unit,
    onOpenControllerPairing: () -> Unit,
    onStartFirstRide: () -> Unit,
) {
    val container = LocalAppContainer.current
    val followed by container.followedRepositories.repos.collectAsState()
    val repoStatus by container.followedRepositories.status.collectAsState()
    val refreshing by container.followedRepositories.refreshing.collectAsState()
    val hasCompletedOnboarding by container.userSettings.hasCompletedOnboarding.collectAsState()
    val dismissedStarter by container.userSettings.hasDismissedStarterCard.collectAsState()
    val sessions by container.sessions.collectAsState()
    val unfinished by container.sessionManager.persistedSnapshot.collectAsState()

    // Riding is trainer-gated throughout the app: without one there is nothing
    // to hold a wattage or feel a gradient. The two pickers can be browsed
    // regardless, because choosing is not riding, but Just ride starts
    // immediately and so has to check here.
    val trainerState by container.trainerManager.connectionState.collectAsState()
    val isMockTrainer by container.trainerManager.isMockTrainer.collectAsState()
    val isTrainerLive = trainerState == TrainerConnectionState.Connected || isMockTrainer

    // Throttled inside FollowedRepositories, so landing here repeatedly before
    // a ride costs one check rather than one per visit.
    LaunchedEffect(followed.size) {
        container.followedRepositories.refresh()
    }

    val showFirstRide = hasCompletedOnboarding && !dismissedStarter && sessions.isEmpty()

    Column(Modifier.fillMaxSize().background(CadenceColors.Bg0)) {
        AppBar(title = "Ride", eyebrow = eyebrow)

        LazyColumn(
            contentPadding = PaddingValues(Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4),
        ) {
            // Resuming beats starting: a rider with a ride still on disk almost
            // never wants to begin a second one. The item is only emitted when
            // there is something to show, because an empty item still takes
            // the list's spacing and leaves a hole under the app bar.
            if (unfinished != null) {
                item(key = "unfinished") {
                    UnfinishedRideBanner(dismissable = true)
                }
            }

            if (showFirstRide) {
                item(key = "first-ride") {
                    FirstRideCard(
                        onStart = onStartFirstRide,
                        onDismiss = { container.userSettings.setHasDismissedStarterCard(true) },
                    )
                }
            }

            item(key = "chooser-head") { SectionHead("WHAT ARE YOU RIDING?") }

            item(key = "mode-workout") {
                ModeCard(
                    icon = Icons.AutoMirrored.Outlined.DirectionsBike,
                    title = "Workout",
                    body = "Intervals the trainer holds for you.",
                    onClick = onChooseWorkout,
                )
            }
            item(key = "mode-route") {
                ModeCard(
                    icon = Icons.Outlined.Terrain,
                    title = "Route",
                    body = "A real road, with its hills under the wheel.",
                    onClick = onChooseRoute,
                )
            }
            item(key = "mode-free") {
                ModeCard(
                    icon = Icons.Outlined.Speed,
                    title = "Just ride",
                    body = if (isTrainerLive) "No plan. Set the resistance yourself."
                    else "Pair a trainer to ride freely at your own power.",
                    onClick = if (isTrainerLive) onStartFreeRide else onOpenTrainerPairing,
                )
            }

            item(key = "devices-head") { SectionHead("BEFORE YOU START") }
            item(key = "devices") {
                DeviceStatusCard(
                    onTrainerClick = onOpenTrainerPairing,
                    onHrmClick = onOpenHrmPairing,
                    onControllerClick = onOpenControllerPairing,
                )
            }

            item(key = "repos-head") {
                SectionHead(
                    title = "REPOSITORIES",
                    hint = if (refreshing) "checking…" else null,
                )
            }
            item(key = "repos") {
                RepositoriesCard(
                    followed = followed.map { it.id to it.name },
                    status = repoStatus,
                    refreshing = refreshing,
                    onOpenRepo = { repoId ->
                        followed.firstOrNull { it.id == repoId }?.let { onOpenRepo(it.manifestUrl) }
                    },
                    onManage = onOpenRepositories,
                )
            }
        }
    }
}

// ─── The chooser ─────────────────────────────────────────────────────────────

@Composable
private fun ModeCard(
    icon: ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Radii.lg)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .clickable(onClick = onClick)
            .padding(Spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CadenceColors.Brand500.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = CadenceColors.Brand500, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = CadenceType.h3, color = CadenceColors.Fg1)
            Spacer(Modifier.height(2.dp))
            Text(body, style = CadenceType.small, color = CadenceColors.Fg3)
        }
        Text("›", style = CadenceType.h2, color = CadenceColors.Brand500)
    }
}

// ─── Repositories ────────────────────────────────────────────────────────────

/**
 * What the followed repositories have been up to. Deliberately a summary and
 * not a second browser: the point is to notice that something is new, which is
 * a glance, and then go and look, which is a different screen.
 */
@Composable
private fun RepositoriesCard(
    followed: List<Pair<String, String>>,
    status: Map<String, RepoStatus>,
    refreshing: Boolean,
    onOpenRepo: (String) -> Unit,
    onManage: () -> Unit,
) {
    val shape = RoundedCornerShape(Radii.md)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape),
    ) {
        if (followed.isEmpty()) {
            Column(Modifier.padding(Spacing.s4)) {
                Text(
                    "Not following any repositories.",
                    style = CadenceType.bodyStrong,
                    color = CadenceColors.Fg1,
                )
                Spacer(Modifier.height(Spacing.s1))
                Text(
                    "Follow one and this tells you when it publishes a workout or a road you do not have yet.",
                    style = CadenceType.tick,
                    color = CadenceColors.Fg3,
                )
            }
        } else {
            for ((repoId, name) in followed) {
                RepoRow(
                    name = name,
                    status = status[repoId],
                    onClick = { onOpenRepo(repoId) },
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onManage)
                .padding(horizontal = Spacing.s4, vertical = Spacing.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.LibraryBooks,
                contentDescription = null,
                tint = CadenceColors.Fg3,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Spacing.s2))
            Text(
                if (followed.isEmpty()) "Find repositories" else "Manage repositories",
                style = CadenceType.bodyStrong,
                color = CadenceColors.Brand500,
                modifier = Modifier.weight(1f),
            )
            if (refreshing) {
                CircularProgressIndicator(
                    color = CadenceColors.Brand500,
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text("›", style = CadenceType.bodyStrong, color = CadenceColors.Brand500)
            }
        }
    }
}

@Composable
private fun RepoRow(name: String, status: RepoStatus?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.s4, vertical = Spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = CadenceType.bodyStrong.copy(fontSize = 14.sp),
                color = CadenceColors.Fg1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                repoStatusLine(status),
                style = CadenceType.tick,
                color = repoStatusColor(status),
            )
        }
        val total = status?.total ?: 0
        if (total > 0) {
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(CadenceColors.Brand500)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("$total new", style = CadenceType.tick, color = CadenceColors.Bg0)
            }
        } else if (status is RepoStatus.Checking) {
            CircularProgressIndicator(
                color = CadenceColors.Fg4,
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

private fun repoStatusLine(status: RepoStatus?): String = when (status) {
    null, is RepoStatus.Idle -> {
        val checked = (status as? RepoStatus.Idle)?.checkedEpoch
        when {
            checked == null -> "Not checked yet"
            status.total > 0 -> describeNew(status)
            else -> "Up to date · checked ${relativeTime(checked)}"
        }
    }
    is RepoStatus.Checking -> "Checking…"
    // A repo being unreachable does not un-publish what it published before,
    // so anything already known to be new is still worth saying.
    is RepoStatus.Failed ->
        if (status.total > 0) "${describeNew(status)} · last check failed"
        else "Could not reach it · ${status.message}"
}

private fun describeNew(status: RepoStatus): String {
    val parts = buildList {
        if (status.newWorkouts > 0) add("${status.newWorkouts} workout${if (status.newWorkouts == 1) "" else "s"}")
        if (status.newRoutes > 0) add("${status.newRoutes} route${if (status.newRoutes == 1) "" else "s"}")
    }
    return parts.joinToString(" and ") + " you do not have"
}

private fun repoStatusColor(status: RepoStatus?): Color = when {
    status is RepoStatus.Failed && status.total == 0 -> CadenceColors.Danger
    (status?.total ?: 0) > 0 -> CadenceColors.Brand500
    else -> CadenceColors.Fg3
}

/** Coarse on purpose: the useful distinction is "just now" against "a while ago". */
private fun relativeTime(epoch: Long): String {
    val minutes = (System.currentTimeMillis() - epoch) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} d ago"
    }
}

// ─── First ride ──────────────────────────────────────────────────────────────

@Composable
private fun FirstRideCard(onStart: () -> Unit, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(Radii.lg)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Brand500.copy(alpha = 0.10f))
            .border(1.dp, CadenceColors.Brand500.copy(alpha = 0.45f), shape)
            .clickable(onClick = onStart)
            .padding(Spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("START HERE · YOUR FIRST RIDE", style = CadenceType.eyebrow, color = CadenceColors.Brand500)
            Spacer(Modifier.height(Spacing.s1))
            Text("First Spin", style = CadenceType.h3, color = CadenceColors.Fg1)
            Text(
                "Fifteen minutes, easy, with a taste of each zone.",
                style = CadenceType.tick,
                color = CadenceColors.Fg2,
            )
        }
        IconBtn(Icons.Outlined.Close, "Dismiss", onClick = onDismiss, tint = CadenceColors.Fg3)
    }
}

// ─── Shared bits ─────────────────────────────────────────────────────────────

@Composable
private fun SectionHead(title: String, hint: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = CadenceType.eyebrow, color = CadenceColors.Fg3, modifier = Modifier.weight(1f))
        if (hint != null) {
            Text(hint, style = CadenceType.tick, color = CadenceColors.Fg4)
        }
    }
}
