package app.indoorbike.shared.progression

import app.indoorbike.shared.db.WorkoutSession
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Local-only progression calculations derived from completed sessions.
 * No backend, no account — everything is recomputed from the WorkoutSession list.
 */

private const val MS_PER_DAY: Long = 86_400_000L
private const val DAYS_PER_WEEK: Int = 7

/**
 * XP-based progression. XP = Watt-minutes / kg, summed across every session:
 *  xp = (avgPower W × duration s / 60) / weightKg.
 *
 * Level thresholds form a geometric series: each level requires the previous
 * level's threshold × 2.5 (i.e. previous + previous × 1.5).
 *  L1 = 20, L2 = 50, L3 = 125, L4 = 312.5, ...
 *
 * A rider with totalXp < 20 is at level 0 (still working toward L1).
 */
data class RiderLevel(
    val level: Int,
    val totalXp: Double,
    val levelFloorXp: Double,    // XP threshold that put rider at current level (0 for L0)
    val levelCeilingXp: Double,  // XP threshold for next level
) {
    val progressFraction: Float
        get() {
            val span = (levelCeilingXp - levelFloorXp).coerceAtLeast(1.0)
            val into = (totalXp - levelFloorXp).coerceAtLeast(0.0)
            return (into / span).toFloat().coerceIn(0f, 1f)
        }

    val xpToNextLevel: Double
        get() = (levelCeilingXp - totalXp).coerceAtLeast(0.0)
}

const val FIRST_LEVEL_XP: Double = 20.0
const val LEVEL_GROWTH_FACTOR: Double = 2.5  // each level = previous × 2.5

/** Watt-minutes/kg contributed by a single session. Returns 0 for nonsense input. */
fun xpFromSession(s: WorkoutSession, weightKg: Float): Double {
    if (weightKg <= 0f || s.durationSeconds <= 0L || s.avgPower <= 0L) return 0.0
    val wattMinutes = s.avgPower.toDouble() * s.durationSeconds.toDouble() / 60.0
    return wattMinutes / weightKg.toDouble()
}

fun totalXp(sessions: List<WorkoutSession>, weightKg: Float): Double =
    sessions.sumOf { xpFromSession(it, weightKg) }

fun computeRiderLevel(sessions: List<WorkoutSession>, weightKg: Float): RiderLevel {
    val xp = totalXp(sessions, weightKg)
    var level = 0
    var floor = 0.0
    var ceiling = FIRST_LEVEL_XP
    while (xp >= ceiling) {
        floor = ceiling
        ceiling *= LEVEL_GROWTH_FACTOR
        level += 1
    }
    return RiderLevel(level = level, totalXp = xp, levelFloorXp = floor, levelCeilingXp = ceiling)
}

private fun kjFromSession(s: WorkoutSession): Long =
    (s.avgPower * s.durationSeconds) / 1000L

/** Week activity row for the streak chart: which week, and how many distinct days had a ride (0..7). */
data class WeekActivity(
    val weekBucket: Long,
    val daysActive: Int,
)

data class StreakInfo(
    /** Current consecutive-week streak (weeks where the rider rode at least once). */
    val currentWeeks: Int,
    /** Longest such weekly streak ever recorded. */
    val longestWeeks: Int,
    /** Most recent N weeks, oldest → newest, with how many days were active in each. */
    val recentWeekActivity: List<WeekActivity>,
)

/**
 * Compute weekly ride streak. A "week" is Monday–Sunday in local time. The
 * streak continues as long as each consecutive week contains at least one ride.
 * If no ride yet this week, the previous week still counts (grace), so users
 * don't see a broken streak on Monday morning.
 *
 * The recent-week window is filled with day counts (0..7) so the UI can render
 * each box with a fill fraction.
 */
