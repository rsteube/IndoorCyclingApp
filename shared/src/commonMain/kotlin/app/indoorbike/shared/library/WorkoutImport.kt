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
package app.indoorbike.shared.library

import app.indoorbike.shared.Workout
import app.indoorbike.shared.repo.ImportKind
import app.indoorbike.shared.repo.ImportPlan
import app.indoorbike.shared.repo.ManifestParser
import app.indoorbike.shared.repo.RepositoryManifest
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.getCurrentEpochMillis
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The files of the rider's own workouts, by name ("<id>.json",
 * "<id>.meta.json"), wherever the platform keeps them. Writes are atomic: the
 * old contents or the new, never half.
 */
interface WorkoutFileStore {
    /** Every file name in the store. */
    fun names(): List<String>
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun exists(name: String): Boolean
    /** False when there was nothing to delete. */
    fun delete(name: String): Boolean
}

/**
 * Sidecar metadata for an imported workout: the upstream source, so the
 * library can attribute "From Cycling-coach pack" without reading the workout
 * file. Lives at `<id>.meta.json`; absent for plain file imports, and never
 * part of a shareable workout payload.
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
     * A repository manifest was parsed. Repos aren't mirrored: callers send
     * the rider to the repository browser, where they pick what to add.
     */
    data class Manifest(val manifest: RepositoryManifest) : ImportResult()

    data class Failure(val message: String) : ImportResult()

    /** The rider was asked whether to replace a workout, and chose to import nothing. */
    data object Cancelled : ImportResult()
}

/**
 * Validates and keeps workouts the rider brings in, shared by both apps: the
 * platforms only say where the files are.
 *
 * Importing never replaces a workout the rider has without asking them
 * through [questions], and never replaces a starter workout at all: see
 * [ImportPlan]. Re-importing a workout that is already there, unchanged, does
 * nothing.
 */
