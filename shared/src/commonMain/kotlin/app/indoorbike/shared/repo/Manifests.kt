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
package app.indoorbike.shared.repo

import app.indoorbike.shared.Workout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-disk format for a workout repository's manifest. Hosted over HTTPS at any
 * URL — GitHub raw, Codeberg Pages, gh-pages, plain web folder, anywhere.
 *
 * Schema v2 (current): the manifest holds metadata plus a list of bundle
 * entries (`{id, version, url, ...}`). Each bundle is a separate JSON file
 * containing one-or-many workouts. The app fetches a manifest on demand when
 * the rider opens the repo in the in-app browser, downloads each supported
 * bundle once, and keeps the parsed workouts in memory while the rider picks
 * which to add — each chosen workout becomes a static local copy. Earlier
 * versions of the app maintained a per-repo on-disk mirror; that has been
 * removed in favour of this explicit-add model.
 *
 * Schema v1 (legacy, no longer accepted): workouts were embedded inline in
 * the manifest. Repos using v1 must migrate to v2 — the app rejects unknown
 * schema versions and surfaces a clear error to the rider.
 *
 * Routes ([routeBundles]) were added later and deliberately did *not* bump the
 * schema version. See [RouteBundleEntry] for why: a v3 manifest would be
 * rejected outright by every already-shipped build, so a repo that added a
 * route would lose its workouts on older installs. Instead the field is
 * optional and old builds skip it through `ignoreUnknownKeys`, which means a
 * maintainer already listed in the registry can start publishing routes by
 * editing their own manifest, with no pull request and no risk to their
 * existing readers.
 */
@Serializable
data class RepositoryManifest(
    val schemaVersion: Int = REPO_MANIFEST_SCHEMA_VERSION_CURRENT,
    val id: String,
    val name: String,
    val description: String? = null,
    val author: String? = null,
    val homepage: String? = null,
    val updatedEpoch: Long? = null,
    val bundles: List<WorkoutBundleEntry> = emptyList(),
    /**
     * Route listings, if this repository publishes any. Optional and
     * independently versioned; a repository may carry workouts, routes, or
     * both.
     */
    val routeBundles: List<RouteBundleEntry> = emptyList(),
)

/**
 * One entry per bundle in a [RepositoryManifest]. A bundle is a single file
 * (typically a JSON array of [Workout] objects) — collecting workouts into
 * one HTTP fetch keeps server round-trips low while still letting big repos
 * split content across multiple files for cache-invalidation granularity
 * (only the bumped bundle re-downloads when content changes).
 *
 *  - [id]: stable bundle id within the repo (filename-safe). Used as the
 *    on-device cache key — don't change it after publication.
 *  - [name]: optional human-readable label. Bundles aren't shown to users
 *    by default; this is a maintainer-facing hint.
 *  - [schemaVersion]: format version of the file at [url]. v1 = bare JSON
 *    array of `Workout` objects. Bundles whose schemaVersion exceeds
 *    [BUNDLE_SCHEMA_VERSION_MAX_SUPPORTED] are skipped without downloading.
 *  - [version]: content version. Bump whenever ANY workout inside the bundle
 *    changes. Currently informational only — the in-app browser refetches the
 *    bundle every time the rider opens the repo. The slot is preserved so
 *    future caching support can land without a wire-format change.
 *  - [url]: absolute (`https://…`) or relative to the manifest URL's
 *    directory. Relative is the portable form — survives a host change.
 */
@Serializable
data class WorkoutBundleEntry(
    val id: String,
    val name: String? = null,
    val schemaVersion: Int = 1,
    val version: Int = 1,
    val url: String,
)

/** Manifest format version emitted by this build. */
const val REPO_MANIFEST_SCHEMA_VERSION_CURRENT = 2
/** Highest manifest format this build can parse — anything higher is rejected. */
const val REPO_MANIFEST_SCHEMA_VERSION_MAX_SUPPORTED = 2
/** Highest bundle file format this build can parse — bundles above are skipped. */
const val BUNDLE_SCHEMA_VERSION_MAX_SUPPORTED = 1

/**
 * Top-level community registry served at the well-known URL (default
 * `https://indoorbike.app/registry.json`). The app fetches it lazily — when
 * absent or unreachable, browse just shows an empty state.
 */
@Serializable
data class RegistryIndex(
    val schemaVersion: Int = 1,
    val updatedEpoch: Long? = null,
    val repositories: List<RegistryEntry> = emptyList(),
)

