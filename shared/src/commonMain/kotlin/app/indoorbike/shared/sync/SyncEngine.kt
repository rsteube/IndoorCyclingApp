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

import app.indoorbike.shared.Workout
import app.indoorbike.shared.library.ImportMeta
import kotlinx.coroutines.IO
import app.indoorbike.shared.util.getCurrentEpochMillis
import app.indoorbike.shared.db.HistoryRepository
import app.indoorbike.shared.settings.UserSettings
import app.indoorbike.shared.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The rider's own workouts as sync sees them: each app's importer and library
 * behind the few calls a round makes.
 */
interface SyncWorkoutLibrary {
    fun loadUserWorkouts(): List<Workout>
    fun loadImportMeta(workoutId: String): ImportMeta?
    suspend fun saveWorkout(workout: Workout, sourceRepoId: String?, sourceRepoName: String?, addedEpoch: Long): Workout
    suspend fun deleteUserWorkout(workoutId: String): Boolean
    /** Re-read the library once a round has changed it, before anything reads it again. */
    suspend fun refresh()
}

/**
 * Orchestrates one sync round-trip against the rider's chosen folder, shared
 * by both apps:
 *
 *   1. **Pull** every device-slice in the folder.
 *   2. **Diff** local state vs. our previous own-slice (if any) to derive
 *      tombstones for sessions / imports the rider deleted on this device
 *      since the last sync.
 *   3. **Merge** all slices — others' + a tombstone-only provisional self —
 *      into a [MergedState] that's the canonical "what the rider's library
 *      should look like" view.
 *   4. **Apply** that view to local DataStore / SqlDelight / filesDir.
 *   5. **Build** the final self-slice from post-apply local state plus
 *      the carried-forward + new tombstones.
 *   6. **Push** the slice via [SyncStorage.writeOwnSlice].
 *
 * The 3+4+5 ordering is what makes deletes correct under all the edge
 * cases — see [BackupSliceBuilder] for the diff contract and the
 * `BackupMerger` doc for how tombstones interact with sessions/imports.
 *
 * **Single-flight.** A [Mutex] serialises [syncNow] calls so a "Sync now"
 * button mash, an app-resume auto-sync, and a post-ride auto-sync can't
 * race each other into a corrupt mid-write state.
 *
 * **Status surface.** [status] is a `StateFlow` the UI can observe to
 * render "Syncing… / Synced 2 min ago / Couldn't reach folder". The engine
 * never throws to callers — failures land as [SyncStatus.Failed].
 */
