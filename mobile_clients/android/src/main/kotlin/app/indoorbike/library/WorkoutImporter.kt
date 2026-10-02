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
package app.indoorbike.library

import android.content.Context
import android.net.Uri
import app.indoorbike.shared.Workout
import app.indoorbike.shared.library.ImportMeta
import app.indoorbike.shared.library.ImportQuestions
import app.indoorbike.shared.library.ImportResult
import app.indoorbike.shared.library.WorkoutFileStore
import app.indoorbike.shared.library.WorkoutImportCore
import app.indoorbike.shared.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Validates + persists user-supplied workout JSON, under
 * `filesDir/user_workouts/<sanitised-id>.json`.
 *
 * What an import does - asking before it replaces anything, never replacing a
 * starter workout, keeping a repository's credit - is [WorkoutImportCore],
 * shared with the iOS app. This keeps the Android side: the files, reading a
 * shared `content://` file, and doing it all off the main thread.
 */
class WorkoutImporter(
    private val context: Context,
    /** Where an import asks before it replaces something. The app shows these. */
    val questions: ImportQuestions = ImportQuestions(),
) {

    private val core = WorkoutImportCore(
        store = AndroidWorkoutFileStore(File(context.filesDir, "user_workouts")),
        bundledById = { bundledById },
        questions = questions,
    )

    /** The starter workouts by id, read once: an import may not replace one. */
    private val bundledById: Map<String, Workout> by lazy {
        WorkoutLibrary.loadBundled(context).associateBy { it.id }
    }

    /** Imports JSON text: a workout, a list of them, or a repository manifest. */
    suspend fun importJsonText(text: String): ImportResult = withContext(Dispatchers.IO) {
        core.importJsonText(text)
    }

    /**
     * Reads a content:// URI then routes through importJsonText. Read no
     * further than a workout could be: a shared file is whatever the sender
     * says it is.
     */
    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readAtMost(HttpFetcher.MAX_BODY_BYTES.toLong(), "That file is too large to be a workout.")
                    .toString(StandardCharsets.UTF_8)
            } ?: return@withContext ImportResult.Failure("Couldn't open $uri")
            core.importJsonText(text)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "URI import failed", t)
            ImportResult.Failure(t.message ?: "Couldn't read file")
        }
    }

    /** All user-imported workouts on disk, sorted by name. */
    fun loadUserWorkouts(): List<Workout> = core.loadUserWorkouts()

    /** Where an imported workout came from, or null for a plain file import. */
    fun loadImportMeta(workoutId: String): ImportMeta? = core.loadImportMeta(workoutId)

    /** Keep a fully-formed workout, with its source; see [WorkoutImportCore.saveWorkout]. */
    suspend fun saveWorkout(
        workout: Workout,
        sourceRepoId: String? = null,
        sourceRepoName: String? = null,
        addedEpoch: Long = System.currentTimeMillis(),
    ): Workout = withContext(Dispatchers.IO) {
        core.saveWorkout(workout, sourceRepoId, sourceRepoName, addedEpoch)
    }

    /** Compatibility shim — preserves the existing call site in the repo browser. */
    suspend fun saveWorkoutFromRepo(workout: Workout, repoId: String, repoName: String): Workout =
        saveWorkout(workout, repoId, repoName)

    /** Removes an imported workout and its sidecar. False when it was already gone. */
    suspend fun deleteUserWorkout(workoutId: String): Boolean = withContext(Dispatchers.IO) {
        core.deleteUserWorkout(workoutId)
    }

    /** Renames an imported workout, keeping its id; see [WorkoutImportCore.renameUserWorkout]. */
    suspend fun renameUserWorkout(workoutId: String, newName: String): Workout? = withContext(Dispatchers.IO) {
        core.renameUserWorkout(workoutId, newName)
    }

    companion object {
        private const val TAG = "WorkoutImporter"
    }
}

/** `user_workouts/` on disk, each write to a temporary file renamed into place. */
private class AndroidWorkoutFileStore(private val dir: File) : WorkoutFileStore {
    private fun file(name: String) = File(dir.apply { mkdirs() }, name)

    override fun names(): List<String> =
        dir.apply { mkdirs() }.listFiles { f -> f.isFile }?.map { it.name }.orEmpty()

    override fun read(name: String): String? = file(name).takeIf { it.exists() }?.readText(Charsets.UTF_8)

    override fun exists(name: String): Boolean = file(name).exists()

    override fun delete(name: String): Boolean = file(name).let { it.exists() && it.delete() }

    override fun write(name: String, text: String) {
        val target = file(name)
        // Atomic write: write to a tmp sibling, then rename onto the target.
        // The rename is atomic on the same filesystem, so a crash/kill between
        // steps either leaves the original file intact or replaces it whole.
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (tmp.renameTo(target)) return
            // Some FUSE-mounted external dirs refuse cross-node rename. Fall
            // back to "write a fresh tmp then rename" so the destination is
            // never half-written.
            val tmp2 = File(target.parentFile, target.name + ".tmp2")
            try {
                tmp2.writeText(text, Charsets.UTF_8)
                if (!tmp2.renameTo(target)) {
                    throw java.io.IOException("Atomic save failed: couldn't rename ${tmp2.name} → ${target.name}")
                }
            } finally {
                if (tmp2.exists()) tmp2.delete()
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
