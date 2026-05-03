package app.indoorbike.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Whatshot
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.indoorbike.LocalAppContainer
import app.indoorbike.shared.progression.Badge
import app.indoorbike.shared.progression.computeBadges
import app.indoorbike.shared.progression.computeRiderLevel
import app.indoorbike.shared.progression.computeStreak
import app.indoorbike.shared.util.getCurrentEpochMillis
import java.util.TimeZone
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Radii
import app.indoorbike.ui.theme.Spacing

@Composable
fun ProgressTab() {
    val container = LocalAppContainer.current
    val sessions by container.historyRepository.getAllSessions().collectAsState(initial = emptyList())
    val now = remember { getCurrentEpochMillis() }
    val tzOffset = remember { TimeZone.getDefault().getOffset(now).toLong() }
    val streak = remember(sessions) { computeStreak(sessions, now, timeZoneOffsetMs = tzOffset) }
    val level = remember(sessions) { computeRiderLevel(sessions) }
    val badges = remember(sessions) { computeBadges(sessions, now, timeZoneOffsetMs = tzOffset) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CadenceColors.Bg0)
    ) {
        AppBar(
            title = "Your Progress",
            eyebrow = "Stats & Achievements"
        )

        if (sessions.isEmpty()) {
            EmptyProgress()
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Spacing.s5, vertical = Spacing.s4),
            verticalArrangement = Arrangement.spacedBy(Spacing.s4)
        ) {
            item { StreakCard(streak.currentDays, streak.longestDays, streak.recentDayActivity) }
            item { LevelCard(level.name, level.nextName, level.totalKj, level.tierFloorKj, level.tierCeilingKj, level.progressFraction) }
            item {
                Text(
                    text = "RECENT BADGES",
                    style = CadenceType.eyebrow,
                    color = CadenceColors.Fg3,
                    modifier = Modifier.padding(top = Spacing.s2)
                )
            }
            item { BadgesGrid(badges) }
        }
    }
}

@Composable
private fun EmptyProgress() {
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
                text = "Complete workouts to track your fitness\ngains and achievements over time.",
                style = CadenceType.body,
                color = CadenceColors.Fg3,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StreakCard(currentDays: Int, longestDays: Int, recent: List<Boolean>) {
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
                text = "CURRENT STREAK",
                style = CadenceType.eyebrow,
                color = CadenceColors.Fg3
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = currentDays.toString(),
                    style = CadenceType.displayM,
                    color = CadenceColors.Fg1
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (currentDays == 1) "day" else "days",
                    style = CadenceType.h3,
                    color = CadenceColors.Fg3,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "BEST · $longestDays",
                    style = CadenceType.tick,
                    color = CadenceColors.Fg3
                )
            }
            Spacer(modifier = Modifier.height(Spacing.s4))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                recent.forEachIndexed { i, active ->
                    val intensity = if (active) (0.4f + i * 0.04f).coerceAtMost(1f) else 1f
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (active) CadenceColors.Brand500.copy(alpha = intensity)
                                else CadenceColors.Bg3
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelCard(
    name: String,
    nextName: String,
    totalKj: Long,
    floorKj: Long,
    ceilingKj: Long,
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
                Text(text = name, style = CadenceType.h2, color = CadenceColors.Fg1)
            }
            Text(
                text = "$totalKj / $ceilingKj kJ",
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
        Text(
            text = "NEXT · ${nextName.uppercase()}",
            style = CadenceType.tick,
            color = CadenceColors.Fg3
        )
    }
}

@Composable
private fun BadgesGrid(badges: List<Badge>) {
    if (badges.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.md))
                .background(CadenceColors.Bg1)
                .border(1.dp, CadenceColors.Line1, RoundedCornerShape(Radii.md))
                .padding(Spacing.s5),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Complete a ride to start unlocking badges.",
                style = CadenceType.small,
                color = CadenceColors.Fg3,
                textAlign = TextAlign.Center
            )
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
        badges.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                row.forEach { b ->
                    BadgeCell(b, modifier = Modifier.weight(1f))
                }
                repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun BadgeCell(badge: Badge, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radii.md)
    Column(
        modifier = modifier
            .clip(shape)
            .background(CadenceColors.Bg1)
            .border(1.dp, CadenceColors.Line2, shape)
            .padding(vertical = Spacing.s4, horizontal = Spacing.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            CadenceColors.Brand500.copy(alpha = 0.20f),
                            CadenceColors.Brand400.copy(alpha = 0.20f)
                        )
                    )
                )
                .border(1.dp, CadenceColors.Line2, RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = iconForBadge(badge.iconHint),
                contentDescription = null,
                tint = CadenceColors.Brand500,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = badge.title,
            style = CadenceType.bodyStrong,
            color = CadenceColors.Fg1,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Text(
            text = badge.subtitle,
            style = CadenceType.small,
            color = CadenceColors.Fg3,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

private fun iconForBadge(hint: String): ImageVector = when (hint) {
    "flag" -> Icons.Outlined.Flag
    "trophy" -> Icons.Outlined.EmojiEvents
    "clock" -> Icons.Outlined.AccessTime
    "bolt" -> Icons.Outlined.Bolt
    "fire" -> Icons.Outlined.LocalFireDepartment
    "flame" -> Icons.Outlined.Whatshot
    else -> Icons.Outlined.EmojiEvents
}
