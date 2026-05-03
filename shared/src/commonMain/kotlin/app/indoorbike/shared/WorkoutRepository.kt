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
