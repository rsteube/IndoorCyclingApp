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
package app.indoorbike.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.progression.Badge
import app.indoorbike.shared.progression.WeekActivity
import app.indoorbike.shared.progression.computeBadges
import app.indoorbike.shared.progression.computeRiderLevel
import app.indoorbike.shared.progression.computeStreak
import app.indoorbike.shared.util.getCurrentEpochMillis
import java.util.TimeZone
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.marginRidges
import app.indoorbike.ui.components.ContentMaxWidth
import app.indoorbike.ui.components.tabRootBottomPadding
import app.indoorbike.ui.components.rememberCollapsingHeaderState
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.SlateCard
import app.indoorbike.ui.components.dashedOutline
import app.indoorbike.ui.components.UnfinishedRideBanner
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.SlateMotion
import app.indoorbike.ui.theme.Spacing
import app.indoorbike.ui.theme.rememberReducedMotion
import kotlin.math.roundToInt

/**
 * The one screen that's allowed to be playful. Everything animates on entry:
 * the XP bar fills, the XP number counts up, the streak boxes grow oldest to
 * newest, the badge tiles pop in one after another. All of it is decoration on
 * top of the same pure functions in shared/progression - nothing here changes
 * what a ride is worth.
 *
 * The motion survived the redesign; the colour did not. Where a gradient or a
 * flame used to carry the reward, it is now the plain white fill against slate,
 * which is the loudest thing this palette has.
 */
@Composable
fun ProgressTab(onNavigateToRides: () -> Unit = {}) {
    val container = LocalAppContainer.current
    val sessions by container.sessions.collectAsState()
    val weightKg by container.userSettings.weightKg.collectAsState()
    val now = remember { getCurrentEpochMillis() }
    val tzOffset = remember { TimeZone.getDefault().getOffset(now).toLong() }
    val streak = remember(sessions) { computeStreak(sessions, now, timeZoneOffsetMs = tzOffset) }
    val level = remember(sessions, weightKg) { computeRiderLevel(sessions, weightKg) }
    val badges = remember(sessions) { computeBadges(sessions, now, timeZoneOffsetMs = tzOffset) }

    val header = rememberCollapsingHeaderState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
            .marginRidges()
            .nestedScroll(header.nestedScrollConnection)
    ) {
        AppBar(
            header = header,
            title = "Your progress",
            eyebrow = "Stats & XP"
        )

        UnfinishedRideBanner(
            // Sits above the list, so it needs the list's cap of its own.
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .padding(horizontal = Spacing.s5, vertical = Spacing.s2)
        )

        if (sessions.isEmpty()) {
            EmptyProgress(onNavigateToRides)
            return
        }

        LazyColumn(
            // Capped and left aligned, matching the screen title above it.
            modifier = Modifier.fillMaxHeight().widthIn(max = ContentMaxWidth),
            contentPadding = PaddingValues(
                start = Spacing.s5,
                end = Spacing.s5,
                top = Spacing.s4,
                bottom = tabRootBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.s3)
        ) {
            item {
                LevelCard(
                    level = level.level,
                    totalXp = level.totalXp,
                    ceilingXp = level.levelCeilingXp,
                    progress = level.progressFraction
                )
            }
            item { WeeklyStreakCard(streak.currentWeeks, streak.longestWeeks, streak.recentWeekActivity) }
            item { BadgesSection(unlocked = badges) }
        }
    }
}

@Composable
private fun EmptyProgress(onNavigateToRides: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.s5),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.TrendingUp,
                contentDescription = null,
                tint = CadenceColors.Fg3,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(Spacing.s4))
            Text(
                text = "Ready to level up?",
                style = CadenceType.h1,
                color = CadenceColors.Fg1,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(Spacing.s2))
            Text(
                text = "Complete workouts to track your fitness\ngains and XP over time.",
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
}

// ─── Level ────────────────────────────────────────────────────────────────────

@Composable
private fun LevelCard(
    level: Int,
    totalXp: Double,
    ceilingXp: Double,
    progress: Float
) {
    // Entry animation state, shared by the bar fill and the XP count-up so
    // they land together. Restarts if the underlying values change (a new
    // ride finished while this tab was alive) - that replay is the reward.
    val entry = remember(totalXp) { Animatable(0f) }
    LaunchedEffect(totalXp) {
        entry.animateTo(1f, tween(durationMillis = 1100, easing = FastOutSlowInEasing))
    }
    val shownProgress = progress * entry.value
    val shownXp = totalXp * entry.value

    SlateCard(padding = PaddingValues(Spacing.s6)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LevelMedallion(level = level, entry = entry.value)
            Spacer(modifier = Modifier.width(Spacing.s4))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Rider level", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = levelTitle(level),
                    style = CadenceType.h2.copy(fontSize = 20.sp),
                    color = CadenceColors.Fg1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${formatXp(shownXp)} XP total",
                    style = CadenceType.small,
                    color = CadenceColors.Fg3
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.s5))

        // XP bar: a flat Fg1 fill on Bg2. The gradient it replaced was three
        // shades of an accent colour that no longer exists, and the animation
        // was always the thing that made this read as progress.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(CadenceColors.Bg2)
        ) {
            if (shownProgress > 0.005f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(shownProgress)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(CadenceColors.Fg1)
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${formatXp((ceilingXp - totalXp).coerceAtLeast(0.0))} XP to level ${level + 1}",
                style = CadenceType.small.copy(fontWeight = FontWeight.SemiBold),
                color = CadenceColors.Fg2,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${(progress * 100).roundToInt()}%",
                style = CadenceType.small.copy(fontWeight = FontWeight.SemiBold),
                color = CadenceColors.Fg2
            )
        }
        Spacer(modifier = Modifier.height(Spacing.s3))
        Text(
            text = "XP = watt-minutes per kilogram. Harder efforts and longer rides earn more.",
            style = CadenceType.small,
            color = CadenceColors.Fg3
        )
    }
}

