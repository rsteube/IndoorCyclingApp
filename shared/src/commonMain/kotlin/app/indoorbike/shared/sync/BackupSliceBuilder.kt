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

/**
 * Pure helpers for assembling this device's backup slice and computing the
 * tombstones it should carry. No I/O — the platform-side `SyncEngine`
 * supplies snapshots of local state and the previous own-slice (if any),
 * and this object hands back the [BackupDocument] to publish.
 *
 * The tombstone-derivation is the load-bearing bit. The contract:
 *
 *  - **Diff against the previous own-slice, not the merged state.** The
 *    slice the rider's previous "this device" wrote to cloud is the
 *    record of what we last published as ours. Anything in there that
 *    isn't in current local state any more must have been deleted on
 *    this device since — emit a tombstone.
 *  - **Skip the diff on first-ever sync.** When `previousOwn == null`
 *    (this device has never written a slice — fresh install or first-time
 *    sync) we have no baseline to diff against, so emitting tombstones
 *    would be guesswork. Just publish current local state and let future
 *    syncs do the diff.
 *  - **Carry forward old tombstones.** Previous tombstones we wrote stay
 *    in the slice indefinitely so devices that come online after a long
 *    absence still see the deletion.
 */
object BackupSliceBuilder {

    /**
     * Assembles a [BackupDocument] for this device. All fields populated
     * from supplied snapshots — no field falls back to a sentinel.
     */
    fun build(
        deviceId: String,
        deviceLabel: String,
        nowEpoch: Long,
        settings: BackupSettings,
        sessions: List<BackupSession>,
        importedWorkouts: List<BackupImportedWorkout>,
        previousOwn: BackupDocument?,
        newSessionTombstones: List<BackupTombstone>,
        newImportTombstones: List<BackupTombstone>,
    ): BackupDocument = BackupDocument(
        deviceId = deviceId,
        deviceLabel = deviceLabel,
        writtenEpoch = nowEpoch,
        settings = settings,
        sessions = sessions,
        sessionTombstones = mergeTombstones(previousOwn?.sessionTombstones, newSessionTombstones),
        importedWorkouts = importedWorkouts,
        importedTombstones = mergeTombstones(previousOwn?.importedTombstones, newImportTombstones),
    )

    /**
     * Computes session-tombstones for any externalId that was in the
     * previous own-slice's session list but isn't in current local state —
     * indicating the rider deleted that session on this device since the
     * last sync.
     *
     * Returns an empty list when [previousOwn] is null (first sync case
     * documented above).
     */
    fun computeSessionDeletes(
        previousOwn: BackupDocument?,
        currentLocalExternalIds: Set<String>,
        nowEpoch: Long,
    ): List<BackupTombstone> {
        if (previousOwn == null) return emptyList()
        return previousOwn.sessions
            .filter { it.externalId !in currentLocalExternalIds }
            .map { BackupTombstone(it.externalId, nowEpoch) }
    }

    /** As [computeSessionDeletes] but for imported workouts (keyed by `workout.id`). */
    fun computeImportDeletes(
        previousOwn: BackupDocument?,
        currentLocalWorkoutIds: Set<String>,
        nowEpoch: Long,
    ): List<BackupTombstone> {
        if (previousOwn == null) return emptyList()
        return previousOwn.importedWorkouts
            .filter { it.workout.id !in currentLocalWorkoutIds }
            .map { BackupTombstone(it.workout.id, nowEpoch) }
    }

    /**
     * Combines old + new tombstones, taking the latest deletedEpoch per id
     * (defensive — old slices shouldn't normally carry future-dated
     * entries, but if they do, the freshest signal wins). Sorted by id for
     * stable diffability.
     */
    private fun mergeTombstones(
        previous: List<BackupTombstone>?,
        new: List<BackupTombstone>,
    ): List<BackupTombstone> {
        val byId = HashMap<String, Long>((previous?.size ?: 0) + new.size)
        previous?.forEach { byId[it.id] = it.deletedEpoch }
        new.forEach {
            val existing = byId[it.id]
            if (existing == null || it.deletedEpoch > existing) byId[it.id] = it.deletedEpoch
        }
        return byId.map { (id, t) -> BackupTombstone(id, t) }.sortedBy { it.id }
    }
}
