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
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
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
     * Deletes the on-disk JSON for an imported workout. Suspending + IO-confined
     * to match [renameUserWorkout]; callers that need to know whether a delete
     * actually happened can read the return value (false = file already gone or
     * filesystem refused to delete).
     */
    suspend fun deleteUserWorkout(workoutId: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(userWorkoutsDir, sanitise(workoutId) + ".json")
        file.exists() && file.delete()
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

    private fun sanitise(id: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "workout" }

    companion object {
        private const val TAG = "WorkoutImporter"
    }
}

sealed class ImportResult {
    /** One or more workouts were validated and added to the user library. */
    data class Workouts(val workouts: List<Workout>) : ImportResult()

    /** A repository manifest was parsed; caller decides whether to subscribe. */
    data class Manifest(val manifest: RepositoryManifest) : ImportResult()

    data class Failure(val message: String) : ImportResult()
}
