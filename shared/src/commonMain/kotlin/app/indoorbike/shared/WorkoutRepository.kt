package app.indoorbike.shared

import kotlinx.serialization.json.Json

object WorkoutRepository {
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parses a list of JSON strings (each representing a list of workouts)
     * and combines them into a single list of Workouts.
     */
    fun parseWorkouts(jsonStrings: List<String>): List<Workout> {
        val allWorkouts = mutableListOf<Workout>()
        for (jsonString in jsonStrings) {
            try {
                val workouts = json.decodeFromString<List<Workout>>(jsonString)
                allWorkouts.addAll(workouts)
            } catch (e: Exception) {
                // In a production app, log this using a cross-platform logger.
                println("Failed to parse workout json: ${e.message}")
            }
        }
        return allWorkouts
    }
}
