package app.indoorbike.ui.workout

import app.indoorbike.shared.Workout
import app.indoorbike.ui.components.ZoneSegment
import app.indoorbike.ui.zones.PowerZone
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class WorkoutSummary(
    val workout: Workout,
    val totalSeconds: Int,
    val durationLabel: String,
    val tag: String,
    val intensityFactor: Float,
    val tss: Int,
    val kilojoules: Int,
    val zoneSegments: List<ZoneSegment>,
    val ftpWatts: Int
) {
    val averagePowerWatts: Int get() = (intensityFactor * ftpWatts).roundToInt()

    companion object {
        // Until the user can configure FTP, use the 248W placeholder from the design system.
        const val AssumedFtpWatts = 248
    }
}

fun Workout.toSummary(ftpWatts: Int = WorkoutSummary.AssumedFtpWatts): WorkoutSummary {
    val zoneSegs = segments.map {
        ZoneSegment(
            zone = PowerZone.fromFtpPercent(it.powerPercentFTP),
            weight = it.lengthInSeconds.toFloat()
        )
    }
    val total = segments.sumOf { it.lengthInSeconds }
    val durationLabel = formatDurationMinutes(total)

    // Intensity factor approximated as RMS of fraction-of-FTP across time-weighted segments.
    val rms = if (total == 0) 0f else sqrt(
        segments.sumOf { seg ->
            val frac = seg.powerPercentFTP / 100.0
            frac * frac * seg.lengthInSeconds
        } / total
    ).toFloat()

    val tss = ((total / 3600.0) * rms * rms * 100).roundToInt()
    val avgWatts = rms * ftpWatts
    val kj = ((avgWatts * total) / 1000.0).roundToInt()
    val tag = inferTag(zoneSegs)

    return WorkoutSummary(
        workout = this,
        totalSeconds = total,
        durationLabel = durationLabel,
        tag = tag,
        intensityFactor = (rms * 100).roundToInt() / 100f,
        tss = tss,
        kilojoules = kj,
        zoneSegments = zoneSegs,
        ftpWatts = ftpWatts
    )
}

private fun inferTag(segs: List<ZoneSegment>): String {
    val byZone = segs.groupBy { it.zone }.mapValues { (_, v) -> v.sumOf { it.weight.toDouble() } }
    val workZones = byZone.filterKeys { it.number >= 3 }
    val dominant = (workZones.takeIf { it.isNotEmpty() } ?: byZone)
        .maxByOrNull { it.value }?.key ?: PowerZone.Z2
    return when (dominant) {
        PowerZone.Z1 -> "Recovery"
        PowerZone.Z2 -> "Endurance"
        PowerZone.Z3 -> "Tempo"
        PowerZone.Z4 -> "Threshold"
        PowerZone.Z5 -> "VO2 Max"
        PowerZone.Z6 -> "Anaerobic"
        PowerZone.Z7 -> "Neuromuscular"
    }
}

private fun formatDurationMinutes(totalSeconds: Int): String {
    val mins = (totalSeconds + 30) / 60
    return when {
        mins < 60 -> "$mins min"
        mins % 60 == 0 -> "${mins / 60} h"
        else -> "${mins / 60} h ${mins % 60} min"
    }
}

fun formatHHMM(totalSeconds: Int): String {
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "%d:%02d".format(mins, secs)
}
