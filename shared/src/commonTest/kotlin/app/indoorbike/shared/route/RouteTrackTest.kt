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
package app.indoorbike.shared.route

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteTrackTest {

    /** Metres of latitude, roughly, so a test can lay out a road in metres. */
    private fun northOf(startLat: Double, meters: Double): Double =
        startLat + meters / 111_320.0

    /**
     * A straight road running north, [lengthMeters] long, whose elevation at
     * each point comes from [elevationAt].
     */
    private fun straightRoad(
        lengthMeters: Double,
        spacing: Double = 10.0,
        elevationAt: (Double) -> Double,
    ): RouteTrack {
        val points = ArrayList<RawRoutePoint>()
        var d = 0.0
        while (d <= lengthMeters) {
            points.add(RawRoutePoint(northOf(60.0, d), 24.9, elevationAt(d)))
            d += spacing
        }
        return routeTrackOf("test", "Test Road", points)
    }

    @Test
    fun `cumulative distance tracks the ground covered`() {
        val route = straightRoad(lengthMeters = 1_000.0) { 0.0 }
        assertTrue(
            abs(route.totalDistanceMeters - 1_000.0) < 5.0,
            "expected about 1000 m, got ${route.totalDistanceMeters}",
        )
        assertEquals(0.0, route.points.first().distanceMeters)
    }

    @Test
    fun `stacked points are dropped`() {
        // A rider stopped at a junction: twenty samples in the same spot.
        val points = buildList {
            repeat(20) { add(RawRoutePoint(60.0, 24.9, 5.0)) }
            add(RawRoutePoint(northOf(60.0, 50.0), 24.9, 5.0))
        }
        val route = routeTrackOf("stopped", "Stopped", points)
        assertEquals(2, route.points.size)
    }

    @Test
    fun `a constant slope reads back as that slope`() {
        // 5 % for a kilometre.
        val route = straightRoad(lengthMeters = 1_000.0) { d -> d * 0.05 }
        // Away from the ends, where the centred difference is clipped by the
        // route's edges, the gradient should be the real one.
        for (d in listOf(200.0, 500.0, 800.0)) {
            val gradient = route.gradientAt(d)
            assertTrue(
                abs(gradient - 0.05) < 0.005,
                "at $d m expected 5 %, got ${gradient * 100} %",
            )
        }
    }

    @Test
    fun `elevation noise does not become gradient`() {
        // A dead-flat road recorded by a GPS whose altitude wanders by a metre
        // and a half between samples ten metres apart. Differentiated raw, that
        // is a 15 % gradient; it must come out flat.
        var seed = 12345L
        fun jitter(): Double {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            // ushr, not shr: a signed shift would skew the noise negative
            // rather than centring it on the road's true height.
            return ((seed ushr 33) % 3001L).toDouble() / 1000.0 - 1.5
        }
        val route = straightRoad(lengthMeters = 1_000.0) { 40.0 + jitter() }

        for (d in listOf(200.0, 400.0, 600.0, 800.0)) {
            assertTrue(
                abs(route.gradientAt(d)) < 0.03,
                "noise leaked through as ${route.gradientAt(d) * 100} % at $d m",
            )
        }
        // And it must not be counted as climbing either.
        assertTrue(route.totalAscentMeters < 25.0, "phantom ascent ${route.totalAscentMeters} m")
    }

    @Test
    fun `absurd gradients are clamped to something rideable`() {
        // A barometric spike: 200 m of altitude appearing over 20 m of road.
        val route = straightRoad(lengthMeters = 500.0) { d -> if (d in 240.0..260.0) 200.0 else 0.0 }
        for (d in 0.0..500.0 step 10.0) {
            assertTrue(
                abs(route.gradientAt(d)) <= RouteTrack.MAX_GRADIENT + 1e-9,
                "gradient ${route.gradientAt(d)} at $d m exceeds the clamp",
            )
        }
    }

    @Test
    fun `climbing is counted once, descending separately`() {
        // Up 100 m over the first half, back down over the second.
        val route = straightRoad(lengthMeters = 2_000.0) { d ->
            if (d <= 1_000.0) d * 0.1 else 100.0 - (d - 1_000.0) * 0.1
        }
        assertTrue(abs(route.totalAscentMeters - 100.0) < 8.0, "ascent ${route.totalAscentMeters}")
        assertTrue(abs(route.totalDescentMeters - 100.0) < 8.0, "descent ${route.totalDescentMeters}")
    }

    @Test
    fun `position interpolates between recorded points`() {
        val route = straightRoad(lengthMeters = 1_000.0, spacing = 100.0) { d -> d * 0.02 }
        val a = route.positionAt(0.0)
        val mid = route.positionAt(450.0)
        val end = route.positionAt(1_000.0)

        assertTrue(mid.latitude > a.latitude && mid.latitude < end.latitude)
        assertEquals(450.0, mid.distanceMeters)
        // Travelling due north.
        assertTrue(abs(mid.bearingDegrees) < 1.0 || abs(mid.bearingDegrees - 360.0) < 1.0)
    }

    @Test
    fun `positions past the end clamp to the finish`() {
        val route = straightRoad(lengthMeters = 500.0) { 0.0 }
        val past = route.positionAt(9_999.0)
        assertEquals(route.totalDistanceMeters, past.distanceMeters)
        val before = route.positionAt(-50.0)
        assertEquals(0.0, before.distanceMeters)
    }

    @Test
    fun `a route that returns to its start is a loop`() {
        val loop = routeTrackOf(
            "loop", "Loop",
            listOf(
                RawRoutePoint(60.0, 24.9, 0.0),
                RawRoutePoint(northOf(60.0, 300.0), 24.9, 0.0),
                RawRoutePoint(northOf(60.0, 300.0), 24.905, 0.0),
                RawRoutePoint(60.0, 24.9, 0.0),
            ),
        )
        assertTrue(loop.isLoop)
        assertFalse(straightRoad(lengthMeters = 1_000.0) { 0.0 }.isLoop)
    }

    @Test
    fun `a route with no elevation simulates as flat`() {
        val route = straightRoad(lengthMeters = 800.0) { 0.0 }
        assertEquals(0.0, route.gradientAt(400.0))
        assertEquals(0.0, route.totalAscentMeters)
    }

    @Test
    fun `drawing samples cover the whole route`() {
        val route = straightRoad(lengthMeters = 1_000.0) { d -> d * 0.05 }
        val samples = route.elevationSamples(50)
        assertEquals(50, samples.size)
        assertTrue(samples.last() > samples.first())
        assertEquals(50, route.gradientSamples(50).size)
    }

    @Test
    fun `an unrideable route reports itself empty rather than throwing`() {
        val empty = routeTrackOf("none", "None", emptyList())
        assertTrue(empty.isEmpty)
        assertEquals(0.0, empty.totalDistanceMeters)
        assertEquals(0.0, empty.gradientAt(10.0))
        assertEquals(0.0, empty.positionAt(10.0).latitude)
    }
}

/** Range-with-step over doubles, so the clamp test can sweep the route. */
private infix fun ClosedFloatingPointRange<Double>.step(step: Double): Iterable<Double> {
    val out = ArrayList<Double>()
    var v = start
    while (v <= endInclusive) {
        out.add(v)
        v += step
    }
    return out
}
