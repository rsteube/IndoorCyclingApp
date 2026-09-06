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

import kotlinx.serialization.Serializable

/**
 * A pointer to one route bundle file, the route side of [WorkoutBundleEntry].
 *
 * **Why routes need a listing separate from their data.** A workout is small
 * enough that the bundle *is* the listing: a few dozen segments of JSON and
 * the app has the whole thing. A GPX is three orders of magnitude bigger.
 * Downloading fifty of them to draw fifty cards would cost tens of megabytes
 * for a browse the rider might abandon, so route metadata and route geometry
 * live in different files: a [RouteEntry] carries every number the picker
 * needs, and [RouteEntry.gpxUrl] points at the geometry, fetched only when a
 * rider actually adds the route.
 *
 * **Why this does not bump the manifest version.** [RepositoryManifest] stays
 * at schema v2 and gains an optional `routeBundles` field. Every already
 * shipped build parses manifests with `ignoreUnknownKeys`, so an old app sees
 * a route-carrying manifest, ignores the routes, and still loads the workouts.
 * Bumping the manifest to v3 would instead trip the "update the app" guard in
 * [ManifestParser.parseRepositoryManifest] and cost that repository its
 * workouts on every older install. Routes therefore version *independently*,
 * through [RouteEntry.schemaVersion] and [schemaVersion] here, the same way
 * workout bundles already do: anything newer than this build understands is
 * skipped, and the rest of the repository still works.
 */
@Serializable
data class RouteBundleEntry(
    /** Stable id, unique within the repository. */
    val id: String,
    val name: String? = null,
    /**
     * Format version of the bundle file this points at. Bundles above
     * [ROUTE_BUNDLE_SCHEMA_VERSION_MAX_SUPPORTED] are skipped *without being
     * downloaded*, which is the whole reason the version lives out here on the
     * pointer rather than inside the file.
     */
    val schemaVersion: Int = 1,
    /** Bumped by the maintainer when the bundle content changes. */
    val version: Int = 1,
    /** Absolute `https://` URL, or a path relative to the manifest. */
    val url: String,
)

/**
 * Where a route came from and what may be done with it.
 *
 * Routes differ from workouts here. A structured workout is a handful of
 * numbers somebody typed, but route geometry is usually derived from a
 * licensed database: OpenStreetMap under ODbL, a national mapping agency, a
 * cycling network publishing its tracks. Those licences want credit, so the
 * fields that give it are part of the format rather than something a
 * maintainer has to remember to write into a description.
 */
@Serializable
data class RouteAttribution(
    /** Human-readable credit, for example "OpenStreetMap contributors". */
    val source: String? = null,
    /** Where the original data lives. */
    val url: String? = null,
    /** Licence name or SPDX-style id, for example "ODbL-1.0", "CC-BY-4.0", "CC0-1.0". */
    val licence: String? = null,
    /** True when the published track was resampled, trimmed, or had elevation added. */
    val modified: Boolean = false,
)

/**
 * One route as it appears in a picker, before its GPX exists on the device.
 *
 * Everything here is derived from the GPX by whoever published it, and the app
 * recomputes the same numbers from the file once the route is added. The
 * listing values are therefore a *preview*: good enough to choose by, never
 * the source of truth for a ride.
 *
 * Three fields carry more than their size suggests:
 *
 *  - [gradientMeters] is how far the road runs at each steepness, bucketed by
 *    [RouteGradientBuckets]. Fourteen numbers, and they are what makes an
 *    honest duration estimate possible without the GPX: time on a route is not
 *    a function of distance and total climbing, because the physics is
 *    non-linear in gradient. Five hundred metres of ascent taken as one steady
 *    5 % drag and the same five hundred taken as twenty 12 % ramps are
 *    different rides, and a histogram is the smallest thing that tells them
 *    apart. See [app.indoorbike.shared.route.RouteEstimate].
 *  - [profile] is the elevation resampled to a fixed, small number of points,
 *    so a card can draw a real profile rather than a placeholder.
 *  - [shape] is the track simplified and encoded as a polyline, so a card can
 *    draw the shape of the road rather than a generic thumbnail.
 *
 * The last two are drawing aids and are deliberately far too coarse to ride.
 */
