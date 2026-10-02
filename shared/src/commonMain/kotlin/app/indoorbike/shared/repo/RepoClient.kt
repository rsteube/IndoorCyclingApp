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
package app.indoorbike.shared.repo

import app.indoorbike.shared.WORKOUT_SCHEMA_VERSION_MAX_SUPPORTED
import app.indoorbike.shared.Workout
import app.indoorbike.shared.util.Log

/** One HTTPS GET returning the body as text, as each platform does it. */
fun interface TextFetcher {
    suspend fun getText(url: String): String
}

sealed class RepoFetchResult {
    data class Ok(val manifest: RepositoryManifest, val workouts: List<Workout>) : RepoFetchResult()
    data class Failure(val message: String) : RepoFetchResult()
}

sealed class RouteFetchResult {
    data class Ok(val manifest: RepositoryManifest, val routes: List<RouteEntry>) : RouteFetchResult()
    data class Failure(val message: String) : RouteFetchResult()
}

/**
 * Reading a community repository: its manifest, and the workout and route
 * bundles it lists. Shared by both apps, which each bring their own [TextFetcher]
 * (and with it their own rules for what a request may do).
 */
object RepoClient {
    private const val TAG = "RepoClient"

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
     * relative to each other.
     */
    fun resolveUrl(entryUrl: String, baseUrl: String): String? {
        if (entryUrl.startsWith("https://", ignoreCase = true) ||
            entryUrl.startsWith("http://", ignoreCase = true)) {
            return entryUrl
        }
        val baseEnd = baseUrl.lastIndexOf('/')
        if (baseEnd < 0) return null
        return baseUrl.substring(0, baseEnd + 1) + entryUrl
    }

    /** Just the manifest, without any of its bundles: the cheap way to see what changed. */
    suspend fun fetchManifest(manifestUrl: String, fetcher: TextFetcher): RepositoryManifest =
        ManifestParser.parseRepositoryManifest(fetcher.getText(manifestUrl))

    /**
     * A repository's workouts: its manifest, then every supported bundle,
     * merged. Nothing is kept: the caller holds the result for the browse, and
     * a workout the rider adds is copied into their library.
     */
    suspend fun fetchRepoWorkouts(manifestUrl: String, fetcher: TextFetcher): RepoFetchResult = try {
        val manifest = fetchManifest(manifestUrl, fetcher)
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
                for (w in ManifestParser.parseWorkoutBundle(fetcher.getText(resolved))) {
                    if (w.schemaVersion <= WORKOUT_SCHEMA_VERSION_MAX_SUPPORTED) workouts += w
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

    /**
     * A repository's routes: its manifest, then every supported route bundle,
     * merged. What comes back is deliberately *not* rideable - a [RouteEntry]
     * is metadata plus a pointer, and no GPX is downloaded here. The rider
     * browses a list built from these numbers and only pays for geometry on
     * the one route they add.
     *
     * Each entry has its `gpxUrl` resolved against the bundle it came from, so
     * nothing downstream needs to know which file it was published in. A
     * repository with no `routeBundles` is not an error: most are
     * workouts-only, and asking for their routes quietly returns nothing.
     */
    suspend fun fetchRepoRoutes(manifestUrl: String, fetcher: TextFetcher): RouteFetchResult = try {
        val manifest = fetchManifest(manifestUrl, fetcher)
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
                // parseRouteBundle has already dropped entries this build is
                // too old for, or that are missing what a ride needs.
                for (route in ManifestParser.parseRouteBundle(fetcher.getText(resolved))) {
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
