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
package app.indoorbike.shared.workout

import app.indoorbike.shared.Workout
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pre-ride summary derived purely from the planned `Workout` definition.
 * Lives in shared so commonTest can validate it and the iOS client gets it
 * for free.
 *
 * Note: `tag` is a coarse classification for filtering; `intensityFactor`
 * is approximated as the time-weighted RMS of FTP fraction (no rolling
 * window). This is the *plan*, not the actual ride — see
 * `progression.computeRideStats()` for telemetry-derived stats.
 */
data class WorkoutSummary(
    val workout: Workout,
    val totalSeconds: Int,
    val tag: String,
    val intensityFactor: Float,
    val tss: Int,
    val kilojoules: Int,
    val ftpWatts: Int,
) {
    /** Normalized power: Intensity Factor × FTP. */
    val normalizedPowerWatts: Int get() = (intensityFactor * ftpWatts).roundToInt()
}

const val DEFAULT_FTP_WATTS = 200

fun Workout.toSummary(ftpWatts: Int = DEFAULT_FTP_WATTS): WorkoutSummary {
    val total = segments.sumOf { it.lengthInSeconds }

    val rms = if (total == 0) 0f else sqrt(
        segments.sumOf { seg ->
            val frac = seg.powerPercentFTP / 100.0
            frac * frac * seg.lengthInSeconds
        } / total
    ).toFloat()

    val tss = ((total / 3600.0) * rms * rms * 100).roundToInt()
    val avgWatts = rms * ftpWatts
    val kj = ((avgWatts * total) / 1000.0).roundToInt()

    return WorkoutSummary(
        workout = this,
        totalSeconds = total,
        tag = inferTag(this),
        intensityFactor = ((rms * 100).roundToInt()) / 100f,
        tss = tss,
        kilojoules = kj,
        ftpWatts = ftpWatts,
    )
}

private val ZONE_HIGH_PCTS = intArrayOf(55, 75, 90, 105, 120, 150, 999)

private fun zoneNumber(pct: Int): Int {
    for (i in ZONE_HIGH_PCTS.indices) if (pct <= ZONE_HIGH_PCTS[i]) return i + 1
    return 7
}

private fun inferTag(workout: Workout): String {
    if (workout.segments.isEmpty()) return "Endurance"
    val byZone = LongArray(7)
    for (seg in workout.segments) {
        val z = zoneNumber(seg.powerPercentFTP) - 1
        byZone[z] = byZone[z] + seg.lengthInSeconds
    }
    val workZones = (2..6).maxByOrNull { byZone[it] } ?: -1
    val dominant = if (workZones >= 0 && byZone[workZones] > 0) workZones + 1
        else (byZone.indices.maxByOrNull { byZone[it] } ?: 1) + 1
    return when (dominant) {
        1 -> "Recovery"
        2 -> "Endurance"
        3 -> "Tempo"
        4 -> "Threshold"
        5 -> "VO2 Max"
        6 -> "Anaerobic"
        else -> "Neuromuscular"
    }
}

fun formatDurationPretty(totalSeconds: Int): String {
    val mins = (totalSeconds + 30) / 60
    return when {
        mins < 60 -> "$mins min"
        mins % 60 == 0 -> "${mins / 60} h"
        else -> "${mins / 60} h ${mins % 60} min"
    }
}

/** "MM:SS" for short durations, "H:MM:SS" for any duration ≥ 1 hour. */
fun formatClock(totalSeconds: Int): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}"
    else "$m:${sec.toString().padStart(2, '0')}"
}
