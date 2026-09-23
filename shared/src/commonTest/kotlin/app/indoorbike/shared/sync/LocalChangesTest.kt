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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * This device's own changes surviving a sync, which they did not: the merge
 * used this device's last published slice in place of its current state, and
 * wrote the answer back over it.
 */
class LocalChangesTest {

    private fun settings(epoch: Long, ftp: Int? = 200, weight: Float? = 70f, favourites: List<String>? = listOf("a")) =
        BackupSettings(epoch = epoch, ftp = ftp, weightKg = weight, favouriteWorkoutIds = favourites)

    private fun doc(device: String, settings: BackupSettings?, imports: List<BackupImportedWorkout> = emptyList()) =
        BackupDocument(deviceId = device, writtenEpoch = settings?.epoch ?: 0L, settings = settings, importedWorkouts = imports)

    /** The report: set 250 after a sync at 200, and the next launch put 200 back. */
    @Test
    fun `a setting changed since the last sync survives the next one`() {
        val published = settings(epoch = 1_000, ftp = 200)
        val now = settings(epoch = 0, ftp = 250)
        val mine = LocalChanges.settings(now, published, nowMs = 5_000)

        val merged = BackupMerger.merge(listOf(doc("me", mine))).settings!!
        assertEquals(250, merged.ftp)
    }

    @Test
    fun `an unchanged setting keeps its date, so a newer change elsewhere wins it`() {
        val published = settings(epoch = 1_000, ftp = 200)
        val mine = LocalChanges.settings(settings(epoch = 0, ftp = 200), published, nowMs = 5_000)
        assertEquals(1_000, mine.epochOf(LocalChanges.FTP))

        val tablet = settings(epoch = 3_000, ftp = 280)
        val merged = BackupMerger.merge(listOf(doc("me", mine), doc("tablet", tablet))).settings!!
        assertEquals(280, merged.ftp)
    }

    /**
     * One device changes FTP, the other weight. Merging whole slices by date
     * gave both fields to whichever synced last; field by field keeps both.
     */
    @Test
    fun `two devices changing two different settings both keep their change`() {
        val phone = BackupSettings(
            epoch = 4_000, ftp = 260, weightKg = 70f,
            fieldEpochs = mapOf(LocalChanges.FTP to 4_000, LocalChanges.WEIGHT to 1_000),
        )
        val tablet = BackupSettings(
            epoch = 5_000, ftp = 200, weightKg = 68f,
            fieldEpochs = mapOf(LocalChanges.FTP to 1_000, LocalChanges.WEIGHT to 5_000),
        )
        val merged = BackupMerger.merge(listOf(doc("phone", phone), doc("tablet", tablet))).settings!!
        assertEquals(260, merged.ftp)
        assertEquals(68f, merged.weightKg)
    }

    @Test
    fun `a device syncing for the first time takes what the others have`() {
        val mine = LocalChanges.settings(settings(epoch = 0, ftp = 150), previous = null, nowMs = 5_000)
        val other = settings(epoch = 1_000, ftp = 240)
        val merged = BackupMerger.merge(listOf(doc("me", mine), doc("other", other))).settings!!
        assertEquals(240, merged.ftp)
    }

    @Test
    fun `favourites in a different order are not a change`() {
        val published = settings(epoch = 1_000, favourites = listOf("a", "b"))
        val mine = LocalChanges.settings(settings(epoch = 0, favourites = listOf("b", "a")), published, nowMs = 5_000)
        assertEquals(1_000, mine.epochOf(LocalChanges.FAVOURITES))
    }

    private fun workout(name: String) = Workout(
        id = "w1", name = name,
        segments = listOf(PowerZone(lengthInSeconds = 60, powerPercentFTP = 50, intervalType = "CONSTANT")),
    )

    /** A renamed imported workout went back to its old name at the next sync. */
    @Test
    fun `a renamed import survives the sync`() {
        val published = listOf(BackupImportedWorkout(workout("Old name"), importedEpoch = 1_000))
        val now = listOf(BackupImportedWorkout(workout("New name"), importedEpoch = 1_000))
        val mine = LocalChanges.imports(now, published, nowMs = 5_000)
        assertEquals(5_000, mine.single().importedEpoch)

        val merged = BackupMerger.merge(
            listOf(doc("me", null, mine), doc("me-before", null, published)),
        )
        assertEquals("New name", merged.importedWorkouts.single().workout.name)
    }

    @Test
    fun `an unchanged import keeps its date`() {
        val published = listOf(BackupImportedWorkout(workout("Same"), importedEpoch = 1_000))
        val mine = LocalChanges.imports(published, published, nowMs = 5_000)
        assertEquals(1_000, mine.single().importedEpoch)
    }

    @Test
    fun `deletes arriving from elsewhere are checked like deletes made here`() {
        assertTrue(BackupSafety.isSuspiciousIncomingDelete(localItemCount = 40, incomingDeletes = 30))
        assertFalse(BackupSafety.isSuspiciousIncomingDelete(localItemCount = 40, incomingDeletes = 2))
        assertFalse(BackupSafety.isSuspiciousIncomingDelete(localItemCount = 6, incomingDeletes = 4))
    }
}
