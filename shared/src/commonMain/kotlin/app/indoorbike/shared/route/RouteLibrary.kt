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
package app.indoorbike.shared.route

import app.indoorbike.shared.companion.CompanionCourse
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.repo.RouteAttribution
import app.indoorbike.shared.workout.simulationRouteId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The rules of the rider's route library, shared by both apps: where a route
 * came from, what is kept beside an added one, what it is called on disk, and
 * how the bundled and added routes make one list. Reading and writing the
 * files is each platform's own.
 */

/** Cap on a single route GPX: roughly a 500 km route, and never a file that fills the device. */
const val MAX_ROUTE_BYTES = 12L * 1024 * 1024

/**
 * Where a route in the catalogue came from.
 *
 * It carries [RouteAttribution], which a workout's source does not need. Route
 * geometry is usually derived from a licensed database whose licence asks for
 * credit wherever the data is shown, so the catalogue keeps it attached rather
 * than leaving it in a file nobody reads.
 */
sealed class RouteSource {

    /** Ships with the app. */
    data object Bundled : RouteSource()

    /**
     * A local copy, added from a community repository or from a file the rider
     * picked. [sourceRepoId] and [sourceRepoName] are null for a file import,
     * which has no upstream.
     */
    data class Imported(
        val sourceRepoId: String? = null,
        val sourceRepoName: String? = null,
        val attribution: RouteAttribution? = null,
        /** The id the repository lists it under, which the local id need not be. */
        val sourceEntryId: String? = null,
    ) : RouteSource()

    /** Short label for the route card, matching how workout cards are tagged. */
    val label: String
        get() = when (this) {
            is Bundled -> "Bundled"
            is Imported -> sourceRepoName ?: "Imported"
        }

    /**
     * The credit line to show wherever the route is, or null when there is
     * nothing to credit. Reads as "OpenStreetMap contributors (ODbL-1.0)".
     */
    val creditLine: String?
        get() = (this as? Imported)?.attribution?.creditLine
}

/** "OpenStreetMap contributors (ODbL-1.0)", or whichever half there is, or null. */
val RouteAttribution.creditLine: String?
    get() {
        val source = source?.takeIf { it.isNotBlank() }
        val licence = licence?.takeIf { it.isNotBlank() }
        return when {
            source != null && licence != null -> "$source ($licence)"
            source != null -> source
            licence != null -> licence
            else -> null
        }
    }

/**
 * What is kept beside an added route's GPX. Only what the GPX cannot say for
 * itself: where it came from, its credit, and when it was added.
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
    /**
     * The id the repository gave the route, when it is kept under another:
     * see [localRouteIdFor]. Null for routes added before that, whose local id
     * is the repository's.
     */
    val sourceEntryId: String? = null,
    val addedEpoch: Long? = null,
)

/** How [RouteImportMeta] is read and written: tolerant of fields a newer build adds. */
val routeMetaJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

/** A route id made safe for a file name: 120 characters of letters, digits, `.`, `_` and `-`. */
fun sanitiseRouteId(id: String): String =
    id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "route" }

/**
 * The id a repository's route is kept under. Its own id when that is free (or
 * already this same route, being updated), otherwise one namespaced by the
 * repository, so two repositories' "alpe-d-huez" never overwrite each other
 * and neither replaces a bundled route.
 */
fun localRouteIdFor(
    entryId: String,
    repoId: String?,
    exists: (String) -> Boolean,
    metaFor: (String) -> RouteImportMeta?,
    bundledIds: Set<String>,
): String {
    val plain = sanitiseRouteId(entryId)
    val existing = exists(plain)
    if (existing && repoId != null &&
        metaFor(plain)?.let { it.sourceRepoId == repoId && (it.sourceEntryId ?: plain) == entryId } == true
    ) {
        return plain
    }
    if (!existing && plain !in bundledIds) return plain
    return sanitiseRouteId("${repoId ?: "local"}.$entryId")
}

/** The routes to list and where each came from, from the bundled ones and the rider's own. */
class RouteCatalogContents(
    val routes: List<RouteTrack>,
    val sources: Map<String, RouteSource>,
)

/**
 * One list from the bundled routes and the rider's added ones. An added route
 * wins a clash of ids, because a rider who went and added a route meant to have
 * that one; no route is listed twice; the list is in name order.
 */
fun mergeRouteCatalog(
    bundled: List<RouteTrack>,
    imported: List<RouteTrack>,
    metaFor: (String) -> RouteImportMeta?,
): RouteCatalogContents {
    val sources = HashMap<String, RouteSource>(bundled.size + imported.size)
    for (track in bundled) sources[track.id] = RouteSource.Bundled
    for (track in imported) {
        val meta = metaFor(track.id)
        sources[track.id] = RouteSource.Imported(
            sourceRepoId = meta?.sourceRepoId,
            sourceRepoName = meta?.sourceRepoName,
            attribution = meta?.attribution,
            sourceEntryId = meta?.sourceEntryId ?: track.id,
        )
    }
    val merged = (bundled + imported)
        .reversed()
        .distinctBy { it.id }
        .sortedBy { it.name.lowercase() }
    return RouteCatalogContents(merged, sources)
}

/**
 * The road a finished ride was on, whichever mode put it there.
 *
 * A route ride keeps a pointer: its `workoutId` names a course in the
 * catalogue, which [findRoute] looks up. A companion ride keeps the road
 * itself, because the game it came from is not something the app can go and
 * ask. Null means the ride had no road: a workout or a free ride, a route
 * since deleted, or a companion that did not disclose its course.
 *
 * Not cheap: it decodes a polyline and rebuilds a track, so work it out once
 * per session rather than per frame.
 */
fun rideRoute(session: WorkoutSession, findRoute: (String) -> RouteTrack?): RouteTrack? {
    simulationRouteId(session.workoutId)?.let { return findRoute(it) }
    if (session.routeShape.isBlank()) return null
    return courseTrackOf(
        // Namespaced by row so it cannot collide with a catalogue route id.
        id = "session:" + session.id,
        name = session.workoutName,
        shape = session.routeShape,
        profileMeters = CompanionCourse.decodeProfile(session.routeProfile),
    )
}
