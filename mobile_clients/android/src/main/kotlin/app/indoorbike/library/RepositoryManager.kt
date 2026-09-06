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
import app.indoorbike.shared.WORKOUT_SCHEMA_VERSION_MAX_SUPPORTED
import app.indoorbike.shared.Workout
import app.indoorbike.shared.repo.BUNDLE_SCHEMA_VERSION_MAX_SUPPORTED
import app.indoorbike.shared.repo.ManifestParser
import app.indoorbike.shared.repo.ROUTE_BUNDLE_SCHEMA_VERSION_MAX_SUPPORTED
import app.indoorbike.shared.repo.RegistryIndex
import app.indoorbike.shared.repo.RepositoryManifest
import app.indoorbike.shared.repo.RouteEntry
import app.indoorbike.shared.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Owns the cached community registry and the on-demand fetch path for any
 * single repository the user is browsing.
 *
 * **Explicit-add model.** The previous "subscribe to a repo, mirror its
 * workouts locally" model was replaced with a remote-library browse: the
 * rider opens a repo, picks workouts they like, and each one is saved as a
 * static local copy (via [WorkoutImporter.saveWorkoutFromRepo]). There is no
 * ongoing relationship between the user and the upstream repo — the local
 * copy isn't auto-updated when the repo changes.
 *
 * What this class still does:
 *  - [refreshRegistry] / [registry] — community-registry cache used by the
 *    "More workouts" page to list browseable repos.
 *  - [fetchRepoWorkouts] — one-shot fetch of a single repo's manifest plus
 *    bundles, returning the resolved workout list to the caller. Nothing is
 *    persisted under `filesDir/repos/`; only the registry cache lives on
 *    disk now.
 */
