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
import app.indoorbike.shared.Workout
import app.indoorbike.shared.WorkoutRepository
import app.indoorbike.shared.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single source of truth for "all workouts the app knows about". Merges three
 * sources, in order:
 *   1. Bundled — JSON files in assets/ (the original library).
 *   2. Imported — JSON files saved to filesDir/user_workouts/ via [WorkoutImporter].
 *   3. Subscribed — workouts from repositories cached by [RepositoryManager].
 *
 * Duplicate workout ids are de-duped, **first wins**, with this priority:
 * Imported > Subscribed > Bundled. The reasoning: a user who explicitly imports
 * a workout that happens to share an id with a bundled or subscribed copy is
 * making a clear "use my version" statement.
 *
 * The list is exposed as a [StateFlow] so the rest of the app can observe it
 * reactively. The current asset-loading split between MainActivity and the
 * shared `WorkoutRepository` is preserved — bundled workouts still come from
 * `parseWorkouts(jsonStrings)` so the existing parser logic and dedup semantics
 * remain centralised.
 */
class WorkoutLibrary(
    private val context: Context,
    private val importer: WorkoutImporter,
    private val repositoryManager: RepositoryManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _entries = MutableStateFlow<List<WorkoutEntry>>(emptyList())
    val entries: StateFlow<List<WorkoutEntry>> = _entries.asStateFlow()

    /**
     * Distinguishes "still loading the bundled assets on first launch" from
     * "loaded but no workouts found", same purpose as MainActivity's old
     * nullable workouts state.
     */
    private val _initialised = MutableStateFlow(false)
    val initialised: StateFlow<Boolean> = _initialised.asStateFlow()

    private var bundled: List<Workout> = emptyList()

    /**
     * Serialises [rebuild] calls. Two paths can trigger a rebuild concurrently:
     * the import refresh from the UI and the `subscribed` flow listener fired
     * by `RepositoryManager`. Without this lock, two overlapping rebuilds race
     * to write `_entries.value` and the order of completion is non-deterministic
     * — so the latest data may briefly lose to an older rebuild that finished
     * after starting first.
     */
    private val rebuildMutex = Mutex()

    init {
        // Rebuild whenever EITHER the subscribed-repo list changes (subscribe /
        // unsubscribe) OR the on-disk bundle cache changes (a refresh just
        // wrote new bundle files for an already-subscribed repo). Combining
        // both avoids missing the second case — the subscription list itself
        // is unchanged when only bundle contents update.
        scope.launch {
            kotlinx.coroutines.flow.combine(
                repositoryManager.subscribed,
                repositoryManager.cacheTick
            ) { _, _ -> Unit }.collect { rebuild() }
        }
    }

    /** Kicks off the initial load: bundled assets + on-disk imports. */
    fun start() {
        scope.launch {
            bundled = withIO { loadBundled(context) }
            rebuild()
            _initialised.value = true
        }
    }

    /**
     * Re-reads on-disk imports. Call after [WorkoutImporter.importJsonText]
     * resolves with [ImportResult.Workouts] to make the new files appear.
     */
    fun refreshImports() {
        scope.launch { rebuild() }
    }

    /**
     * Suspending variant — completes only once the new [entries] value has
     * been emitted. Use this when the caller needs to navigate to a freshly
     * imported workout immediately after refresh; firing-and-forgetting via
     * [refreshImports] would race the navigation.
     */
    suspend fun refreshImportsAwait() {
        rebuild()
    }

    fun dispose() {
        scope.cancel()
    }

    private suspend fun rebuild() = rebuildMutex.withLock {
        val imports = withIO { importer.loadUserWorkouts() }
        val repoEntries = withIO {
            repositoryManager.subscribed.value.flatMap { sub ->
                repositoryManager.loadWorkoutsForSubscription(sub.id).map { workout ->
                    WorkoutEntry(workout, WorkoutSource.Repository(sub.id, sub.name))
                }
            }
        }
        val bundledEntries = bundled.map { WorkoutEntry(it, WorkoutSource.Bundled) }
        val importedEntries = imports.map { WorkoutEntry(it, WorkoutSource.Imported) }

        // Imported > Subscribed > Bundled. First wins on duplicate ids.
        val seen = mutableSetOf<String>()
        val out = mutableListOf<WorkoutEntry>()
        for (entry in importedEntries + repoEntries + bundledEntries) {
            if (seen.add(entry.workout.id)) out.add(entry)
        }
        _entries.value = out
    }

    private suspend fun <T> withIO(block: () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    companion object {
        private const val TAG = "WorkoutLibrary"

        /** Loads `assets/workouts*.json` and returns the parsed bundled list. */
        fun loadBundled(context: Context): List<Workout> {
            val jsonStrings = mutableListOf<String>()
            try {
                val assetManager = context.assets
                val allAssets = assetManager.list("") ?: emptyArray()
                val workoutFiles = allAssets.filter {
                    it.startsWith("workouts") && it.endsWith(".json")
                }
                for (fileName in workoutFiles) {
                    val text = assetManager.open(fileName).bufferedReader().use { it.readText() }
                    jsonStrings.add(text)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Asset load failed", t)
            }
            return WorkoutRepository.parseWorkouts(jsonStrings)
        }
    }
}
