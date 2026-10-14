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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TcxBuilderTest {

    private fun ride(
        startEpoch: Long = 1_700_000_000_000L,
        durationSeconds: Long = 1800,
        withHr: Boolean = true,
    ): Triple<WorkoutSession, List<WorkoutTelemetry>, Workout> {
        val workout = Workout(
            id = "test", name = "Test", description = null,
            segments = listOf(
                PowerZone(lengthInSeconds = 600, powerPercentFTP = 50, intervalType = "CONSTANT"),
                PowerZone(lengthInSeconds = 600, powerPercentFTP = 100, intervalType = "CONSTANT"),
                PowerZone(lengthInSeconds = 600, powerPercentFTP = 60, intervalType = "CONSTANT"),
            )
        )
        val telemetry = (0 until durationSeconds.toInt()).map { i ->
            WorkoutTelemetry(
                id = i.toLong(), sessionId = 1,
                timestampEpoch = startEpoch + i * 1000L,
                power = when {
                    i < 600 -> 100L
                    i < 1200 -> 200L
                    else -> 120L
                },
                cadence = 90L,
                heartRate = if (withHr) (130 + i / 60).coerceAtMost(170).toLong() else 0L, distanceMeters = null, latitude = null, longitude = null, altitudeMeters = null,
            )
        }
        val session = WorkoutSession(
            id = 1, workoutId = "test", workoutName = "Test",
            startTimeEpoch = startEpoch, durationSeconds = durationSeconds,
            avgPower = 140L, avgCadence = 90L, avgHeartRate = 150L,
            externalId = "test:1",
            intervalsUploadedEpoch = 0L,
            fitTrackeeUploadedEpoch = 0L,
    routeDistanceMeters = 0.0,
    routeElevationGainMeters = 0.0,
    routeStartMeters = 0.0,
    routeStartElevationGainMeters = 0.0,
    routeShape = "",
    routeProfile = "",
    ftp = 0L,
    segmentMarks = "",
    sport = "",
        )
        return Triple(session, telemetry, workout)
    }

    @Test
    fun producesWellFormedTcxWithExpectedStructure() {
        val (session, telemetry, workout) = ride()
        val tcx = TcxBuilder.build(session, telemetry, workout, ftp = 200)

        assertTrue(tcx.startsWith("<?xml version=\"1.0\""), "XML prolog present")
        assertTrue(tcx.contains("<TrainingCenterDatabase"), "root element present")
        assertTrue(tcx.contains("Sport=\"Biking\""), "sport is cycling")
        assertTrue(tcx.trimEnd().endsWith("</TrainingCenterDatabase>"), "root closed")

        // One trackpoint per telemetry sample.
        assertEquals(1800, countOccurrences(tcx, "<Trackpoint>"))
        // One lap per workout segment.
        assertEquals(3, countOccurrences(tcx, "<Lap StartTime="))
        // Power present on trackpoints via the ActivityExtension.
        assertEquals(1800, countOccurrences(tcx, "<ns3:Watts>"))
        // Heart rate present (this ride has an HRM).
        assertTrue(tcx.contains("<HeartRateBpm><Value>"), "HR emitted when present")

        // Activity Id is the ISO start instant.
        assertTrue(tcx.contains("<Id>${TcxBuilder.isoUtc(session.startTimeEpoch)}</Id>"))
    }

    @Test
    fun omitsHeartRateWhenAbsent() {
        val (session, telemetry, workout) = ride(withHr = false)
        val tcx = TcxBuilder.build(session, telemetry, workout, ftp = 200)
        assertTrue(!tcx.contains("<HeartRateBpm>"), "no HR element when no HRM data")
        // Power and cadence still present.
        assertEquals(1800, countOccurrences(tcx, "<ns3:Watts>"))
    }

    @Test
    fun singleWholeRideLapWhenNoWorkout() {
        val (session, telemetry, _) = ride()
        val tcx = TcxBuilder.build(session, telemetry, workout = null, ftp = 200)
        assertEquals(1, countOccurrences(tcx, "<Lap StartTime="))
        assertEquals(1800, countOccurrences(tcx, "<Trackpoint>"))
    }

    @Test
    fun pausedRideKeepsEverySampleAndLeavesThePauseOutOfLapTime() {
        // 300 s of riding, a two-minute pause, a sample the moment it resumes,
        // and 300 s more: what the session writes for a paused ride.
        val start = 1_700_000_000_000L
        val (session, _, _) = ride(startEpoch = start, durationSeconds = 600)
        val stamps = (0..300).map { start + it * 1000L } + (420..720).map { start + it * 1000L }
        val telemetry = stamps.mapIndexed { i, stamp ->
            WorkoutTelemetry(
                id = i.toLong(), sessionId = 1, timestampEpoch = stamp,
                power = 150L, cadence = 90L, heartRate = 140L, distanceMeters = null, latitude = null, longitude = null, altitudeMeters = null,
            )
        }
        val workout = Workout(
            id = "test", name = "Test", description = null,
            segments = listOf(
                PowerZone(lengthInSeconds = 300, powerPercentFTP = 60, intervalType = "CONSTANT"),
                PowerZone(lengthInSeconds = 300, powerPercentFTP = 90, intervalType = "CONSTANT"),
            )
        )

        val tcx = TcxBuilder.build(session, telemetry, workout, ftp = 200)

        assertEquals(602, countOccurrences(tcx, "<Trackpoint>"))
        assertTrue(tcx.contains("<Time>${TcxBuilder.isoUtc(start + 720_000L)}</Time>"), "last sample kept")
        assertTrue(tcx.contains("<Lap StartTime=\"${TcxBuilder.isoUtc(start)}\">"))
        assertTrue(tcx.contains("<Lap StartTime=\"${TcxBuilder.isoUtc(start + 300_000L)}\">"))
        assertEquals(2, countOccurrences(tcx, "<TotalTimeSeconds>300</TotalTimeSeconds>"))
    }

    @Test
    fun isoUtcFormatsKnownInstants() {
        // 2024-01-01T00:00:00Z
        assertEquals("2024-01-01T00:00:00Z", TcxBuilder.isoUtc(1_704_067_200_000L))
        // 1970 epoch
        assertEquals("1970-01-01T00:00:00Z", TcxBuilder.isoUtc(0L))
        // A leap-year date with time-of-day: 2024-02-29T13:45:07Z
        assertEquals("2024-02-29T13:45:07Z", TcxBuilder.isoUtc(1_709_214_307_000L))
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }
}
