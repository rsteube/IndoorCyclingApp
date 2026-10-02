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
package app.indoorbike.shared.fit

import app.indoorbike.shared.activity.TcxBuilder
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.db.WorkoutTelemetry
import app.indoorbike.shared.workout.COMPANION_WORKOUT_ID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A companion ride's track, exactly as its game reported it: every placed
 * second where the game said, every unplaced one left empty, and the distance
 * the game counted rather than one shared out over the ride.
 */
class RideTrackTest {

    /**
     * Ten seconds of a ride that began 5000 m into the game's count. The game
     * placed every second but the fourth and fifth, when it went quiet; it
     * reported no altitude for the first.
     */
    private val telemetry = (0 until 10).map { i ->
        val placed = i != 3 && i != 4
        WorkoutTelemetry(
            id = i.toLong(), sessionId = 9, timestampEpoch = START + i * 1000L,
            power = 200L, cadence = 88L, heartRate = 140L,
            distanceMeters = if (i == 4) null else 5000.0 + 8.0 * i,
            latitude = if (placed) 47.6 + i * 0.0001 else null,
            longitude = if (placed) -11.3 - i * 0.0001 else null,
            altitudeMeters = if (placed && i > 0) 800.0 + i else null,
        )
    }

    private val session = WorkoutSession(
        id = 9, workoutId = COMPANION_WORKOUT_ID, workoutName = "Kesselberg",
        startTimeEpoch = START, durationSeconds = 10,
        avgPower = 200L, avgCadence = 88L, avgHeartRate = 140L,
        externalId = "test:9",
        intervalsUploadedEpoch = 0L,
        fitTrackeeUploadedEpoch = 0L,
        routeDistanceMeters = 5072.0,
        routeElevationGainMeters = 0.0,
        routeStartMeters = 5000.0,
        routeStartElevationGainMeters = 0.0,
        routeShape = "",
        routeProfile = "",
        ftp = 0L,
        segmentMarks = "",
    )

    private fun records(bytes: ByteArray) = readFit(bytes).all(FitMessage.RECORD)

    private fun signed(record: FitMessageRead, field: Int) = record.number(field).toInt()

    private fun degrees(semicircles: Int) = semicircles * (180.0 / 2_147_483_648.0)

    @Test
    fun `every placed second is where the game said`() {
        val records = records(RideFitBuilder.build(session, telemetry, workout = null, ftp = 200))
        assertEquals(10, records.size)
        for ((i, record) in records.withIndex()) {
            val sample = telemetry[i]
            if (sample.latitude == null) {
                assertEquals(FitInvalid.SINT32, signed(record, 0), "second $i was placed")
                assertEquals(FitInvalid.SINT32, signed(record, 1), "second $i was placed")
            } else {
                assertEquals(sample.latitude!!, degrees(signed(record, 0)), 1e-6)
                assertEquals(sample.longitude!!, degrees(signed(record, 1)), 1e-6)
            }
            val altitude = sample.altitudeMeters
            if (altitude == null) {
                assertEquals(FitInvalid.UINT16.toLong(), record.number(2))
                assertEquals(FitInvalid.UINT32, record.number(78))
            } else {
                assertEquals(altitude, record.number(2) / 5.0 - 500.0, 0.2)
                assertEquals(altitude, record.number(78) / 5.0 - 500.0, 0.2)
            }
        }
    }

    @Test
    fun `the distance is the game's own count - from where the ride began`() {
        val records = records(RideFitBuilder.build(session, telemetry, workout = null, ftp = 200))
        // 1/100 m: the game's count less the ride's origin. The second it said
        // nothing holds; the next report carries the ground in between.
        val expected = listOf(0, 8, 16, 24, 24, 40, 48, 56, 64, 72).map { it * 100L }
        assertEquals(expected, records.map { it.number(5) })
    }

    @Test
    fun `a ride no game placed carries no position at all`() {
        val plain = telemetry.map { it.copy(distanceMeters = null, latitude = null, longitude = null, altitudeMeters = null) }
        val record = records(RideFitBuilder.build(session, plain, workout = null, ftp = 200)).first()
        assertFalse(0 in record.fields)
        assertFalse(1 in record.fields)
        assertFalse(2 in record.fields)
        assertFalse(78 in record.fields)
    }

    @Test
    fun `the TCX track places the same seconds and no others`() {
        val tcx = TcxBuilder.build(session, telemetry, workout = null, ftp = 200)
        val points = tcx.split("<Trackpoint>").drop(1)
        assertEquals(10, points.size)
        for ((i, point) in points.withIndex()) {
            val sample = telemetry[i]
            if (sample.latitude == null) {
                assertFalse("<Position>" in point, "second $i was placed")
            } else {
                val lat = point.substringAfter("<LatitudeDegrees>").substringBefore("<").toDouble()
                val lon = point.substringAfter("<LongitudeDegrees>").substringBefore("<").toDouble()
                assertEquals(sample.latitude!!, lat, 1e-7)
                assertEquals(sample.longitude!!, lon, 1e-7)
                assertTrue(lon < 0, "a western longitude stays west")
            }
            assertEquals(sample.altitudeMeters != null, "<AltitudeMeters>" in point, "second $i")
            // Trackpoint_t: Time, Position, AltitudeMeters, DistanceMeters.
            val order = listOf("<Time>", "<Position>", "<AltitudeMeters>", "<DistanceMeters>")
                .map { point.indexOf(it) }.filter { it >= 0 }
            assertEquals(order.sorted(), order, "schema order at second $i")
        }
    }

    private companion object {
        const val START = 1_700_000_000_000L
    }
}