@Serializable
data class RegistryEntry(
    val id: String,
    val name: String,
    val description: String? = null,
    val author: String? = null,
    val manifestUrl: String,
    val tags: List<String> = emptyList(),
    val homepage: String? = null,
    /**
     * What this repository publishes: `"workouts"`, `"routes"`, or both.
     *
     * A hint, not a contract. Whether a repository has routes is only truly
     * knowable by fetching its manifest, and fetching every manifest to draw
     * one list is exactly the round-trip the registry exists to avoid. So a
     * maintainer declares it here and the browser uses it to decide which
     * affordances to offer; an empty list means "not stated" and is read as
     * workouts, which is what every repository predating routes is.
     */
    val content: List<String> = emptyList(),
) {
    /** True when this repository says it publishes rideable routes. */
    val hasRoutes: Boolean get() = content.any { it.equals("routes", ignoreCase = true) }
}

object ManifestParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parse a repository manifest. Throws if the manifest's `schemaVersion`
     * exceeds the highest version this build understands so callers can
     * surface a clear "please upgrade the app" message instead of trying to
     * decode an unknown shape.
     */
    fun parseRepositoryManifest(text: String): RepositoryManifest {
        val manifest = json.decodeFromString(RepositoryManifest.serializer(), text)
        if (manifest.schemaVersion > REPO_MANIFEST_SCHEMA_VERSION_MAX_SUPPORTED) {
            throw IllegalStateException(
                "Repository \"${manifest.name}\" uses manifest format v${manifest.schemaVersion}, " +
                    "this build understands up to v$REPO_MANIFEST_SCHEMA_VERSION_MAX_SUPPORTED. " +
                    "Update the app to open this repository."
            )
        }
        return manifest
    }

    /** Bundle file = JSON array of [Workout] objects (bundle schemaVersion 1). */
    fun parseWorkoutBundle(text: String): List<Workout> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Workout.serializer()), text)

    /**
     * Route bundle file = JSON array of [RouteEntry] objects (bundle
     * schemaVersion 1).
     *
     * Entries this build cannot use are dropped here rather than surfaced as a
     * parse failure: a repository that adds one route against a newer schema,
     * or ships one malformed entry, should cost the rider that route and not
     * the whole bundle.
     */
    fun parseRouteBundle(text: String): List<RouteEntry> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(RouteEntry.serializer()), text)
            .filter { it.isSupported && it.isUsable }

    fun parseRegistryIndex(text: String): RegistryIndex =
        json.decodeFromString(RegistryIndex.serializer(), text)

    fun parseSingleWorkout(text: String): Workout =
        json.decodeFromString(Workout.serializer(), text)

    /**
     * Heuristic: does this JSON look like a manifest, a registry, or a single
     * workout? Used by the universal import flow so the user doesn't have to
     * pick the right mode up front.
     *
     * Manifests are detected by a top-level `"bundles"` key (schema v2). The
     * legacy v1 manifest's `"workouts"` key is no longer recognised — old
     * manifests parse-fail at decode time, which surfaces as a graceful
     * Failure import result.
     *
     * Route files are told apart by `"gpxUrl"`, which only a [RouteEntry] has.
     * That check has to come before the bare-array shortcut, because a route
     * bundle and a workout bundle are both top-level JSON arrays. A manifest
     * that carries only routes has no `"bundles"` key at all, so
     * `"routeBundles"` is recognised on its own — note the two do not collide
     * as substrings, since the search includes the opening quote.
     */
    fun classify(text: String): ImportKind {
        // Cheap pre-checks first.
        val trimmed = text.trimStart()
        if (trimmed.startsWith("[")) {
            return if ("\"gpxUrl\"" in text) ImportKind.RouteList else ImportKind.WorkoutList
        }
        return when {
            "\"repositories\"" in text -> ImportKind.Registry
            ("\"bundles\"" in text || "\"routeBundles\"" in text) &&
                "\"manifestUrl\"" !in text -> ImportKind.RepositoryManifest
            "\"segments\"" in text -> ImportKind.SingleWorkout
            else -> ImportKind.Unknown
        }
    }

    fun encodeRepositoryManifest(manifest: RepositoryManifest): String =
        json.encodeToString(RepositoryManifest.serializer(), manifest)
}

enum class ImportKind {
    SingleWorkout, WorkoutList, RouteList, RepositoryManifest, Registry, Unknown,
}
