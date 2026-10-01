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
package app.indoorbike.shared.companion

/**
 * Where a companion says the rider is on its course, already checked.
 *
 * [latitude] and [longitude] are one value and are both set or both null;
 * [altitudeM] stands on its own, because a game may know how high the road is
 * without placing it on the earth.
 */
data class CompanionPosition(
    val latitude: Double?,
    val longitude: Double?,
    val altitudeM: Double?,
)

/**
 * One second of what a companion has said about the road, for the ride record.
 *
 * Every field is the companion's own number or null. Nothing here is worked out
 * on the phone: a second the companion said nothing fresh about has no
 * position, rather than one guessed from the seconds around it.
 */
data class CompanionSample(
    /** Cumulative along-road distance as the companion counts it, or null if it never said. */
    val distanceMeters: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
)
