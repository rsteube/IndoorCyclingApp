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

import app.indoorbike.shared.route.RawRoutePoint
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.shared.route.routeTrackOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A straight road north, climbing [climbPerKm] metres every kilometre. */
private fun road(km: Double, climbPerKm: Double = 10.0): RouteTrack {
    val points = ArrayList<RawRoutePoint>()
    var d = 0.0
    while (d <= km * 1_000.0) {
        points += RawRoutePoint(60.0 + d / 111_320.0, 24.9, d / 1_000.0 * climbPerKm)
        d += 100.0
    }
    return routeTrackOf("road", "Road", points)
}

/** Picking where a long route starts, before the ride. */
class RouteStartTest {

    @Test
    fun `only a route of 20 km or more asks where to start`() {
        assertFalse(RouteStart.asksWhereToStart(road(km = 15.0)))
        assertTrue(RouteStart.asksWhereToStart(road(km = 25.0)))
    }

    @Test
    fun `a pick is the start, near the trailhead it is the trailhead`() {
        val route = road(km = 40.0)
        assertEquals(12_345.0, RouteStart.startFor(route, 12_345.0))
        assertEquals(0.0, RouteStart.startFor(route, 180.0))
        assertEquals(0.0, RouteStart.startFor(route, -50.0))
    }

    @Test
    fun `a pick never leaves less than a kilometre to ride`() {
        val route = road(km = 40.0)
        val start = RouteStart.startFor(route, route.totalDistanceMeters - 100.0)
        assertTrue(abs(route.totalDistanceMeters - start - RouteStart.MIN_LEFT_METERS) < 0.01, "start $start")
    }

    @Test
    fun `a pick near where the last ride stopped is that place, with its climb`() {
        val route = road(km = 40.0)
        val saved = RouteProgress(routeId = "road", distanceMeters = 22_000.0, elevationGainMeters = 231.0)
        val start = RouteStart.startFor(route, 22_150.0, saved)
        assertEquals(22_000.0, start)
        assertEquals(231.0, RouteStart.elevationGainAt(route, start, saved))
    }

    @Test
    fun `anywhere else starts with the climb behind it on the profile`() {
        val route = road(km = 40.0, climbPerKm = 10.0)
        val gain = RouteStart.elevationGainAt(route, 20_000.0)
        assertTrue(abs(gain - 200.0) < 10.0, "gain $gain")
        assertEquals(0.0, RouteStart.elevationGainAt(route, 0.0))
    }
}
