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
