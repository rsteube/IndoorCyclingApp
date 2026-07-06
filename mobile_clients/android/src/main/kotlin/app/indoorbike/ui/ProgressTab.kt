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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
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
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.components.UnfinishedRideBanner
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * The one screen that's allowed to be playful. Everything animates on entry:
 * the XP bar fills, the XP number counts up, the streak flame breathes, the
 * badge tiles pop in one after another. All of it is decoration on top of the
 * same pure functions in shared/progression - nothing here changes what a
 * ride is worth.
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
    ) {
        AppBar(
            title = "Your Progress",
            eyebrow = "Stats & XP"
        )

        UnfinishedRideBanner(
            modifier = Modifier.padding(horizontal = Spacing.s5, vertical = Spacing.s2)
        )

        if (sessions.isEmpty()) {
            EmptyProgress(onNavigateToRides)
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Spacing.s5, vertical = Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4)
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
                tint = CadenceColors.Brand500.copy(alpha = 0.5f),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(Spacing.s4))
            Text(
                text = "Ready to level up?",
                style = CadenceType.h2,
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

    val shape = RoundedCornerShape(Radii.lg)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .background(
                Brush.verticalGradient(
                    listOf(CadenceColors.Brand500.copy(alpha = 0.08f), androidx.compose.ui.graphics.Color.Transparent)
                )
            )
            .border(1.dp, CadenceColors.Brand500.copy(alpha = 0.35f), shape)
            .padding(Spacing.s5)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LevelMedallion(level = level, entry = entry.value)
            Spacer(modifier = Modifier.width(Spacing.s4))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "RIDER LEVEL", style = CadenceType.eyebrow, color = CadenceColors.Brand500)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = levelTitle(level),
                    style = CadenceType.h2,
                    color = CadenceColors.Fg1
                )
                Text(
                    text = "${formatXp(shownXp)} XP total",
                    style = CadenceType.tick,
                    color = CadenceColors.Fg3
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.s4))

        // XP bar: animated fill with a bright head so progress reads as
        // motion, not just a static amount.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(CadenceColors.Bg0)
        ) {
            if (shownProgress > 0.005f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(shownProgress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(CadenceColors.Brand700, CadenceColors.Brand500, CadenceColors.Brand300)
                            )
                        )
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.s2))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${formatXp((ceilingXp - totalXp).coerceAtLeast(0.0))} XP TO LEVEL ${level + 1}",
                style = CadenceType.tick,
                color = CadenceColors.Fg2,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${(progress * 100).roundToInt()}%",
                style = CadenceType.tick,
                color = CadenceColors.Brand500
            )
        }
        Spacer(modifier = Modifier.height(Spacing.s3))
        Text(
            text = "XP = watt-minutes per kilogram. Harder efforts and longer rides earn more.",
            style = CadenceType.small,
            color = CadenceColors.Fg4
        )
    }
}

/** Big level number in a ringed medallion that pops in with the entry animation. */
@Composable
private fun LevelMedallion(level: Int, entry: Float) {
    val pop = 0.7f + 0.3f * entry
    Box(
        modifier = Modifier
            .size(72.dp)
            .scale(pop)
            .clip(CircleShape)
            .background(CadenceColors.Brand500.copy(alpha = 0.14f))
            .border(2.dp, CadenceColors.Brand500, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "LVL",
                style = CadenceType.tick.copy(fontSize = 9.sp),
                color = CadenceColors.Brand500
            )
            Text(
                text = level.toString(),
                style = CadenceType.h1,
                color = CadenceColors.Fg1
            )
        }
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

    val shape = RoundedCornerShape(Radii.lg)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
    ) {
        Column(modifier = Modifier.padding(Spacing.s5)) {
            Text(
                text = "WEEKLY STREAK",
                style = CadenceType.eyebrow,
                color = CadenceColors.Fg3
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                if (currentWeeks > 0) {
                    StreakFlame()
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = currentWeeks.toString(),
                    style = CadenceType.displayM,
                    color = CadenceColors.Fg1
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (currentWeeks == 1) "week" else "weeks",
                    style = CadenceType.h3,
                    color = CadenceColors.Fg3,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "BEST · $longestWeeks",
                    style = CadenceType.tick,
                    color = CadenceColors.Fg3
                )
            }
            Spacer(modifier = Modifier.height(Spacing.s4))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
}

/** Gently breathing flame next to a live streak - alive, not distracting. */
@Composable
private fun StreakFlame() {
    val breath = rememberInfiniteTransition(label = "flame")
    val scale by breath.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flameScale"
    )
    Icon(
        imageVector = Icons.Outlined.LocalFireDepartment,
        contentDescription = null,
        tint = CadenceColors.Brand500,
        modifier = Modifier
            .size(30.dp)
            .padding(bottom = 2.dp)
            .scale(scale)
    )
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
            .height(34.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(CadenceColors.Bg3)
    ) {
        // Bottom-anchored fill: place a child Box that fills (fraction) of the
        // height and align it to the bottom of the parent.
        if (fillFraction > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(fillFraction)
                    .clip(RoundedCornerShape(4.dp))
                    .background(CadenceColors.Brand500)
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
                text = "BADGES",
                style = CadenceType.eyebrow,
                color = CadenceColors.Fg3,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$unlockedCount / ${BADGE_CATALOG.size}",
                style = CadenceType.tick,
                color = CadenceColors.Brand500
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

@Composable
private fun BadgeTile(
    def: BadgeDef,
    earned: Badge?,
    appear: Float,
    modifier: Modifier = Modifier,
) {
    val unlocked = earned != null
    val shape = RoundedCornerShape(Radii.md)
    val pop = 0.85f + 0.15f * appear
    Column(
        modifier = modifier
            .scale(pop)
            .clip(shape)
            .background(if (unlocked) CadenceColors.Bg1 else CadenceColors.Bg1.copy(alpha = 0.5f))
            .border(
                1.dp,
                if (unlocked) CadenceColors.Brand500.copy(alpha = 0.5f) else CadenceColors.Line1,
                shape
            )
            .padding(Spacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s2)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (unlocked) CadenceColors.Brand500.copy(alpha = 0.16f)
                    else CadenceColors.Bg2
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (unlocked) def.icon else Icons.Outlined.Lock,
                contentDescription = null,
                tint = if (unlocked) CadenceColors.Brand500 else CadenceColors.Fg4,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = def.title,
            style = CadenceType.bodyStrong,
            color = if (unlocked) CadenceColors.Fg1 else CadenceColors.Fg3,
            textAlign = TextAlign.Center
        )
        Text(
            text = earned?.subtitle ?: def.lockedHint,
            style = CadenceType.tick,
            color = if (unlocked) CadenceColors.Fg3 else CadenceColors.Fg4,
            textAlign = TextAlign.Center
        )
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
