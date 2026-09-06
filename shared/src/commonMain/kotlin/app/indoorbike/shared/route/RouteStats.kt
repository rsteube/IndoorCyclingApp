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

import app.indoorbike.shared.repo.ROUTE_PROFILE_SAMPLES
import app.indoorbike.shared.repo.ROUTE_SHAPE_POINTS
import app.indoorbike.shared.repo.RouteAttribution
import app.indoorbike.shared.repo.RouteEntry
import app.indoorbike.shared.repo.RouteGradientBuckets
import kotlin.math.abs
import kotlin.math.round

/**
 * Turns a parsed [RouteTrack] into the numbers a listing publishes.
 *
 * This is the reference implementation of the [RouteEntry] format. The website
 * tool that maintainers paste their GPX into computes the same values in
 * JavaScript, and the app runs this over a locally imported GPX so that a
 * route added from a file and the same route added from a repository describe
 * themselves identically. When the two disagree, this one is right.
 *
 * Everything is measured against the *smoothed* profiles [RouteTrack] already
 * derives, never the raw points. Consumer GPS altitude wanders by metres
 * between points a few metres apart, and differentiating that noise invents
 * gradients no road has. Measuring the smoothed road means the histogram
 * describes the ride the rider is actually given.
 */
object RouteStats {

    /**
     * Metres of road in each [RouteGradientBuckets] bucket.
     *
     * Walks the route at the same [RouteTrack.GRADIENT_SAMPLE_METERS] grid the
     * simulation reads gradients from, so a bucket holds exactly the road the
     * rider will be made to feel at that steepness.
     */
    fun gradientHistogram(track: RouteTrack): List<Double> {
        val buckets = DoubleArray(RouteGradientBuckets.COUNT)
        val total = track.totalDistanceMeters
        if (total <= 0.0) return buckets.toList()

        val step = RouteTrack.GRADIENT_SAMPLE_METERS
        var distance = 0.0
        while (distance < total) {
            // The last sample is short; count only the road that is really there.
            val span = if (distance + step > total) total - distance else step
            val percent = track.gradientAt(distance + span / 2.0) * 100.0
            buckets[RouteGradientBuckets.indexOf(percent)] += span
            distance += step
        }
        return buckets.toList()
    }

    /** Steepest gradient on the smoothed profile, as a percentage. */
    fun maxGradientPercent(track: RouteTrack): Double {
        val total = track.totalDistanceMeters
        if (total <= 0.0) return 0.0
        var steepest = 0.0
        var distance = 0.0
        while (distance < total) {
            val percent = track.gradientAt(distance) * 100.0
            if (abs(percent) > abs(steepest)) steepest = percent
            distance += RouteTrack.GRADIENT_SAMPLE_METERS
        }
        return steepest
    }

    /** Elevation resampled to [ROUTE_PROFILE_SAMPLES] evenly spaced points, for drawing. */
    fun profile(track: RouteTrack): List<Double> =
        track.elevationSamples(ROUTE_PROFILE_SAMPLES).map { round1(it) }

    /**
     * The track simplified to [ROUTE_SHAPE_POINTS] and encoded, for drawing.
     *
     * Sampled evenly by distance rather than by a corner-preserving
     * simplification: the result is only ever drawn a couple of centimetres
     * wide on a card, even spacing is trivially portable to the JavaScript
     * tool, and it cannot degenerate on a route whose points are unevenly
     * spaced to begin with.
     */
    fun shape(track: RouteTrack): String {
        if (track.isEmpty) return ""
        val count = minOf(ROUTE_SHAPE_POINTS, track.points.size)
        if (count < 2) return ""
        val step = track.totalDistanceMeters / (count - 1)
        val points = ArrayList<Pair<Double, Double>>(count)
        for (i in 0 until count) {
            val position = track.positionAt(i * step)
            points.add(position.latitude to position.longitude)
        }
        return RoutePolyline.encode(points)
    }

    /** `[minLat, minLon, maxLat, maxLon]`, the order [RouteEntry.bounds] uses. */
    fun bounds(track: RouteTrack): List<Double> = listOf(
        round5(track.bounds.minLatitude),
        round5(track.bounds.minLongitude),
        round5(track.bounds.maxLatitude),
        round5(track.bounds.maxLongitude),
    )

    /**
     * Build a complete [RouteEntry] for [track]. The caller supplies what the
     * GPX cannot know: where it will be hosted, and who to credit.
     */
    fun entryFor(
        track: RouteTrack,
        id: String = track.id,
        name: String = track.name,
        gpxUrl: String,
        description: String? = null,
        tags: List<String> = emptyList(),
        place: String? = null,
        version: Int = 1,
        gpxBytes: Long? = null,
        gpxSha256: String? = null,
        attribution: RouteAttribution? = null,
    ): RouteEntry = RouteEntry(
        id = id,
        name = name,
        description = description,
        tags = tags,
        place = place,
        version = version,
        gpxUrl = gpxUrl,
        gpxBytes = gpxBytes,
        gpxSha256 = gpxSha256,
        distanceMeters = round1(track.totalDistanceMeters),
        ascentMeters = round1(track.totalAscentMeters),
        descentMeters = round1(track.totalDescentMeters),
        minElevationMeters = round1(track.minElevationMeters),
        maxElevationMeters = round1(track.maxElevationMeters),
        maxGradientPercent = round1(maxGradientPercent(track)),
        loop = track.isLoop,
        gradientMeters = gradientHistogram(track).map { round1(it) },
        profile = profile(track),
        shape = shape(track),
        bounds = bounds(track),
        attribution = attribution,
    )

    /** One decimal is plenty for metres and percentages, and keeps the JSON small. */
    private fun round1(value: Double): Double = round(value * 10.0) / 10.0

    /** Five decimals matches the polyline precision, about a metre. */
    private fun round5(value: Double): Double = round(value * 1e5) / 1e5
}
