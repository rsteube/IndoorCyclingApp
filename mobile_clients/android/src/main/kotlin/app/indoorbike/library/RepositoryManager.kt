package app.indoorbike.library

import android.content.Context
import app.indoorbike.shared.Workout
import app.indoorbike.shared.repo.ManifestParser
import app.indoorbike.shared.repo.RegistryEntry
import app.indoorbike.shared.repo.RegistryIndex
import app.indoorbike.shared.repo.RepositoryManifest
import app.indoorbike.shared.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Owns the on-disk cache of subscribed repositories and the cached community
 * registry. All subscribed repos appear via [subscribed] as a `StateFlow` so
 * the [WorkoutLibrary] aggregator can rebuild its workout list on changes.
 *
 * On-disk layout under `filesDir`:
 *   subscriptions.json                  — array of [Subscription] (id, url, fetched-at)
 *   repos/<sanitised-id>/manifest.json  — last successfully fetched manifest
 *   registry/cache.json                 — last successfully fetched registry index
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

    private val _registry = MutableStateFlow<RegistryState>(RegistryState.Initial)
    val registry: StateFlow<RegistryState> = _registry.asStateFlow()

    init {
        // Hydrate from disk on construction so the UI has data on first frame.
        _subscribed.value = loadSubscriptionsFromDisk()
        _registry.value = loadRegistryFromDisk()
    }

    // ── Subscriptions ────────────────────────────────────────────────────────

    /**
     * Fetch a repository manifest by URL, persist it, and add it to the
     * subscription list. If the URL is already subscribed, this refreshes it.
     */
    suspend fun subscribeByUrl(manifestUrl: String): SubscribeResult {
        return try {
            val text = HttpFetcher.getString(manifestUrl)
            val manifest = ManifestParser.parseRepositoryManifest(text)
            persistSubscription(manifest, manifestUrl)
            SubscribeResult.Ok(manifest)
        } catch (t: Throwable) {
            Log.w(TAG, "Subscribe failed for $manifestUrl: ${t.message}")
            SubscribeResult.Failure(t.message ?: "Couldn't fetch repository")
        }
    }

    /**
     * Persist a manifest the user already has in hand (e.g. from a deep-link or
     * local file) — no HTTP call. Used when the import flow hands us a manifest
     * directly. We still record the source URL so future refreshes work.
     */
    fun subscribeFromManifest(manifest: RepositoryManifest, sourceUrl: String?): SubscribeResult {
        return try {
            persistSubscription(manifest, sourceUrl)
            SubscribeResult.Ok(manifest)
        } catch (t: Throwable) {
            Log.w(TAG, "Persist failed: ${t.message}")
            SubscribeResult.Failure(t.message ?: "Couldn't save repository")
        }
    }

    fun unsubscribe(id: String) {
        val sanitised = sanitise(id)
        File(reposDir, sanitised).deleteRecursively()
        val updated = _subscribed.value.filterNot { it.id == id }
        _subscribed.value = updated
        writeSubscriptionsFile(updated)
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

    fun loadWorkoutsForSubscription(id: String): List<Workout> {
        val manifest = readManifestFromDisk(id) ?: return emptyList()
        return manifest.workouts
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

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun persistSubscription(manifest: RepositoryManifest, sourceUrl: String?) {
        val dir = File(reposDir, sanitise(manifest.id)).apply { mkdirs() }
        val manifestText = ManifestParser.encodeRepositoryManifest(manifest)
        File(dir, "manifest.json").writeText(manifestText, Charsets.UTF_8)
        val updated = (_subscribed.value.filterNot { it.id == manifest.id } +
            SubscribedRepository(
                id = manifest.id,
                name = manifest.name,
                description = manifest.description,
                manifestUrl = sourceUrl,
                lastFetchedEpoch = System.currentTimeMillis(),
                workoutCount = manifest.workouts.size,
            )).sortedBy { it.name.lowercase() }
        _subscribed.value = updated
        writeSubscriptionsFile(updated)
    }

    private fun readManifestFromDisk(id: String): RepositoryManifest? {
        val file = File(reposDir, sanitise(id) + "/manifest.json")
        if (!file.exists()) return null
        return try {
            ManifestParser.parseRepositoryManifest(file.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            Log.w(TAG, "Corrupt manifest for $id: ${t.message}")
            null
        }
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
        val tmp = File(subscriptionsFile.parentFile, subscriptionsFile.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(subscriptionsFile)) {
            subscriptionsFile.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
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

    private fun sanitise(id: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "repo" }

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