class SyncEngine(
    private val userSettings: UserSettings,
    private val historyRepository: HistoryRepository,
    /** The rider's own workouts, which sync carries between devices. */
    private val workoutLibrary: SyncWorkoutLibrary,
    /**
     * Builds a [SyncStorage] from current settings or returns null when
     * the rider hasn't picked a sync folder. Re-evaluated on every
     * [syncNow] so a fresh-picked folder is honored without an engine
     * rebuild.
     */
    private val storageProvider: () -> SyncStorage?,
    private val now: () -> Long = ::getCurrentEpochMillis,
) {

    private val mutex = Mutex()

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * Performs one sync round-trip. Caller's responsibility to launch on a
     * suitable scope — typical sites: a Settings "Sync now" button click
     * (on an Activity / ViewModel scope) or an app-resume hook (on the
     * Application's IO scope). Concurrent invocations serialise via the
     * mutex; the second caller waits for the first to finish, then runs.
     */
    suspend fun syncNow(): SyncStatus = mutex.withLock {
        val storage = storageProvider()
        if (storage == null) {
            val s = SyncStatus.NotConfigured
            _status.value = s
            return s
        }
        // A previous round flagged a suspicious local reset and paused syncing
        // to protect the backup. Don't auto-resume (that's exactly how a wipe
        // would propagate) - surface the review state so the rider can decide.
        // This is the single chokepoint that gates both the on-launch auto-sync
        // and a manual "Sync now" tap.
        if (userSettings.syncPaused.value) {
            val s = try {
                assess(storage)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Assess failed", t)
                SyncStatus.Failed(t.message ?: "Couldn't read backup")
            }
            _status.value = s
            return s
        }
        runSync(storage, SyncMode.Normal)
    }

    /**
     * Rider chose **Restore from backup**: pull the backup into local without
     * inferring any deletes, then republish. Clears the pause on success.
     */
    suspend fun resolveRestoreFromBackup(): SyncStatus = mutex.withLock {
        val storage = storageProvider() ?: run {
            _status.value = SyncStatus.NotConfigured; return SyncStatus.NotConfigured
        }
        val out = runSync(storage, SyncMode.RestoreFromBackup)
        if (out is SyncStatus.Success) userSettings.setSyncPaused(false)
        out
    }

    /**
     * Rider chose **Keep this device's data** after a reset warning: proceed
     * with the inferred deletes (the backup is made to match local). Clears
     * the pause on success.
     */
    suspend fun resolveKeepLocal(): SyncStatus = mutex.withLock {
        val storage = storageProvider() ?: run {
            _status.value = SyncStatus.NotConfigured; return SyncStatus.NotConfigured
        }
        val out = runSync(storage, SyncMode.ForceKeepLocal)
        if (out is SyncStatus.Success) userSettings.setSyncPaused(false)
        out
    }

    /** Shared run wrapper: status transitions + exception → [SyncStatus.Failed]. */
    private suspend fun runSync(storage: SyncStorage, mode: SyncMode): SyncStatus {
        _status.value = SyncStatus.Syncing
        val out = try {
            performSync(storage, mode)
        } catch (e: CancellationException) {
            // Cancelled is not failed. Reporting it as one told the rider
            // "Couldn't sync" about a sync that was only interrupted, and hid
            // the cancellation from whoever cancelled it.
            _status.value = SyncStatus.Idle
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Sync failed", t)
            SyncStatus.Failed(t.message ?: "Sync failed")
        }
        _status.value = out
        return out
    }

    /** How a sync round treats inferred deletes / the safety check. */
    private enum class SyncMode {
        /** Detect a suspicious reset and pause instead of publishing deletes. */
        Normal,

        /** Skip the safety check; publish the inferred deletes (rider confirmed). */
        ForceKeepLocal,

        /** Infer no deletes; pull the backup into local and republish. */
        RestoreFromBackup,
    }

    /**
     * Read-only assessment used when syncing is paused: counts what's local vs
     * what's in the backup so the UI can render the review prompt. Writes
     * nothing.
     */
    private suspend fun assess(storage: SyncStorage): SyncStatus = withContext(Dispatchers.IO) {
        val deviceId = userSettings.deviceId
        val others = storage.readAllSlices().mapNotNull { text ->
            try {
                BackupSerializer.decode(text)
            } catch (e: BackupParseException) {
                null
            }
        }.filter { it.deviceId != deviceId }
        val previousOwn = (storage.readOwnSlice(deviceId) as? OwnSlice.Found)?.candidates
            ?.firstNotNullOfOrNull { text ->
                try {
                    BackupSerializer.decode(text)
                } catch (e: BackupParseException) {
                    null
                }
            }
        val localSessions = historyRepository.getAllSessions().first()
            .count { it.externalId.isNotEmpty() }
        val localImports = workoutLibrary.loadUserWorkouts().size
        needsReview(previousOwn, others, localSessions, localImports)
    }

    private fun needsReview(
        previousOwn: BackupDocument?,
        others: List<BackupDocument>,
        localSessions: Int,
        localImports: Int,
    ): SyncStatus.NeedsReview {
        val all = (if (previousOwn != null) listOf(previousOwn) else emptyList()) + others
        val backupSessions = all.flatMap { it.sessions }.map { it.externalId }.toSet().size
        val backupImports = all.flatMap { it.importedWorkouts }.map { it.workout.id }.toSet().size
        return SyncStatus.NeedsReview(
            localSessions = localSessions,
            backupSessions = backupSessions,
            localImports = localImports,
            backupImports = backupImports,
        )
    }

    /**
     * Internal carrier so [performSync] can report both the device count and
     * the number of slices that failed to parse. Without surfacing skipped
     * slices, a corrupt peer's data silently disappears from the merged
     * state and the rider's "Sync now" status reads as an unqualified
     * success — see [SyncStatus.Success.slicesSkipped] for the user-facing
     * symptom.
     */
    private suspend fun performSync(storage: SyncStorage, mode: SyncMode): SyncStatus = withContext(Dispatchers.IO) {
        val nowMs = now()
        val deviceId = userSettings.deviceId

        // 1. Pull. Track skipped slices so the success status can flag any
        //    that failed to parse - silently dropping them lets one corrupt
        //    peer's data vanish from every other peer's view with no signal.
        val rawSlices = storage.readAllSlices()
        var slicesSkipped = 0
        val others = rawSlices.mapNotNull { text ->
            try {
                BackupSerializer.decode(text)
            } catch (t: BackupParseException) {
                slicesSkipped += 1
                Log.w(TAG, "Skipping unreadable slice: ${t.message}")
                null
            }
        }.filter { it.deviceId != deviceId }

        // This device's own slice is read on its own, because what happens when
        // it cannot be read is different. A peer that cannot be read is left
        // out; this device's slice left out makes the round a first sync, with
        // no deletes inferred and none of the old tombstones carried forward,
        // so rides deleted here came back from the other devices, and the
        // slice written back no longer said they had ever been deleted. A cloud
        // folder that fails to open a file once is common.
        val previousOwn: BackupDocument? = when (val own = storage.readOwnSlice(deviceId)) {
            is OwnSlice.Missing -> null
            is OwnSlice.Unreadable -> return@withContext SyncStatus.Failed(
                "Couldn't read this device's backup file (${own.reason}). Nothing was changed; " +
                    "the next sync will try again."
            )
            is OwnSlice.Found -> own.candidates.firstNotNullOfOrNull { text ->
                runCatching { BackupSerializer.decode(text) }.getOrNull()?.takeIf { it.deviceId == deviceId }
            } ?: run {
                // Readable but not a slice, current or previous copy alike:
                // nothing to wait for, so the round goes on as a first sync,
                // and says it skipped something.
                slicesSkipped += 1
                Log.w(TAG, "This device's own slice would not parse; syncing as if it were the first time")
                null
            }
        }

        // 2. Snapshot local state BEFORE apply, then diff for new tombstones.
        val localSessionsBefore = historyRepository.getAllSessions().first()
            .filter { it.externalId.isNotEmpty() }
        val localSessionIdsBefore = localSessionsBefore.map { it.externalId }.toSet()
        val localImportsBefore = localImports()
        val localImportIdsBefore = localImportsBefore.map { it.workout.id }.toSet()

        // RestoreFromBackup deliberately infers NO deletes - the whole point is
        // to pull the backup back into an emptied local, not to mirror local's
        // emptiness up to the backup.
        val restoring = mode == SyncMode.RestoreFromBackup
        val newSessionTombstones = if (restoring) emptyList()
        else BackupSliceBuilder.computeSessionDeletes(
            previousOwn = previousOwn,
            currentLocalExternalIds = localSessionIdsBefore,
            nowEpoch = nowMs,
        )
        val newImportTombstones = if (restoring) emptyList()
        else BackupSliceBuilder.computeImportDeletes(
            previousOwn = previousOwn,
            currentLocalWorkoutIds = localImportIdsBefore,
            nowEpoch = nowMs,
        )

        // 2b. Safety gate (Normal only). If this round would delete a large
        //     share of the backup - the shape of an accidental local reset
        //     rather than a deliberate delete - pause syncing and surface a
        //     review prompt INSTEAD of applying/publishing. Nothing is written
        //     locally or to the folder, so both local and backup are preserved
        //     exactly as they were until the rider chooses.
        if (mode == SyncMode.Normal) {
            val suspicious = BackupSafety.isSuspiciousReset(
                hadPreviousOwn = previousOwn != null,
                localItemCount = localSessionIdsBefore.size + localImportIdsBefore.size,
                priorItemCount = (previousOwn?.sessions?.size ?: 0) +
                    (previousOwn?.importedWorkouts?.size ?: 0),
                deletedItemCount = newSessionTombstones.size + newImportTombstones.size,
            )
            if (suspicious) {
                Log.w(TAG, "Suspicious reset detected; pausing sync to protect backup.")
                userSettings.setSyncPaused(true)
                return@withContext needsReview(
                    previousOwn, others,
                    localSessions = localSessionIdsBefore.size,
                    localImports = localImportIdsBefore.size,
                )
            }
        }

        // 3. Merge. This device's current state goes in first, dated against
        //    what it last published (see LocalChanges), so a change made here
        //    since the last sync is in the merge rather than overwritten by it,
        //    and a tie goes to it. The previous own slice still carries the
        //    sessions a reinstall needs back, and its tombstones; its settings
        //    and imports are superseded by the current ones. A restore is the
        //    exception: it wants the backup's word, so local does not vote.
        val self = BackupDocument(
            deviceId = deviceId,
            writtenEpoch = nowMs,
            settings = if (restoring) null
            else LocalChanges.settings(currentLocalSettings(), previousOwn?.settings, nowMs),
            sessions = emptyList(),
            sessionTombstones = (previousOwn?.sessionTombstones ?: emptyList()) + newSessionTombstones,
            importedWorkouts = if (restoring) emptyList()
            else LocalChanges.imports(localImportsBefore, previousOwn?.importedWorkouts, nowMs),
            importedTombstones = (previousOwn?.importedTombstones ?: emptyList()) + newImportTombstones,
        )
        val previousForMerge = previousOwn?.let {
            if (restoring) it else it.copy(settings = null, importedWorkouts = emptyList())
        }
        val merged = BackupMerger.merge(listOfNotNull(self, previousForMerge) + others)

        // 3b. The same guard for deletes arriving from the other devices, which
        //     it never used to look at. Keep-local is the rider having decided
        //     this device's data stands, so it neither pauses nor applies them.
        if (mode == SyncMode.Normal) {
            val sessionStarts = localSessionsBefore.associate { it.externalId to it.startTimeEpoch }
            val incoming = merged.sessionTombstones.count { t ->
                sessionStarts[t.id]?.let { t.deletedEpoch >= it } == true
            } + merged.importedTombstones.count { it.id in localImportIdsBefore }
            if (BackupSafety.isSuspiciousIncomingDelete(localSessionIdsBefore.size + localImportIdsBefore.size, incoming)) {
                Log.w(TAG, "Another device's deletes would remove $incoming item(s) here; pausing sync.")
                userSettings.setSyncPaused(true)
                return@withContext needsReview(
                    previousOwn, others,
                    localSessions = localSessionIdsBefore.size,
                    localImports = localImportIdsBefore.size,
                )
            }
        }
        val applyDeletes = mode != SyncMode.ForceKeepLocal

        // 4. Apply merged to local.
        applyMergedSettings(merged.settings)
        applyMergedSessions(merged, applyDeletes)
        applyMergedImports(merged, localImportsBefore, applyDeletes)
        // Single rebuild after all import writes - saves N redundant rebuilds
        // when many workouts arrive from a peer.
        workoutLibrary.refresh()

        // 5. Build final self-slice from POST-apply local state, each value
        //    carrying the date it won the merge with.
        val localSessionsAfter = historyRepository.getAllSessions().first()
            .filter { it.externalId.isNotEmpty() }
        val sliceSessions = localSessionsAfter.map(::toBackupSession)
        val mergedImportEpochs = merged.importedWorkouts.associate { it.workout.id to it.importedEpoch }
        val sliceImports = localImports().map { imported ->
            val previousEpoch = previousOwn?.importedWorkouts
                ?.firstOrNull { it.workout.id == imported.workout.id }?.importedEpoch
            imported.copy(
                importedEpoch = mergedImportEpochs[imported.workout.id]
                    ?: previousEpoch
                    ?: imported.importedEpoch.takeIf { it > 0 }
                    ?: nowMs,
            )
        }
        val sliceSettings = currentLocalSettings().copy(
            epoch = nowMs,
            fieldEpochs = merged.settings?.fieldEpochs ?: self.settings?.fieldEpochs ?: emptyMap(),
        )
        val finalSelf = BackupSliceBuilder.build(
            deviceId = deviceId,
            deviceLabel = userSettings.deviceLabel.value,
            nowEpoch = nowMs,
            settings = sliceSettings,
            sessions = sliceSessions,
            importedWorkouts = sliceImports,
            previousOwn = previousOwn,
            newSessionTombstones = newSessionTombstones,
            newImportTombstones = newImportTombstones,
        )

        // 6. Push
        storage.writeOwnSlice(deviceId, BackupSerializer.encode(finalSelf))

        // distinct device count, for the status line
        val devicesSeen = others.map { it.deviceId }.toSet().size + 1
        SyncStatus.Success(
            syncedAtEpoch = now(),
            devicesSeen = devicesSeen,
            slicesSkipped = slicesSkipped,
        )
    }

    /** This device's imported workouts, with where they came from, as the backup describes them. */
    private suspend fun localImports(): List<BackupImportedWorkout> = withContext(Dispatchers.IO) {
        workoutLibrary.loadUserWorkouts().map { workout ->
            val meta = workoutLibrary.loadImportMeta(workout.id)
            BackupImportedWorkout(
                workout = workout,
                sourceRepoId = meta?.sourceRepoId,
                sourceRepoName = meta?.sourceRepoName,
                importedEpoch = meta?.addedEpoch ?: 0L,
            )
        }
    }

    private fun applyMergedSettings(merged: BackupSettings?) {
        if (merged == null) return
        merged.ftp?.let { if (userSettings.ftp.value != it) userSettings.setFtp(it) }
        merged.weightKg?.let { if (userSettings.weightKg.value != it) userSettings.setWeightKg(it) }
        merged.maxHr?.let { if (userSettings.maxHr.value != it) userSettings.setMaxHr(it) }
        merged.hasCompletedOnboarding?.let {
            if (userSettings.hasCompletedOnboarding.value != it) userSettings.setHasCompletedOnboarding(it)
        }
        merged.hasDismissedStarterCard?.let {
            if (userSettings.hasDismissedStarterCard.value != it) userSettings.setHasDismissedStarterCard(it)
        }
        merged.intervalsAthleteId?.let {
            if (userSettings.intervalsAthleteId.value != it) userSettings.setIntervalsAthleteId(it)
        }
        merged.intervalsAutoUpload?.let {
            if (userSettings.intervalsAutoUpload.value != it) userSettings.setIntervalsAutoUpload(it)
        }
        merged.favouriteWorkoutIds?.let { ids ->
            // One write of the whole set. It was a write per changed id, each
            // launched on its own, and they could land in any order.
            val target = ids.toSet()
            if (userSettings.favouriteWorkoutIds.value != target) userSettings.setFavouriteWorkoutIds(target)
        }
    }

    private suspend fun applyMergedSessions(state: MergedState, applyDeletes: Boolean) {
        // Insert sessions present in the merged view but missing locally.
        for (s in state.sessions) {
            if (historyRepository.getSessionByExternalId(s.externalId) == null) {
                historyRepository.insertWorkoutSession(
                    workoutId = s.workoutId,
                    workoutName = s.workoutName,
                    startTimeEpoch = s.startTimeEpoch,
                    durationSeconds = s.durationSeconds,
                    avgPower = s.avgPower,
                    avgCadence = s.avgCadence,
                    avgHeartRate = s.avgHeartRate,
                    externalId = s.externalId,
                    routeDistanceMeters = s.routeDistanceMeters ?: 0.0,
                    routeElevationGainMeters = s.routeElevationGainMeters ?: 0.0,
                    routeStartMeters = s.routeStartMeters ?: 0.0,
                    routeStartElevationGainMeters = s.routeStartElevationGainMeters ?: 0.0,
                    // A companion ride's road, which the receiving device has
                    // no catalogue entry for and could not reconstruct.
                    routeShape = s.routeShape.orEmpty(),
                    routeProfile = s.routeProfile.orEmpty(),
                    // v1 deliberately drops telemetry from sync - sessions
                    // arriving from another device land without per-second
                    // power data. The post-ride chart on those rides will be
                    // empty; totals/streaks/badges still work because they
                    // read session-level avgs.
                    telemetry = emptyList(),
                )
            }
        }
        if (!applyDeletes) return
        // Apply tombstones: drop local sessions whose externalId has been
        // tombstoned across the cluster.
        for (t in state.sessionTombstones) {
            val local = historyRepository.getSessionByExternalId(t.id) ?: continue
            // Defensive: ignore tombstones from before the session existed.
            if (t.deletedEpoch >= local.startTimeEpoch) {
                historyRepository.deleteSession(local.id)
            }
        }
    }

    private suspend fun applyMergedImports(
        state: MergedState,
        before: List<BackupImportedWorkout>,
        applyDeletes: Boolean,
    ) {
        // Only what differs from what is here. Every merged import used to be
        // written back, which rewrote the whole library each sync and, with
        // this device's own state missing from the merge, put a renamed
        // workout back under its old name.
        val mine = before.associateBy { it.workout.id }
        for (w in state.importedWorkouts) {
            val local = mine[w.workout.id]
            val same = local != null && local.workout == w.workout &&
                local.sourceRepoId == w.sourceRepoId && local.sourceRepoName == w.sourceRepoName
            if (same) continue
            workoutLibrary.saveWorkout(
                workout = w.workout,
                sourceRepoId = w.sourceRepoId,
                sourceRepoName = w.sourceRepoName,
                addedEpoch = w.importedEpoch,
            )
        }
        if (!applyDeletes) return
        // Tombstones: delete local copies of anything tombstoned.
        val localIds = mine.keys
        for (t in state.importedTombstones) {
            if (t.id in localIds) workoutLibrary.deleteUserWorkout(t.id)
        }
    }

    /** This device's settings as they stand, undated: [LocalChanges] dates them. */
    private fun currentLocalSettings(): BackupSettings = BackupSettings(
        epoch = 0L,
        ftp = userSettings.ftp.value,
        weightKg = userSettings.weightKg.value,
        maxHr = userSettings.maxHr.value,
        hasCompletedOnboarding = userSettings.hasCompletedOnboarding.value,
        hasDismissedStarterCard = userSettings.hasDismissedStarterCard.value,
        intervalsAthleteId = userSettings.intervalsAthleteId.value,
        // intervalsApiKey deliberately NOT included — credentials never leave
        // the device.
        intervalsAutoUpload = userSettings.intervalsAutoUpload.value,
        favouriteWorkoutIds = userSettings.favouriteWorkoutIds.value.toList(),
    )

    private fun toBackupSession(row: app.indoorbike.shared.db.WorkoutSession) = BackupSession(
        externalId = row.externalId,
        workoutId = row.workoutId,
        workoutName = row.workoutName,
        startTimeEpoch = row.startTimeEpoch,
        durationSeconds = row.durationSeconds,
        avgPower = row.avgPower,
        avgCadence = row.avgCadence,
        avgHeartRate = row.avgHeartRate,
        routeDistanceMeters = row.routeDistanceMeters.takeIf { it > 0.0 },
        routeElevationGainMeters = row.routeElevationGainMeters.takeIf { it > 0.0 },
        routeStartMeters = row.routeStartMeters.takeIf { it > 0.0 },
        routeStartElevationGainMeters = row.routeStartElevationGainMeters.takeIf { it > 0.0 },
        routeShape = row.routeShape.takeIf { it.isNotBlank() },
        routeProfile = row.routeProfile.takeIf { it.isNotBlank() },
    )

    companion object {
        private const val TAG = "SyncEngine"
    }
}

/**
 * UI-observable status. [Idle] is the initial state before any sync runs;
 * [Syncing] guards the Sync-now button while a round-trip is in flight;
 * [Success] / [Failed] / [NotConfigured] are terminal states for that
 * round-trip but are overwritten on the next call to [SyncEngine.syncNow].
 */
sealed class SyncStatus {
    data object Idle : SyncStatus()
    data object Syncing : SyncStatus()

    /**
     * [slicesSkipped] is non-zero when one or more device-slice files in
     * the sync folder failed to parse (truncated download, mid-write race
     * from a peer, schema-version mismatch). The merge still completes
     * with what's readable, but the rider should know their view of
     * cluster state may be incomplete — the Settings UI surfaces this as
     * a small ⚠ next to the device count.
     */
    data class Success(
        val syncedAtEpoch: Long,
        val devicesSeen: Int,
        val slicesSkipped: Int = 0,
    ) : SyncStatus()
    data class Failed(val message: String) : SyncStatus()
    data object NotConfigured : SyncStatus()

    /**
     * Syncing is paused because the last round looked like an accidental local
     * reset (local data far smaller than the backup). The engine wrote nothing;
     * the rider must choose to restore from the backup or keep local data and
     * discard the backup's extras. Carries the counts so the UI can spell out
     * the stakes ("backup has N rides, this device has M").
     */
    data class NeedsReview(
        val localSessions: Int,
        val backupSessions: Int,
        val localImports: Int,
        val backupImports: Int,
    ) : SyncStatus()
}
