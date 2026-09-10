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
package app.indoorbike.shared.sync

import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupMergerTest {

    // ── Empty / single-doc baselines ──────────────────────────────────────

    @Test
    fun emptyInputProducesEmptyState() {
        val state = BackupMerger.merge(emptyList())
        assertNull(state.settings)
        assertTrue(state.sessions.isEmpty())
        assertTrue(state.sessionTombstones.isEmpty())
        assertTrue(state.importedWorkouts.isEmpty())
        assertTrue(state.importedTombstones.isEmpty())
    }

    @Test
    fun singleDocumentPassesThrough() {
        val doc = doc(
            "phone",
            sessions = listOf(session("phone:1", start = 1_000)),
            settings = settings(epoch = 100, ftp = 220),
        )
        val state = BackupMerger.merge(listOf(doc))
        assertEquals(1, state.sessions.size)
        assertEquals("phone:1", state.sessions.first().externalId)
        assertEquals(220, state.settings?.ftp)
    }

    // ── Sessions: union + tombstones ──────────────────────────────────────

    @Test
    fun sessionsUnionedAcrossDevices() {
        val phone = doc("phone", sessions = listOf(session("phone:1", start = 1_000)))
        val tablet = doc("tablet", sessions = listOf(session("tablet:7", start = 2_000)))
        val state = BackupMerger.merge(listOf(phone, tablet))
        assertEquals(2, state.sessions.size)
        // Sorted by startTimeEpoch desc.
        assertEquals("tablet:7", state.sessions[0].externalId)
        assertEquals("phone:1", state.sessions[1].externalId)
    }

    @Test
    fun tombstoneInOneDeviceDeletesSessionFromAnother() {
        val phone = doc("phone", sessions = listOf(session("phone:1", start = 1_000)))
        val tablet = doc(
            "tablet",
            sessionTombstones = listOf(BackupTombstone("phone:1", deletedEpoch = 1_500)),
        )
        val state = BackupMerger.merge(listOf(phone, tablet))
        assertTrue(state.sessions.isEmpty())
        assertEquals(1, state.sessionTombstones.size)
        assertEquals("phone:1", state.sessionTombstones.first().id)
    }

    @Test
    fun staleTombstoneBeforeSessionExistsIsIgnored() {
        // Defensive: a tombstone with deletedEpoch < session.startTimeEpoch
        // shouldn't ever be written, but if it is, the session survives.
        val phone = doc("phone", sessions = listOf(session("phone:1", start = 5_000)))
        val tablet = doc(
            "tablet",
            sessionTombstones = listOf(BackupTombstone("phone:1", deletedEpoch = 100)),
        )
        val state = BackupMerger.merge(listOf(phone, tablet))
        assertEquals(1, state.sessions.size)
    }

    @Test
    fun latestTombstoneWinsAcrossDevices() {
        // Two devices both wrote a tombstone for the same session at different
        // epochs. The merger keeps the latest.
        val phone = doc(
            "phone",
            sessionTombstones = listOf(BackupTombstone("x:1", deletedEpoch = 1_000)),
        )
        val tablet = doc(
            "tablet",
            sessionTombstones = listOf(BackupTombstone("x:1", deletedEpoch = 2_000)),
        )
        val state = BackupMerger.merge(listOf(phone, tablet))
        assertEquals(1, state.sessionTombstones.size)
        assertEquals(2_000L, state.sessionTombstones.first().deletedEpoch)
    }

    // ── Settings: per-field LWW ───────────────────────────────────────────

    @Test
    fun settingsNewestNonNullPerFieldWins() {
        val older = doc(
            "phone",
            settings = settings(epoch = 100, ftp = 220, weightKg = 80f, maxHr = null),
        )
        val newer = doc(
            "tablet",
            settings = settings(epoch = 200, ftp = null, weightKg = 75f, maxHr = 190),
        )
        val state = BackupMerger.merge(listOf(older, newer))
        // Newer wins where it has a value, falls through to older where null.
        assertEquals(220, state.settings?.ftp)
        assertEquals(75f, state.settings?.weightKg)
        assertEquals(190, state.settings?.maxHr)
    }

    @Test
    fun settingsEpochFromNewestSlice() {
        val older = doc("phone", settings = settings(epoch = 100, ftp = 220))
        val newer = doc("tablet", settings = settings(epoch = 200, ftp = 230))
        val state = BackupMerger.merge(listOf(older, newer))
        assertEquals(200L, state.settings?.epoch)
        assertEquals(230, state.settings?.ftp)
    }

    @Test
    fun settingsExplicitEmptyFavouritesIsHonored() {
        // Newer slice explicitly cleared favourites (empty list); older had
        // some. Empty wins because it's the newest non-null value.
        val older = doc(
            "phone",
            settings = settings(epoch = 100, favouriteWorkoutIds = listOf("a", "b")),
        )
        val newer = doc(
            "tablet",
            settings = settings(epoch = 200, favouriteWorkoutIds = emptyList()),
        )
        val state = BackupMerger.merge(listOf(older, newer))
        assertEquals(emptyList(), state.settings?.favouriteWorkoutIds)
    }

    @Test
    fun settingsAbsentSlicesIgnored() {
        val phone = doc("phone", settings = settings(epoch = 200, ftp = 220))
        val tablet = doc("tablet", settings = null)
        val state = BackupMerger.merge(listOf(phone, tablet))
        assertNotNull(state.settings)
        assertEquals(220, state.settings?.ftp)
    }

    // ── Imports: dedup by id, LWW on epoch ────────────────────────────────

    @Test
    fun importsDedupedByIdLatestEpochWins() {
        val older = doc(
            "phone",
            importedWorkouts = listOf(import(workout("vo2-30s"), epoch = 100, repoName = "OLD-PACK")),
        )
        val newer = doc(
            "tablet",
            importedWorkouts = listOf(import(workout("vo2-30s"), epoch = 200, repoName = "NEW-PACK")),
        )
        val state = BackupMerger.merge(listOf(older, newer))
        assertEquals(1, state.importedWorkouts.size)
        assertEquals("NEW-PACK", state.importedWorkouts.first().sourceRepoName)
        assertEquals(200L, state.importedWorkouts.first().importedEpoch)
    }

    @Test
    fun importTombstoneBeforeReimportLetsImportWin() {
        // Rider deleted on phone, then deliberately re-imported on tablet.
        // The newer import resurrects.
        val phone = doc(
            "phone",
            importedTombstones = listOf(BackupTombstone("vo2-30s", deletedEpoch = 100)),
        )
        val tablet = doc(
            "tablet",
            importedWorkouts = listOf(import(workout("vo2-30s"), epoch = 200)),
        )
        val state = BackupMerger.merge(listOf(phone, tablet))
        assertEquals(1, state.importedWorkouts.size)
    }

    @Test
    fun importTombstoneAfterImportDeletes() {
        val phone = doc(
            "phone",
            importedWorkouts = listOf(import(workout("vo2-30s"), epoch = 100)),
        )
        val tablet = doc(
            "tablet",
            importedTombstones = listOf(BackupTombstone("vo2-30s", deletedEpoch = 200)),
        )
        val state = BackupMerger.merge(listOf(phone, tablet))
        assertTrue(state.importedWorkouts.isEmpty())
        assertEquals(1, state.importedTombstones.size)
    }

    @Test
    fun importTombstoneAtSameEpochAsImportLetsImportWin() {
        // Boundary: equal epochs treated as "delete first, then re-import"
        // — re-import wins. Documents the merger's resurrection semantics.
        val phone = doc(
            "phone",
            importedTombstones = listOf(BackupTombstone("vo2-30s", deletedEpoch = 100)),
        )
        val tablet = doc(
            "tablet",
            importedWorkouts = listOf(import(workout("vo2-30s"), epoch = 100)),
        )
        val state = BackupMerger.merge(listOf(phone, tablet))
        assertEquals(1, state.importedWorkouts.size)
    }

    @Test
    fun importsSortedByNameCaseInsensitive() {
        val docX = doc(
            "phone",
            importedWorkouts = listOf(
                import(workout("a", name = "Beta"), epoch = 1),
                import(workout("b", name = "alpha"), epoch = 1),
                import(workout("c", name = "Gamma"), epoch = 1),
            ),
        )
        val state = BackupMerger.merge(listOf(docX))
        assertEquals(listOf("alpha", "Beta", "Gamma"), state.importedWorkouts.map { it.workout.name })
    }

    // ── Determinism ───────────────────────────────────────────────────────

    @Test
    fun mergeIsOrderIndependent() {
        val a = doc("a", sessions = listOf(session("a:1", start = 1_000)))
        val b = doc("b", sessions = listOf(session("b:2", start = 2_000)))
        val c = doc("c", sessions = listOf(session("c:3", start = 3_000)))
        val state1 = BackupMerger.merge(listOf(a, b, c))
        val state2 = BackupMerger.merge(listOf(c, a, b))
        assertEquals(state1.sessions.map { it.externalId }, state2.sessions.map { it.externalId })
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun doc(
        deviceId: String,
        settings: BackupSettings? = null,
        sessions: List<BackupSession> = emptyList(),
        sessionTombstones: List<BackupTombstone> = emptyList(),
        importedWorkouts: List<BackupImportedWorkout> = emptyList(),
        importedTombstones: List<BackupTombstone> = emptyList(),
    ) = BackupDocument(
        deviceId = deviceId,
        deviceLabel = deviceId,
        writtenEpoch = 0,
        settings = settings,
        sessions = sessions,
        sessionTombstones = sessionTombstones,
        importedWorkouts = importedWorkouts,
        importedTombstones = importedTombstones,
    )

    private fun session(
        externalId: String,
        start: Long,
        duration: Long = 1_800,
        avgPower: Long = 200,
    ) = BackupSession(
        externalId = externalId,
        workoutId = "starter-15min",
        workoutName = "Starter",
        startTimeEpoch = start,
        durationSeconds = duration,
        avgPower = avgPower,
        avgCadence = 85,
        avgHeartRate = 140,
    )

    private fun settings(
        epoch: Long,
        ftp: Int? = null,
        weightKg: Float? = null,
        maxHr: Int? = null,
        hasCompletedOnboarding: Boolean? = null,
        intervalsAthleteId: String? = null,
        intervalsAutoUpload: Boolean? = null,
        favouriteWorkoutIds: List<String>? = null,
    ) = BackupSettings(
        epoch = epoch,
        ftp = ftp,
        weightKg = weightKg,
        maxHr = maxHr,
        hasCompletedOnboarding = hasCompletedOnboarding,
        intervalsAthleteId = intervalsAthleteId,
        intervalsAutoUpload = intervalsAutoUpload,
        favouriteWorkoutIds = favouriteWorkoutIds,
    )

    private fun workout(id: String, name: String = id) = Workout(
        id = id,
        name = name,
        segments = listOf(PowerZone(lengthInSeconds = 60, powerPercentFTP = 80, intervalType = "Steady")),
    )

    private fun import(
        workout: Workout,
        epoch: Long,
        repoId: String? = null,
        repoName: String? = null,
    ) = BackupImportedWorkout(
        workout = workout,
        sourceRepoId = repoId,
        sourceRepoName = repoName,
        importedEpoch = epoch,
    )
}
