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

import app.indoorbike.shared.repo.RouteEntry
import app.indoorbike.shared.route.GpxParser
import app.indoorbike.shared.route.MAX_ROUTE_BYTES
import app.indoorbike.shared.route.RouteImportMeta
import app.indoorbike.shared.route.RouteSource
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.shared.route.localRouteIdFor
import app.indoorbike.shared.route.mergeRouteCatalog
import app.indoorbike.shared.route.routeMetaJson
import app.indoorbike.shared.route.sanitiseRouteId
import app.indoorbike.shared.util.Log
import app.indoorbike.shared.util.getCurrentEpochMillis
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
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
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager

/** How adding a route went: the route, or why not in words for the rider. */
sealed class RouteImportResult {
    class Added(val track: RouteTrack) : RouteImportResult()
    class Failure(val message: String) : RouteImportResult()
}

/**
 * Every route the rider can ride on this phone: the ones the app ships with
 * and the ones they added. The iOS counterpart of the Android app's
 * `RouteCatalog` and `RouteImporter`, under the same rules (shared in
 * `RouteLibrary.kt`) and the same layout: an added route is its GPX, kept
 * verbatim, and a `.meta.json` beside it, in `user_routes/`.
 */
@OptIn(ExperimentalForeignApi::class)
class IosRouteLibrary(private val scope: CoroutineScope) {
    private val _routes = MutableStateFlow<List<RouteTrack>>(emptyList())
    val routes: StateFlow<List<RouteTrack>> = _routes.asStateFlow()

    private val _sources = MutableStateFlow<Map<String, RouteSource>>(emptyMap())
    val sources: StateFlow<Map<String, RouteSource>> = _sources.asStateFlow()

    /** False until the first load, so "loading" and "none yet" can be told apart. */
    private val _initialised = MutableStateFlow(false)
    val initialised: StateFlow<Boolean> = _initialised.asStateFlow()

    private val reloadLock = Mutex()
    private var bundledIds: Set<String> = emptySet()

