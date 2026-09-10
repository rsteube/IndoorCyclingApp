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
package app.indoorbike.shared.route

/**
 * Rebuild a [RouteTrack] from the two things a course can be reduced to for
 * storage: an encoded polyline and a handful of elevation samples.
 *
 * This is what lets a companion ride draw the same road in history, on the
 * post-ride screen and in the share image that a route ride draws from its
 * GPX. A route ride can afford to keep a pointer to a file; a companion ride
 * has no file, so the shape travels with it and this puts it back together.
 *
 * ## Why the two halves have different lengths
 *
 * [shape] is the road simplified for drawing, so its points are spaced by
 * where the road bends rather than evenly. [profileMeters] is the elevation
 * resampled onto an even grid, because that is what an elevation chart wants
 * and 64 numbers is a great deal cheaper than an altitude per point. Putting
 * them back together therefore means measuring how far along the road each
 * shape point is and reading the profile at that fraction, rather than pairing
 * them up by index - which would drape the climbs over the wrong corners.
 *
 * Distances here are only used to place the samples. [routeTrackOf] computes
 * the real ones again from the points it is handed, so a small error in the
 * measuring below cannot reach the ride record.
 */
fun courseTrackOf(
    id: String,
    name: String,
    shape: String,
    profileMeters: List<Int>,
): RouteTrack? {
    if (shape.isBlank()) return null
    val points = RoutePolyline.decode(shape)
    if (points.size < 2) return null

    // How far along the road each point is, as a fraction of the whole. The
    // last point is 1.0 by construction, so a route whose points all land on
    // one spot (a stationary rider, a corrupt shape) divides by zero here and
    // is refused rather than producing a flat line at sea level.
    val cumulative = DoubleArray(points.size)
    for (i in 1 until points.size) {
        cumulative[i] = cumulative[i - 1] + haversineMeters(
            points[i - 1].first,
            points[i - 1].second,
            points[i].first,
            points[i].second,
        )
    }
    val total = cumulative[points.size - 1]
    if (total <= 0.0) return null

    val raw = points.mapIndexed { i, point ->
        RawRoutePoint(
            latitude = point.first,
            longitude = point.second,
            elevationMeters = sampleAt(profileMeters, cumulative[i] / total),
        )
    }
    val track = routeTrackOf(id = id, name = name, raw = raw)
    return track.takeUnless { it.isEmpty }
}

/**
 * The profile at [fraction] of the way along, linearly interpolated.
 *
 * A course with no profile at all is still worth drawing - the shape of the
 * road is most of what a rider recognises - so this answers zero rather than
 * refusing, and the elevation chart simply comes out flat.
 */
private fun sampleAt(profileMeters: List<Int>, fraction: Double): Double {
    if (profileMeters.isEmpty()) return 0.0
    if (profileMeters.size == 1) return profileMeters[0].toDouble()
    val x = (fraction.coerceIn(0.0, 1.0)) * (profileMeters.size - 1)
    val i = x.toInt().coerceIn(0, profileMeters.size - 2)
    val t = x - i
    return profileMeters[i] + (profileMeters[i + 1] - profileMeters[i]) * t
}