/**
 * The level number on a plain Bg2 disc. It springs in with a touch of
 * overshoot - the one thing on this card allowed to bounce, because it is the
 * rank, and a rank should land with some weight.
 */
@Composable
private fun LevelMedallion(level: Int, entry: Float) {
    val reduce = rememberReducedMotion()
    val pop = remember { Animatable(if (reduce) 1f else 0.6f) }
    LaunchedEffect(level, reduce) {
        if (reduce) { pop.snapTo(1f); return@LaunchedEffect }
        pop.snapTo(0.6f)
        pop.animateTo(1f, SlateMotion.Pop)
    }
    Box(
        modifier = Modifier
            .size(64.dp)
            .scale(pop.value)
            .clip(CircleShape)
            .background(CadenceColors.Bg2),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = level.toString(),
            style = CadenceType.h1,
            color = CadenceColors.Fg1
        )
    }
}

/** Playful rank names so a level number carries a little identity. */
private fun levelTitle(level: Int): String = when (level) {
    0 -> "Rolling Out"
    1 -> "Commuter"
    2 -> "Weekend Warrior"
    3 -> "Puncheur"
    4 -> "Rouleur"
    5 -> "Domestique"
    6 -> "Breakaway Artist"
    7 -> "Climber"
    8 -> "Time Trialist"
    9 -> "Hour Record Chaser"
    else -> "Legend of the Pain Cave"
}

// ─── Streak ───────────────────────────────────────────────────────────────────

@Composable
private fun WeeklyStreakCard(currentWeeks: Int, longestWeeks: Int, recent: List<WeekActivity>) {
    // Week boxes grow bottom-up on entry, oldest to newest, so the chart
    // "replays" the rider's consistency every time they visit.
    val entry = remember(recent) { Animatable(0f) }
    LaunchedEffect(recent) {
        entry.animateTo(1f, tween(durationMillis = 900, easing = FastOutSlowInEasing))
    }

    SlateCard(padding = PaddingValues(Spacing.s6)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Weekly streak",
                    style = CadenceType.eyebrow,
                    color = CadenceColors.Fg3
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = currentWeeks.toString(),
                        style = CadenceType.h1,
                        color = CadenceColors.Fg1
                    )
                    Text(
                        text = if (currentWeeks == 1) " week" else " weeks",
                        style = CadenceType.bodyStrong.copy(fontSize = 16.sp),
                        color = CadenceColors.Fg3,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
            Text(
                text = "Best · $longestWeeks",
                style = CadenceType.eyebrow,
                color = CadenceColors.Fg3
            )
        }
        Spacer(modifier = Modifier.height(Spacing.s4))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            recent.forEachIndexed { i, week ->
                // Stagger: each box only starts growing once the entry
                // value passes its slot, oldest first.
                val slotStart = i.toFloat() / recent.size
                val local = ((entry.value - slotStart) / (1f - slotStart).coerceAtLeast(0.05f))
                    .coerceIn(0f, 1f)
                WeekBox(
                    daysActive = week.daysActive,
                    growth = local,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * One box on the streak chart = one week. Fills bottom-up by 1/7 per ride day,
 * so a 4-day week is just over half full. Empty weeks read as a flat dim base.
 */
@Composable
private fun WeekBox(daysActive: Int, growth: Float = 1f, modifier: Modifier = Modifier) {
    val safeDays = daysActive.coerceIn(0, 7)
    val fillFraction = (safeDays / 7f) * growth
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(Radii.sm))
            .background(CadenceColors.Bg2)
    ) {
        // Bottom-anchored fill: place a child Box that fills (fraction) of the
        // height and align it to the bottom of the parent.
        if (fillFraction > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(fillFraction)
                    .background(CadenceColors.Fg1)
            )
        }
    }
}

// ─── Badges ───────────────────────────────────────────────────────────────────

