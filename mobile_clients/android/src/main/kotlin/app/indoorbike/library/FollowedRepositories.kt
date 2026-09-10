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
import app.indoorbike.shared.repo.ManifestParser
import app.indoorbike.shared.repo.ROUTE_BUNDLE_SCHEMA_VERSION_MAX_SUPPORTED
import app.indoorbike.shared.repo.RegistryEntry
import app.indoorbike.shared.repo.BUNDLE_SCHEMA_VERSION_MAX_SUPPORTED
import app.indoorbike.shared.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The repositories the rider follows, and what each one has published since
 * they last looked.
 *
 * **Why this exists again.** Subscriptions were removed once already, because
 * mirroring a repo meant a maintainer could change what was on someone's
 * device without them asking. That objection is about *content*, not about
 * *knowing*, so this brings back only the knowing: following a repository
 * checks it for new items and says so, and nothing enters the library until
 * the rider taps Add. [WorkoutSource] and [app.indoorbike.route.RouteSource]
 * still describe every local copy as a static import, because that is what it
 * remains.
 *
 * **What a check costs.** Naively, "has anything changed?" means downloading
 * every bundle in every followed repo on every look, which for a 400-workout
 * repository is a real transfer to answer a question that is almost always
 * "no". Instead a check fetches the manifest, which is small by design, and
 * compares each bundle's `version` against the version last seen. A bundle is
 * downloaded only when that number moves. This is the first use of a field the
 * format has carried unused since v2, and it is the reason the contributor
 * docs tell maintainers to bump it whenever bundle content changes: a
 * maintainer who does not is invisible to this, by contract.
 *
 * **What "new" means.** Everything ever fetched from a repo is *known*;
 * everything the rider has actually browsed is *seen*. New is the difference.
 * Refreshing never marks anything seen, or nothing would ever be new; only
 * [markSeen], called when the rider opens the repo, does that. Both sets are
 * persisted, so a count survives the app being killed.
 *
 * Persistence under `filesDir`:
 *
 *     followed/repos.json         the list itself
 *     followed/seen/<id>.json     per-repo known / seen / bundle versions
 */
