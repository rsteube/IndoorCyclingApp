package app.indoorbike.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object TabRides : NavKey

@Serializable
data object TabHistory : NavKey

@Serializable
data object TabProgress : NavKey

@Serializable
data object TabSettings : NavKey

@Serializable
data class RouteWorkoutDetail(val workoutId: String) : NavKey

@Serializable
data object RouteTrainerPairing : NavKey

@Serializable
data object RouteHrmPairing : NavKey

@Serializable
data object RouteControllerPairing : NavKey

@Serializable
data class RouteRideScreen(val workoutId: String) : NavKey

@Serializable
data class RouteWorkoutOverview(val sessionId: Long, val fromRide: Boolean = false) : NavKey
