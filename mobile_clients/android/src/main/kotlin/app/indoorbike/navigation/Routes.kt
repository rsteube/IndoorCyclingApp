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

/** Where finished rides can be sent: Intervals.icu, FitTrackee, and so on. */
@Serializable
data object RouteConnections : NavKey

/** Bike and gearing settings, which only a simulated route ride reads. */
@Serializable
data object RouteSimulationSettings : NavKey

@Serializable
data class RouteRideScreen(val workoutId: String) : NavKey

@Serializable
data class RouteWorkoutOverview(
    val sessionId: Long,
    val fromRide: Boolean = false,
    /**
     * The ride that has just ended was a companion ride, so finishing with the
     * summary should hand the rider back to the companion screen rather than
     * to the home tab. The game on the other device is still running and the
     * rider is still on the bike; the thing they want next is the socket open
     * again, not a menu.
     */
    val fromCompanion: Boolean = false,
) : NavKey

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

/**
 * Remote-route browser: the same repository seen from its route side. Split
 * from [RouteRemoteRepo] rather than tabbed inside it because the two lists
 * cost different things — workouts arrive with the browse, routes are
 * listings whose data is only fetched on add — and a rider looking for a road
 * to ride is not part-way through looking for an interval session.
 */
@Serializable
data class RouteRemoteRoutes(val manifestUrl: String) : NavKey

/**
 * The rider own routes: which road to ride tonight, and what each one will
 * cost them. Its own destination rather than a section of the rides list
 * because picking a route is a different question from picking a workout, and
 * the answer depends on numbers (distance, climbing, time) that need room.
 */
@Serializable
data object RouteRouteLibrary : NavKey

/**
 * Pair a companion, then hand the ride over to it.
 *
 * Its own destination rather than a state of the ride screen because pairing is
 * not riding: the rider is off the bike, reading a code, and the screen they
 * need is a chooser of network details rather than a HUD with nothing on it.
 */
@Serializable
data object RouteCompanion : NavKey

/**
 * Choose a workout. Split off the home screen when home became a chooser of
 * ride *kinds* rather than a list of one kind.
 */
@Serializable
data object RouteWorkoutPicker : NavKey

/** Follow, unfollow and check community repositories. */
@Serializable
data object RouteRepositories : NavKey
