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

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One point of a route: where it is, how high, and how far along. */
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    /** Metres above sea level. Zero for routes whose GPX carried no elevation. */
    val elevationMeters: Double,
    /** Cumulative ground distance from the route start, in metres. */
    val distanceMeters: Double,
)

/** Lat/lon extent of a route, used to frame it on a map. */
data class RouteBounds(
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
) {
    val centerLatitude: Double get() = (minLatitude + maxLatitude) / 2.0
    val centerLongitude: Double get() = (minLongitude + maxLongitude) / 2.0
}

/**
 * Where the simulated rider is right now: an interpolated point between two
 * recorded ones, plus the direction of travel so a map can face the right way.
 */
data class RoutePosition(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double,
    val distanceMeters: Double,
    /** Compass bearing of travel in degrees, 0 = north. */
    val bearingDegrees: Double,
)

/**
 * A real-world route the rider can simulate: an ordered polyline with
 * elevation, plus the derived numbers a ride asks for several times a second.
 *
 * Two things are precomputed at load time rather than on every tick:
 *
 *  - **Cumulative distance** per point, so "where am I at 12 340 m?" is a
 *    binary search plus one lerp instead of a walk from the start.
 *  - **A resampled gradient profile** on a fixed [GRADIENT_SAMPLE_METERS] grid.
 *    Raw GPX elevation is noisy: consumer GPS altitude wanders by several
 *    metres between points that may be 5 m apart, which differentiates into
 *    gradients of 50 % and more that no road has. Resampling onto an even
 *    grid, smoothing, and then taking a centred difference over
 *    [GRADIENT_WINDOW_METERS] turns that noise back into the road the rider
 *    actually sees. The result is clamped to [MAX_GRADIENT] so a torn or
 *    barometric-spiked GPX can never hand the trainer an unrideable wall.
 *
 * Build one with [routeTrackOf] rather than calling the constructor, so the
 * derived state always comes from the same code path.
 */
class RouteTrack internal constructor(
    val id: String,
    val name: String,
    val points: List<RoutePoint>,
    val bounds: RouteBounds,
    /** Smoothed gradient (rise/run, so 0.064 = 6.4 %) sampled every [GRADIENT_SAMPLE_METERS]. */
    private val gradientProfile: DoubleArray,
    /** Smoothed elevation on the same grid as [gradientProfile]. */
    private val elevationProfile: DoubleArray,
) {
    val totalDistanceMeters: Double = points.lastOrNull()?.distanceMeters ?: 0.0

    val minElevationMeters: Double = elevationProfile.minOrNull() ?: 0.0
    val maxElevationMeters: Double = elevationProfile.maxOrNull() ?: 0.0

    /**
     * Total climbing, measured on the smoothed profile. Summing raw
     * point-to-point rises would count GPS noise as climbing and typically
     * inflates a flat route's ascent by hundreds of metres.
     */
    val totalAscentMeters: Double = run {
        var gain = 0.0
        for (i in 1 until elevationProfile.size) {
            val delta = elevationProfile[i] - elevationProfile[i - 1]
            if (delta > 0) gain += delta
        }
        gain
    }

    val totalDescentMeters: Double = run {
        var loss = 0.0
        for (i in 1 until elevationProfile.size) {
            val delta = elevationProfile[i] - elevationProfile[i - 1]
            if (delta < 0) loss -= delta
        }
        loss
    }

    /** True when the finish is back at the start, so the route could be lapped. */
    val isLoop: Boolean = points.size > 2 &&
        haversineMeters(points.first(), points.last()) <= LOOP_CLOSE_METERS

    val isEmpty: Boolean get() = points.size < 2

    /**
     * Interpolated position at [distanceMeters] along the route. Distances
     * past the end clamp to the finish rather than wrapping, so a ride that
     * overshoots on its final tick still reports a sane place to be.
     */
    fun positionAt(distanceMeters: Double): RoutePosition {
        if (points.isEmpty()) return RoutePosition(0.0, 0.0, 0.0, 0.0, 0.0)
        val d = distanceMeters.coerceIn(0.0, totalDistanceMeters)
        val i = indexBefore(d)
        val a = points[i]
        val b = points.getOrNull(i + 1) ?: a
        val span = b.distanceMeters - a.distanceMeters
        val t = if (span > 0.0) ((d - a.distanceMeters) / span).coerceIn(0.0, 1.0) else 0.0
        return RoutePosition(
            latitude = a.latitude + (b.latitude - a.latitude) * t,
            longitude = a.longitude + (b.longitude - a.longitude) * t,
            // Elevation comes off the smoothed profile, not the raw points: the
            // altitude readout should agree with the gradient the rider is
            // being made to feel.
            elevationMeters = elevationAt(d),
            distanceMeters = d,
            bearingDegrees = bearingDegrees(a, b),
        )
    }

    /** Smoothed gradient as a fraction (0.064 = 6.4 %) at [distanceMeters]. */
    fun gradientAt(distanceMeters: Double): Double = sampleProfile(gradientProfile, distanceMeters)

    /** Smoothed elevation in metres at [distanceMeters]. */
    fun elevationAt(distanceMeters: Double): Double = sampleProfile(elevationProfile, distanceMeters)

    /**
     * The elevation profile resampled to [count] evenly spaced points, for
     * drawing. Cheap, but callers should still remember it against the route
     * rather than rebuilding it every frame.
     */
    fun elevationSamples(count: Int): DoubleArray {
        val n = count.coerceAtLeast(2)
        val out = DoubleArray(n)
        val step = totalDistanceMeters / (n - 1)
        for (i in 0 until n) out[i] = elevationAt(i * step)
        return out
    }

    /** The gradient profile resampled to [count] evenly spaced points, for drawing. */
    fun gradientSamples(count: Int): DoubleArray {
        val n = count.coerceAtLeast(2)
        val out = DoubleArray(n)
        val step = totalDistanceMeters / (n - 1)
        for (i in 0 until n) out[i] = gradientAt(i * step)
        return out
    }

    private fun sampleProfile(profile: DoubleArray, distanceMeters: Double): Double {
        if (profile.isEmpty()) return 0.0
        if (profile.size == 1) return profile[0]
        val d = distanceMeters.coerceIn(0.0, totalDistanceMeters)
        val exact = d / GRADIENT_SAMPLE_METERS
        val i = exact.toInt().coerceIn(0, profile.size - 1)
        val j = (i + 1).coerceAtMost(profile.size - 1)
        val t = (exact - i).coerceIn(0.0, 1.0)
        return profile[i] + (profile[j] - profile[i]) * t
    }

    /** Index of the last point at or before [distanceMeters]. Binary search. */
    private fun indexBefore(distanceMeters: Double): Int {
        var lo = 0
        var hi = points.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (points[mid].distanceMeters <= distanceMeters) lo = mid else hi = mid - 1
        }
        return lo.coerceIn(0, points.size - 1)
    }

    companion object {
        /** Grid spacing of the derived elevation / gradient profiles. */
        const val GRADIENT_SAMPLE_METERS = 10.0

        /**
         * Length the centred difference spans. Long enough to average out GPS
         * altitude noise, short enough that a real ramp arrives before the
         * rider is already on it.
         */
        const val GRADIENT_WINDOW_METERS = 40.0

        /**
         * Hard ceiling on gradient magnitude. Steeper than any paved road, so
         * anything above it is a data artefact rather than terrain.
         */
        const val MAX_GRADIENT = 0.30

        /** Start and finish closer than this means the route is a loop. */
        const val LOOP_CLOSE_METERS = 35.0
    }
}

