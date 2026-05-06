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
package app.indoorbike.shared

import kotlinx.serialization.Serializable

/**
 * Represents a single segment/power zone within a workout.
 */
@Serializable
data class PowerZone(
    val lengthInSeconds: Int,
    val powerPercentFTP: Int,
    val intervalType: String
)

/**
 * Represents a structured workout based on power.
 */
@Serializable
data class Workout(
    val id: String,
    val name: String,
    val description: String?,
    val screenshot: String? = null,
    val url: String? = null,
    val segments: List<PowerZone>
)
