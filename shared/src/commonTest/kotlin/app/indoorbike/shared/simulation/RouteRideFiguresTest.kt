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
import kotlin.test.assertTrue

/** A straight road north, climbing ten metres every kilometre. */
private fun road(km: Double): RouteTrack {
    val points = ArrayList<RawRoutePoint>()
    var d = 0.0
    while (d <= km * 1_000.0) {
        points += RawRoutePoint(60.0 + d / 111_320.0, 24.9, d / 100.0)
        d += 100.0
    }
    return routeTrackOf("road", "Road", points)
}

private fun assertNear(expected: Double, actual: Double, what: String) =
    assertTrue(abs(expected - actual) <= 0.01, "$what: expected $expected, was $actual")

/** The figures a rider is shown, counted from where their ride began. */
class RouteRideFiguresTest {

    @Test
    fun `from the trailhead the ride and the road agree`() {
        val route = road(km = 40.0)
        val figures = RouteRideFigures.of(route, distanceMeters = 12_000.0, elevationGainMeters = 120.0)
        assertEquals(12_000.0, figures.riddenMeters)
        assertNear(route.totalDistanceMeters - 12_000.0, figures.toGoMeters, "to go")
        assertEquals(120.0, figures.climbedMeters)
        assertNear(route.totalAscentMeters - 120.0, figures.climbToGoMeters, "climb to go")
        assertNear(route.totalDistanceMeters, figures.stretchMeters, "stretch")
        assertNear(route.totalAscentMeters, figures.stretchClimbMeters, "stretch climb")
    }

    @Test
    fun `a ride joined part way along has done nothing at its first pedal stroke`() {
        val route = road(km = 40.0)
        val figures = RouteRideFigures.of(
            route,
            distanceMeters = 22_000.0, elevationGainMeters = 220.0,
            startedAtMeters = 22_000.0, startedAtElevationGainMeters = 220.0,
        )
        assertEquals(0.0, figures.riddenMeters)
        assertEquals(0.0, figures.climbedMeters)
        assertNear(route.totalDistanceMeters - 22_000.0, figures.toGoMeters, "to go")
        assertNear(route.totalAscentMeters - 220.0, figures.climbToGoMeters, "climb to go")
        assertNear(route.totalDistanceMeters - 22_000.0, figures.stretchMeters, "stretch")
        assertNear(route.totalAscentMeters - 220.0, figures.stretchClimbMeters, "stretch climb")
    }

    @Test
    fun `five kilometres on, it has ridden five kilometres and their climbing`() {
        val route = road(km = 40.0)
        val figures = RouteRideFigures.of(
            route,
            distanceMeters = 27_000.0, elevationGainMeters = 270.0,
            startedAtMeters = 22_000.0, startedAtElevationGainMeters = 220.0,
        )
        assertEquals(5_000.0, figures.riddenMeters)
        assertEquals(50.0, figures.climbedMeters)
        assertNear(route.totalDistanceMeters - 27_000.0, figures.toGoMeters, "to go")
        assertNear(route.totalAscentMeters - 270.0, figures.climbToGoMeters, "climb to go")
    }

    @Test
    fun `a road republished shorter than the start gives an empty stretch, never a negative one`() {
        val route = road(km = 10.0)
        val figures = RouteRideFigures.of(
            route,
            distanceMeters = 12_000.0, elevationGainMeters = 120.0,
            startedAtMeters = 12_000.0, startedAtElevationGainMeters = 120.0,
        )
        assertEquals(0.0, figures.riddenMeters)
        assertEquals(0.0, figures.toGoMeters)
        assertEquals(0.0, figures.stretchMeters)
        assertEquals(0.0, figures.climbToGoMeters)
    }

    @Test
    fun `the finish reads as nothing to go even when the engine overshoots by a metre`() {
        val route = road(km = 40.0)
        val figures = RouteRideFigures.of(
            route,
            distanceMeters = route.totalDistanceMeters + 1.0, elevationGainMeters = 400.0,
            startedAtMeters = 22_000.0, startedAtElevationGainMeters = 220.0,
        )
        assertEquals(0.0, figures.toGoMeters)
        assertNear(route.totalDistanceMeters - 22_000.0, figures.riddenMeters, "ridden")
    }
}
