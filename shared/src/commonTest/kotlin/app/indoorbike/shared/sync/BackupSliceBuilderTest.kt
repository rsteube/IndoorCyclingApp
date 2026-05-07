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
import kotlin.test.assertTrue

class BackupSliceBuilderTest {

    // ── computeSessionDeletes ─────────────────────────────────────────────

    @Test
    fun firstSyncEmitsNoSessionTombstones() {
        // No previous-own-slice means we have no baseline to diff against —
        // so we never guess at deletes, even if local is empty (reinstall
        // case where cloud has session data we'd otherwise nuke).
        val result = BackupSliceBuilder.computeSessionDeletes(
            previousOwn = null,
            currentLocalExternalIds = emptySet(),
            nowEpoch = 1_000,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun deletedSessionsBecomeTombstones() {
        val previous = doc("phone", sessions = listOf(session("phone:A", start = 100), session("phone:B", start = 200)))
        val result = BackupSliceBuilder.computeSessionDeletes(
            previousOwn = previous,
            currentLocalExternalIds = setOf("phone:B"),  // A was deleted locally
            nowEpoch = 5_000,
        )
        assertEquals(1, result.size)
        assertEquals("phone:A", result.first().id)
        assertEquals(5_000L, result.first().deletedEpoch)
    }

    @Test
    fun unchangedLocalEmitsNoSessionTombstones() {
        val previous = doc("phone", sessions = listOf(session("phone:A", start = 100)))
        val result = BackupSliceBuilder.computeSessionDeletes(
            previousOwn = previous,
            currentLocalExternalIds = setOf("phone:A"),
            nowEpoch = 5_000,
        )
        assertTrue(result.isEmpty())
    }

    // ── computeImportDeletes ──────────────────────────────────────────────

    @Test
    fun deletedImportsBecomeTombstones() {
        val previous = doc(
            "phone",
            importedWorkouts = listOf(
                import(workout("a"), epoch = 1),
                import(workout("b"), epoch = 1),
            ),
        )
        val result = BackupSliceBuilder.computeImportDeletes(
            previousOwn = previous,
            currentLocalWorkoutIds = setOf("a"),  // user deleted "b"
            nowEpoch = 5_000,
        )
        assertEquals(1, result.size)
        assertEquals("b", result.first().id)
    }

    // ── build: tombstone carry-forward ────────────────────────────────────

    @Test
    fun buildCarriesForwardPreviousTombstones() {
        val previous = doc(
            "phone",
            sessionTombstones = listOf(BackupTombstone("phone:OLD", 50)),
        )
        val slice = BackupSliceBuilder.build(
            deviceId = "phone",
            deviceLabel = "Pixel 8",
            nowEpoch = 1_000,
            settings = settings(epoch = 1_000),
            sessions = emptyList(),
            importedWorkouts = emptyList(),
            previousOwn = previous,
            newSessionTombstones = emptyList(),
            newImportTombstones = emptyList(),
        )
        assertEquals(1, slice.sessionTombstones.size)
        assertEquals("phone:OLD", slice.sessionTombstones.first().id)
        assertEquals(50L, slice.sessionTombstones.first().deletedEpoch)
    }

    @Test
    fun buildMergesOldAndNewTombstones() {
        val previous = doc(
            "phone",
            sessionTombstones = listOf(BackupTombstone("phone:OLD", 50)),
        )
        val slice = BackupSliceBuilder.build(
            deviceId = "phone",
            deviceLabel = "Pixel 8",
            nowEpoch = 1_000,
            settings = settings(epoch = 1_000),
            sessions = emptyList(),
            importedWorkouts = emptyList(),
            previousOwn = previous,
            newSessionTombstones = listOf(BackupTombstone("phone:NEW", 1_000)),
            newImportTombstones = emptyList(),
        )
        assertEquals(setOf("phone:NEW", "phone:OLD"), slice.sessionTombstones.map { it.id }.toSet())
    }

    @Test
    fun buildPicksLatestEpochOnDuplicateTombstones() {
        // If somehow the same id appears in both old and new tombstones, the
        // freshest deletedEpoch wins. (Defensive — shouldn't happen with
        // monotonic clocks but the merger needs a deterministic answer.)
        val previous = doc(
            "phone",
            sessionTombstones = listOf(BackupTombstone("dup", 50)),
        )
        val slice = BackupSliceBuilder.build(
            deviceId = "phone",
            deviceLabel = "Pixel 8",
            nowEpoch = 1_000,
            settings = settings(epoch = 1_000),
            sessions = emptyList(),
            importedWorkouts = emptyList(),
            previousOwn = previous,
            newSessionTombstones = listOf(BackupTombstone("dup", 1_000)),
            newImportTombstones = emptyList(),
        )
        assertEquals(1, slice.sessionTombstones.size)
        assertEquals(1_000L, slice.sessionTombstones.first().deletedEpoch)
    }

    @Test
    fun buildPopulatesAllFieldsFromSnapshot() {
        // Sanity: the builder is just plumbing — confirm it doesn't drop
        // anything the caller passed in.
        val sessions = listOf(session("phone:A", start = 100))
        val imports = listOf(import(workout("a"), epoch = 200))
        val s = settings(epoch = 5_000, ftp = 220)
        val slice = BackupSliceBuilder.build(
            deviceId = "phone",
            deviceLabel = "Pixel 8",
            nowEpoch = 6_000,
            settings = s,
            sessions = sessions,
            importedWorkouts = imports,
            previousOwn = null,
            newSessionTombstones = emptyList(),
            newImportTombstones = emptyList(),
        )
        assertEquals("phone", slice.deviceId)
        assertEquals("Pixel 8", slice.deviceLabel)
        assertEquals(6_000L, slice.writtenEpoch)
        assertEquals(s, slice.settings)
        assertEquals(sessions, slice.sessions)
        assertEquals(imports, slice.importedWorkouts)
        assertTrue(slice.sessionTombstones.isEmpty())
        assertTrue(slice.importedTombstones.isEmpty())
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun doc(
        deviceId: String,
        sessions: List<BackupSession> = emptyList(),
        sessionTombstones: List<BackupTombstone> = emptyList(),
        importedWorkouts: List<BackupImportedWorkout> = emptyList(),
        importedTombstones: List<BackupTombstone> = emptyList(),
    ) = BackupDocument(
        deviceId = deviceId,
        writtenEpoch = 0,
        sessions = sessions,
        sessionTombstones = sessionTombstones,
        importedWorkouts = importedWorkouts,
        importedTombstones = importedTombstones,
    )

    private fun session(externalId: String, start: Long) = BackupSession(
        externalId = externalId,
        workoutId = "w",
        workoutName = "W",
        startTimeEpoch = start,
        durationSeconds = 600,
        avgPower = 150,
        avgCadence = 85,
        avgHeartRate = 140,
    )

    private fun settings(epoch: Long, ftp: Int? = null) = BackupSettings(epoch = epoch, ftp = ftp)

    private fun workout(id: String) = Workout(
        id = id, name = id,
        segments = listOf(PowerZone(60, 80, "Steady")),
    )

    private fun import(workout: Workout, epoch: Long) = BackupImportedWorkout(
        workout = workout, importedEpoch = epoch,
    )
}