class FollowedRepositories(
    private val context: Context,
    private val repositoryManager: RepositoryManager,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val baseDir: File get() = File(context.filesDir, "followed").apply { mkdirs() }
    private val listFile: File get() = File(baseDir, "repos.json")
    private val seenDir: File get() = File(baseDir, "seen").apply { mkdirs() }

    private val refreshMutex = Mutex()

    private val _repos = MutableStateFlow<List<FollowedRepo>>(emptyList())
    val repos: StateFlow<List<FollowedRepo>> = _repos.asStateFlow()

    private val _status = MutableStateFlow<Map<String, RepoStatus>>(emptyMap())
    val status: StateFlow<Map<String, RepoStatus>> = _status.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        _repos.value = loadList()
        // Hydrate counts from disk so the home screen can say "2 new" on its
        // first frame rather than after a network round trip.
        _status.value = _repos.value.associate { repo ->
            val seen = loadSeen(repo.id)
            repo.id to RepoStatus.Idle(
                newWorkouts = (seen.knownWorkoutIds - seen.seenWorkoutIds).size,
                newRoutes = (seen.knownRouteIds - seen.seenRouteIds).size,
                checkedEpoch = seen.lastCheckedEpoch.takeIf { it > 0 },
            )
        }
    }

    /** True when this repository is already followed. */
    fun isFollowed(repoId: String): Boolean = _repos.value.any { it.id == repoId }

    fun follow(entry: RegistryEntry) = follow(
        FollowedRepo(
            id = entry.id,
            name = entry.name,
            manifestUrl = entry.manifestUrl,
            author = entry.author,
            homepage = entry.homepage,
            addedEpoch = System.currentTimeMillis(),
        )
    )

    fun follow(repo: FollowedRepo) {
        if (isFollowed(repo.id)) return
        _repos.value = _repos.value + repo
        saveList()
        // A newly followed repo has nothing seen, so everything in it is new.
        // That is the honest answer, and it is also what makes the first check
        // useful rather than silent.
        _status.value = _status.value + (repo.id to RepoStatus.Idle(0, 0, null))
    }

    fun unfollow(repoId: String) {
        _repos.value = _repos.value.filterNot { it.id == repoId }
        saveList()
        _status.value = _status.value - repoId
        // Forget what was known too: re-following later should behave like a
        // fresh start rather than resurrecting a stale idea of "new".
        File(seenDir, sanitise(repoId) + ".json").delete()
    }

    /**
     * Everything the rider has not yet looked at, across every followed repo.
     * The home screen shows this as one number.
     */
    fun totalNew(): Int = _status.value.values.sumOf { it.newWorkouts + it.newRoutes }

    /**
     * Mark a repository as looked at. Called when the rider opens its browser,
     * which is the only thing that clears a "new" badge: a refresh must never
     * do it, or nothing would ever be new for long enough to be seen.
     */
    fun markSeen(repoId: String) {
        val seen = loadSeen(repoId)
        saveSeen(
            repoId,
            seen.copy(seenWorkoutIds = seen.knownWorkoutIds, seenRouteIds = seen.knownRouteIds),
        )
        _status.value = _status.value + (repoId to RepoStatus.Idle(0, 0, seen.lastCheckedEpoch.takeIf { it > 0 }))
    }

    /**
     * Check every followed repository, unless one was checked recently and
     * [force] is false.
     *
     * Throttled rather than scheduled: this runs when the rider opens the home
     * screen, so a rider who opens the app four times before a ride pays for
     * one check, and the app does nothing at all while it is closed.
     */
    suspend fun refresh(force: Boolean = false) {
        if (_repos.value.isEmpty()) return
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            val due = _repos.value.filter { repo ->
                force || (loadSeen(repo.id).lastCheckedEpoch + REFRESH_INTERVAL_MS) <= now
            }
            if (due.isEmpty()) return

            _refreshing.value = true
            _status.value = _status.value + due.associate { repo ->
                repo.id to RepoStatus.Checking(_status.value[repo.id]?.newWorkouts ?: 0,
                    _status.value[repo.id]?.newRoutes ?: 0)
            }
            try {
                // Repositories are independent, so one slow host does not hold
                // up the rest, and one failure never costs the others.
                coroutineScope {
                    due.map { repo -> async(Dispatchers.IO) { check(repo) } }.awaitAll()
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    private suspend fun check(repo: FollowedRepo) {
        val result = try {
            val manifest = repositoryManager.fetchManifest(repo.manifestUrl)
            val seen = loadSeen(repo.id)

            val workoutBundles = manifest.bundles
                .filter { it.schemaVersion <= BUNDLE_SCHEMA_VERSION_MAX_SUPPORTED }
            val routeBundles = manifest.routeBundles
                .filter { it.schemaVersion <= ROUTE_BUNDLE_SCHEMA_VERSION_MAX_SUPPORTED }

            val changedWorkoutBundles = workoutBundles
                .filter { seen.bundleVersions[it.id] != it.version }
            val changedRouteBundles = routeBundles
                .filter { seen.routeBundleVersions[it.id] != it.version }

            val knownWorkouts = seen.knownWorkoutIds.toMutableSet()
            val knownRoutes = seen.knownRouteIds.toMutableSet()

            for (bundle in changedWorkoutBundles) {
                val url = repositoryManager.resolveUrl(bundle.url, repo.manifestUrl) ?: continue
                runCatching { ManifestParser.parseWorkoutBundle(HttpFetcher.getString(url)) }
                    .onSuccess { workouts -> knownWorkouts += workouts.map { it.id } }
                    .onFailure { Log.w(TAG, "Bundle ${bundle.id} of ${repo.id}: ${it.message}") }
            }
            for (bundle in changedRouteBundles) {
                val url = repositoryManager.resolveUrl(bundle.url, repo.manifestUrl) ?: continue
                runCatching { ManifestParser.parseRouteBundle(HttpFetcher.getString(url)) }
                    .onSuccess { routes -> knownRoutes += routes.map { it.id } }
                    .onFailure { Log.w(TAG, "Route bundle ${bundle.id} of ${repo.id}: ${it.message}") }
            }

            // A repository the rider has never browsed starts with nothing
            // seen, so its whole catalogue counts as new. That is deliberate:
            // they followed it to find out what is in it.
            val updated = seen.copy(
                bundleVersions = workoutBundles.associate { it.id to it.version },
                routeBundleVersions = routeBundles.associate { it.id to it.version },
                knownWorkoutIds = knownWorkouts,
                knownRouteIds = knownRoutes,
                lastCheckedEpoch = System.currentTimeMillis(),
            )
            saveSeen(repo.id, updated)

            RepoStatus.Idle(
                newWorkouts = (updated.knownWorkoutIds - updated.seenWorkoutIds).size,
                newRoutes = (updated.knownRouteIds - updated.seenRouteIds).size,
                checkedEpoch = updated.lastCheckedEpoch,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Check failed for ${repo.id}: ${t.message}")
            val seen = loadSeen(repo.id)
            RepoStatus.Failed(
                message = t.message ?: "Could not reach it",
                newWorkouts = (seen.knownWorkoutIds - seen.seenWorkoutIds).size,
                newRoutes = (seen.knownRouteIds - seen.seenRouteIds).size,
                checkedEpoch = seen.lastCheckedEpoch.takeIf { it > 0 },
            )
        }
        _status.value = _status.value + (repo.id to result)
    }

    // ── Persistence ──────────────────────────────────────────────────────

    private fun loadList(): List<FollowedRepo> {
        if (!listFile.exists()) return emptyList()
        return try {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(FollowedRepo.serializer()),
                listFile.readText(Charsets.UTF_8),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read followed repos: ${t.message}")
            emptyList()
        }
    }

    private fun saveList() {
        val text = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(FollowedRepo.serializer()),
            _repos.value,
        )
        atomicWrite(listFile, text)
    }

    private fun loadSeen(repoId: String): SeenState {
        val file = File(seenDir, sanitise(repoId) + ".json")
        if (!file.exists()) return SeenState()
        return try {
            json.decodeFromString(SeenState.serializer(), file.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            Log.w(TAG, "Corrupt seen state for $repoId: ${t.message}")
            SeenState()
        }
    }

    private fun saveSeen(repoId: String, state: SeenState) {
        atomicWrite(
            File(seenDir, sanitise(repoId) + ".json"),
            json.encodeToString(SeenState.serializer(), state),
        )
    }

    private fun atomicWrite(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) file.writeText(text, Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not write ${file.name}: ${t.message}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun sanitise(id: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "repo" }

    companion object {
        private const val TAG = "FollowedRepositories"

        /**
         * How stale a check may be before opening the home screen triggers
         * another. Community repositories change on the order of weeks, so
         * this is about being current enough to be useful, not about being
         * live.
         */
        const val REFRESH_INTERVAL_MS = 3 * 60 * 60 * 1000L
    }
}

/** A repository the rider has chosen to follow. */
@Serializable
data class FollowedRepo(
    val id: String,
    val name: String,
    val manifestUrl: String,
    val author: String? = null,
    val homepage: String? = null,
    val addedEpoch: Long = 0L,
)

/**
 * What is known about a repository and what the rider has actually looked at.
 * The difference between the two is what "new" means; see the class doc.
 */
@Serializable
internal data class SeenState(
    /** Bundle id to the version last fetched, so an unchanged bundle is skipped. */
    val bundleVersions: Map<String, Int> = emptyMap(),
    val routeBundleVersions: Map<String, Int> = emptyMap(),
    val knownWorkoutIds: Set<String> = emptySet(),
    val knownRouteIds: Set<String> = emptySet(),
    val seenWorkoutIds: Set<String> = emptySet(),
    val seenRouteIds: Set<String> = emptySet(),
    val lastCheckedEpoch: Long = 0L,
)

/** Where a followed repository stands right now. */
sealed class RepoStatus {
    abstract val newWorkouts: Int
    abstract val newRoutes: Int

    /** Not being checked at the moment. [checkedEpoch] is null if never checked. */
    data class Idle(
        override val newWorkouts: Int,
        override val newRoutes: Int,
        val checkedEpoch: Long?,
    ) : RepoStatus()

    data class Checking(
        override val newWorkouts: Int,
        override val newRoutes: Int,
    ) : RepoStatus()

    /**
     * The last check failed. Any previously known "new" count is carried
     * through rather than reset, because a repo being unreachable today does
     * not un-publish what it published yesterday.
     */
    data class Failed(
        val message: String,
        override val newWorkouts: Int,
        override val newRoutes: Int,
        val checkedEpoch: Long?,
    ) : RepoStatus()

    val total: Int get() = newWorkouts + newRoutes
}
