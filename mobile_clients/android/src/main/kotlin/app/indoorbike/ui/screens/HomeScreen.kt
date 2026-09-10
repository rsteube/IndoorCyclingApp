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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import app.indoorbike.ui.components.marginRidges
import app.indoorbike.ui.components.ContentMaxWidth
import app.indoorbike.ui.components.tabRootBottomPadding
import app.indoorbike.ui.components.rememberCollapsingHeaderState
import app.indoorbike.ui.components.CardDivider
import app.indoorbike.ui.components.CountPill
import app.indoorbike.ui.components.DeviceStatusCard
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.ListCard
import app.indoorbike.ui.components.SectionLabel
import app.indoorbike.ui.components.UnfinishedRideBanner
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.PressScale
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.SlateMotion
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.theme.enterReveal
import app.indoorbike.ui.theme.pressScale

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
    onChooseCompanion: () -> Unit,
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

    val header = rememberCollapsingHeaderState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
            .marginRidges()
            .nestedScroll(header.nestedScrollConnection)
    ) {
        AppBar(title = "What are you riding?", eyebrow = eyebrow, header = header)

        LazyColumn(
            // Left aligned, not centred: the screen title above it is, and a
            // capped list floating in the middle of the window while its own
            // heading sits at the far left reads as two screens.
            modifier = Modifier.widthIn(max = ContentMaxWidth),
            contentPadding = PaddingValues(
                start = Spacing.s5,
                end = Spacing.s5,
                top = Spacing.s4,
                bottom = tabRootBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.s3),
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

            // The three choices arrive one after the other, then the readiness
            // cards follow. The stagger is what makes the screen read as a
            // list of options being laid out rather than a wall appearing.
            item(key = "mode-workout") {
                ModeCard(
                    icon = Icons.AutoMirrored.Outlined.DirectionsBike,
                    title = "Workout",
                    body = "Intervals the trainer holds for you.",
                    onClick = onChooseWorkout,
                    modifier = Modifier.enterReveal(index = 0),
                )
            }
            item(key = "mode-route") {
                ModeCard(
                    icon = Icons.Outlined.Terrain,
                    title = "Route",
                    body = "A real road, with its hills under the wheel.",
                    onClick = onChooseRoute,
                    stage = "Beta",
                    modifier = Modifier.enterReveal(index = 1),
                )
            }
            item(key = "mode-free") {
                ModeCard(
                    icon = Icons.Outlined.Speed,
                    title = "Just ride",
                    body = if (isTrainerLive) "No plan. Set the resistance yourself."
                    else "Pair a trainer to ride freely at your own power.",
                    onClick = if (isTrainerLive) onStartFreeRide else onOpenTrainerPairing,
                    modifier = Modifier.enterReveal(index = 2),
                )
            }

            item(key = "mode-companion") {
                ModeCard(
                    icon = Icons.Outlined.Cast,
                    title = "Companion",
                    // Says what it needs before the tap, not after. Somebody
                    // who does not have a second app should be able to tell
                    // that from here rather than from a pairing code they
                    // have nothing to type into.
                    body = if (isTrainerLive)
                        "Hand the trainer to a game or app on another device. Needs one running."
                    else "Pair a trainer, and a companion app on another device.",
                    onClick = if (isTrainerLive) onChooseCompanion else onOpenTrainerPairing,
                    stage = "Alpha",
                    modifier = Modifier.enterReveal(index = 3),
                )
            }

            item(key = "devices-head") {
                SectionLabel(
                    "Before you start",
                    Modifier.padding(top = Spacing.s5).enterReveal(index = 3),
                )
            }
            item(key = "devices") {
                Box(Modifier.enterReveal(index = 3)) {
                    DeviceStatusCard(
                        onTrainerClick = onOpenTrainerPairing,
                        onHrmClick = onOpenHrmPairing,
                        onControllerClick = onOpenControllerPairing,
                    )
                }
            }

            item(key = "repos-head") {
                SectionLabel(
                    text = "Repositories",
                    modifier = Modifier.padding(top = Spacing.s5).enterReveal(index = 4),
                    hint = if (refreshing) "Checking…" else null,
                )
            }
            item(key = "repos") {
                Box(Modifier.enterReveal(index = 4)) {
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
}

// ─── The chooser ─────────────────────────────────────────────────────────────

@Composable
private fun ModeCard(
    icon: ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
    /**
     * How finished this mode is: "Beta", "Alpha", or null for shipped. Marks a
     * mode still being built, so nobody meets a rough edge unwarned.
     */
    stage: String? = null,
    modifier: Modifier = Modifier,
) {
    // A card gives a little under the finger and darkens one step, the same
    // answer every pressable surface in the app gives.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        targetValue = if (pressed) CadenceColors.Bg2 else CadenceColors.Bg1,
        animationSpec = tween(SlateMotion.Fast, easing = SlateMotion.Easing),
        label = "modeCardBg",
    )
    Row(
        modifier
            .fillMaxWidth()
            .pressScale(interaction, PressScale.Card)
            .clip(RoundedCornerShape(Radii.lg))
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(Spacing.s5),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s4),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(CadenceColors.Bg2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = CadenceColors.Fg1, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = CadenceType.h3, color = CadenceColors.Fg1)
                if (stage != null) {
                    Spacer(Modifier.width(Spacing.s2))
                    StageTag(stage)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                body,
                style = CadenceType.body.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = CadenceColors.Fg2,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = CadenceColors.Fg3,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Says how finished a feature is. Bg2 rather than a colour of its own: the
 * palette keeps saturation for power zones, and a label that shouted would be
 * competing with the ride the rider came here to start.
 *
 * The word carries the weight instead. "Alpha" on a mode that cannot work by
 * itself is worth more than a colour, because the thing a rider needs to know
 * is not "this is rough" but "this needs something you may not have".
 */
@Composable
private fun StageTag(text: String) {
    Text(
        text = text,
        style = CadenceType.micro,
        color = CadenceColors.Fg3,
        modifier = Modifier
            .clip(CircleShape)
            .background(CadenceColors.Bg2)
            .padding(horizontal = Spacing.s2, vertical = 2.dp),
    )
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
    ListCard {
        if (followed.isEmpty()) {
            Column(Modifier.padding(horizontal = Spacing.s5, vertical = 14.dp)) {
                Text(
                    "Not following any repositories.",
                    style = CadenceType.bodyStrong,
                    color = CadenceColors.Fg1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Follow one and this tells you when it publishes a workout or a road you do not have yet.",
                    style = CadenceType.small,
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
                CardDivider()
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onManage)
                .padding(horizontal = Spacing.s5, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.LibraryBooks,
                contentDescription = null,
                tint = CadenceColors.Fg2,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                if (followed.isEmpty()) "Find repositories" else "Manage repositories",
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1,
                modifier = Modifier.weight(1f),
            )
            if (refreshing) {
                CircularProgressIndicator(
                    color = CadenceColors.Fg3,
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = CadenceColors.Fg3,
                    modifier = Modifier.size(22.dp),
                )
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
            .padding(horizontal = Spacing.s5, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = CadenceType.bodyStrong,
                color = CadenceColors.Fg1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                repoStatusLine(status),
                style = CadenceType.small,
                color = repoStatusColor(status),
            )
        }
        val total = status?.total ?: 0
        if (total > 0) {
            Spacer(Modifier.width(Spacing.s3))
            CountPill("$total new")
        } else if (status is RepoStatus.Checking) {
            Spacer(Modifier.width(Spacing.s3))
            CircularProgressIndicator(
                color = CadenceColors.Fg3,
                modifier = Modifier.size(14.dp),
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
    // No accent to promote a line with: what marks "something new" is the count
    // pill beside it, so the sub-line only has to step up out of Fg3.
    (status?.total ?: 0) > 0 -> CadenceColors.Fg2
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

/**
 * The one card in the app that inverts. With no accent colour left, a promoted
 * card cannot be tinted into prominence, so it swaps ground for figure instead:
 * an Fg1 fill on the slate canvas, which is louder than anything else on the
 * screen and still part of the same two-colour system.
 */
@Composable
private fun FirstRideCard(onStart: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.lg))
            .background(CadenceColors.Fg1)
            .clickable(onClick = onStart)
            .padding(start = Spacing.s5, top = Spacing.s5, end = Spacing.s3, bottom = Spacing.s5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Start here · your first ride", style = CadenceType.eyebrow, color = CadenceColors.Bg3)
            Spacer(Modifier.height(Spacing.s1))
            Text("First Spin", style = CadenceType.h3, color = CadenceColors.OnBrand)
            Spacer(Modifier.height(2.dp))
            Text(
                "Fifteen minutes, easy, with a taste of each zone.",
                style = CadenceType.small,
                color = CadenceColors.Bg2,
            )
        }
        IconBtn(
            Icons.Outlined.Close,
            "Dismiss",
            onClick = onDismiss,
            background = Color.Transparent,
            tint = CadenceColors.Bg2,
        )
    }
}
