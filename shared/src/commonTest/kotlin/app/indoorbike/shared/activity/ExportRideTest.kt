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
package app.indoorbike.shared.activity

import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.db.WorkoutTelemetry
import app.indoorbike.shared.progression.normalizedPower
import app.indoorbike.shared.session.SegmentMark
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportRideTest {

    private val start = 1_700_000_000_000L

    private val workout = Workout(
        id = "abc", name = "A B C", description = null,
        segments = listOf(
            PowerZone(lengthInSeconds = 600, powerPercentFTP = 50, intervalType = "CONSTANT"),
            PowerZone(lengthInSeconds = 600, powerPercentFTP = 100, intervalType = "CONSTANT"),
            PowerZone(lengthInSeconds = 600, powerPercentFTP = 70, intervalType = "CONSTANT"),
        ),
    )

    private fun session(
        seconds: Int,
        segmentMarks: String = "",
        ftp: Long = 0,
        routeMetres: Double = 0.0,
    ) = WorkoutSession(
        id = 1, workoutId = "abc", workoutName = "A B C",
        startTimeEpoch = start, durationSeconds = seconds.toLong(),
        avgPower = 150, avgCadence = 90, avgHeartRate = 140,
        externalId = "t:1", intervalsUploadedEpoch = 0, fitTrackeeUploadedEpoch = 0,
        routeDistanceMeters = routeMetres, routeElevationGainMeters = 0.0,
        routeStartMeters = 0.0, routeStartElevationGainMeters = 0.0,
        routeShape = "", routeProfile = "",
        ftp = ftp, segmentMarks = segmentMarks,
    )

    private fun samples(seconds: Int, watts: (Int) -> Long = { 150L }) = (0 until seconds).map { i ->
        WorkoutTelemetry(
            id = i.toLong(), sessionId = 1, timestampEpoch = start + i * 1000L,
            power = watts(i), cadence = 90, heartRate = 140,
        )
    }

    /**
     * A, then B skipped a minute in, then C. On the plan, C's riding landed in
     * B's lap under B's target and C got a sliver. Cut where the ride recorded
     * the changes, each lap is what was ridden against it.
     */
    @Test
    fun `laps fall where the ride recorded its segments changing`() {
        val marks = SegmentMark.encode(listOf(SegmentMark(0, 0), SegmentMark(1, 600), SegmentMark(2, 660)))
        val ride = ExportRide(session(1_260, marks), samples(1_260), workout, fallbackFtp = 200, riderKg = 0f)

        assertEquals(3, ride.laps.size)
        assertEquals(listOf(50, 100, 70), ride.laps.map { it.segment?.powerPercentFTP })
        assertEquals(listOf(600, 60, 600), ride.laps.map { it.endPoint - it.firstPoint })
        assertEquals(listOf(600, 60, 600), ride.laps.map { it.timerSeconds })
    }

    /** Segments skipped with nothing ridden in them are not laps. */
    @Test
    fun `a segment with no riding in it is left out`() {
        val marks = SegmentMark.encode(listOf(SegmentMark(0, 0), SegmentMark(1, 300), SegmentMark(2, 300)))
        val ride = ExportRide(session(600, marks), samples(600), workout, fallbackFtp = 200, riderKg = 0f)

        assertEquals(listOf(50, 70), ride.laps.map { it.segment?.powerPercentFTP })
    }

    /**
     * Ended 100 s into B, on a ride saved before segment changes were
     * recorded. The planned laps stop at B, which runs to the end of the ride;
     * the TCX used to write C too, empty, which strict importers reject.
     */
    @Test
    fun `a ride ended early has no empty laps after its end`() {
        val ride = ExportRide(session(700), samples(700), workout, fallbackFtp = 200, riderKg = 0f)

        assertEquals(2, ride.laps.size)
        assertEquals(100, ride.laps.last().timerSeconds)
        assertTrue(ride.laps.all { it.endPoint > it.firstPoint })
    }

    @Test
    fun `the FTP is the ride's own when history recorded it`() {
        assertEquals(265, ExportRide(session(60, ftp = 265), samples(60), workout, 200, 0f).ftp)
        assertEquals(200, ExportRide(session(60, ftp = 0), samples(60), workout, 200, 0f).ftp)
    }

    /**
     * A route ride exports the road it rode, shared out over its samples, and
     * does so with no body weight set, where the flat-road estimate had nothing.
     */
    @Test
    fun `a route ride's distance is the road's`() {
        val ride = ExportRide(
            session(600, routeMetres = 4_321.0), samples(600) { if (it < 300) 120L else 240L },
            workout = null, fallbackFtp = 200, riderKg = 0f,
        )
        assertTrue(ride.hasDistance)
        assertTrue(abs(ride.metres.sum() - 4_321.0) < 0.01, "shared out ${ride.metres.sum()} m")
        // Harder seconds cover more of it.
        assertTrue(ride.metres[500] > ride.metres[100])
    }

    @Test
    fun `no road and no weight is no distance`() {
        val ride = ExportRide(session(60), samples(60), workout = null, fallbackFtp = 200, riderKg = 0f)
        assertFalse(ride.hasDistance)
    }

    @Test
    fun `heart rate averages over the readings there were`() {
        assertEquals(150L, averageHeartRate(List(600) { 0L } + List(3_000) { 150L }))
        assertEquals(0L, averageHeartRate(List(60) { 0L }))
    }

    /**
     * 10 s at 1000 W every five minutes over 150 W. The raw fourth-power mean
     * made this about 429 W; the 30 s rolling average the definition asks for
     * makes it about 244.
     */
    @Test
    fun `normalized power smooths over thirty seconds first`() {
        val watts = List(3_600) { if (it % 300 < 10) 1_000L else 150L }
        val np = normalizedPower(watts)
        assertTrue(np in 235.0..255.0, "NP was $np")
    }

    @Test
    fun `a ride shorter than the window has no normalized power`() {
        assertEquals(0.0, normalizedPower(List(29) { 200L }))
        assertEquals(200.0, normalizedPower(List(30) { 200L }), 1e-9)
    }
}