fun computeStreak(
    sessions: List<WorkoutSession>,
    nowEpochMs: Long,
    windowWeeks: Int = 14,
    /**
     * Local timezone offset in milliseconds (e.g. +3 h = 10_800_000). Used to
     * align day/week boundaries to the user's local midnight rather than UTC.
     */
    timeZoneOffsetMs: Long = 0,
): StreakInfo {
    val localDay = { epochMs: Long -> (epochMs + timeZoneOffsetMs) / MS_PER_DAY }
    // Day 0 of the Unix epoch (Jan 1 1970) was a Thursday. Adding 3 shifts the
    // bucket boundary so each integer division yields a Monday-anchored week.
    val weekBucket = { day: Long -> (day + 3).floorDiv(DAYS_PER_WEEK.toLong()) }

    // Distinct local days with at least one ride.
    val activeDays: Set<Long> = sessions.map { localDay(it.startTimeEpoch) }.toSet()
    val daysPerWeek: Map<Long, Int> = activeDays.groupBy { weekBucket(it) }.mapValues { it.value.size }

    val thisWeek = weekBucket(localDay(nowEpochMs))

    // Current streak counts back from this week (with one-week grace for the
    // common "haven't ridden yet this week" case).
    var current = 0
    var w = thisWeek
    if ((daysPerWeek[w] ?: 0) == 0) w -= 1
    while ((daysPerWeek[w] ?: 0) > 0) {
        current += 1
        w -= 1
    }

    // Longest streak: scan all weeks with rides.
    val sortedWeeks = daysPerWeek.keys.sorted()
    var longest = 0
    var run = 0
    var prev: Long? = null
    for (week in sortedWeeks) {
        run = if (prev == null || week == prev + 1) run + 1 else 1
        longest = max(longest, run)
        prev = week
    }

    val window = (windowWeeks - 1 downTo 0).map { offset ->
        val bucket = thisWeek - offset
        WeekActivity(weekBucket = bucket, daysActive = daysPerWeek[bucket] ?: 0)
    }
    return StreakInfo(currentWeeks = current, longestWeeks = longest, recentWeekActivity = window)
}

data class Badge(
    val id: String,
    val title: String,
    val subtitle: String,
    val iconHint: String, // semantic name, ui maps to Compose icon
    val unlockedAtEpochMs: Long? = null,
) {
    val unlocked: Boolean get() = unlockedAtEpochMs != null
}

fun computeBadges(
    sessions: List<WorkoutSession>,
    nowEpochMs: Long,
    timeZoneOffsetMs: Long = 0,
): List<Badge> {
    if (sessions.isEmpty()) return emptyList()

    val sortedAsc = sessions.sortedBy { it.startTimeEpoch }
    val streak = computeStreak(sessions, nowEpochMs, timeZoneOffsetMs = timeZoneOffsetMs)
    val totalKj = sessions.sumOf { kjFromSession(it) }
    val totalSeconds = sessions.sumOf { it.durationSeconds }
    val out = mutableListOf<Badge>()

    out += Badge(
        id = "first-ride",
        title = "First Ride",
        subtitle = "Welcome to Cadence",
        iconHint = "flag",
        unlockedAtEpochMs = sortedAsc.first().startTimeEpoch
    )

    if (sortedAsc.size >= 5) {
        out += Badge(
            id = "five-rides",
            title = "Five Strong",
            subtitle = "5 rides logged",
            iconHint = "trophy",
            unlockedAtEpochMs = sortedAsc[4].startTimeEpoch
        )
    }

    if (totalSeconds >= 3600L) {
        out += Badge(
            id = "first-hour",
            title = "First Hour",
            subtitle = "1 h saddle time",
            iconHint = "clock",
            unlockedAtEpochMs = sortedAsc
                .runningFold(0L) { acc, s -> acc + s.durationSeconds }
                .indexOfFirst { it >= 3600L }
                .let { idx -> if (idx > 0) sortedAsc[idx - 1].startTimeEpoch else nowEpochMs }
        )
    }

    if (totalKj >= 1_000L) {
        out += Badge(
            id = "kilojoule-club",
            title = "Kilojoule Club",
            subtitle = "1,000 kJ burned",
            iconHint = "bolt",
            unlockedAtEpochMs = nowEpochMs
        )
    }

    if (streak.longestWeeks >= 3) {
        out += Badge(
            id = "consistency-3",
            title = "Three in a Row",
            subtitle = "${streak.longestWeeks} week streak",
            iconHint = "fire",
            unlockedAtEpochMs = nowEpochMs
        )
    }

    if (streak.longestWeeks >= 7) {
        out += Badge(
            id = "consistency-7",
            title = "On Fire",
            subtitle = "7 week streak",
            iconHint = "flame",
            unlockedAtEpochMs = nowEpochMs
        )
    }

    return out.sortedByDescending { it.unlockedAtEpochMs ?: 0L }
}

