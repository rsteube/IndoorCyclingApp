/*
 * Copyright (C) 2026 Juhani Lehtimäki
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
package app.indoorbike.shared.sync

import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupSerializerTest {

    @Test
    fun roundTripPreservesAllFields() {
        val original = BackupDocument(
            deviceId = "device-abc",
            deviceLabel = "Pixel 8",
            writtenEpoch = 1_715_098_234_000,
            settings = BackupSettings(
                epoch = 1_715_098_100_000,
                ftp = 220,
                weightKg = 75.5f,
                maxHr = 190,
                hasCompletedOnboarding = true,
                intervalsAthleteId = "i12345",
                intervalsAutoUpload = false,
                favouriteWorkoutIds = listOf("starter-15min", "vo2-4x4"),
            ),
            sessions = listOf(
                BackupSession(
                    externalId = "device-abc:1",
                    workoutId = "starter-15min",
                    workoutName = "Starter",
                    startTimeEpoch = 1_715_000_000_000,
                    durationSeconds = 900,
                    avgPower = 180,
                    avgCadence = 85,
                    avgHeartRate = 138,
                ),
            ),
            sessionTombstones = listOf(
                BackupTombstone(id = "other:9", deletedEpoch = 1_714_900_000_000),
            ),
            importedWorkouts = listOf(
                BackupImportedWorkout(
                    workout = Workout(
                        id = "vo2-4x4",
                        name = "VO2 4×4",
                        segments = listOf(
                            PowerZone(lengthInSeconds = 240, powerPercentFTP = 110, intervalType = "VO2"),
                        ),
                    ),
                    sourceRepoId = "cycling-coach",
                    sourceRepoName = "Cycling Coach",
                    importedEpoch = 1_715_000_000_000,
                ),
            ),
            importedTombstones = emptyList(),
        )
        val text = BackupSerializer.encode(original)
        val decoded = BackupSerializer.decode(text)
        assertEquals(original, decoded)
    }

    @Test
    fun rejectsFutureSchemaVersion() {
        val text = """
            {
              "schemaVersion": 99,
              "deviceId": "x",
              "writtenEpoch": 0
            }
        """.trimIndent()
        val ex = assertFailsWith<BackupParseException> { BackupSerializer.decode(text) }
        assertTrue(ex.message!!.contains("v99"), "Expected version-mismatch message, got: ${ex.message}")
    }

    @Test
    fun acceptsCurrentSchemaVersion() {
        val text = """
            {
              "schemaVersion": $BACKUP_SCHEMA_VERSION_CURRENT,
              "deviceId": "x",
              "writtenEpoch": 0
            }
        """.trimIndent()
        val doc = BackupSerializer.decode(text)
        assertEquals("x", doc.deviceId)
    }

    @Test
    fun ignoresUnknownFields() {
        // Forward compat: a slice from a newer minor version with extra
        // fields parses cleanly as long as the major schemaVersion is
        // current. Drops what it doesn't understand.
        val text = """
            {
              "schemaVersion": $BACKUP_SCHEMA_VERSION_CURRENT,
              "deviceId": "x",
              "writtenEpoch": 0,
              "futureField": "ignore me",
              "anotherFutureField": [1, 2, 3]
            }
        """.trimIndent()
        val doc = BackupSerializer.decode(text)
        assertEquals("x", doc.deviceId)
    }

    @Test
    fun malformedJsonThrowsBackupParseException() {
        val ex = assertFailsWith<BackupParseException> {
            BackupSerializer.decode("{ not json }")
        }
        assertTrue(ex.cause != null)
    }

    @Test
    fun emptyOptionalsRoundTripCleanly() {
        val original = BackupDocument(
            deviceId = "minimal",
            writtenEpoch = 0,
        )
        val decoded = BackupSerializer.decode(BackupSerializer.encode(original))
        assertEquals(original, decoded)
    }
}
