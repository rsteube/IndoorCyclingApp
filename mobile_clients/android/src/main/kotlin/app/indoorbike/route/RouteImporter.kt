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
package app.indoorbike.route

import android.content.Context
import android.net.Uri
import app.indoorbike.library.HttpFetcher
import app.indoorbike.shared.repo.RouteAttribution
import app.indoorbike.shared.repo.RouteEntry
import app.indoorbike.shared.route.GpxParser
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.shared.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Downloads, validates and persists route GPX files, the route-side twin of
 * [app.indoorbike.library.WorkoutImporter].
 *
 * Persistence layout under `filesDir`:
 *
 *     user_routes/<sanitised-id>.gpx
 *     user_routes/<sanitised-id>.meta.json
 *
 * The GPX is stored raw rather than as parsed points. It is the thing the
 * publisher signed, the thing a rider could hand to a head unit, and the thing
 * [GpxParser] is the single interpreter of; keeping it verbatim means the file
 * on disk stays re-readable if the parser or the derived profiles ever change.
 *
 * **Why a local copy at all.** [RouteCatalog] parses at startup precisely so a
 * ride never waits on IO, and a ride certainly cannot wait on a network. A
 * route the rider has added has to be on the device, complete, before they tap
 * Start. That is the same explicit-add model workouts use: what is added is a
 * static copy, and it does not follow the upstream repository afterwards.
 *
 * The sidecar holds provenance the GPX cannot: which repository it came from
 * and who the data must be credited to. Route data is usually licensed
 * ([RouteAttribution] explains why), so unlike the workout sidecar this one
 * carries information the app is obliged to keep showing.
 */