fun totalKjAcross(sessions: List<WorkoutSession>): Long =
    sessions.sumOf { kjFromSession(it) }

fun totalDurationSeconds(sessions: List<WorkoutSession>): Long =
    sessions.sumOf { it.durationSeconds }

/**
 * Time-in-zone breakdown derived from telemetry. Returns one entry per zone (Z1..Z7),
 * even if zero seconds were spent in some — UI can choose how to render zeros.
 */
fun timeInZoneSeconds(
    telemetryWatts: List<Long>,
    ftp: Int,
    sampleSeconds: Int = 1
): IntArray {
    val out = IntArray(7)
    if (ftp <= 0) return out
    for (w in telemetryWatts) {
        val pct = (w.toDouble() / ftp * 100).toInt().coerceAtLeast(0)
        val zoneIdx = ftpPctToZoneIndex(pct)
        out[zoneIdx] = out[zoneIdx] + sampleSeconds
    }
    return out
}

private val ZONE_HIGH_PCTS = intArrayOf(55, 75, 90, 105, 120, 150, 999)

private fun ftpPctToZoneIndex(pct: Int): Int {
    for (i in ZONE_HIGH_PCTS.indices) {
        if (pct <= ZONE_HIGH_PCTS[i]) return i
    }
    return ZONE_HIGH_PCTS.lastIndex
}

data class RideStats(
    val avgPowerWatts: Int,
    val normalizedPowerWatts: Int,
    val intensityFactor: Float,
    val tss: Int,
    val kilojoules: Int,
)

/**
 * Compute headline ride stats from telemetry. Uses simplified NP (4th-power mean over
 * raw 1Hz samples; no rolling 30s smoothing — accurate enough for intervals shorter than
 * a few minutes and ERG-driven steady states, which is the typical case here).
 */
fun computeRideStats(
    telemetryWatts: List<Long>,
    durationSeconds: Long,
    ftp: Int,
): RideStats {
    if (telemetryWatts.isEmpty() || ftp <= 0 || durationSeconds <= 0) {
        return RideStats(0, 0, 0f, 0, 0)
    }
    val avg = telemetryWatts.average()
    val fourthMean = telemetryWatts
        .map { (it.toDouble()).let { d -> d * d * d * d } }
        .average()
    val np = if (fourthMean > 0) fourthMean.pow(0.25) else 0.0
    val intensity = if (ftp > 0) (np / ftp).toFloat() else 0f
    val tss = ((durationSeconds / 3600.0) * intensity * intensity * 100).toInt()
    val kj = ((avg * durationSeconds) / 1000.0).toInt()
    return RideStats(
        avgPowerWatts = avg.toInt(),
        normalizedPowerWatts = np.toInt(),
        intensityFactor = ((intensity * 100).toInt() / 100f),
        tss = tss,
        kilojoules = kj
    )
}

fun durationLabelLong(totalSeconds: Long): String {
    val mins = ((totalSeconds + 30) / 60).toInt()
    return when {
        mins <= 0 -> "<1 min"
        mins < 60 -> "$mins min"
        mins % 60 == 0 -> "${mins / 60} h"
        else -> "${mins / 60} h ${mins % 60} min"
    }
}
