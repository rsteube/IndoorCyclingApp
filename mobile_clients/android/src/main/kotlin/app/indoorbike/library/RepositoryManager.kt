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
import app.indoorbike.shared.repo.RegistryEntry
import app.indoorbike.shared.repo.RegistryIndex
import app.indoorbike.shared.repo.RepositoryManifest
import app.indoorbike.shared.repo.WorkoutBundleEntry
import app.indoorbike.shared.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Owns the on-disk cache of subscribed repositories and the cached community
 * registry. All subscribed repos appear via [subscribed] as a `StateFlow` so
 * the [WorkoutLibrary] aggregator can rebuild its workout list on changes.
 *
 * On-disk layout under `filesDir`:
 *   subscriptions.json                          — array of [SubscribedRepository]
 *   repos/<sanitised-id>/manifest.json          — last successfully fetched manifest
 *   repos/<sanitised-id>/versions.json          — bundleId → cached content version
 *   repos/<sanitised-id>/bundles/<bundleId>.json — cached bundle file (JSON array of Workouts)
 *   registry/cache.json                         — last successfully fetched registry index
 *
 * **Manifest schema v2 — bandwidth-aware sync.**
 * The manifest itself contains only metadata + a list of bundle entries
 * `{id, version, url, schemaVersion}`. Workouts live in separate bundle
 * files; the app downloads the small manifest at subscribe / refresh time,
 * compares each bundle's `version` against the local [VersionMap], and
 * re-fetches *only* the bundles whose version changed. Bumping a single
 * workout means bumping that one bundle; unrelated bundles stay cached.
 *
 * Two reactive signals drive [WorkoutLibrary]:
 *   - [subscribed] changes when the user subscribes / unsubscribes a repo.
 *   - [cacheTick] increments after a successful bundle sync, so the library
 *     rebuilds even when the subscription list itself is unchanged (e.g. a
 *     refresh updated some bundle files in place).
 *
 * The community registry URL is hardcoded (BuildConfig.REGISTRY_URL). When
 * fetching it fails (404, DNS, parse error) the app stays usable — Browse
 * shows an empty state and the user can still add repositories by URL or QR.
 */
