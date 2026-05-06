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

import app.indoorbike.shared.util.Log
import kotlinx.serialization.json.Json

object WorkoutRepository {

    private const val TAG = "WorkoutRepository"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parses a list of JSON strings (each representing a list of workouts) and
     * combines them. Workouts with duplicate ids are de-duped (first wins) and
     * a warning is logged.
     */
    fun parseWorkouts(jsonStrings: List<String>): List<Workout> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Workout>()
        for (jsonString in jsonStrings) {
            try {
                val workouts = json.decodeFromString<List<Workout>>(jsonString)
                for (w in workouts) {
                    if (seen.add(w.id)) {
                        out.add(w)
                    } else {
                        Log.w(TAG, "Duplicate workout id '${w.id}' — keeping first occurrence.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse workout json", e)
            }
        }
        return out
    }
}
