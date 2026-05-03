package app.indoorbike.shared

/**
 * Represents a single power zone within a workout.
 * 
 * @property durationSeconds Duration of the zone in seconds.
 * @property targetPowerPercentage Target power as a percentage of FTP (e.g., 0.65 for 65% FTP).
 * @property color Hex color string or descriptive name for the UI.
 */
data class PowerZone(
    val durationSeconds: Int,
    val targetPowerPercentage: Double,
    val color: String
)

/**
 * Represents a structured workout based on power.
 * 
 * @property id Unique identifier.
 * @property name Name of the workout.
 * @property description Optional description.
 * @property zones Sequential list of power zones defining the structure.
 */
data class Workout(
    val id: String,
    val name: String,
    val description: String?,
    val zones: List<PowerZone>
)
