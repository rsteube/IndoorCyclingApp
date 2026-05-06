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

@Serializable
data object RouteLibrary : NavKey

@Serializable
data object RouteQrScanner : NavKey

/**
 * Remote-repo browser: fetches a single repository's manifest+bundles on the
 * fly and lets the rider pick individual workouts to copy into their local
 * library. The route key is the manifest URL — keeps the destination
 * stateless and shareable via deep link if/when we add link routing.
 */
@Serializable
data class RouteRemoteRepo(val manifestUrl: String) : NavKey