class RepositoryManager(
    private val context: Context,
    private val registryUrl: String,
) {

    private val baseDir: File get() = context.filesDir
    private val registryDir: File get() = File(baseDir, "registry").apply { mkdirs() }
    private val registryCacheFile: File get() = File(registryDir, "cache.json")

    private val _registry = MutableStateFlow<RegistryState>(RegistryState.Initial)
    val registry: StateFlow<RegistryState> = _registry.asStateFlow()

    init {
        // Hydrate from disk on construction so the UI has data on first frame.
        _registry.value = loadRegistryFromDisk()
        pruneLegacyReposDir()
    }

    /**
     * One-time cleanup of the on-disk repo mirror that pre-explicit-add builds
     * maintained at `filesDir/repos/<id>/`. The directory is no longer written
     * (the explicit-add model keeps fetched repos in memory only), but devices
     * that ever ran an older build can carry orphaned bundles. Best-effort —
     * a failure here is silent because there's nothing the user can do about
     * it and the orphans don't affect correctness, only disk usage.
     */
    private fun pruneLegacyReposDir() {
        val legacy = File(baseDir, "repos")
        if (!legacy.exists()) return
        try {
            if (legacy.deleteRecursively()) {
                Log.i(TAG, "Pruned legacy filesDir/repos/")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't prune legacy repos dir: ${t.message}")
        }
    }

    // ── Registry ─────────────────────────────────────────────────────────────

    /**
     * Fetches the community registry. Failure leaves the previous cache (if any)
     * in place — the UI can decide whether to surface the error.
     */
    suspend fun refreshRegistry(): RegistryState = withContext(Dispatchers.IO) {
        val state = try {
            val text = HttpFetcher.getString(registryUrl)
            val index = ManifestParser.parseRegistryIndex(text)
            registryCacheFile.writeText(text, Charsets.UTF_8)
            RegistryState.Loaded(index, fromCache = false)
        } catch (t: Throwable) {
            Log.w(TAG, "Registry refresh failed: ${t.message}")
            // Keep any pre-existing cache visible; just flag the failure.
            val cached = (loadRegistryFromDisk() as? RegistryState.Loaded)?.index
            RegistryState.Failed(error = t.message ?: "Couldn't reach registry", cachedIndex = cached)
        }
        _registry.value = state
        state
    }

    // ── On-demand repo fetch ─────────────────────────────────────────────────

    /**
     * Fetch a single repository: download its manifest, then download every
     * supported bundle, then return the merged list of workouts. Nothing is
     * cached on disk — callers (typically the remote-repo browser screen)
     * keep the result in memory for the duration of the browse, and any
     * workout the user explicitly adds is copied into the local imports via
     * [WorkoutImporter.saveWorkoutFromRepo].
     */
    suspend fun fetchRepoWorkouts(manifestUrl: String): RepoFetchResult = withContext(Dispatchers.IO) {
        try {
            val manifestText = HttpFetcher.getString(manifestUrl)
            val manifest = ManifestParser.parseRepositoryManifest(manifestText)
            val workouts = mutableListOf<Workout>()
            for (entry in manifest.bundles) {
                if (entry.schemaVersion > BUNDLE_SCHEMA_VERSION_MAX_SUPPORTED) {
                    Log.w(TAG, "Skipping bundle '${entry.id}' - schema v${entry.schemaVersion} > supported")
                    continue
                }
                val resolved = resolveUrl(entry.url, manifestUrl) ?: run {
                    Log.w(TAG, "Bundle '${entry.id}' has relative URL '${entry.url}' but no base - skip")
                    continue
                }
                try {
                    val bundleText = HttpFetcher.getString(resolved)
                    val bundle = ManifestParser.parseWorkoutBundle(bundleText)
                    for (w in bundle) {
                        if (w.schemaVersion <= WORKOUT_SCHEMA_VERSION_MAX_SUPPORTED) {
                            workouts += w
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Bundle '${entry.id}' fetch/parse failed: ${t.message}")
                }
            }
            RepoFetchResult.Ok(manifest, workouts)
        } catch (t: Throwable) {
            Log.w(TAG, "Repo fetch failed for $manifestUrl: ${t.message}")
            RepoFetchResult.Failure(t.message ?: "Couldn't fetch repository")
        }
    }

    /**
     * Fetch a single repository's routes: download its manifest, then every
     * supported route bundle, and return the merged listing.
     *
     * The shape mirrors [fetchRepoWorkouts], but what comes back is
     * deliberately *not* rideable. A [RouteEntry] is metadata plus a pointer;
     * no GPX is downloaded here. The rider browses a list built entirely from
     * these numbers and only pays for geometry on the one route they add,
     * which is the whole reason routes are listed separately from their data.
     *
     * Each entry has its `gpxUrl` resolved against the bundle it came from
     * before being handed on, so callers downstream never need to know which
     * file an entry was published in.
     *
     * A repository with no `routeBundles` is not an error: most repositories
     * are workouts-only, and asking for their routes should quietly return
     * nothing rather than fail.
     */
    suspend fun fetchRepoRoutes(manifestUrl: String): RouteFetchResult = withContext(Dispatchers.IO) {
        try {
            val manifestText = HttpFetcher.getString(manifestUrl)
            val manifest = ManifestParser.parseRepositoryManifest(manifestText)
            val routes = mutableListOf<RouteEntry>()
            for (entry in manifest.routeBundles) {
                if (entry.schemaVersion > ROUTE_BUNDLE_SCHEMA_VERSION_MAX_SUPPORTED) {
                    Log.w(TAG, "Skipping route bundle '${entry.id}' - schema v${entry.schemaVersion} > supported")
                    continue
                }
                val resolved = resolveUrl(entry.url, manifestUrl) ?: run {
                    Log.w(TAG, "Route bundle '${entry.id}' has relative URL '${entry.url}' but no base - skip")
                    continue
                }
                try {
                    val bundleText = HttpFetcher.getString(resolved)
                    // parseRouteBundle has already dropped entries this build
                    // is too old for, or that are missing what a ride needs.
                    for (route in ManifestParser.parseRouteBundle(bundleText)) {
                        val gpxUrl = resolveUrl(route.gpxUrl, resolved) ?: run {
                            Log.w(TAG, "Route '${route.id}' has unresolvable GPX URL '${route.gpxUrl}' - skip")
                            continue
                        }
                        routes += route.copy(gpxUrl = gpxUrl)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Route bundle '${entry.id}' fetch/parse failed: ${t.message}")
                }
            }
            RouteFetchResult.Ok(manifest, routes)
        } catch (t: Throwable) {
            Log.w(TAG, "Route fetch failed for $manifestUrl: ${t.message}")
            RouteFetchResult.Failure(t.message ?: "Couldn't fetch repository")
        }
    }

    /**
     * Resolve a URL found inside a fetched file. Absolute (`https://`) URLs
     * return as-is; relative URLs resolve against the containing file's
     * directory. Returns null only when a relative URL has no base to resolve
     * against.
     *
     * Used for bundle URLs against the manifest, and for a route's `gpxUrl`
     * against its own bundle file, which is why the base is a parameter rather
     * than assumed to be the manifest: a repository may keep its route
     * listings and its GPX files in the same subdirectory and refer to them
     * with bare filenames.
     */
    private fun resolveUrl(entryUrl: String, baseUrl: String): String? {
        if (entryUrl.startsWith("https://", ignoreCase = true) ||
            entryUrl.startsWith("http://", ignoreCase = true)) {
            return entryUrl
        }
        val baseEnd = baseUrl.lastIndexOf('/')
        if (baseEnd < 0) return null
        return baseUrl.substring(0, baseEnd + 1) + entryUrl
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun loadRegistryFromDisk(): RegistryState {
        val file = registryCacheFile
        if (!file.exists()) return RegistryState.Initial
        return try {
            val index = ManifestParser.parseRegistryIndex(file.readText(Charsets.UTF_8))
            RegistryState.Loaded(index, fromCache = true)
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't load cached registry: ${t.message}")
            RegistryState.Initial
        }
    }

    companion object {
        private const val TAG = "RepositoryManager"
    }
}

sealed class RepoFetchResult {
    data class Ok(val manifest: RepositoryManifest, val workouts: List<Workout>) : RepoFetchResult()
    data class Failure(val message: String) : RepoFetchResult()
}

/** The route-side counterpart of [RepoFetchResult]: a listing, not ride data. */
sealed class RouteFetchResult {
    data class Ok(val manifest: RepositoryManifest, val routes: List<RouteEntry>) : RouteFetchResult()
    data class Failure(val message: String) : RouteFetchResult()
}

sealed class RegistryState {
    data object Initial : RegistryState()
    data class Loaded(val index: RegistryIndex, val fromCache: Boolean) : RegistryState()
    data class Failed(val error: String, val cachedIndex: RegistryIndex?) : RegistryState()
}
