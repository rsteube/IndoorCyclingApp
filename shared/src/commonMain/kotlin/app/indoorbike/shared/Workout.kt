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
