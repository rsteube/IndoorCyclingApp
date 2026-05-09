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

/**
 * Pure merge logic. Given every [BackupDocument] in the rider's sync folder
 * (one slice per device), produces a deterministic [MergedState] the
 * platform layer can apply to local SqlDelight / DataStore / imports.
 *
 * Conflict policy:
 *  - **Settings** — first-non-null wins per field across slices ordered by
 *    [BackupSettings.epoch] descending. See [BackupSettings] for why this
 *    works under the merge-before-write contract.
 *  - **Sessions** — union by [BackupSession.externalId]. Adds from any
 *    device are non-conflicting because external ids are device-namespaced.
 *  - **Session tombstones** — drop a session iff a tombstone exists for its
 *    external id with [BackupTombstone.deletedEpoch] >= the session's
 *    `startTimeEpoch`. A tombstone written before the session existed is
 *    treated as stale (defensive — shouldn't happen in practice).
 *  - **Imported workouts** — merged by `workout.id`. Highest [importedEpoch]
 *    wins on duplicate ids across devices.
 *  - **Imported tombstones** — drop the workout iff
 *    `deletedEpoch > importedEpoch`. Equal epochs let the import win
 *    (re-import after a same-instant delete is a deliberate resurrection).
 *
 * No I/O, no platform deps. Time isn't sourced from the merger — slices
 * supply their own epochs — so tests are deterministic.
 */
object BackupMerger {

    fun merge(documents: List<BackupDocument>): MergedState {
        val mergedSettings = mergeSettings(documents.mapNotNull { it.settings })

        val sessionTombstones = collapseTombstones(documents.flatMap { it.sessionTombstones })
        val sessionsById = LinkedHashMap<String, BackupSession>()
        for (d in documents) for (s in d.sessions) sessionsById.putIfAbsent(s.externalId, s)
        val sessions = sessionsById.values.filter { s ->
            val deletedAt = sessionTombstones[s.externalId] ?: return@filter true
            deletedAt < s.startTimeEpoch
        }.sortedByDescending { it.startTimeEpoch }

        val importTombstones = collapseTombstones(documents.flatMap { it.importedTombstones })
        val importsById = LinkedHashMap<String, BackupImportedWorkout>()
        for (d in documents) for (w in d.importedWorkouts) {
            val existing = importsById[w.workout.id]
            if (existing == null || w.importedEpoch > existing.importedEpoch) {
                importsById[w.workout.id] = w
            }
        }
        val imports = importsById.values.filter { w ->
            val deletedAt = importTombstones[w.workout.id] ?: return@filter true
            deletedAt <= w.importedEpoch
        }.sortedBy { it.workout.name.lowercase() }

        return MergedState(
            settings = mergedSettings,
            sessions = sessions,
            sessionTombstones = sessionTombstones.toBackupList(),
            importedWorkouts = imports,
            importedTombstones = importTombstones.toBackupList(),
        )
    }

    /**
     * For each id, keep the latest [BackupTombstone.deletedEpoch] across all
     * slices. Returns a map id → latest deletedEpoch.
     */
    private fun collapseTombstones(all: List<BackupTombstone>): Map<String, Long> {
        val out = HashMap<String, Long>(all.size)
        for (t in all) {
            val current = out[t.id]
            if (current == null || t.deletedEpoch > current) out[t.id] = t.deletedEpoch
        }
        return out
    }

    private fun Map<String, Long>.toBackupList(): List<BackupTombstone> =
        map { (id, t) -> BackupTombstone(id, t) }.sortedBy { it.id }

    private fun mergeSettings(slices: List<BackupSettings>): BackupSettings? {
        if (slices.isEmpty()) return null
        val ordered = slices.sortedByDescending { it.epoch }
        var ftp: Int? = null
        var weightKg: Float? = null
        var maxHr: Int? = null
        var hasCompletedOnboarding: Boolean? = null
        var hasDismissedStarterCard: Boolean? = null
        var intervalsAthleteId: String? = null
        var intervalsAutoUpload: Boolean? = null
        var favouriteWorkoutIds: List<String>? = null

        for (s in ordered) {
            if (ftp == null) ftp = s.ftp
            if (weightKg == null) weightKg = s.weightKg
            if (maxHr == null) maxHr = s.maxHr
            if (hasCompletedOnboarding == null) hasCompletedOnboarding = s.hasCompletedOnboarding
            if (hasDismissedStarterCard == null) hasDismissedStarterCard = s.hasDismissedStarterCard
            if (intervalsAthleteId == null) intervalsAthleteId = s.intervalsAthleteId
            if (intervalsAutoUpload == null) intervalsAutoUpload = s.intervalsAutoUpload
            if (favouriteWorkoutIds == null) favouriteWorkoutIds = s.favouriteWorkoutIds
        }
        return BackupSettings(
            epoch = ordered.first().epoch,
            ftp = ftp,
            weightKg = weightKg,
            maxHr = maxHr,
            hasCompletedOnboarding = hasCompletedOnboarding,
            hasDismissedStarterCard = hasDismissedStarterCard,
            intervalsAthleteId = intervalsAthleteId,
            intervalsAutoUpload = intervalsAutoUpload,
            favouriteWorkoutIds = favouriteWorkoutIds,
        )
    }
}

/**
 * Output of [BackupMerger.merge]. The platform layer reconciles this against
 * local state — that's where the actual SqlDelight / DataStore / filesystem
 * writes happen. See `mobile_clients/android/.../sync/` (later) for the
 * Android-side glue.
 */
data class MergedState(
    val settings: BackupSettings?,
    val sessions: List<BackupSession>,
    val sessionTombstones: List<BackupTombstone>,
    val importedWorkouts: List<BackupImportedWorkout>,
    val importedTombstones: List<BackupTombstone>,
)
