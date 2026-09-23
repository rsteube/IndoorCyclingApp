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
package app.indoorbike.route

import android.content.Context
import app.indoorbike.shared.route.GpxParser
import app.indoorbike.shared.route.RouteTrack
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
import kotlinx.coroutines.withContext

/**
 * Every route the app can simulate, and the one place that turns a GPX file
 * into a rideable [RouteTrack].
 *
 * Two sources, merged: the GPX files bundled under `assets/routes/`, and the
 * ones the rider added, which [RouteImporter] keeps under
 * `filesDir/user_routes/`. An imported route wins a clash of ids, because a
 * rider who went and added a route meant to have that one.
 *
 * Parsing happens once, at startup, on IO. A GPX is a few thousand points and
 * the derived elevation and gradient profiles are built with it, which is far
 * too much work to repeat on a composition or, worse, on a ride tick. Parsed
 * tracks stay in memory: a 100 km route costs on the order of a hundred
 * kilobytes, and the alternative is a rider waiting on a disk read between
 * tapping Start and the first pedal stroke.
 */
class RouteCatalog(
    private val context: Context,
    private val importer: RouteImporter = RouteImporter(context),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reloadMutex = Mutex()

    private val _routes = MutableStateFlow<List<RouteTrack>>(emptyList())
    val routes: StateFlow<List<RouteTrack>> = _routes.asStateFlow()

    /**
     * Distinguishes "still reading the assets" from "read them and found
     * nothing", so the rides list can show a placeholder rather than an empty
     * state that turns out to be wrong a moment later.
     */
    private val _initialised = MutableStateFlow(false)
    val initialised: StateFlow<Boolean> = _initialised.asStateFlow()

    /**
     * Provenance by route id, kept beside [routes] rather than folded into
     * [RouteTrack]. A track is the road; where the file came from is a fact
     * about the install, and the simulation has no business carrying it.
     */
    private val _sources = MutableStateFlow<Map<String, RouteSource>>(emptyMap())

    fun start() {
        scope.launch {
            reload()
            _initialised.value = true
        }
    }

    fun dispose() {
        scope.cancel()
    }

    /** The route with this id, or null if it is no longer available. */
    fun find(routeId: String): RouteTrack? = _routes.value.find { it.id == routeId }

    /**
     * Where a route came from, for the attribution a licensed route has to
     * keep showing. Unknown ids read as [RouteSource.Bundled] rather than
     * null: a caller asking about a route that is gone wants a label to draw,
     * not a branch to handle.
     */
    fun sourceOf(routeId: String): RouteSource = _sources.value[routeId] ?: RouteSource.Bundled

    /**
     * [sourceOf] for every route, as it changes. A route browser reads which
     * of a repository's routes are added from here, by
     * [RouteSource.Imported.sourceEntryId]: a repository's route can be kept
     * under another id when its own was taken (see RouteImporter), and a
     * bundled route or another repository's with the same id is not this one.
     */
    val sources: StateFlow<Map<String, RouteSource>> get() = _sources

    /**
     * Re-read both sources. Called after a route is added or removed, so the
     * rides list reflects it without waiting for the next app start.
     */
    fun refresh() {
        scope.launch { reload() }
    }

    /**
     * Route ids to display names, for [app.indoorbike.shared.workout.resolveRideWorkout]
     * so a recovered simulated ride shows the road's name rather than its id.
     */
    fun routeNames(): Map<String, String> = _routes.value.associate { it.id to it.name }

    private suspend fun reload() = reloadMutex.withLock {
        val (loaded, sources) = withContext(Dispatchers.IO) {
            val bundled = loadBundled()
            val imported = importer.loadUserRoutes()

            val sources = HashMap<String, RouteSource>(bundled.size + imported.size)
            for (track in bundled) sources[track.id] = RouteSource.Bundled
            for (track in imported) {
                val meta = importer.loadMeta(track.id)
                sources[track.id] = RouteSource.Imported(
                    sourceRepoId = meta?.sourceRepoId,
                    sourceRepoName = meta?.sourceRepoName,
                    attribution = meta?.attribution,
                    sourceEntryId = meta?.sourceEntryId ?: track.id,
                )
            }

            // Imported last so it replaces a bundled route of the same id, and
            // distinct-by-id so the rides list can never show one route twice.
            val merged = (bundled + imported)
                .reversed()
                .distinctBy { it.id }
                .sortedBy { it.name.lowercase() }
            merged to sources
        }
        _routes.value = loaded
        _sources.value = sources
        Log.i(TAG, "Loaded ${loaded.size} route(s)")
    }

    private fun loadBundled(): List<RouteTrack> {
        val names = try {
            context.assets.list(ROUTES_DIR)?.filter { it.endsWith(GPX_SUFFIX, ignoreCase = true) }
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't list bundled routes", e)
            return emptyList()
        }
        return names.sorted().mapNotNull { fileName ->
            val id = fileName.removeSuffix(GPX_SUFFIX).removeSuffix(".GPX")
            try {
                val xml = context.assets.open("$ROUTES_DIR/$fileName")
                    .bufferedReader()
                    .use { it.readText() }
                GpxParser.parseGpx(xml = xml, id = id, fallbackName = id).also {
                    if (it == null) Log.w(TAG, "Route $fileName has too few points to ride")
                }
            } catch (e: Exception) {
                // One unreadable file must not cost the rider the rest of the
                // catalogue, the same way one bad workout JSON doesn't.
                Log.w(TAG, "Couldn't read bundled route $fileName", e)
                null
            }
        }
    }

    companion object {
        private const val TAG = "RouteCatalog"

        /** Where the bundled routes are, under the APK's assets. */
        const val ROUTES_DIR = "routes"
        private const val GPX_SUFFIX = ".gpx"
    }
}