@Serializable
data class RouteEntry(
    val schemaVersion: Int = ROUTE_SCHEMA_VERSION_CURRENT,
    /** Stable id, unique within the repository. The dedup key and the file name. */
    val id: String,
    val name: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    /** Free-text location, for example "Provence, France". Shown under the name. */
    val place: String? = null,
    /** Bumped by the maintainer when the GPX or the metadata changes. */
    val version: Int = 1,

    // Geometry, fetched on demand.
    /** Absolute `https://` URL, or a path relative to the bundle file. */
    val gpxUrl: String,
    /** Size of the GPX, so the app can warn before a large download. */
    val gpxBytes: Long? = null,
    /** Lowercase hex SHA-256 of the GPX, checked after download when present. */
    val gpxSha256: String? = null,

    // Numbers the picker shows.
    val distanceMeters: Double,
    val ascentMeters: Double = 0.0,
    val descentMeters: Double = 0.0,
    val minElevationMeters: Double = 0.0,
    val maxElevationMeters: Double = 0.0,
    /** Steepest sustained gradient as a percentage, measured on the smoothed profile. */
    val maxGradientPercent: Double = 0.0,
    /** True when the finish is back at the start, so the route can be lapped. */
    val loop: Boolean = false,

    /**
     * Metres of road in each gradient bucket of [RouteGradientBuckets].
     * Fourteen entries; any other length is treated as absent and the estimate
     * falls back to distance alone.
     */
    val gradientMeters: List<Double> = emptyList(),

    // Drawing aids.
    /** Elevation in metres, evenly spaced along the route. See [ROUTE_PROFILE_SAMPLES]. */
    val profile: List<Double> = emptyList(),
    /** Simplified track as an encoded polyline, precision 5. See [app.indoorbike.shared.route.RoutePolyline]. */
    val shape: String? = null,
    /** `[minLat, minLon, maxLat, maxLon]`. */
    val bounds: List<Double>? = null,

    val attribution: RouteAttribution? = null,
) {
    /** False when this build is too old to understand the entry. */
    val isSupported: Boolean get() = schemaVersion <= ROUTE_SCHEMA_VERSION_MAX_SUPPORTED

    /**
     * False for an entry that could never be ridden, so a malformed one is
     * dropped at parse time rather than failing later in front of the rider.
     */
    val isUsable: Boolean
        get() = id.isNotBlank() && name.isNotBlank() &&
            gpxUrl.isNotBlank() && distanceMeters > 0.0

    /** True when [gradientMeters] is present and the right length to estimate from. */
    val hasGradientHistogram: Boolean
        get() = gradientMeters.size == RouteGradientBuckets.COUNT
}

/** Current version of the [RouteEntry] format that this build writes. */
const val ROUTE_SCHEMA_VERSION_CURRENT = 1

/**
 * Highest [RouteEntry] version this build can read. Entries above it are
 * skipped individually, so one route published against a newer schema costs
 * the rider that route and nothing else.
 */
const val ROUTE_SCHEMA_VERSION_MAX_SUPPORTED = 1

/** Highest route *bundle file* version this build can read. */
const val ROUTE_BUNDLE_SCHEMA_VERSION_MAX_SUPPORTED = 1

/** How many elevation samples [RouteEntry.profile] carries. */
const val ROUTE_PROFILE_SAMPLES = 64

/** Target point count for [RouteEntry.shape] after simplification. */
const val ROUTE_SHAPE_POINTS = 160

/**
 * The gradient buckets [RouteEntry.gradientMeters] is measured against.
 *
 * The edges are part of the schema rather than per-entry data. Pinning them
 * means a histogram is fourteen bare numbers instead of a self-describing
 * structure, and means every generator produces something the app can compare.
 * Changing them is a [ROUTE_SCHEMA_VERSION_CURRENT] bump.
 *
 * They are spaced the way gradients actually matter to a rider: finely between
 * 0 and 12 %, where a percentage point is the difference between spinning and
 * grinding, and coarsely outside it, where the road is either a descent, which
 * is speed-capped anyway, or a wall.
 */
object RouteGradientBuckets {
    /** Bucket boundaries in percent, ascending. Bucket `i` covers `[EDGES[i], EDGES[i + 1])`. */
    val EDGES_PERCENT = doubleArrayOf(
        -100.0, -10.0, -7.0, -4.0, -2.0, 0.0, 2.0, 4.0,
        6.0, 8.0, 10.0, 12.0, 15.0, 20.0, 100.0,
    )

    /**
     * The gradient each bucket is estimated at: the midpoint, except for the
     * two open-ended buckets, which use a plausible real-road value rather
     * than the meaningless midpoint of a 90-point-wide range.
     */
    val CENTRE_PERCENT = doubleArrayOf(
        -14.0, -8.5, -5.5, -3.0, -1.0, 1.0, 3.0, 5.0,
        7.0, 9.0, 11.0, 13.5, 17.5, 24.0,
    )

    val COUNT: Int get() = CENTRE_PERCENT.size

    /** Index of the bucket [gradientPercent] falls in. */
    fun indexOf(gradientPercent: Double): Int {
        for (i in 0 until COUNT) {
            if (gradientPercent < EDGES_PERCENT[i + 1]) return i
        }
        return COUNT - 1
    }
}