class RouteImporter(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val userRoutesDir: File
        get() = File(context.filesDir, "user_routes").apply { mkdirs() }

    /** True when this route id already exists locally, so a browser can show it as added. */
    fun hasRoute(routeId: String): Boolean = gpxFile(routeId).exists()

    /**
     * Download the GPX behind [entry] and add it to the rider's routes.
     *
     * Ordering matters here. The download lands on a staging file, is checked
     * against the listing, and is parsed before anything replaces an existing
     * route: a truncated transfer or a corrupt file must never be able to
     * destroy a route the rider already had. Only a GPX that parsed into a
     * rideable track gets renamed into place.
     */
    suspend fun addFromRepo(
        entry: RouteEntry,
        repoId: String? = null,
        repoName: String? = null,
        addedEpoch: Long = System.currentTimeMillis(),
    ): RouteImportResult = withContext(Dispatchers.IO) {
        if (!entry.isUsable) return@withContext RouteImportResult.Failure("That route listing is incomplete.")
        val staging = File(userRoutesDir, sanitise(entry.id) + ".gpx.new")
        try {
            val download = HttpFetcher.downloadTo(entry.gpxUrl, staging)

            // A published hash is an integrity check, not a security boundary:
            // it catches a truncated transfer or a file swapped out under a
            // stale listing. Refusing the mismatch is still right, because the
            // rider asked for the route the listing described.
            val expected = entry.gpxSha256?.lowercase()
            if (expected != null && expected != download.sha256) {
                return@withContext RouteImportResult.Failure(
                    "That route file does not match what the repository listed. It may have changed since."
                )
            }

            val track = parseFile(staging, entry.id, entry.name)
                ?: return@withContext RouteImportResult.Failure(
                    "That file could not be read as a rideable route."
                )

            if (!staging.renameTo(gpxFile(entry.id))) {
                staging.copyTo(gpxFile(entry.id), overwrite = true)
            }
            writeMeta(
                entry.id,
                RouteImportMeta(
                    name = entry.name,
                    description = entry.description,
                    place = entry.place,
                    tags = entry.tags,
                    attribution = entry.attribution,
                    sourceRepoId = repoId,
                    sourceRepoName = repoName,
                    addedEpoch = addedEpoch,
                ),
            )
            RouteImportResult.Added(track)
        } catch (t: Throwable) {
            Log.w(TAG, "Route add failed for ${entry.id}: ${t.message}")
            RouteImportResult.Failure(t.message ?: "Couldn't add that route")
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    /**
     * Add a GPX the rider picked from their own device: an export from a head
     * unit, or a file someone sent them.
     *
     * The id is derived from the file name because a bare GPX carries no
     * stable identifier of its own, and re-importing the same file should
     * update the route rather than accumulate copies of it.
     */
    suspend fun importFromUri(uri: Uri): RouteImportResult = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext RouteImportResult.Failure("Couldn't open that file.")
            if (bytes.size > HttpFetcher.MAX_ROUTE_BYTES) {
                return@withContext RouteImportResult.Failure("That route file is too large.")
            }
            val name = displayName(uri) ?: "route"
            val id = sanitise(name.removeSuffix(".gpx").removeSuffix(".GPX"))
            val xml = String(bytes, Charsets.UTF_8)
            val track = GpxParser.parseGpx(xml = xml, id = id, fallbackName = id)
                ?: return@withContext RouteImportResult.Failure(
                    "That file could not be read as a rideable route."
                )
            gpxFile(id).writeText(xml, Charsets.UTF_8)
            writeMeta(
                id,
                RouteImportMeta(name = track.name, addedEpoch = System.currentTimeMillis()),
            )
            RouteImportResult.Added(track)
        } catch (t: Throwable) {
            Log.w(TAG, "Route file import failed: ${t.message}")
            RouteImportResult.Failure(t.message ?: "Couldn't read that file")
        }
    }

    /**
     * Every locally added route, parsed. Called once from [RouteCatalog] on
     * startup; one unreadable file costs the rider that route and not the rest
     * of the catalogue.
     */
    fun loadUserRoutes(): List<RouteTrack> {
        val files = userRoutesDir.listFiles { f -> f.isFile && f.name.endsWith(GPX_SUFFIX) }
            ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { file ->
            val id = file.name.removeSuffix(GPX_SUFFIX)
            parseFile(file, id, loadMeta(id)?.name ?: id)
        }
    }

    /** Provenance for a locally added route, or null when none was recorded. */
    fun loadMeta(routeId: String): RouteImportMeta? {
        val file = File(userRoutesDir, sanitise(routeId) + ".meta.json")
        if (!file.exists()) return null
        return try {
            json.decodeFromString(RouteImportMeta.serializer(), file.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            Log.w(TAG, "Corrupt route meta for $routeId: ${t.message}")
            null
        }
    }

    /** Removes a locally added route and its sidecar. False when it was already gone. */
    suspend fun deleteUserRoute(routeId: String): Boolean = withContext(Dispatchers.IO) {
        val sanitised = sanitise(routeId)
        val gpx = File(userRoutesDir, sanitised + GPX_SUFFIX)
        val meta = File(userRoutesDir, "$sanitised.meta.json")
        val ok = gpx.exists() && gpx.delete()
        // Orphaning a sidecar is untidy but harmless, since loadMeta tolerates
        // a missing one and nothing reads it without the GPX.
        if (meta.exists()) meta.delete()
        ok
    }

    private fun gpxFile(routeId: String): File = File(userRoutesDir, sanitise(routeId) + GPX_SUFFIX)

    private fun parseFile(file: File, id: String, fallbackName: String): RouteTrack? = try {
        GpxParser.parseGpx(
            xml = file.readText(Charsets.UTF_8),
            id = id,
            fallbackName = fallbackName,
        )
    } catch (t: Throwable) {
        Log.w(TAG, "Couldn't read route ${file.name}: ${t.message}")
        null
    }

    private fun writeMeta(routeId: String, meta: RouteImportMeta) {
        val file = File(userRoutesDir, sanitise(routeId) + ".meta.json")
        val text = json.encodeToString(RouteImportMeta.serializer(), meta)
        // Atomic write, the same way WorkoutImporter does it: a partial write
        // leaves the previous contents rather than a half file.
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(text, Charsets.UTF_8)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: uri.lastPathSegment
    } catch (t: Throwable) {
        uri.lastPathSegment
    }

    private fun sanitise(id: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "route" }

    companion object {
        private const val TAG = "RouteImporter"
        private const val GPX_SUFFIX = ".gpx"
    }
}

/**
 * Provenance for a locally added route.
 *
 * Deliberately not a cached copy of the [RouteEntry] it came from. The listing
 * numbers are a preview and the app recomputes them from the GPX, so keeping
 * them here would create a second, staler answer to questions the track can
 * already answer. What survives is only what the GPX cannot say for itself.
 */
@Serializable
data class RouteImportMeta(
    val name: String? = null,
    val description: String? = null,
    val place: String? = null,
    val tags: List<String> = emptyList(),
    /** Kept and displayed for as long as the route is installed, because the licence says so. */
    val attribution: RouteAttribution? = null,
    val sourceRepoId: String? = null,
    val sourceRepoName: String? = null,
    val addedEpoch: Long? = null,
)

sealed class RouteImportResult {
    data class Added(val track: RouteTrack) : RouteImportResult()
    data class Failure(val message: String) : RouteImportResult()
}
