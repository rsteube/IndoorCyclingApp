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
package app.indoorbike.sync

import app.indoorbike.library.WorkoutImporter
import app.indoorbike.library.WorkoutLibrary
import app.indoorbike.shared.db.HistoryRepository
import app.indoorbike.shared.settings.UserSettings
import app.indoorbike.shared.sync.BackupDocument
import app.indoorbike.shared.sync.BackupImportedWorkout
import app.indoorbike.shared.sync.BackupMerger
import app.indoorbike.shared.sync.BackupSafety
import app.indoorbike.shared.sync.BackupParseException
import app.indoorbike.shared.sync.BackupSerializer
import app.indoorbike.shared.sync.BackupSession
import app.indoorbike.shared.sync.BackupSettings
import app.indoorbike.shared.sync.BackupSliceBuilder
import app.indoorbike.shared.sync.MergedState
import app.indoorbike.shared.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates one sync round-trip against the rider's chosen folder:
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
    private val workoutImporter: WorkoutImporter,
    private val workoutLibrary: WorkoutLibrary,
    /**
     * Builds a [SyncStorage] from current settings or returns null when
     * the rider hasn't picked a sync folder. Re-evaluated on every
     * [syncNow] so a fresh-picked folder is honored without an engine
     * rebuild.
     */
    private val storageProvider: () -> SyncStorage?,
    private val now: () -> Long = { System.currentTimeMillis() },
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
    private suspend fun assess(storage: SyncStorage): SyncStatus {
        val deviceId = userSettings.deviceId
        val parsed = storage.readAllSlices().mapNotNull { text ->
            runCatching { BackupSerializer.decode(text) }.getOrNull()
        }
        val previousOwn = parsed.firstOrNull { it.deviceId == deviceId }
        val others = parsed.filter { it.deviceId != deviceId }
        val localSessions = historyRepository.getAllSessions().first()
            .count { it.externalId.isNotEmpty() }
        val localImports = workoutImporter.loadUserWorkouts().size
        return needsReview(previousOwn, others, localSessions, localImports)
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
    private suspend fun performSync(storage: SyncStorage, mode: SyncMode): SyncStatus {
        val nowMs = now()
        val deviceId = userSettings.deviceId

        // 1. Pull. Track skipped slices so the success status can flag any
        //    that failed to parse — silently dropping them lets one corrupt
        //    peer's data vanish from every other peer's view with no signal.
        val rawSlices = storage.readAllSlices()
        var slicesSkipped = 0
        val parsed = rawSlices.mapNotNull { text ->
            try {
                BackupSerializer.decode(text)
            } catch (t: BackupParseException) {
                slicesSkipped += 1
                Log.w(TAG, "Skipping unreadable slice: ${t.message}")
                null
            }
        }
        val previousOwn = parsed.firstOrNull { it.deviceId == deviceId }
        val others = parsed.filter { it.deviceId != deviceId }

        // 2. Snapshot local state BEFORE apply, then diff for new tombstones.
        val localSessionsBefore = historyRepository.getAllSessions().first()
            .filter { it.externalId.isNotEmpty() }
        val localSessionIdsBefore = localSessionsBefore.map { it.externalId }.toSet()
        val localImportsBefore = workoutImporter.loadUserWorkouts()
        val localImportIdsBefore = localImportsBefore.map { it.id }.toSet()

        // RestoreFromBackup deliberately infers NO deletes - the whole point is
        // to pull the backup back into an emptied local, not to mirror local's
        // emptiness up to the backup.
        val newSessionTombstones = if (mode == SyncMode.RestoreFromBackup) emptyList()
        else BackupSliceBuilder.computeSessionDeletes(
            previousOwn = previousOwn,
            currentLocalExternalIds = localSessionIdsBefore,
            nowEpoch = nowMs,
        )
        val newImportTombstones = if (mode == SyncMode.RestoreFromBackup) emptyList()
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
                return needsReview(
                    previousOwn, others,
                    localSessions = localSessionIdsBefore.size,
                    localImports = localImportIdsBefore.size,
                )
            }
        }

        // 3. Merge: previous-own (still authoritative for our previously-published
        //    sessions on a reinstall) + others + a tombstone-only provisional self
        //    so the merger filters out anything we just deleted.
        val provisionalSelf = BackupDocument(
            deviceId = deviceId,
            writtenEpoch = nowMs,
            sessions = emptyList(),
            sessionTombstones = (previousOwn?.sessionTombstones ?: emptyList()) + newSessionTombstones,
            importedWorkouts = emptyList(),
            importedTombstones = (previousOwn?.importedTombstones ?: emptyList()) + newImportTombstones,
        )
        val toMerge = mutableListOf<BackupDocument>().apply {
            previousOwn?.let { add(it) }
            addAll(others)
            add(provisionalSelf)
        }
        val merged = BackupMerger.merge(toMerge)

        // 4. Apply merged to local.
        applyMergedSettings(merged.settings)
        applyMergedSessions(merged)
        applyMergedImports(merged)
        // Single rebuild after all import writes — saves N redundant rebuilds
        // when many workouts arrive from a peer.
        workoutLibrary.refreshImportsAwait()

        // 5. Build final self-slice from POST-apply local state.
        val localSessionsAfter = historyRepository.getAllSessions().first()
            .filter { it.externalId.isNotEmpty() }
        val sliceSessions = localSessionsAfter.map(::toBackupSession)
        val sliceImports = workoutImporter.loadUserWorkouts().map { workout ->
            val meta = workoutImporter.loadImportMeta(workout.id)
            // Preserve importedEpoch across syncs: reuse whatever the previous
            // own-slice had for this id (so cross-device LWW order doesn't
            // reset every sync); otherwise meta.addedEpoch (the write
            // timestamp); otherwise now (genuinely new).
            val previousEpoch = previousOwn?.importedWorkouts
                ?.firstOrNull { it.workout.id == workout.id }?.importedEpoch
            BackupImportedWorkout(
                workout = workout,
                sourceRepoId = meta?.sourceRepoId,
                sourceRepoName = meta?.sourceRepoName,
                importedEpoch = previousEpoch ?: meta?.addedEpoch?.takeIf { it > 0 } ?: nowMs,
            )
        }
        val sliceSettings = currentLocalSettingsSnapshot(nowMs)
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
        val devicesSeen = parsed.map { it.deviceId }.toSet().size +
            (if (previousOwn == null) 1 else 0)
        return SyncStatus.Success(
            syncedAtEpoch = now(),
            devicesSeen = devicesSeen,
            slicesSkipped = slicesSkipped,
        )
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
            // Reconcile the favourites set: add anything in target not local,
            // remove anything in local not target. The setter is idempotent so
            // this is safe to run on each sync — a no-op when already in sync.
            val target = ids.toSet()
            val current = userSettings.favouriteWorkoutIds.value
            for (id in target - current) userSettings.setWorkoutFavourite(id, true)
            for (id in current - target) userSettings.setWorkoutFavourite(id, false)
        }
    }

    private suspend fun applyMergedSessions(state: MergedState) {
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
                    // v1 deliberately drops telemetry from sync — sessions
                    // arriving from another device land without per-second
                    // power data. The post-ride chart on those rides will be
                    // empty; totals/streaks/badges still work because they
                    // read session-level avgs.
                    telemetry = emptyList(),
                )
            }
        }
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

    private suspend fun applyMergedImports(state: MergedState) {
        // Save (or overwrite) every import that survived the merger's
        // tombstone filter. saveWorkout is idempotent — the file is replaced
        // atomically and the meta sidecar reflects current provenance.
        for (w in state.importedWorkouts) {
            workoutImporter.saveWorkout(
                workout = w.workout,
                sourceRepoId = w.sourceRepoId,
                sourceRepoName = w.sourceRepoName,
                addedEpoch = w.importedEpoch,
            )
        }
        // Tombstones: delete local copies of anything tombstoned.
        val localById = workoutImporter.loadUserWorkouts().associateBy { it.id }
        for (t in state.importedTombstones) {
            if (localById.containsKey(t.id)) {
                workoutImporter.deleteUserWorkout(t.id)
            }
        }
    }

    private fun currentLocalSettingsSnapshot(nowMs: Long): BackupSettings = BackupSettings(
        epoch = nowMs,
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
