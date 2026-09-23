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
package app.indoorbike.shared.ios

import app.indoorbike.shared.Workout
import app.indoorbike.shared.library.ImportQuestions
import app.indoorbike.shared.library.ImportResult
import app.indoorbike.shared.library.WorkoutEntry
import app.indoorbike.shared.library.WorkoutFileStore
import app.indoorbike.shared.library.WorkoutImportCore
import app.indoorbike.shared.library.mergeWorkoutLibrary
import app.indoorbike.shared.repo.TextStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager

/** A folder in Application Support, as a store of small text files written atomically. */
@OptIn(ExperimentalForeignApi::class)
internal class IosFolder(folder: String) : WorkoutFileStore {
    private val dir: String by lazy {
        IosFiles.path(folder).also {
            NSFileManager.defaultManager.createDirectoryAtPath(it, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }

    private fun path(name: String): String {
        val full = "$dir/$name"
        // Names may carry one folder ("seen/x.json"); make sure it is there.
        full.substringBeforeLast('/').takeIf { it != dir }?.let {
            NSFileManager.defaultManager.createDirectoryAtPath(it, withIntermediateDirectories = true, attributes = null, error = null)
        }
        return full
    }

    @Suppress("UNCHECKED_CAST")
    override fun names(): List<String> =
        (NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null) as? List<String>).orEmpty()

    override fun read(name: String): String? = IosFiles.readText(path(name))
    override fun write(name: String, text: String) = IosFiles.writeText(path(name), text)
    override fun exists(name: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path(name))

    override fun delete(name: String): Boolean {
        val existed = exists(name)
        IosFiles.delete(path(name))
        return existed
    }

    /** The same folder, for [app.indoorbike.shared.repo.FollowedRepositories]. */
    fun asTextStore(): TextStore = object : TextStore {
        override fun read(name: String): String? = this@IosFolder.read(name)
        override fun write(name: String, text: String) = this@IosFolder.write(name, text)
        override fun delete(name: String) {
            this@IosFolder.delete(name)
        }
    }
}

/**
 * Every workout the rider can ride here: their own, then the bundled ones.
 * The iOS counterpart of the Android `WorkoutLibrary` and `WorkoutImporter`,
 * with the importing itself shared ([WorkoutImportCore]) and the files kept
 * in `user_workouts/`, as on Android.
 */
class IosWorkoutLibrary(private val scope: CoroutineScope, private val bundled: List<Workout>) {
    private val bundledById = bundled.associateBy { it.id }

    val questions = ImportQuestions()
    private val core = WorkoutImportCore(IosFolder("user_workouts"), { bundledById }, questions)

    private val _entries = MutableStateFlow(mergeWorkoutLibrary(emptyList(), bundled) { null })
    val entries: StateFlow<List<WorkoutEntry>> = _entries.asStateFlow()

    private val rebuildLock = Mutex()

    fun start() {
        scope.launch { rebuild() }
    }

    /** Read the rider's own workouts again, after anything changed them. */
    suspend fun rebuild() = rebuildLock.withLock {
        _entries.value = withContext(Dispatchers.IO) {
            mergeWorkoutLibrary(core.loadUserWorkouts(), bundled, core::loadImportMeta)
        }
    }

    fun find(id: String): Workout? = _entries.value.firstOrNull { it.workout.id == id }?.workout

    suspend fun importJsonText(text: String): ImportResult =
        core.importJsonText(text).also { if (it is ImportResult.Workouts) rebuild() }

    suspend fun addFromRepo(workout: Workout, repoId: String, repoName: String) {
        withContext(Dispatchers.IO) { core.saveWorkout(workout, repoId, repoName) }
        rebuild()
    }

    suspend fun delete(workoutId: String): Boolean =
        withContext(Dispatchers.IO) { core.deleteUserWorkout(workoutId) }.also { rebuild() }

    suspend fun rename(workoutId: String, name: String): Workout? =
        withContext(Dispatchers.IO) { core.renameUserWorkout(workoutId, name) }.also { rebuild() }
}