/** A point as it comes out of a file, before distances are computed. */
data class RawRoutePoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double,
)

/**
 * Build a [RouteTrack] from raw lat/lon/elevation triples, computing cumulative
 * distance and the smoothed profiles.
 *
 * Consecutive points closer together than [MIN_POINT_SPACING_METERS] are
 * dropped: a GPX recorded at 1 Hz while the rider was stopped at a junction
 * stacks dozens of points in one spot, and they add nothing but noise.
 */
fun routeTrackOf(
    id: String,
    name: String,
    raw: List<RawRoutePoint>,
): RouteTrack {
    val kept = ArrayList<RoutePoint>(raw.size)
    var cumulative = 0.0
    for (p in raw) {
        val previous = kept.lastOrNull()
        if (previous == null) {
            kept.add(RoutePoint(p.latitude, p.longitude, p.elevationMeters, 0.0))
            continue
        }
        val step = haversineMeters(previous.latitude, previous.longitude, p.latitude, p.longitude)
        if (step < MIN_POINT_SPACING_METERS) continue
        cumulative += step
        kept.add(RoutePoint(p.latitude, p.longitude, p.elevationMeters, cumulative))
    }

    if (kept.isEmpty()) {
        return RouteTrack(
            id = id,
            name = name,
            points = emptyList(),
            bounds = RouteBounds(0.0, 0.0, 0.0, 0.0),
            gradientProfile = DoubleArray(0),
            elevationProfile = DoubleArray(0),
        )
    }

    val bounds = RouteBounds(
        minLatitude = kept.minOf { it.latitude },
        maxLatitude = kept.maxOf { it.latitude },
        minLongitude = kept.minOf { it.longitude },
        maxLongitude = kept.maxOf { it.longitude },
    )

    val total = kept.last().distanceMeters
    val sampleCount = (total / RouteTrack.GRADIENT_SAMPLE_METERS).toInt() + 1
    val elevation = resampleElevation(kept, sampleCount)
    smoothInPlace(elevation, SMOOTHING_HALF_WINDOW)
    val gradient = differentiate(elevation)

    return RouteTrack(
        id = id,
        name = name,
        points = kept,
        bounds = bounds,
        gradientProfile = gradient,
        elevationProfile = elevation,
    )
}