class RepositoryManager(
    private val context: Context,
    private val registryUrl: String,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    private val baseDir: File get() = context.filesDir
    private val subscriptionsFile: File get() = File(baseDir, "subscriptions.json")
    private val reposDir: File get() = File(baseDir, "repos").apply { mkdirs() }
    private val registryDir: File get() = File(baseDir, "registry").apply { mkdirs() }
    private val registryCacheFile: File get() = File(registryDir, "cache.json")

    private val _subscribed = MutableStateFlow<List<SubscribedRepository>>(emptyList())
    val subscribed: StateFlow<List<SubscribedRepository>> = _subscribed.asStateFlow()

    /**
     * Bumped whenever the on-disk bundle cache changes (sync, unsubscribe,
     * eviction). Pair this with [subscribed] in a `combine` to rebuild any
     * derived view that reads cached workouts — `subscribed` alone misses
     * the case where the same set of repos got *new* bundle data.
     */
    private val _cacheTick = MutableStateFlow(0)
    val cacheTick: StateFlow<Int> = _cacheTick.asStateFlow()

    private val _registry = MutableStateFlow<RegistryState>(RegistryState.Initial)
    val registry: StateFlow<RegistryState> = _registry.asStateFlow()

    init {
        // Hydrate from disk on construction so the UI has data on first frame.
        _subscribed.value = loadSubscriptionsFromDisk()
        _registry.value = loadRegistryFromDisk()
    }

    // ── Subscriptions ────────────────────────────────────────────────────────

    /**
     * Fetch a repository manifest by URL, persist it, sync any new / changed
     * bundles, and add the repo to the subscription list. Re-subscribing
     * (calling with an already-subscribed URL) refreshes it.
     */
    suspend fun subscribeByUrl(manifestUrl: String): SubscribeResult = withContext(Dispatchers.IO) {
        try {
            val text = HttpFetcher.getString(manifestUrl)
            val manifest = ManifestParser.parseRepositoryManifest(text)
            persistSubscription(manifest, manifestUrl)
            syncBundles(manifest, manifestUrl)
            bumpCacheTick()
            SubscribeResult.Ok(manifest)
        } catch (t: Throwable) {
            Log.w(TAG, "Subscribe failed for $manifestUrl: ${t.message}")
            SubscribeResult.Failure(t.message ?: "Couldn't fetch repository")
        }
    }

    /**
     * Persist a manifest the user already has in hand (e.g. from a deep-link or
     * local file) — no HTTP call for the manifest itself, but bundles still
     * need fetching since the new schema doesn't embed them. Bundle URLs that
     * are relative are unresolvable without a [sourceUrl] base; absolute URLs
     * still work in that case.
     */
    suspend fun subscribeFromManifest(manifest: RepositoryManifest, sourceUrl: String?): SubscribeResult =
        withContext(Dispatchers.IO) {
            try {
                persistSubscription(manifest, sourceUrl)
                syncBundles(manifest, sourceUrl)
                bumpCacheTick()
                SubscribeResult.Ok(manifest)
            } catch (t: Throwable) {
                Log.w(TAG, "Persist failed: ${t.message}")
                SubscribeResult.Failure(t.message ?: "Couldn't save repository")
            }
        }

    suspend fun unsubscribe(id: String) = withContext(Dispatchers.IO) {
        val sanitised = sanitise(id)
        File(reposDir, sanitised).deleteRecursively()
        val updated = _subscribed.value.filterNot { it.id == id }
        _subscribed.value = updated
        writeSubscriptionsFile(updated)
        bumpCacheTick()
    }

    /** Re-fetches all subscribed repos that have a known source URL. */
    suspend fun refreshAll(): RefreshSummary {
        var ok = 0
        var failed = 0
        _subscribed.value.forEach { sub ->
            val url = sub.manifestUrl ?: return@forEach
            val result = subscribeByUrl(url)
            if (result is SubscribeResult.Ok) ok++ else failed++
        }
        return RefreshSummary(refreshed = ok, failed = failed, total = _subscribed.value.size)
    }

    /**
     * All workouts from the locally cached bundles of one subscribed repo.
     * Workouts whose `schemaVersion` exceeds what this build understands are
     * skipped — keeps the list stable when a repo ships a future-format
     * workout in an otherwise-readable bundle.
     */
    fun loadWorkoutsForSubscription(id: String): List<Workout> {
        val bundlesDir = File(reposDir, sanitise(id) + "/bundles")
        if (!bundlesDir.exists()) return emptyList()
        val files = bundlesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return emptyList()
        val out = mutableListOf<Workout>()
        for (file in files) {
            try {
                val bundle = ManifestParser.parseWorkoutBundle(file.readText(Charsets.UTF_8))
                for (w in bundle) {
                    if (w.schemaVersion <= WORKOUT_SCHEMA_VERSION_MAX_SUPPORTED) {
                        out += w
                    } else {
                        Log.w(TAG, "Skipping workout '${w.id}' from ${file.name} — schema v${w.schemaVersion} > supported")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Corrupt bundle ${file.name}: ${t.message}")
            }
        }
        return out
    }

    fun isSubscribed(id: String): Boolean = _subscribed.value.any { it.id == id }

    // ── Registry ─────────────────────────────────────────────────────────────

    /**
     * Fetches the community registry. Failure leaves the previous cache (if any)
     * in place — the UI can decide whether to surface the error.
     */
    suspend fun refreshRegistry(): RegistryState {
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
        return state
    }

    // ── Bundle sync ──────────────────────────────────────────────────────────

    /**
     * Walks the manifest's bundle list and downloads anything missing or
     * out-of-date. Best-effort: a single bundle's failure leaves the rest of
     * the sync to proceed and the bundle's existing cache (if any) untouched.
     */
    private suspend fun syncBundles(manifest: RepositoryManifest, manifestUrl: String?) {
        val sanitisedRepo = sanitise(manifest.id)
        val repoDir = File(reposDir, sanitisedRepo).apply { mkdirs() }
        val bundlesDir = File(repoDir, "bundles").apply { mkdirs() }
        val versionsFile = File(repoDir, "versions.json")
        val versions = loadVersions(versionsFile).toMutableMap()
        val keep = mutableSetOf<String>()

        for (entry in manifest.bundles) {
            // Bundle format newer than this build can parse — skip without
            // touching any existing cache. Lets new repos ship a v2 bundle
            // alongside a v1 fallback for older clients (when we eventually
            // need v2).
            if (entry.schemaVersion > BUNDLE_SCHEMA_VERSION_MAX_SUPPORTED) {
                Log.w(TAG, "Skipping bundle '${entry.id}' — schema v${entry.schemaVersion} > supported")
                continue
            }
            keep += entry.id
            val cachedFile = File(bundlesDir, sanitise(entry.id) + ".json")
            if (versions[entry.id] == entry.version && cachedFile.exists()) {
                continue // already at the requested version
            }
            val resolved = resolveBundleUrl(entry.url, manifestUrl)
            if (resolved == null) {
                Log.w(TAG, "Bundle '${entry.id}' has relative URL '${entry.url}' but no base — skip")
                continue
            }
            try {
                val bundleText = HttpFetcher.getString(resolved)
                // Parse-validate before committing so a corrupt remote doesn't
                // poison the cache. The result is discarded; loadWorkoutsForSubscription
                // re-parses on demand.
                ManifestParser.parseWorkoutBundle(bundleText)
                writeAtomically(cachedFile, bundleText)
                versions[entry.id] = entry.version
            } catch (t: Throwable) {
                Log.w(TAG, "Bundle '${entry.id}' fetch/parse failed: ${t.message}")
            }
        }

        // Evict bundles the manifest no longer lists. Keeps disk usage bounded
        // even when a maintainer renames or removes bundles.
        versions.keys.toList().forEach { id ->
            if (id !in keep) {
                File(bundlesDir, sanitise(id) + ".json").delete()
                versions.remove(id)
            }
        }

        saveVersions(versionsFile, versions)
    }

    /**
     * Resolve a bundle entry's URL. Absolute (`https://`) URLs are returned
     * as-is; relative URLs resolve against the manifest URL's directory
     * (`https://host.example/path/manifest.json` + `bundles/foo.json`
     * → `https://host.example/path/bundles/foo.json`). Returns null when a
     * relative URL has no base to resolve against.
     */
    private fun resolveBundleUrl(entryUrl: String, manifestUrl: String?): String? {
        if (entryUrl.startsWith("https://", ignoreCase = true) ||
            entryUrl.startsWith("http://", ignoreCase = true)) {
            return entryUrl
        }
        if (manifestUrl.isNullOrBlank()) return null
        val baseEnd = manifestUrl.lastIndexOf('/')
        if (baseEnd < 0) return null
        return manifestUrl.substring(0, baseEnd + 1) + entryUrl
    }

    private fun loadVersions(file: File): Map<String, Int> {
        if (!file.exists()) return emptyMap()
        return try {
            json.decodeFromString(VersionMap.serializer(), file.readText(Charsets.UTF_8)).versions
        } catch (t: Throwable) {
            Log.w(TAG, "Versions cache corrupt for ${file.parentFile?.name}: ${t.message}")
            emptyMap()
        }
    }

    private fun saveVersions(file: File, versions: Map<String, Int>) {
        val text = json.encodeToString(VersionMap.serializer(), VersionMap(versions))
        writeAtomically(file, text)
    }

    private fun bumpCacheTick() { _cacheTick.update { it + 1 } }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun persistSubscription(manifest: RepositoryManifest, sourceUrl: String?) {
        val dir = File(reposDir, sanitise(manifest.id)).apply { mkdirs() }
        val manifestText = ManifestParser.encodeRepositoryManifest(manifest)
        writeAtomically(File(dir, "manifest.json"), manifestText)
        val updated = (_subscribed.value.filterNot { it.id == manifest.id } +
            SubscribedRepository(
                id = manifest.id,
                name = manifest.name,
                description = manifest.description,
                manifestUrl = sourceUrl,
                lastFetchedEpoch = System.currentTimeMillis(),
                workoutCount = manifest.bundles.size,
            )).sortedBy { it.name.lowercase() }
        _subscribed.value = updated
        writeSubscriptionsFile(updated)
    }

    private fun loadSubscriptionsFromDisk(): List<SubscribedRepository> {
        val file = subscriptionsFile
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(SubscriptionListEnvelope.serializer(), file.readText(Charsets.UTF_8))
                .subscriptions
                .sortedBy { it.name.lowercase() }
        } catch (t: Throwable) {
            Log.w(TAG, "Couldn't load subscriptions: ${t.message}")
            emptyList()
        }
    }

    private fun writeSubscriptionsFile(subs: List<SubscribedRepository>) {
        val envelope = SubscriptionListEnvelope(subscriptions = subs)
        val text = json.encodeToString(SubscriptionListEnvelope.serializer(), envelope)
        writeAtomically(subscriptionsFile, text)
    }

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

    /**
     * tmp + rename atomic write so a partial write or process kill doesn't
     * leave a corrupt destination. FUSE-mounted external dirs sometimes
     * refuse cross-node rename — direct write fallback covers that.
     */
    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (tmp.renameTo(file)) return
            file.writeText(text, Charsets.UTF_8)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun sanitise(id: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "repo" }

    @Serializable
    private data class VersionMap(val versions: Map<String, Int> = emptyMap())

    companion object {
        private const val TAG = "RepositoryManager"
    }
}

@Serializable
private data class SubscriptionListEnvelope(
    val subscriptions: List<SubscribedRepository> = emptyList(),
)

@Serializable
data class SubscribedRepository(
    val id: String,
    val name: String,
    val description: String? = null,
    val manifestUrl: String? = null,
    val lastFetchedEpoch: Long = 0,
    val workoutCount: Int = 0,
)

sealed class SubscribeResult {
    data class Ok(val manifest: RepositoryManifest) : SubscribeResult()
    data class Failure(val message: String) : SubscribeResult()
}

data class RefreshSummary(val refreshed: Int, val failed: Int, val total: Int)

sealed class RegistryState {
    data object Initial : RegistryState()
    data class Loaded(val index: RegistryIndex, val fromCache: Boolean) : RegistryState()
    data class Failed(val error: String, val cachedIndex: RegistryIndex?) : RegistryState()
}