class WorkoutImportCore(
    private val store: WorkoutFileStore,
    /** The starter workouts by id: an import may not replace one. */
    private val bundledById: () -> Map<String, Workout>,
    val questions: ImportQuestions = ImportQuestions(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Import JSON text: a single workout, a list of workouts, or a repository
     * manifest, told apart by [ManifestParser.classify].
     */
    suspend fun importJsonText(text: String): ImportResult = try {
        when (ManifestParser.classify(text)) {
            ImportKind.SingleWorkout -> saveIncoming(listOf(ManifestParser.parseSingleWorkout(text)))
            ImportKind.WorkoutList -> saveIncoming(json.decodeFromString<List<Workout>>(text))
            ImportKind.RepositoryManifest -> ImportResult.Manifest(ManifestParser.parseRepositoryManifest(text))
            ImportKind.Registry -> ImportResult.Failure("That's a registry file, not a workout or repository.")
            // Route bundles are listings, not rideable data: each entry only
            // points at a GPX. Adding them is the route browser's job.
            ImportKind.RouteList -> ImportResult.Failure(
                "That's a route pack. Open it from Routes to add routes from it."
            )
            ImportKind.Unknown -> ImportResult.Failure(
                "Couldn't recognise this file. Expected a workout JSON or a repository manifest."
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.e(TAG, "Import failed", t)
        ImportResult.Failure(t.message ?: "Import failed")
    }

    /** Write [incoming] as [ImportPlan] says, first asking the rider if any of it would replace a workout of theirs. */
    private suspend fun saveIncoming(incoming: List<Workout>): ImportResult {
        val replaced = ImportPlan.replaced(incoming, ::readUserWorkout)
        val replace = if (replaced.isEmpty()) {
            false
        } else {
            when (questions.ask(replaced.map { it.name })) {
                ImportQuestions.Answer.REPLACE -> true
                ImportQuestions.Answer.KEEP_BOTH -> false
                ImportQuestions.Answer.CANCEL -> return ImportResult.Cancelled
            }
        }
        val steps = ImportPlan.plan(
            incoming = incoming,
            imported = ::readUserWorkout,
            bundled = bundledById(),
            isTaken = { id -> store.exists(workoutName(id)) },
            replace = replace,
        )
        for (step in steps) {
            if (step !is ImportPlan.Write) continue
            val id = step.workout.id
            // A replaced workout is no longer the one its source provided, so
            // the source's credit goes with it.
            if (readUserWorkout(id) != null) store.delete(metaName(id))
            saveUserWorkout(step.workout)
        }
        return ImportResult.Workouts(steps.map { it.workout })
    }

    /** The rider's imported workout stored under [id], or null. */
    fun readUserWorkout(id: String): Workout? = try {
        store.read(workoutName(id))?.let { json.decodeFromString<Workout>(it) }
    } catch (t: Throwable) {
        null
    }

    /** All the rider's imported workouts, sorted by name. A corrupt file costs only itself. */
    fun loadUserWorkouts(): List<Workout> =
        store.names()
            .filter { it.endsWith(".json") && !it.endsWith(".meta.json") }
            .mapNotNull { name ->
                try {
                    store.read(name)?.let { json.decodeFromString<Workout>(it) }
                } catch (t: Throwable) {
                    Log.w(TAG, "Skipping corrupt user workout $name: ${t.message}")
                    null
                }
            }
            .sortedBy { it.name.lowercase() }

    /** Where an imported workout came from, or null for a plain file import. */
    fun loadImportMeta(workoutId: String): ImportMeta? {
        val text = store.read(metaName(workoutId)) ?: return null
        return try {
            json.decodeFromString(ImportMeta.serializer(), text)
        } catch (t: Throwable) {
            Log.w(TAG, "Corrupt meta for $workoutId: ${t.message}")
            null
        }
    }

    /**
     * Drop a fully-formed [Workout] into the rider's library, with a sidecar
     * naming its source when there is one: community-repository adds, and the
     * sync layer applying remote copies. The local copy is the source of
     * truth from then on; it is never updated from upstream by itself.
     *
     * [addedEpoch] is when it first entered the rider's library; sync passes
     * the remote copy's, so the newest-wins rule on duplicate ids stays stable.
     */
    fun saveWorkout(
        workout: Workout,
        sourceRepoId: String? = null,
        sourceRepoName: String? = null,
        addedEpoch: Long = getCurrentEpochMillis(),
    ): Workout {
        saveUserWorkout(workout)
        if (sourceRepoId != null && sourceRepoName != null) {
            store.write(
                metaName(workout.id),
                json.encodeToString(ImportMeta.serializer(), ImportMeta(sourceRepoId, sourceRepoName, addedEpoch)),
            )
        }
        return workout
    }

    /** Remove an imported workout and its sidecar. False when it was already gone. */
    fun deleteUserWorkout(workoutId: String): Boolean {
        val ok = store.delete(workoutName(workoutId))
        // Orphaning a sidecar is untidy but harmless: loadImportMeta tolerates it.
        store.delete(metaName(workoutId))
        return ok
    }

    /**
     * Give an imported workout a new name. The id, and so the file, stays the
     * same, so ride history and the library keep pointing at it. Null when the
     * workout is missing or unreadable, or the name is blank.
     */
    fun renameUserWorkout(workoutId: String, newName: String): Workout? {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return null
        val current = readUserWorkout(workoutId) ?: return null
        return current.copy(name = trimmed).also(::saveUserWorkout)
    }

    private fun saveUserWorkout(workout: Workout) {
        store.write(workoutName(workout.id), json.encodeToString(Workout.serializer(), workout))
    }

    private companion object {
        const val TAG = "WorkoutImporter"
    }
}

/** A workout id made safe for a file name. */
fun sanitiseWorkoutId(id: String): String =
    id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "workout" }

private fun workoutName(id: String) = sanitiseWorkoutId(id) + ".json"
private fun metaName(id: String) = sanitiseWorkoutId(id) + ".meta.json"

/**
 * Every workout the app knows about, in priority order: the rider's imports
 * (by name) and then the bundled ones. An import wins over a bundled workout
 * of the same id, and no id is listed twice.
 */
fun mergeWorkoutLibrary(
    imported: List<Workout>,
    bundled: List<Workout>,
    metaFor: (String) -> ImportMeta?,
): List<WorkoutEntry> {
    val importedEntries = imported.map { workout ->
        val meta = metaFor(workout.id)
        WorkoutEntry(workout, WorkoutSource.Imported(meta?.sourceRepoId, meta?.sourceRepoName))
    }
    val seen = mutableSetOf<String>()
    return (importedEntries + bundled.map { WorkoutEntry(it, WorkoutSource.Bundled) })
        .filter { seen.add(it.workout.id) }
}