/** Elevation sampled onto the fixed grid by linear interpolation between points. */
private fun resampleElevation(points: List<RoutePoint>, sampleCount: Int): DoubleArray {
    val out = DoubleArray(max(sampleCount, 1))
    if (points.isEmpty()) return out
    var cursor = 0
    for (i in out.indices) {
        val d = i * RouteTrack.GRADIENT_SAMPLE_METERS
        while (cursor < points.size - 2 && points[cursor + 1].distanceMeters < d) cursor++
        val a = points[cursor]
        val b = points.getOrNull(cursor + 1) ?: a
        val span = b.distanceMeters - a.distanceMeters
        val t = if (span > 0.0) ((d - a.distanceMeters) / span).coerceIn(0.0, 1.0) else 0.0
        out[i] = a.elevationMeters + (b.elevationMeters - a.elevationMeters) * t
    }
    return out
}

/** Centred moving average, read from a copy so later samples aren't fed their own output. */
private fun smoothInPlace(values: DoubleArray, halfWindow: Int) {
    if (values.size < 3 || halfWindow < 1) return
    val source = values.copyOf()
    for (i in values.indices) {
        var sum = 0.0
        var n = 0
        for (k in (i - halfWindow)..(i + halfWindow)) {
            if (k in source.indices) {
                sum += source[k]
                n++
            }
        }
        values[i] = sum / n
    }
}

/**
 * Centred difference over [RouteTrack.GRADIENT_WINDOW_METERS], clamped to
 * [RouteTrack.MAX_GRADIENT].
 */
private fun differentiate(elevation: DoubleArray): DoubleArray {
    val out = DoubleArray(elevation.size)
    if (elevation.size < 2) return out
    val half = (RouteTrack.GRADIENT_WINDOW_METERS / RouteTrack.GRADIENT_SAMPLE_METERS / 2.0)
        .roundToInt().coerceAtLeast(1)
    for (i in elevation.indices) {
        val lo = (i - half).coerceIn(0, elevation.size - 1)
        val hi = (i + half).coerceIn(0, elevation.size - 1)
        val run = (hi - lo) * RouteTrack.GRADIENT_SAMPLE_METERS
        out[i] = if (run > 0.0) {
            ((elevation[hi] - elevation[lo]) / run)
                .coerceIn(-RouteTrack.MAX_GRADIENT, RouteTrack.MAX_GRADIENT)
        } else {
            0.0
        }
    }
    return out
}

/** Points closer together than this are treated as the same place. */
private const val MIN_POINT_SPACING_METERS = 1.0

/**
 * Half-width of the elevation smoother, in grid samples, so plus or minus 50 m.
 *
 * Sized against the noise it has to survive rather than against the terrain it
 * has to keep: consumer GPS altitude wanders by a metre or two between samples
 * ten metres apart, and a shorter window leaves several per cent of phantom
 * gradient on a dead-flat road. The rider feels that directly, as resistance
 * that flutters for no reason, so erring wide is the right way to be wrong. A
 * hundred metres is still short enough that no real ramp is smoothed away.
 */
private const val SMOOTHING_HALF_WINDOW = 5

private const val EARTH_RADIUS_METERS = 6_371_008.8
private const val DEG_TO_RAD = 0.017453292519943295

/** Great-circle distance between two points, in metres. */
fun haversineMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val dLat = (lat2 - lat1) * DEG_TO_RAD
    val dLon = (lon2 - lon1) * DEG_TO_RAD
    val sinLat = sin(dLat / 2)
    val sinLon = sin(dLon / 2)
    val a = sinLat * sinLat +
        cos(lat1 * DEG_TO_RAD) * cos(lat2 * DEG_TO_RAD) * sinLon * sinLon
    return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
}

private fun haversineMeters(a: RoutePoint, b: RoutePoint): Double =
    haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)

/**
 * Compass bearing from [a] to [b], in degrees clockwise from north. Longitude
 * is cosine-corrected for latitude, which over the few metres between two
 * route points is exact enough for anything a map marker can show.
 */
private fun bearingDegrees(a: RoutePoint, b: RoutePoint): Double {
    val dLat = b.latitude - a.latitude
    val dLon = (b.longitude - a.longitude) * cos(a.latitude * DEG_TO_RAD)
    if (abs(dLat) < 1e-12 && abs(dLon) < 1e-12) return 0.0
    val degrees = atan2(dLon, dLat) / DEG_TO_RAD
    return if (degrees < 0) degrees + 360.0 else degrees
}
