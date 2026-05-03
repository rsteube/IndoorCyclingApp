package app.indoorbike.ui.workout

import app.indoorbike.shared.workout.WorkoutSummary
import app.indoorbike.ui.components.ZoneSegment
import app.indoorbike.ui.zones.PowerZone

/**
 * UI-side adapters for the shared WorkoutSummary. Keeps zone color mapping out
 * of the shared module (which stays Compose-free).
 */

fun WorkoutSummary.zoneSegments(): List<ZoneSegment> =
    workout.segments.map {
        ZoneSegment(
            zone = PowerZone.fromFtpPercent(it.powerPercentFTP),
            weight = it.lengthInSeconds.toFloat()
        )
    }

fun zoneSegmentsFor(summary: WorkoutSummary): List<ZoneSegment> = summary.zoneSegments()