    private val userDir: String by lazy {
        IosFiles.path(USER_ROUTES_DIR).also {
            NSFileManager.defaultManager.createDirectoryAtPath(it, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }

    fun start() {
        scope.launch {
            reload()
            _initialised.value = true
        }
    }

    /** Read both sources again, after a route is added or removed. */
    fun refresh() {
        scope.launch { reload() }
    }

    fun find(routeId: String): RouteTrack? = _routes.value.firstOrNull { it.id == routeId }

    fun sourceOf(routeId: String): RouteSource = _sources.value[routeId] ?: RouteSource.Bundled

    /** Route id to name, so a recovered route ride is labelled with its road. */
    fun routeNames(): Map<String, String> = _routes.value.associate { it.id to it.name }

    private suspend fun reload() = reloadLock.withLock {
        val contents = withContext(Dispatchers.IO) {
            val bundled = loadBundled()
            bundledIds = bundled.map { it.id }.toSet()
            mergeRouteCatalog(bundled, loadUserRoutes(), ::loadMeta)
        }
        _routes.value = contents.routes
        _sources.value = contents.sources
        Log.i(TAG, "Loaded ${contents.routes.size} route(s)")
    }

    /** The GPX files shipped in the app's `routes` folder, named by file. */
    private fun loadBundled(): List<RouteTrack> {
        @Suppress("UNCHECKED_CAST")
        val paths = NSBundle.mainBundle.pathsForResourcesOfType("gpx", inDirectory = "routes") as List<String>
        return paths.sorted().mapNotNull { path ->
            val id = path.substringAfterLast('/').removeSuffix(".gpx")
            parse(path, id, id)
        }
    }

    private fun loadUserRoutes(): List<RouteTrack> {
        @Suppress("UNCHECKED_CAST")
        val names = (NSFileManager.defaultManager.contentsOfDirectoryAtPath(userDir, null) as? List<String>).orEmpty()
        return names.filter { it.endsWith(GPX_SUFFIX) }.sorted().mapNotNull { name ->
            val id = name.removeSuffix(GPX_SUFFIX)
            parse(gpxPath(id), id, loadMeta(id)?.name ?: id)
        }
    }

    private fun parse(path: String, id: String, fallbackName: String): RouteTrack? = try {
        IosFiles.readText(path)?.let { GpxParser.parseGpx(xml = it, id = id, fallbackName = fallbackName) }
    } catch (t: Throwable) {
        Log.w(TAG, "Couldn't read route $id: ${t.message}")
        null
    }

    fun loadMeta(routeId: String): RouteImportMeta? {
        val text = IosFiles.readText(metaPath(routeId)) ?: return null
        return try {
            routeMetaJson.decodeFromString(RouteImportMeta.serializer(), text)
        } catch (t: Throwable) {
            Log.w(TAG, "Corrupt route meta for $routeId: ${t.message}")
            null
        }
    }

    private fun gpxPath(routeId: String) = "$userDir/${sanitiseRouteId(routeId)}$GPX_SUFFIX"
    private fun metaPath(routeId: String) = "$userDir/${sanitiseRouteId(routeId)}.meta.json"
    private fun exists(path: String) = NSFileManager.defaultManager.fileExistsAtPath(path)

    private fun writeRoute(id: String, bytes: ByteArray, meta: RouteImportMeta) {
        IosFiles.writeBytes(gpxPath(id), bytes)
        IosFiles.writeText(metaPath(id), routeMetaJson.encodeToString(RouteImportMeta.serializer(), meta))
    }

    /**
     * Download the GPX behind [entry] and add it. Nothing replaces an existing
     * route until the download has matched its listing and parsed into a
     * rideable track, so a truncated transfer can never cost the rider a route
     * they already had.
     */
    suspend fun addFromRepo(entry: RouteEntry, repoId: String?, repoName: String?): RouteImportResult {
        if (!entry.isUsable) return RouteImportResult.Failure("That route listing is incomplete.")
        return try {
            val id = localRouteIdFor(entry.id, repoId, { exists(gpxPath(it)) }, ::loadMeta, bundledIds)
            val bytes = IosHttp.getBytes(entry.gpxUrl, MAX_ROUTE_BYTES)
            // An integrity check, not a security boundary: it catches a
            // truncated transfer or a file swapped under a stale listing.
            val expected = entry.gpxSha256?.lowercase()
            if (expected != null && expected != IosHttp.sha256Hex(bytes)) {
                return RouteImportResult.Failure(
                    "That route file does not match what the repository listed. It may have changed since."
                )
            }
            val track = GpxParser.parseGpx(bytes.decodeToString(), id, entry.name)
                ?: return RouteImportResult.Failure("That file could not be read as a rideable route.")
            withContext(Dispatchers.IO) {
                writeRoute(
                    id, bytes,
                    RouteImportMeta(
                        name = entry.name,
                        description = entry.description,
                        place = entry.place,
                        tags = entry.tags,
                        attribution = entry.attribution,
                        sourceRepoId = repoId,
                        sourceRepoName = repoName,
                        sourceEntryId = entry.id,
                        addedEpoch = getCurrentEpochMillis(),
                    ),
                )
            }
            reload()
            RouteImportResult.Added(track)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Route add failed for ${entry.id}: ${t.message}")
            RouteImportResult.Failure(t.message ?: "Couldn't add that route")
        }
    }

    /**
     * Add a GPX the rider picked in Files. The id comes from the file name,
     * since a bare GPX has none of its own: the same file again changes
     * nothing, and a different file under a name in use is added beside it.
     */
    suspend fun importFile(fileName: String, bytes: ByteArray): RouteImportResult {
        if (bytes.size > MAX_ROUTE_BYTES) return RouteImportResult.Failure("That route file is too large.")
        return try {
            val base = sanitiseRouteId(fileName.removeSuffix(".gpx").removeSuffix(".GPX"))
            var id = base
            var n = 2
            while (true) {
                if (exists(gpxPath(id)) && IosFiles.readText(gpxPath(id)) == bytes.decodeToString()) {
                    parse(gpxPath(id), id, loadMeta(id)?.name ?: id)?.let { return RouteImportResult.Added(it) }
                }
                if (!exists(gpxPath(id)) && id !in bundledIds) break
                id = "$base-${n++}"
            }
            val track = GpxParser.parseGpx(bytes.decodeToString(), id, base)
                ?: return RouteImportResult.Failure("That file could not be read as a rideable route.")
            withContext(Dispatchers.IO) {
                writeRoute(id, bytes, RouteImportMeta(name = track.name, addedEpoch = getCurrentEpochMillis()))
            }
            reload()
            RouteImportResult.Added(track)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Route file import failed: ${t.message}")
            RouteImportResult.Failure(t.message ?: "Couldn't read that file")
        }
    }

    /** Remove an added route and its sidecar. False when it was already gone or is bundled. */
    suspend fun delete(routeId: String): Boolean {
        val removed = withContext(Dispatchers.IO) {
            val existed = exists(gpxPath(routeId))
            IosFiles.delete(gpxPath(routeId))
            IosFiles.delete(metaPath(routeId))
            existed
        }
        reload()
        return removed
    }

    private companion object {
        const val TAG = "RouteLibrary"
        const val USER_ROUTES_DIR = "user_routes"
        const val GPX_SUFFIX = ".gpx"
    }
}
