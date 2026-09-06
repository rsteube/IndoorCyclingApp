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
package app.indoorbike.library

import android.content.Context
import android.net.Uri
import app.indoorbike.shared.Workout
import app.indoorbike.shared.repo.ImportKind
import app.indoorbike.shared.repo.ManifestParser
import app.indoorbike.shared.repo.RepositoryManifest
import app.indoorbike.shared.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Validates + persists user-supplied workout JSON.
 *
 * Persistence layout under `filesDir`:
 *   user_workouts/<sanitised-id>.json
 *
 * Uses the workout's own `id` as the filename so re-importing the same workout
 * overwrites cleanly (idempotent). Sanitisation strips path separators and
 * keeps the filename predictable for hand-debugging.
 */
class WorkoutImporter(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val userWorkoutsDir: File
        get() = File(context.filesDir, "user_workouts").apply { mkdirs() }

    /**
     * Imports JSON text. Auto-detects whether it's a single workout, a list of
     * workouts, or a repository manifest, and routes accordingly.
     */
    suspend fun importJsonText(text: String): ImportResult = withContext(Dispatchers.IO) {
        try {
            when (ManifestParser.classify(text)) {
                ImportKind.SingleWorkout -> {
                    val workout = ManifestParser.parseSingleWorkout(text)
                    saveUserWorkout(workout)
                    ImportResult.Workouts(listOf(workout))
                }
                ImportKind.WorkoutList -> {
                    val workouts = json.decodeFromString<List<Workout>>(text)
                    workouts.forEach { saveUserWorkout(it) }
                    ImportResult.Workouts(workouts)
                }
                ImportKind.RepositoryManifest -> {
                    val manifest = ManifestParser.parseRepositoryManifest(text)
                    ImportResult.Manifest(manifest)
                }
                ImportKind.Registry -> ImportResult.Failure(
                    "That's a registry file, not a workout or repository."
                )
                // Route bundles are listings, not rideable data: each entry
                // only points at a GPX. Adding them means downloading those,
                // which is the route browser's job, not the workout importer's.
                ImportKind.RouteList -> ImportResult.Failure(
                    "That's a route pack. Open it from Routes to add routes from it."
                )
                ImportKind.Unknown -> ImportResult.Failure(
                    "Couldn't recognise this file. Expected a workout JSON or a repository manifest."
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Import failed", t)
            ImportResult.Failure(t.message ?: "Import failed")
        }
    }

    /** Reads bytes from a content:// or file:// URI then routes through importJsonText. */
    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(StandardCharsets.UTF_8)
            } ?: return@withContext ImportResult.Failure("Couldn't open $uri")
            importJsonText(text)
        } catch (t: Throwable) {
            Log.e(TAG, "URI import failed", t)
            ImportResult.Failure(t.message ?: "Couldn't read file")
        }
    }

    /** All user-imported workouts on disk, sorted by name. */
    fun loadUserWorkouts(): List<Workout> {
        val dir = userWorkoutsDir
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.endsWith(".meta.json") }
            ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                json.decodeFromString<Workout>(file.readText(Charsets.UTF_8))
            } catch (t: Throwable) {
                Log.w(TAG, "Skipping corrupt user workout ${file.name}: ${t.message}")
                null
            }
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * Reads the import-meta sidecar for a workout, or null when the workout
     * was imported as a bare file (no provenance tracked). The sidecar lives
     * alongside the workout JSON as `<id>.meta.json` and isn't part of the
     * shareable workout payload — share-to-phone and QR flows export only the
     * workout itself, never the local provenance.
     */
    fun loadImportMeta(workoutId: String): ImportMeta? {
        val file = File(userWorkoutsDir, sanitise(workoutId) + ".meta.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString(ImportMeta.serializer(), file.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            Log.w(TAG, "Corrupt meta for $workoutId: ${t.message}")
            null
        }
    }

    /**
     * Unified persist entry point used by everything that needs to drop a
     * fully-formed [Workout] into the user's local library — community-repo
     * adds, share-target imports, and the backup-sync layer applying remote
     * slices. Writes the workout JSON plus, if any provenance is supplied, a
     * sidecar `<id>.meta.json` that the rides list reads to render
     * attribution.
     *
     * The local copy is the source of truth from this point on — the
     * explicit-add model means we never auto-update it from upstream once
     * saved.
     *
     * [addedEpoch] should be the wall-clock when the workout first entered
     * the rider's library. The sync layer passes the epoch from the remote
     * slice so cross-device LWW (highest importedEpoch wins on duplicate
     * ids) stays stable across syncs.
     */
    suspend fun saveWorkout(
        workout: Workout,
        sourceRepoId: String? = null,
        sourceRepoName: String? = null,
        addedEpoch: Long = System.currentTimeMillis(),
    ): Workout = withContext(Dispatchers.IO) {
        saveUserWorkout(workout)
        if (sourceRepoId != null && sourceRepoName != null) {
            writeMeta(
                workout.id,
                ImportMeta(
                    sourceRepoId = sourceRepoId,
                    sourceRepoName = sourceRepoName,
                    addedEpoch = addedEpoch,
                ),
            )
        }
        workout
    }

    /** Compatibility shim — preserves the existing call site in the repo browser. */
    suspend fun saveWorkoutFromRepo(
        workout: Workout,
        repoId: String,
        repoName: String,
    ): Workout = saveWorkout(workout, repoId, repoName)

    /**
     * Deletes the on-disk JSON for an imported workout (and its sidecar meta,
     * if any). Suspending + IO-confined to match [renameUserWorkout]; callers
     * that need to know whether a delete actually happened can read the
     * return value (false = file already gone or filesystem refused to
     * delete).
     */
    suspend fun deleteUserWorkout(workoutId: String): Boolean = withContext(Dispatchers.IO) {
        val sanitised = sanitise(workoutId)
        val file = File(userWorkoutsDir, "$sanitised.json")
        val meta = File(userWorkoutsDir, "$sanitised.meta.json")
        val ok = file.exists() && file.delete()
        // Meta is best-effort; orphaning a sidecar is ugly but never breaks
        // anything since loadImportMeta is null-tolerant.
        if (meta.exists()) meta.delete()
        ok
    }

    /**
     * Rewrites the on-disk JSON for an imported workout with a new name. The
     * id (and therefore the filename) is intentionally preserved so existing
     * ride history, deep links, and the library's dedup keep pointing at the
     * same entry. Returns the updated workout, or null if the file is missing
     * / unreadable / the new name is blank.
     */
    suspend fun renameUserWorkout(workoutId: String, newName: String): Workout? = withContext(Dispatchers.IO) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return@withContext null
        val file = File(userWorkoutsDir, sanitise(workoutId) + ".json")
        if (!file.exists()) return@withContext null
        val current = try {
            json.decodeFromString<Workout>(file.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            Log.w(TAG, "Rename failed to decode ${file.name}: ${t.message}")
            return@withContext null
        }
        val updated = current.copy(name = trimmed)
        saveUserWorkout(updated)
        updated
    }

    private fun saveUserWorkout(workout: Workout) {
        val file = File(userWorkoutsDir, sanitise(workout.id) + ".json")
        val text = json.encodeToString(Workout.serializer(), workout)
        // Atomic write: write to a tmp sibling, then rename onto the target.
        // The rename is atomic on the same filesystem, so a crash/kill between
        // steps either leaves the original file intact or replaces it whole.
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (tmp.renameTo(file)) return
            // Some FUSE-mounted external dirs refuse cross-node rename. Fall
            // back to "write a fresh tmp then rename" so the destination is
            // never half-written: if writeText throws partway, `file` keeps its
            // previous contents.
            val tmp2 = File(file.parentFile, file.name + ".tmp2")
            try {
                tmp2.writeText(text, Charsets.UTF_8)
                if (!tmp2.renameTo(file)) {
                    // Last-resort: throw rather than silently leaving a corrupt
                    // file. The caller's importJsonText catch will surface this
                    // as ImportResult.Failure.
                    throw java.io.IOException("Atomic save failed: couldn't rename ${tmp2.name} → ${file.name}")
                }
            } finally {
                if (tmp2.exists()) tmp2.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun writeMeta(workoutId: String, meta: ImportMeta) {
        val file = File(userWorkoutsDir, sanitise(workoutId) + ".meta.json")
        val text = json.encodeToString(ImportMeta.serializer(), meta)
        // Same atomic-write pattern as saveUserWorkout — if a partial write
        // dies, the destination keeps its previous contents (or stays absent).
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(text, Charsets.UTF_8)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun sanitise(id: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "workout" }

    companion object {
        private const val TAG = "WorkoutImporter"
    }
}

/**
 * Sidecar metadata for an imported workout. Tracks the upstream source so
 * the rides list can attribute "From Cycling-coach pack" without reading
 * the workout file itself. Lives at `<id>.meta.json`; absent for plain
 * file-imports.
 */
@Serializable
data class ImportMeta(
    val sourceRepoId: String? = null,
    val sourceRepoName: String? = null,
    val addedEpoch: Long = 0,
)

sealed class ImportResult {
    /** One or more workouts were validated and added to the user library. */
    data class Workouts(val workouts: List<Workout>) : ImportResult()

    /**
     * A repository manifest was parsed. Repos aren't auto-mirrored under the
     * explicit-add model — callers route the user to the remote-repo browser
     * (where they pick individual workouts to add), or surface a Toast pointing
     * at it.
     */
    data class Manifest(val manifest: RepositoryManifest) : ImportResult()

    data class Failure(val message: String) : ImportResult()
}
