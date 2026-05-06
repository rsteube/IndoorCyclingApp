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
package app.indoorbike.library

import app.indoorbike.shared.Workout

/**
 * Where a [Workout] in the in-app library originated. Used for the small
 * attribution shown on the workout card and to know which storage to delete
 * from when a user removes a workout.
 */
sealed class WorkoutSource {
    data object Bundled : WorkoutSource()
    data object Imported : WorkoutSource()
    data class Repository(val id: String, val name: String) : WorkoutSource()

    val attribution: String
        get() = when (this) {
            is Bundled -> "BUNDLED"
            is Imported -> "IMPORTED"
            is Repository -> name.uppercase()
        }
}

/** Workout + provenance. The Rides list and Library both consume this. */
data class WorkoutEntry(
    val workout: Workout,
    val source: WorkoutSource,
)
