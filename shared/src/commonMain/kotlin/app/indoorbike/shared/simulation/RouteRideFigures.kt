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
package app.indoorbike.shared.simulation

import app.indoorbike.shared.route.RouteTrack

/**
 * What a route ride has done and has left, counted from where it began.
 *
 * The engine's distance is a position on the road and its climbing is the
 * road's climbing up to there, because that is what a ride picked up part way
 * along needs in order to carry on. Neither is what a rider wants read back.
 * Someone who joins a 1,200 km route at its 600 km mark has ridden nothing at
 * the first pedal stroke, not 600 km, and the climbing they are shown should
 * be the climbing they did. So every figure a screen shows comes from here,
 * with the start taken off once, rather than each screen taking it off for
 * itself - which is how the ride screen came to show the road's position while
 * the summary showed the ride's distance.
 *
 * Nothing here is negative. A route republished shorter than where a saved
 * ride began gives an empty stretch, not a ride of minus six kilometres.
 */
data class RouteRideFigures(
    /** Ridden since this ride began. */
    val riddenMeters: Double,
    /** Road left to the finish. */
    val toGoMeters: Double,
    /** Climbed since this ride began. */
    val climbedMeters: Double,
    /** Climbing left to the finish, on the route's profile. */
    val climbToGoMeters: Double,
    /** The whole stretch this ride is riding, from its start to the finish. */
    val stretchMeters: Double,
    /** The climbing in that stretch. */
    val stretchClimbMeters: Double,
) {
    companion object {
        fun of(
            route: RouteTrack,
            /** Position on the road, in metres from the trailhead. */
            distanceMeters: Double,
            /** The road's climbing collected up to that position. */
            elevationGainMeters: Double,
            /** Where this ride began; zero for a ride from the trailhead. */
            startedAtMeters: Double = 0.0,
            startedAtElevationGainMeters: Double = 0.0,
        ): RouteRideFigures {
            val total = route.totalDistanceMeters.coerceAtLeast(0.0)
            val start = startedAtMeters.coerceIn(0.0, total)
            val stretch = total - start
            val ridden = (distanceMeters - start).coerceIn(0.0, stretch)

            val startClimb = startedAtElevationGainMeters.coerceAtLeast(0.0)
            val stretchClimb = (route.totalAscentMeters - startClimb).coerceAtLeast(0.0)
            val climbed = (elevationGainMeters - startClimb).coerceAtLeast(0.0)
            return RouteRideFigures(
                riddenMeters = ridden,
                toGoMeters = stretch - ridden,
                climbedMeters = climbed,
                climbToGoMeters = (stretchClimb - climbed).coerceAtLeast(0.0),
                stretchMeters = stretch,
                stretchClimbMeters = stretchClimb,
            )
        }
    }
}
