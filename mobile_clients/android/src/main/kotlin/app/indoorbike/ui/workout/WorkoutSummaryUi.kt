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