/**
 * Full badge catalog, mirrored from shared/progression's computeBadges ids.
 * The shared function only returns *earned* badges; showing the locked ones
 * greyed-out with their requirement is what makes the grid a goal list
 * instead of a trophy shelf.
 */
private data class BadgeDef(
    val id: String,
    val title: String,
    val lockedHint: String,
    val icon: ImageVector,
)

private val BADGE_CATALOG = listOf(
    BadgeDef("first-ride", "First Ride", "Complete a ride", Icons.Outlined.Flag),
    BadgeDef("five-rides", "Five Strong", "Log 5 rides", Icons.Outlined.EmojiEvents),
    BadgeDef("first-hour", "First Hour", "1 h total saddle time", Icons.Outlined.Schedule),
    BadgeDef("kilojoule-club", "Kilojoule Club", "Burn 1,000 kJ", Icons.Outlined.Bolt),
    BadgeDef("consistency-3", "Three in a Row", "Ride 3 weeks in a row", Icons.Outlined.LocalFireDepartment),
    BadgeDef("consistency-7", "On Fire", "Ride 7 weeks in a row", Icons.Outlined.Whatshot),
)

@Composable
private fun BadgesSection(unlocked: List<Badge>) {
    val unlockedById = remember(unlocked) { unlocked.associateBy { it.id } }
    val unlockedCount = BADGE_CATALOG.count { it.id in unlockedById }

    // Staggered pop-in across the whole grid.
    val entry = remember(unlockedCount) { Animatable(0f) }
    LaunchedEffect(unlockedCount) {
        entry.animateTo(1f, tween(durationMillis = 900, easing = FastOutSlowInEasing))
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.s3),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Badges",
                style = CadenceType.eyebrow,
                color = CadenceColors.Fg3,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$unlockedCount / ${BADGE_CATALOG.size}",
                style = CadenceType.eyebrow,
                color = CadenceColors.Fg3
            )
        }
        BADGE_CATALOG.chunked(2).forEachIndexed { rowIdx, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s3)) {
                row.forEachIndexed { colIdx, def ->
                    val idx = rowIdx * 2 + colIdx
                    val slotStart = idx.toFloat() / BADGE_CATALOG.size
                    val local = ((entry.value - slotStart) / (1f - slotStart).coerceAtLeast(0.05f))
                        .coerceIn(0f, 1f)
                    BadgeTile(
                        def = def,
                        earned = unlockedById[def.id],
                        appear = local,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(Spacing.s3))
        }
    }
}

/**
 * Earned badges are a card with a white disc; locked ones are a dashed outline
 * on the canvas with the disc knocked back to Bg1. Earned reads as a thing you
 * have, locked as a space where a thing would go.
 */
@Composable
private fun BadgeTile(
    def: BadgeDef,
    earned: Badge?,
    appear: Float,
    modifier: Modifier = Modifier,
) {
    val unlocked = earned != null
    // Earned tiles spring in a size up from small with a little overshoot;
    // locked ones simply fade to their place, since an empty slot has nothing
    // to celebrate.
    val reduce = rememberReducedMotion()
    val pop = remember { Animatable(if (reduce || !unlocked) 1f else 0.8f) }
    LaunchedEffect(unlocked, reduce) {
        if (reduce || !unlocked) { pop.snapTo(1f); return@LaunchedEffect }
        pop.snapTo(0.8f)
        pop.animateTo(1f, SlateMotion.Pop)
    }
    Column(
        modifier = modifier
            .graphicsLayer {
                alpha = appear
                val s = pop.value
                scaleX = s
                scaleY = s
            }
            .clip(RoundedCornerShape(Radii.lg))
            .then(
                if (unlocked) Modifier.background(CadenceColors.Bg1)
                else Modifier.dashedOutline()
            )
            .padding(Spacing.s5),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (unlocked) CadenceColors.Fg1 else CadenceColors.Bg1),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (unlocked) def.icon else Icons.Outlined.Lock,
                contentDescription = null,
                tint = if (unlocked) CadenceColors.OnBrand else CadenceColors.Fg3,
                modifier = Modifier.size(24.dp)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = def.title,
                style = CadenceType.bodyStrong,
                color = if (unlocked) CadenceColors.Fg1 else CadenceColors.Fg2,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = earned?.subtitle ?: def.lockedHint,
                style = CadenceType.tick,
                color = CadenceColors.Fg3,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatXp(xp: Double): String {
    // Below 1000 show whole numbers (avoid noisy decimals on small values);
    // above 1000 use a "1.2k" form so the headline doesn't blow out width.
    return when {
        xp < 1_000.0 -> xp.roundToInt().toString()
        xp < 100_000.0 -> {
            val k = xp / 1_000.0
            // one decimal, e.g. 12.4k
            val whole = k.toInt()
            val frac = ((k - whole) * 10).toInt()
            if (frac == 0) "${whole}k" else "$whole.${frac}k"
        }
        else -> "${(xp / 1_000.0).roundToInt()}k"
    }
}
