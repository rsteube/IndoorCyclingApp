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

import kotlin.test.Test
import kotlin.time.TimeSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpxParserTest {

    @Test
    fun `parses a plain track`() {
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" creator="test">
              <trk>
                <name>Sunday Loop</name>
                <trkseg>
                  <trkpt lat="60.1000" lon="24.9000"><ele>10.0</ele></trkpt>
                  <trkpt lat="60.1010" lon="24.9000"><ele>20.0</ele></trkpt>
                  <trkpt lat="60.1020" lon="24.9000"><ele>30.0</ele></trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val route = assertNotNull(GpxParser.parseGpx(gpx, id = "sunday"))
        assertEquals("sunday", route.id)
        assertEquals("Sunday Loop", route.name)
        assertEquals(3, route.points.size)
        assertEquals(10.0, route.points[0].elevationMeters)
        assertEquals(0.0, route.points[0].distanceMeters)
        // 0.001 degrees of latitude is about 111 m.
        assertTrue(route.points[1].distanceMeters in 105.0..118.0, "got ${route.points[1].distanceMeters}")
        assertTrue(route.points[2].distanceMeters > route.points[1].distanceMeters)
    }

    @Test
    fun `accepts namespaced tags, single quotes and self-closing points`() {
        val gpx = """
            <gpx:gpx xmlns:gpx="http://www.topografix.com/GPX/1/1">
              <gpx:trk>
                <gpx:name>Namespaced</gpx:name>
                <gpx:trkseg>
                  <gpx:trkpt lat='60.1000' lon='24.9000' />
                  <gpx:trkpt lat = "60.1010"
                             lon = "24.9000" >
                    <gpx:ele>42</gpx:ele>
                  </gpx:trkpt>
                </gpx:trkseg>
              </gpx:trk>
            </gpx:gpx>
        """.trimIndent()

        val route = assertNotNull(GpxParser.parseGpx(gpx, id = "ns"))
        assertEquals("Namespaced", route.name)
        assertEquals(2, route.points.size)
        // A self-closing point has no elevation to read; flat is the answer.
        assertEquals(0.0, route.points[0].elevationMeters)
        assertEquals(42.0, route.points[1].elevationMeters)
    }

    @Test
    fun `bounds attributes do not masquerade as coordinates`() {
        // <bounds> carries minlat / minlon, which a naive substring search for
        // "lat" and "lon" would happily read as a track point's coordinates.
        val gpx = """
            <gpx>
              <metadata>
                <bounds minlat="60.0" minlon="24.0" maxlat="61.0" maxlon="25.0"/>
              </metadata>
              <trk><trkseg>
                <trkpt lat="60.5000" lon="24.5000"><ele>5</ele></trkpt>
                <trkpt lat="60.5010" lon="24.5000"><ele>6</ele></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val route = assertNotNull(GpxParser.parseGpx(gpx, id = "bounded"))
        assertEquals(2, route.points.size)
        assertEquals(60.5, route.points[0].latitude)
        assertEquals(24.5, route.points[0].longitude)
    }

    @Test
    fun `skips unknown children and extension blocks`() {
        val gpx = """
            <gpx>
              <trk><trkseg>
                <trkpt lat="60.1" lon="24.9">
                  <ele>100</ele>
                  <time>2026-01-01T10:00:00Z</time>
                  <extensions><power>240</power><gpxtpx:hr>151</gpxtpx:hr></extensions>
                </trkpt>
                <trkpt lat="60.101" lon="24.9">
                  <time>2026-01-01T10:00:05Z</time>
                  <ele>112</ele>
                </trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val route = assertNotNull(GpxParser.parseGpx(gpx, id = "rich"))
        assertEquals(2, route.points.size)
        assertEquals(100.0, route.points[0].elevationMeters)
        assertEquals(112.0, route.points[1].elevationMeters)
    }

    @Test
    fun `falls back to route points when there is no track`() {
        val gpx = """
            <gpx>
              <rte>
                <name>Planned</name>
                <rtept lat="60.1" lon="24.9"><ele>4</ele></rtept>
                <rtept lat="60.102" lon="24.9"><ele>9</ele></rtept>
                <rtept lat="60.104" lon="24.9"><ele>14</ele></rtept>
              </rte>
            </gpx>
        """.trimIndent()

        val route = assertNotNull(GpxParser.parseGpx(gpx, id = "planned"))
        assertEquals("Planned", route.name)
        assertEquals(3, route.points.size)
    }

    @Test
    fun `decodes entities in the name and falls back when there is none`() {
        val named = "<gpx><trk><name>Tour &amp; Detour</name><trkseg>" +
            "<trkpt lat='60.1' lon='24.9'/><trkpt lat='60.11' lon='24.9'/>" +
            "</trkseg></trk></gpx>"
        assertEquals("Tour & Detour", assertNotNull(GpxParser.parseGpx(named, "x")).name)

        val unnamed = "<gpx><trk><trkseg>" +
            "<trkpt lat='60.1' lon='24.9'/><trkpt lat='60.11' lon='24.9'/>" +
            "</trkseg></trk></gpx>"
        assertEquals("Fallback", assertNotNull(GpxParser.parseGpx(unnamed, "x", "Fallback")).name)
    }

    @Test
    fun `reads a Garmin Connect export`() {
        // The exact shape of the bundled route: a multi-line root tag whose
        // attributes contain both colons and slashes, elevation on its own
        // line, and a name in metadata as well as on the track. Every one of
        // those has fooled a hand-rolled scanner at some point.
        val gpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx creator="Garmin Connect" version="1.1"
              xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/11.xsd"
              xmlns:ns3="http://www.garmin.com/xmlschemas/TrackPointExtension/v1"
              xmlns="http://www.topografix.com/GPX/1/1"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <metadata>
                <name>bike_test</name>
                <link href="connect.garmin.com"><text>Garmin Connect</text></link>
                <time>2026-09-05T20:54:32.000Z</time>
              </metadata>
              <trk>
                <name>bike_test</name>
                <trkseg>
                  <trkpt lat="47.801655158400536" lon="11.141491485759616">
                    <ele>573.76</ele>
                  </trkpt>
                  <trkpt lat="47.80144485644996" lon="11.14166172221303">
                    <ele>574.24</ele>
                  </trkpt>
                  <trkpt lat="47.80123472213745" lon="11.141831874847412">
                    <ele>574.85</ele>
                  </trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()

        val route = assertNotNull(GpxParser.parseGpx(gpx, id = "bike-test"))
        assertEquals("bike_test", route.name)
        assertEquals(3, route.points.size)
        assertEquals(47.801655158400536, route.points[0].latitude)
        assertEquals(11.141491485759616, route.points[0].longitude)
        assertEquals(573.76, route.points[0].elevationMeters)
        assertEquals(574.85, route.points[2].elevationMeters)
        // Roughly 25 m between Garmin course points.
        assertTrue(route.totalDistanceMeters in 40.0..80.0, "got ${route.totalDistanceMeters} m")
    }

    @Test
    fun `rejects files with nothing rideable in them`() {
        assertNull(GpxParser.parseGpx("", "empty"))
        assertNull(GpxParser.parseGpx("<gpx></gpx>", "empty"))
        assertNull(
            GpxParser.parseGpx("<gpx><trk><trkseg><trkpt lat='60' lon='24'/></trkseg></trk></gpx>", "one")
        )
    }

    @Test
    fun `drops corrupt coordinates rather than the whole file`() {
        val gpx = """
            <gpx><trk><trkseg>
              <trkpt lat="60.1" lon="24.9"><ele>1</ele></trkpt>
              <trkpt lat="not-a-number" lon="24.9"><ele>2</ele></trkpt>
              <trkpt lat="999.0" lon="24.9"><ele>3</ele></trkpt>
              <trkpt lat="60.102" lon="24.9"><ele>4</ele></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()

        val route = assertNotNull(GpxParser.parseGpx(gpx, id = "corrupt"))
        assertEquals(2, route.points.size)
        assertEquals(4.0, route.points[1].elevationMeters)
    }

    @Test
    fun `survives a truncated file`() {
        // A GPX cut off mid-write: the last point has no closing tag at all.
        val gpx = "<gpx><trk><trkseg>" +
            "<trkpt lat='60.1' lon='24.9'><ele>1</ele></trkpt>" +
            "<trkpt lat='60.102' lon='24.9'><ele>2</ele></trkpt>" +
            "<trkpt lat='60.104' lon='24.9'><ele>3"

        val route = assertNotNull(GpxParser.parseGpx(gpx, id = "torn"))
        assertTrue(route.points.size >= 2, "kept ${route.points.size}")
    }

    /** A GPX of [points] points with no namespace prefix and no colon after the header. */
    private fun longGpx(points: Int): String = buildString(points * 90) {
        append("<gpx><trk><name>Long one</name><trkseg>")
        for (i in 0 until points) {
            append("<trkpt lat='")
            append(41.1 + i * 0.00001)
            append("' lon='")
            append(1.28 + i * 0.00001)
            append("'><ele>")
            append(17 + (i % 100))
            append("</ele></trkpt>")
        }
        append("</trkseg></trk></gpx>")
    }

    @Test
    fun `cost grows with the file, not with its square`() {
        // findTag used to look for a namespace prefix with an unbounded
        // indexOf(':'), so every tag it examined scanned to the end of the
        // document. A GPX with no colon after the header - which is most of
        // them - made the parse quadratic: on a phone a 500 KB route took
        // minutes and reached the rider as a spinner that never stopped.
        //
        // Asserted as a ratio between two sizes rather than a wall clock,
        // because the absolute time says more about the machine than the
        // code: a desktop JVM's indexOf is vectorised enough to hide the
        // defect entirely, while ART is not. Doubling the input doubles a
        // linear parse and quadruples a quadratic one, on any machine.
        repeat(3) { GpxParser.parseGpx(longGpx(4000), id = "warmup") }

        fun timeOf(points: Int): Double {
            val gpx = longGpx(points)
            val started = TimeSource.Monotonic.markNow()
            assertNotNull(GpxParser.parseGpx(gpx, id = "long"))
            return started.elapsedNow().inWholeMicroseconds.toDouble()
        }

        // Best of three each way: a single run is at the mercy of a GC pause.
        val small = (1..3).minOf { timeOf(4000) }
        val large = (1..3).minOf { timeOf(16000) }

        // Four times the points. Linear predicts ~4x, quadratic ~16x. The
        // bound sits well clear of both so ordinary noise cannot trip it.
        val ratio = large / small
        assertTrue(ratio < 8.0, "16000 points took ${ratio}x the time of 4000; quadratic parsing is back")
    }

    @Test
    fun `keeps every point of a large file`() {
        val route = assertNotNull(GpxParser.parseGpx(longGpx(6000), id = "long"))
        assertEquals(6000, route.points.size)
    }

    @Test
    fun `finds elevation only within its own point`() {
        // Bounding findTag must not let a point borrow the next point's <ele>.
        val gpx = "<gpx><trk><trkseg>" +
            "<trkpt lat='60.1' lon='24.9'></trkpt>" +
            "<trkpt lat='60.102' lon='24.9'><ele>250</ele></trkpt>" +
            "</trkseg></trk></gpx>"

        val route = assertNotNull(GpxParser.parseGpx(gpx, id = "borrow"))
        assertEquals(2, route.points.size)
        assertEquals(0.0, route.points[0].elevationMeters, "must not take the next point's elevation")
        assertEquals(250.0, route.points[1].elevationMeters)
    }
}
