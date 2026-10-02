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
        for (d in documents) for (s in d.sessions) sessionsById.getOrPut(s.externalId) { s }
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

    /**
     * Field by field, the value with the latest change wins; a tie goes to the
     * earlier slice, which is why the engine puts this device's own first.
     *
     * It used to take each field from the newest slice as a whole, and a
     * slice's date was when its device last synced, not when anything in it
     * changed. So a device that synced later won every field, whether or not
     * it had touched them - and this device's own current settings were not in
     * the merge at all, only what it had published last time, so an FTP set
     * since the last sync was put back at the next launch.
     */
    private fun mergeSettings(slices: List<BackupSettings>): BackupSettings? {
        if (slices.isEmpty()) return null
        val epochs = HashMap<String, Long>()

        fun <T> pick(field: String, value: (BackupSettings) -> T?): T? {
            var best: T? = null
            var bestEpoch = Long.MIN_VALUE
            for (s in slices) {
                val v = value(s) ?: continue
                val e = s.epochOf(field)
                if (best == null || e > bestEpoch) {
                    best = v
                    bestEpoch = e
                }
            }
            if (best != null) epochs[field] = bestEpoch
            return best
        }

        val merged = BackupSettings(
            epoch = 0L,
            ftp = pick(LocalChanges.FTP) { it.ftp },
            weightKg = pick(LocalChanges.WEIGHT) { it.weightKg },
            maxHr = pick(LocalChanges.MAX_HR) { it.maxHr },
            hasCompletedOnboarding = pick(LocalChanges.ONBOARDED) { it.hasCompletedOnboarding },
            hasDismissedStarterCard = pick(LocalChanges.STARTER_DISMISSED) { it.hasDismissedStarterCard },
            intervalsAthleteId = pick(LocalChanges.INTERVALS_ATHLETE) { it.intervalsAthleteId },
            intervalsAutoUpload = pick(LocalChanges.INTERVALS_AUTO_UPLOAD) { it.intervalsAutoUpload },
            favouriteWorkoutIds = pick(LocalChanges.FAVOURITES) { it.favouriteWorkoutIds },
        )
        return merged.copy(epoch = epochs.values.maxOrNull() ?: slices.maxOf { it.epoch }, fieldEpochs = epochs)
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
