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

import app.indoorbike.shared.library.ImportResult
import app.indoorbike.shared.library.WorkoutEntry
import app.indoorbike.shared.library.mergeWorkoutLibrary
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
 * Single source of truth for "all workouts the app knows about". Merges two
 * sources, in priority order:
 *   1. Imported — JSON files saved to filesDir/user_workouts/ via
 *      [WorkoutImporter]. Includes both file-pick / QR / share-sheet imports
 *      and workouts the rider explicitly added from a community repo.
 *   2. Bundled — JSON files in assets/ (the starter pack that ships with the
 *      APK).
 *
 * Duplicate workout ids are de-duped, **first wins**, with the import path
 * winning over a bundled copy of the same id.
 *
 * The previous third source (Subscribed-repository live mirror) was removed
 * when subscriptions were dropped in favour of the explicit-add model. Repos
 * are now browsed on demand via [RepositoryManager.fetchRepoWorkouts]; the
 * rider picks individual workouts and they land here as Imported with the
 * source-repo recorded in the per-workout meta sidecar.
 */
class WorkoutLibrary(
    private val context: Context,
    private val importer: WorkoutImporter,
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
     * Serialises [rebuild] calls. Concurrent rebuild triggers (manual import
     * + repo "Add" + delete from another screen) can otherwise race to write
     * `_entries.value` non-deterministically; the lock keeps the latest
     * rebuild authoritative.
     */
    private val rebuildMutex = Mutex()

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
        // Imported > Bundled, first wins on duplicate ids: see
        // mergeWorkoutLibrary, which the iOS app shares.
        _entries.value = withIO {
            mergeWorkoutLibrary(importer.loadUserWorkouts(), bundled, importer::loadImportMeta)
        }
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
