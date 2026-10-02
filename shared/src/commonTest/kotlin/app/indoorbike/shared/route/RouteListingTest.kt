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

import app.indoorbike.shared.physics.VirtualSpeed
import app.indoorbike.shared.repo.ImportKind
import app.indoorbike.shared.repo.ManifestParser
import app.indoorbike.shared.repo.ROUTE_PROFILE_SAMPLES
import app.indoorbike.shared.repo.RouteGradientBuckets
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteListingTest {

    private fun northOf(startLat: Double, meters: Double): Double =
        startLat + meters / 111_320.0

    /** A straight road running north whose elevation at each point is [elevationAt]. */
    private fun road(
        lengthMeters: Double,
        elevationAt: (Double) -> Double,
    ): RouteTrack {
        val points = ArrayList<RawRoutePoint>()
        var d = 0.0
        while (d <= lengthMeters) {
            points.add(RawRoutePoint(northOf(45.0, d), 5.0, elevationAt(d)))
            d += 10.0
        }
        return routeTrackOf("test", "Test Road", points)
    }

    // ── The histogram ────────────────────────────────────────────────────

    @Test
    fun `histogram accounts for the whole route`() {
        val route = road(lengthMeters = 5_000.0) { d -> d * 0.05 }
        val total = RouteStats.gradientHistogram(route).sum()
        assertTrue(
            abs(total - route.totalDistanceMeters) < 1.0,
            "histogram covered $total m of ${route.totalDistanceMeters} m",
        )
    }

    @Test
    fun `a steady climb lands in one bucket`() {
        // A constant 5 % road: everything belongs in the 4-to-6 bucket, except
        // the smoothing ramp at each end where the profile is still catching up.
        val route = road(lengthMeters = 4_000.0) { d -> d * 0.05 }
        val histogram = RouteStats.gradientHistogram(route)
        val bucket = RouteGradientBuckets.indexOf(5.0)
        assertTrue(
            histogram[bucket] > route.totalDistanceMeters * 0.9,
            "expected most of the road at 5 %, got ${histogram[bucket]} m",
        )
    }

    @Test
    fun `a flat road is all in the zero bucket`() {
        val route = road(lengthMeters = 2_000.0) { 100.0 }
        val histogram = RouteStats.gradientHistogram(route)
        // 0 % falls in the 0-to-2 bucket, since buckets are closed at the low edge.
        val bucket = RouteGradientBuckets.indexOf(0.0)
        assertEquals(RouteGradientBuckets.COUNT, histogram.size)
        assertTrue(
            histogram[bucket] > route.totalDistanceMeters * 0.99,
            "expected a flat road entirely at 0 %, got ${histogram[bucket]} m",
        )
    }

    // ── The estimate ─────────────────────────────────────────────────────

    @Test
    fun `flat steady speed matches the workout readout`() {
        // The whole point of sharing constants with VirtualSpeed: a 0 % bucket
        // must be ridden at exactly the speed the HUD shows for those watts,
        // or the estimate and the ride disagree about the same road.
        val riderKg = 75f
        val expected = VirtualSpeed.speedMps(200.0, riderKg)
        val actual = RouteEstimate.steadySpeedMps(
            powerWatts = 200.0,
            gradient = 0.0,
            totalMassKg = (riderKg + VirtualSpeed.DEFAULT_BIKE_MASS_KG).toDouble(),
        )
        assertTrue(
            abs(expected - actual) < 1e-6,
            "flat estimate $actual m/s should match VirtualSpeed $expected m/s",
        )
    }

    @Test
    fun `climbing is slower than flat and descending is faster`() {
        val mass = 84.0
        val flat = RouteEstimate.steadySpeedMps(200.0, 0.0, mass)
        val climb = RouteEstimate.steadySpeedMps(200.0, 0.08, mass)
        val descent = RouteEstimate.steadySpeedMps(200.0, -0.08, mass)
        assertTrue(climb < flat, "an 8 % climb ($climb) should be slower than flat ($flat)")
        assertTrue(descent > flat, "an 8 % descent ($descent) should be faster than flat ($flat)")
    }

    @Test
    fun `a hilly route takes longer than a flat one of the same length`() {
        // Same distance, same net elevation change of zero. Only a histogram
        // can tell these apart, and this is the reason it exists: the time
        // lost climbing is never repaid on the way back down.
        val length = 8_000.0
        val flat = RouteStats.entryFor(road(length) { 100.0 }, gpxUrl = "flat.gpx")
        val rolling = RouteStats.entryFor(
            road(length) { d -> 100.0 + 40.0 * kotlin.math.sin(d / 400.0) },
            gpxUrl = "rolling.gpx",
        )

        val flatSeconds = RouteEstimate.secondsFor(flat, ftpWatts = 250, riderKg = 75f)
        val rollingSeconds = RouteEstimate.secondsFor(rolling, ftpWatts = 250, riderKg = 75f)
        assertNotNull(flatSeconds)
        assertNotNull(rollingSeconds)
        assertTrue(
            rollingSeconds > flatSeconds,
            "rolling ${rollingSeconds}s should beat flat ${flatSeconds}s over the same $length m",
        )
    }

    @Test
    fun `no rider weight means no estimate`() {
        val entry = RouteStats.entryFor(road(1_000.0) { 0.0 }, gpxUrl = "x.gpx")
        assertNull(RouteEstimate.secondsFor(entry, ftpWatts = 200, riderKg = 0f))
        assertNull(RouteEstimate.secondsFor(entry, ftpWatts = 0, riderKg = 75f))
    }

    @Test
    fun `an entry with no histogram still estimates from distance`() {
        val entry = RouteStats.entryFor(road(10_000.0) { 0.0 }, gpxUrl = "x.gpx")
            .copy(gradientMeters = emptyList())
        val seconds = RouteEstimate.secondsFor(entry, ftpWatts = 200, riderKg = 75f)
        assertNotNull(seconds)
        assertTrue(seconds > 0)
    }

    // ── Polyline ─────────────────────────────────────────────────────────

    @Test
    fun `polyline survives a round trip`() {
        val points = listOf(
            38.5 to -120.2,
            40.7 to -120.95,
            43.252 to -126.453,
            -33.8688 to 151.2093,
        )
        val decoded = RoutePolyline.decode(RoutePolyline.encode(points))
        assertEquals(points.size, decoded.size)
        points.forEachIndexed { i, (lat, lon) ->
            assertTrue(abs(lat - decoded[i].first) < 1e-5, "lat $i: ${decoded[i].first}")
            assertTrue(abs(lon - decoded[i].second) < 1e-5, "lon $i: ${decoded[i].second}")
        }
    }

    @Test
    fun `polyline matches the reference encoding`() {
        // The well-known example from the format definition. Being byte-exact
        // matters because the tool encodes in JavaScript and the app decodes
        // in Kotlin; a private dialect would work in tests and nowhere else.
        val encoded = RoutePolyline.encode(
            listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453)
        )
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encoded)
    }

    @Test
    fun `a corrupt shape decodes to nothing rather than throwing`() {
        assertEquals(emptyList(), RoutePolyline.decode("_p~iF~ps|U_ulL"))
        assertEquals(emptyList(), RoutePolyline.decode("!!!"))
    }

    // ── Entry generation ─────────────────────────────────────────────────

    @Test
    fun `entry carries everything a picker needs`() {
        val route = road(lengthMeters = 3_000.0) { d -> d * 0.04 }
        val entry = RouteStats.entryFor(
            route,
            id = "test-climb",
            name = "Test Climb",
            gpxUrl = "routes/test-climb.gpx",
        )
        assertTrue(entry.isUsable)
        assertTrue(entry.isSupported)
        assertTrue(entry.hasGradientHistogram)
        assertEquals(ROUTE_PROFILE_SAMPLES, entry.profile.size)
        assertEquals(4, entry.bounds?.size)
        assertTrue(entry.shape!!.isNotEmpty())
        assertTrue(entry.ascentMeters > 100.0, "a 4 % 3 km climb should gain over 100 m")
        assertTrue(abs(entry.maxGradientPercent - 4.0) < 1.0)
    }

    // ── Wire format ──────────────────────────────────────────────────────

    @Test
    fun `unsupported and malformed route entries are dropped not fatal`() {
        val bundle = """
            [
              {"schemaVersion":1,"id":"good","name":"Good","gpxUrl":"a.gpx","distanceMeters":1000},
              {"schemaVersion":99,"id":"future","name":"Future","gpxUrl":"b.gpx","distanceMeters":1000},
              {"schemaVersion":1,"id":"nodistance","name":"Broken","gpxUrl":"c.gpx","distanceMeters":0}
            ]
        """.trimIndent()
        val routes = ManifestParser.parseRouteBundle(bundle)
        assertEquals(1, routes.size)
        assertEquals("good", routes.first().id)
    }

    @Test
    fun `a manifest with routes still parses at schema v2`() {
        // The compatibility guarantee in one test: adding routes must not make
        // the manifest unreadable, because a version bump would take the
        // repository workouts down with it on every older install.
        val manifest = ManifestParser.parseRepositoryManifest(
            """
            {
              "schemaVersion": 2,
              "id": "demo",
              "name": "Demo",
              "bundles": [{"id":"main","url":"main.json"}],
              "routeBundles": [{"id":"routes","url":"routes.json"}]
            }
            """.trimIndent()
        )
        assertEquals(1, manifest.bundles.size)
        assertEquals(1, manifest.routeBundles.size)
        assertEquals("routes.json", manifest.routeBundles.first().url)
    }

    @Test
    fun `classify tells route files from workout files`() {
        assertEquals(
            ImportKind.RouteList,
            ManifestParser.classify("""[{"id":"a","name":"A","gpxUrl":"a.gpx","distanceMeters":1}]"""),
        )
        assertEquals(
            ImportKind.WorkoutList,
            ManifestParser.classify("""[{"id":"a","name":"A","segments":[]}]"""),
        )
        // A routes-only manifest has no "bundles" key at all.
        assertEquals(
            ImportKind.RepositoryManifest,
            ManifestParser.classify("""{"schemaVersion":2,"id":"r","name":"R","routeBundles":[]}"""),
        )
        assertEquals(
            ImportKind.Registry,
            ManifestParser.classify("""{"repositories":[{"manifestUrl":"x"}]}"""),
        )
    }
}
