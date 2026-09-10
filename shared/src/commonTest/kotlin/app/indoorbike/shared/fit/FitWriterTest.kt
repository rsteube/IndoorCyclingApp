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

import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.db.WorkoutTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FitWriterTest {

    @Test
    fun headerHasCorrectMagicAndProtocol() {
        val w = FitWriter()
        w.writePlaceholderHeader()
        w.finalizeAndCrc()
        val bytes = w.bytes()
        assertEquals(14 + 2, bytes.size, "header-only file: 14 header bytes + 2 file CRC")
        assertEquals(14, bytes[0].toInt() and 0xFF)
        assertEquals(0x20, bytes[1].toInt() and 0xFF)         // protocol 2.0
        assertEquals('.'.code, bytes[8].toInt() and 0xFF)
        assertEquals('F'.code, bytes[9].toInt() and 0xFF)
        assertEquals('I'.code, bytes[10].toInt() and 0xFF)
        assertEquals('T'.code, bytes[11].toInt() and 0xFF)
    }

    @Test
    fun fileCrcMatchesContent() {
        // Round-trip: write a tiny file, then verify the CRC at the end
        // matches what fitCrc reports over the rest of the bytes.
        val w = FitWriter()
        w.writePlaceholderHeader()
        // Add one tiny definition + data message so there's a body to CRC.
        w.writeDefinition(0, FitMessage.FILE_ID, listOf(
            Triple(0, 1, FitBaseType.ENUM),
            Triple(4, 4, FitBaseType.UINT32),
        ))
        w.writeDataHeader(0)
        w.u8(4)
        w.u32(123_456_789L)
        w.finalizeAndCrc()
        val bytes = w.bytes()
        val storedCrc = (bytes[bytes.size - 2].toInt() and 0xFF) or
            ((bytes[bytes.size - 1].toInt() and 0xFF) shl 8)
        val expected = FitWriter.fitCrc(bytes.toList(), 0, bytes.size - 2)
        assertEquals(expected, storedCrc, "trailing file CRC must match content")
    }

    @Test
    fun headerCrcMatchesHeaderBytes() {
        val w = FitWriter()
        w.writePlaceholderHeader()
        w.finalizeAndCrc()
        val bytes = w.bytes()
        val storedHeaderCrc = (bytes[12].toInt() and 0xFF) or
            ((bytes[13].toInt() and 0xFF) shl 8)
        val expected = FitWriter.fitCrc(bytes.toList(), 0, 12)
        assertEquals(expected, storedHeaderCrc, "header CRC must match first 12 bytes")
    }

    @Test
    fun dataSizeFieldEqualsBodyLength() {
        val w = FitWriter()
        w.writePlaceholderHeader()
        w.writeDefinition(0, FitMessage.FILE_ID, listOf(
            Triple(0, 1, FitBaseType.ENUM),
        ))
        w.writeDataHeader(0)
        w.u8(4)
        w.finalizeAndCrc()
        val bytes = w.bytes()
        // bytes[4..7] LE = total size of records (everything between header and trailing CRC)
        val storedSize = (bytes[4].toInt() and 0xFF) or
            ((bytes[5].toInt() and 0xFF) shl 8) or
            ((bytes[6].toInt() and 0xFF) shl 16) or
            ((bytes[7].toInt() and 0xFF) shl 24)
        // Body = total - 14-byte header - 2-byte trailing CRC.
        val expected = bytes.size - 14 - 2
        assertEquals(expected, storedSize)
    }

    @Test
    fun rideFitBuilderProducesAFile() {
        // 30-minute ride at three different power levels.
        val startEpoch = 1_700_000_000_000L
        val workout = Workout(
            id = "test", name = "Test", description = null,
            segments = listOf(
                PowerZone(lengthInSeconds = 600, powerPercentFTP = 50, intervalType = "CONSTANT"),
                PowerZone(lengthInSeconds = 600, powerPercentFTP = 100, intervalType = "CONSTANT"),
                PowerZone(lengthInSeconds = 600, powerPercentFTP = 60, intervalType = "CONSTANT"),
            )
        )
        val telemetry = (0 until 1800).map { i ->
            WorkoutTelemetry(
                id = i.toLong(), sessionId = 1,
                timestampEpoch = startEpoch + i * 1000L,
                power = when {
                    i < 600 -> 100L
                    i < 1200 -> 200L
                    else -> 120L
                },
                cadence = 90L,
                heartRate = (130 + i / 60).coerceAtMost(170).toLong(),
            )
        }
        val session = WorkoutSession(
            id = 1, workoutId = "test", workoutName = "Test",
            startTimeEpoch = startEpoch, durationSeconds = 1800,
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
        )

        val bytes = RideFitBuilder.build(session, telemetry, workout, ftp = 200)

        // Magic + size sanity
        assertEquals('.'.code, bytes[8].toInt() and 0xFF)
        assertEquals('F'.code, bytes[9].toInt() and 0xFF)
        assertTrue(bytes.size > 1000, "Should have substantial bytes for 1800 records")

        // CRC integrity end-to-end
        val storedCrc = (bytes[bytes.size - 2].toInt() and 0xFF) or
            ((bytes[bytes.size - 1].toInt() and 0xFF) shl 8)
        val expectedCrc = FitWriter.fitCrc(bytes.toList(), 0, bytes.size - 2)
        assertEquals(expectedCrc, storedCrc)
    }

    @Test
    fun lapsCarrySegmentPowerAndCadenceTargets() {
        val startEpoch = 1_700_000_000_000L
        val workout = Workout(
            id = "test", name = "Test", description = null,
            segments = listOf(
                // A stated range, a single value, and no ask at all.
                PowerZone(600, 50, "CONSTANT", cadenceMinRpm = 85, cadenceMaxRpm = 95),
                PowerZone(600, 100, "CONSTANT", cadenceMinRpm = 90),
                PowerZone(600, 60, "CONSTANT"),
            )
        )
        val telemetry = (0 until 1800).map { i ->
            WorkoutTelemetry(
                id = i.toLong(), sessionId = 1,
                timestampEpoch = startEpoch + i * 1000L,
                power = 150L, cadence = 90L, heartRate = 140L,
            )
        }
        val session = WorkoutSession(
            id = 1, workoutId = "test", workoutName = "Test",
            startTimeEpoch = startEpoch, durationSeconds = 1800,
            avgPower = 150L, avgCadence = 90L, avgHeartRate = 140L,
            externalId = "test:1",
            intervalsUploadedEpoch = 0L,
            fitTrackeeUploadedEpoch = 0L,
    routeDistanceMeters = 0.0,
    routeElevationGainMeters = 0.0,
    routeStartMeters = 0.0,
    routeStartElevationGainMeters = 0.0,
    routeShape = "",
    routeProfile = "",
        )

        val laps = lapDeveloperFields(RideFitBuilder.build(session, telemetry, workout, ftp = 200))

        assertEquals(3, laps.size, "one lap per segment")
        // 50% of a 200 W FTP = 100 W, and the range is exported as authored.
        assertEquals(listOf(100, 50, 85, 95), laps[0])
        // A lone cadenceMinRpm is a single-value target: low and high match.
        assertEquals(listOf(200, 100, 90, 90), laps[1])
        // No cadence ask, so both bounds are the spec's uint8 invalid sentinel.
        assertEquals(listOf(120, 60, 0xFF, 0xFF), laps[2])
    }

    @Test
    fun unstructuredRideWritesInvalidTargets() {
        val session = WorkoutSession(
            id = 7, workoutId = "freeride", workoutName = "Free Ride",
            startTimeEpoch = 1_700_000_000_000L, durationSeconds = 60,
            avgPower = 150L, avgCadence = 85L, avgHeartRate = 140L,
            externalId = "test:7",
            intervalsUploadedEpoch = 0L,
            fitTrackeeUploadedEpoch = 0L,
    routeDistanceMeters = 0.0,
    routeElevationGainMeters = 0.0,
    routeStartMeters = 0.0,
    routeStartElevationGainMeters = 0.0,
    routeShape = "",
    routeProfile = "",
        )
        val telemetry = (0 until 60).map { i ->
            WorkoutTelemetry(
                id = i.toLong(), sessionId = 7,
                timestampEpoch = session.startTimeEpoch + i * 1000L,
                power = 150L, cadence = 85L, heartRate = 140L,
            )
        }

        val laps = lapDeveloperFields(RideFitBuilder.build(session, telemetry, workout = null, ftp = 200))

        assertEquals(1, laps.size)
        // Nothing was planned, so no target may claim to have been.
        assertEquals(listOf(0xFFFF, 0xFFFF, 0xFF, 0xFF), laps[0])
    }

    // ── Minimal FIT reader, enough to verify our own output ──────────────
    // Decoding the file is the only way to catch a malformed developer-field
    // section: the CRC assertions above pass on any self-consistent garbage.

    private class Definition(
        val globalMessageNum: Int,
        val fieldSizes: List<Int>,
        val developerFieldSizes: List<Int>,
    )

    /**
     * The four developer values on each lap, in declared order:
     * target_power, target_power_pct, target_cadence_low, target_cadence_high.
     */
    private fun lapDeveloperFields(bytes: ByteArray): List<List<Int>> {
        val definitions = HashMap<Int, Definition>()
        val laps = mutableListOf<List<Int>>()
        fun u8At(i: Int) = bytes[i].toInt() and 0xFF
        var pos = 14                       // skip the file header
        val end = bytes.size - 2           // stop before the trailing file CRC
        while (pos < end) {
            val header = u8At(pos); pos += 1
            val localType = header and 0x0F
            if (header and 0x40 != 0) {
                pos += 2                   // reserved + architecture
                val globalMessageNum = u8At(pos) or (u8At(pos + 1) shl 8); pos += 2
                val fieldCount = u8At(pos); pos += 1
                val fieldSizes = (0 until fieldCount).map { i -> u8At(pos + i * 3 + 1) }
                pos += fieldCount * 3
                val developerFieldSizes = if (header and 0x20 == 0) emptyList() else {
                    val devCount = u8At(pos); pos += 1
                    (0 until devCount).map { i -> u8At(pos + i * 3 + 1) }
                        .also { pos += devCount * 3 }
                }
                definitions[localType] = Definition(globalMessageNum, fieldSizes, developerFieldSizes)
            } else {
                val def = definitions[localType] ?: error("data message for undefined local type $localType")
                pos += def.fieldSizes.sum()
                val developerValues = def.developerFieldSizes.map { size ->
                    var value = 0
                    for (b in 0 until size) value = value or (u8At(pos + b) shl (8 * b))
                    pos += size
                    value
                }
                if (def.globalMessageNum == FitMessage.LAP) laps.add(developerValues)
            }
        }
        assertEquals(end, pos, "reader must consume the body exactly")
        return laps
    }

    @Test
    fun rideFitBuilderHandlesNoWorkout() {
        // Unstructured ride — single lap should cover the whole thing.
        val session = WorkoutSession(
            id = 42, workoutId = "freeride", workoutName = "Free Ride",
            startTimeEpoch = 1_700_000_000_000L,
            durationSeconds = 60,
            avgPower = 150L, avgCadence = 85L, avgHeartRate = 140L,
            externalId = "test:42",
            intervalsUploadedEpoch = 0L,
            fitTrackeeUploadedEpoch = 0L,
    routeDistanceMeters = 0.0,
    routeElevationGainMeters = 0.0,
    routeStartMeters = 0.0,
    routeStartElevationGainMeters = 0.0,
    routeShape = "",
    routeProfile = "",
        )
        val telemetry = (0 until 60).map { i ->
            WorkoutTelemetry(
                id = i.toLong(), sessionId = 42,
                timestampEpoch = session.startTimeEpoch + i * 1000L,
                power = 150L, cadence = 85L, heartRate = 140L,
            )
        }
        val bytes = RideFitBuilder.build(session, telemetry, workout = null, ftp = 200)
        assertTrue(bytes.size > 200)
        // CRC integrity
        val storedCrc = (bytes[bytes.size - 2].toInt() and 0xFF) or
            ((bytes[bytes.size - 1].toInt() and 0xFF) shl 8)
        val expectedCrc = FitWriter.fitCrc(bytes.toList(), 0, bytes.size - 2)
        assertEquals(expectedCrc, storedCrc)
    }
}
