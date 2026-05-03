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

data class RiderLevel(
    val tier: Int,            // 1-based tier index
    val name: String,         // e.g. "Threshold IV"
    val totalKj: Long,        // lifetime kJ across all sessions
    val tierFloorKj: Long,
    val tierCeilingKj: Long,
) {
    val progressFraction: Float
        get() {
            val span = (tierCeilingKj - tierFloorKj).coerceAtLeast(1L)
            val into = (totalKj - tierFloorKj).coerceAtLeast(0L)
            return (into.toFloat() / span.toFloat()).coerceIn(0f, 1f)
        }

    val nextName: String
        get() = TIER_NAMES.getOrNull(tier) ?: "Maxed"
}

private val TIER_NAMES = listOf(
    "Spinner I", "Spinner II", "Spinner III",
    "Endurance I", "Endurance II", "Endurance III",
    "Tempo I", "Tempo II", "Tempo III",
    "Threshold I", "Threshold II", "Threshold III", "Threshold IV",
    "VO2 I", "VO2 II", "VO2 III",
    "Anaerobic", "Neuromuscular"
)

// Roughly geometric — first level at 200 kJ, doubles every 3 tiers.
private val TIER_THRESHOLDS_KJ: List<Long> = run {
    val out = mutableListOf(0L, 200L, 500L, 1_000L)
    var step = 1_500L
    while (out.size < TIER_NAMES.size + 1) {
        out += out.last() + step
        step = (step * 1.25).toLong()
    }
    out
}

fun computeRiderLevel(sessions: List<WorkoutSession>): RiderLevel {
    val totalKj = sessions.sumOf { kjFromSession(it) }
    val tierIdx = TIER_THRESHOLDS_KJ
        .indexOfLast { it <= totalKj }
        .coerceAtLeast(0)
    val name = TIER_NAMES.getOrElse(tierIdx) { TIER_NAMES.last() }
    val floor = TIER_THRESHOLDS_KJ.getOrElse(tierIdx) { 0L }
    val ceiling = TIER_THRESHOLDS_KJ.getOrElse(tierIdx + 1) { floor + 1L }
    return RiderLevel(
        tier = tierIdx + 1,
        name = name,
        totalKj = totalKj,
        tierFloorKj = floor,
        tierCeilingKj = ceiling
    )
}

private fun kjFromSession(s: WorkoutSession): Long =
    (s.avgPower * s.durationSeconds) / 1000L

data class StreakInfo(
    val currentDays: Int,
    val longestDays: Int,
    val recentDayActivity: List<Boolean>, // last N days, oldest → newest
)

fun computeStreak(
    sessions: List<WorkoutSession>,
    nowEpochMs: Long,
    windowDays: Int = 14,
    /**
     * Local timezone offset in milliseconds (e.g. +3 h = 10_800_000). Used to
     * align day boundaries to the user's local midnight rather than UTC.
     */
    timeZoneOffsetMs: Long = 0,
): StreakInfo {
    val localDay = { epochMs: Long -> (epochMs + timeZoneOffsetMs) / MS_PER_DAY }

    val activeDays: Set<Long> = sessions
        .map { localDay(it.startTimeEpoch) }
        .toSet()

    val today = localDay(nowEpochMs)

    // Current streak counts back from today (or yesterday if today empty).
    var current = 0
    var d = today
    if (d !in activeDays) d -= 1 // grace: missing today still counts if yesterday was active
    while (d in activeDays) {
        current += 1
        d -= 1
    }

    // Longest streak: scan all active days.
    val sortedDays = activeDays.sorted()
    var longest = 0
    var run = 0
    var prev: Long? = null
    for (day in sortedDays) {
        run = if (prev == null || day == prev + 1) run + 1 else 1
        longest = max(longest, run)
        prev = day
    }

    val window = (windowDays - 1 downTo 0).map { offset ->
        (today - offset) in activeDays
    }
    return StreakInfo(currentDays = current, longestDays = longest, recentDayActivity = window)
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

    if (streak.longestDays >= 3) {
        out += Badge(
            id = "consistency-3",
            title = "Three in a Row",
            subtitle = "${streak.longestDays} day streak",
            iconHint = "fire",
            unlockedAtEpochMs = nowEpochMs
        )
    }

    if (streak.longestDays >= 7) {
        out += Badge(
            id = "consistency-7",
            title = "On Fire",
            subtitle = "7 day streak",
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
