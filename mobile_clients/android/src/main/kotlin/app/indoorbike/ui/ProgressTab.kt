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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.progression.WeekActivity
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

@Composable
fun ProgressTab(onNavigateToRides: () -> Unit = {}) {
    val container = LocalAppContainer.current
    val sessions by container.sessions.collectAsState()
    val weightKg by container.userSettings.weightKg.collectAsState()
    val now = remember { getCurrentEpochMillis() }
    val tzOffset = remember { TimeZone.getDefault().getOffset(now).toLong() }
    val streak = remember(sessions) { computeStreak(sessions, now, timeZoneOffsetMs = tzOffset) }
    val level = remember(sessions, weightKg) { computeRiderLevel(sessions, weightKg) }

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
            item { WeeklyStreakCard(streak.currentWeeks, streak.longestWeeks, streak.recentWeekActivity) }
            item {
                LevelCard(
                    level = level.level,
                    totalXp = level.totalXp,
                    floorXp = level.levelFloorXp,
                    ceilingXp = level.levelCeilingXp,
                    progress = level.progressFraction
                )
            }
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

@Composable
private fun WeeklyStreakCard(currentWeeks: Int, longestWeeks: Int, recent: List<WeekActivity>) {
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
                recent.forEach { week ->
                    WeekBox(daysActive = week.daysActive, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * One box on the streak chart = one week. Fills bottom-up by 1/7 per ride day,
 * so a 4-day week is just over half full. Empty weeks read as a flat dim base.
 */
@Composable
private fun WeekBox(daysActive: Int, modifier: Modifier = Modifier) {
    val safeDays = daysActive.coerceIn(0, 7)
    val fillFraction = safeDays / 7f
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(CadenceColors.Bg3)
    ) {
        // Bottom-anchored fill: place a child Box that fills (fraction) of the
        // height and align it to the bottom of the parent.
        if (safeDays > 0) {
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

@Composable
private fun LevelCard(
    level: Int,
    totalXp: Double,
    floorXp: Double,
    ceilingXp: Double,
    progress: Float
) {
    val shape = RoundedCornerShape(Radii.lg)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .padding(Spacing.s5)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(text = "RIDER LEVEL", style = CadenceType.eyebrow, color = CadenceColors.Fg3)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Level $level", style = CadenceType.h2, color = CadenceColors.Fg1)
            }
            Text(
                text = "${formatXp(totalXp)} / ${formatXp(ceilingXp)} XP",
                style = CadenceType.tick,
                color = CadenceColors.Brand500
            )
        }
        Spacer(modifier = Modifier.height(Spacing.s3))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(CadenceColors.Bg0)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            listOf(CadenceColors.Brand700, CadenceColors.Brand500, CadenceColors.Brand400)
                        )
                    )
            )
        }
        Spacer(modifier = Modifier.height(Spacing.s2))
        val toGo = (ceilingXp - totalXp).coerceAtLeast(0.0)
        Text(
            text = "${formatXp(toGo)} XP TO LEVEL ${level + 1}",
            style = CadenceType.tick,
            color = CadenceColors.Fg3
        )
        Spacer(modifier = Modifier.height(Spacing.s3))
        Text(
            text = "XP = Watt-minutes per kilogram. Heavier efforts and longer rides earn more.",
            style = CadenceType.small,
            color = CadenceColors.Fg3
        )
        // Floor reference (small, debug-y but useful) — not shown when level is 0
        // because the floor is also 0 and the progress bar already says it.
        if (level > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Level $level unlocked at ${formatXp(floorXp)} XP",
                style = CadenceType.small,
                color = CadenceColors.Fg4
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
