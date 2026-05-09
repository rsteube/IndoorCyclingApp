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

import app.indoorbike.shared.Workout
import kotlinx.serialization.Serializable

/**
 * Wire format for one device's slice of the backup. The full backup is the
 * merged view across every `indoorbike-device-<id>.json` file in the user's
 * sync folder; each device only writes its own file. See [BackupMerger] for
 * how slices fold together.
 *
 * **Multi-writer model.** A device only writes its own slice. Riding on phone
 * + tablet means two slices in the folder; the merger reads both and the
 * platform layer reconciles into local SqlDelight / DataStore / imports.
 *
 * **No telemetry in v1.** Sessions carry totals only (avg power / cadence /
 * HR + duration). Per-second telemetry stays in the originating device's
 * SQLite — riders who reinstall keep streaks/badges/progression on history
 * but lose per-second power graphs on old rides. This keeps the backup
 * document small (~500 B per session) and avoids sidecar files.
 *
 * **No credentials.** [BackupSettings] never carries the Intervals.icu API
 * key. The athlete id and auto-upload toggle do sync; the rider re-enters
 * the API key once per device.
 *
 * **No paired BLE ids.** Trainer / HRM / controller pairings are
 * device-local — BLE pairing doesn't survive cross-device anyway.
 */
@Serializable
data class BackupDocument(
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION_CURRENT,

    /** Stable per-device identifier. Generated once, persisted locally. */
    val deviceId: String,

    /** Free-form display label, surfaced in the Backup UI ("Pixel 8"). */
    val deviceLabel: String? = null,

    /** Wall-clock epoch ms when this device last wrote its slice. */
    val writtenEpoch: Long,

    val settings: BackupSettings? = null,

    val sessions: List<BackupSession> = emptyList(),
    val sessionTombstones: List<BackupTombstone> = emptyList(),

    val importedWorkouts: List<BackupImportedWorkout> = emptyList(),
    val importedTombstones: List<BackupTombstone> = emptyList(),
)

/**
 * The non-credential, non-hardware-local subset of `UserSettings`.
 *
 * Each field is nullable in the wire format with `null` meaning "this slice
 * doesn't carry a value for this field" (which is distinct from
 * "explicitly empty / cleared"). The merger does first-non-null-wins per
 * field across slices ordered by [epoch] descending — so a fresh device that
 * hasn't set its weight yet won't clobber the weight another device has set.
 *
 * The platform layer should populate every field it knows when writing,
 * regardless of whether that field changed locally; under that contract
 * "newest slice wins" and the per-field tolerance is just a safety net.
 */
@Serializable
data class BackupSettings(
    /** Wall-clock when this device last touched any of these values. */
    val epoch: Long,
    val ftp: Int? = null,
    val weightKg: Float? = null,
    val maxHr: Int? = null,
    val hasCompletedOnboarding: Boolean? = null,
    val hasDismissedStarterCard: Boolean? = null,
    val intervalsAthleteId: String? = null,
    val intervalsAutoUpload: Boolean? = null,
    /** `null` = absent; empty list = explicitly cleared. */
    val favouriteWorkoutIds: List<String>? = null,
)

/**
 * Session metadata only — no per-second telemetry. See class doc on
 * [BackupDocument] for the v1 trade-off.
 *
 * [externalId] is a globally-unique stable string id used for tombstones
 * and dedup. Recommended derivation: `"$deviceId:${UUID}"` — the
 * device-id namespace plus a fresh UUID per session means two phones can
 * never collide without any cross-device coordination. The merger doesn't
 * care how it's derived — just that the same session always gets the same
 * id, and that no two distinct sessions ever share one.
 */
@Serializable
data class BackupSession(
    val externalId: String,
    val workoutId: String,
    val workoutName: String,
    val startTimeEpoch: Long,
    val durationSeconds: Long,
    val avgPower: Long,
    val avgCadence: Long,
    val avgHeartRate: Long,
)

/**
 * One imported workout in the rider's local library, with optional source-repo
 * provenance and the local-import wall-clock epoch for last-write-wins on
 * duplicate ids across devices.
 */
@Serializable
data class BackupImportedWorkout(
    val workout: Workout,
    val sourceRepoId: String? = null,
    val sourceRepoName: String? = null,
    /** Wall-clock when this device imported it. Highest wins on dup id. */
    val importedEpoch: Long,
)

/**
 * Generic deletion marker. The id is whatever the deleted entity's id is
 * ([BackupSession.externalId] for sessions, `Workout.id` for imports).
 *
 * Tombstones are kept in slices indefinitely so devices that have been
 * offline for a while still see the deletion when they sync. Future
 * versions may garbage-collect tombstones older than ~90 days; the merger
 * itself doesn't expire them.
 */
@Serializable
data class BackupTombstone(
    val id: String,
    val deletedEpoch: Long,
)

/** Format version this build emits when writing a slice. */
const val BACKUP_SCHEMA_VERSION_CURRENT = 1

/** Highest format version this build can parse — slices above this are rejected. */
const val BACKUP_SCHEMA_VERSION_MAX_SUPPORTED = 1
