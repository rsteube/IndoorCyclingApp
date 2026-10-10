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
package app.indoorbike.shared.simulation

import app.indoorbike.shared.route.RouteTrack
import kotlin.math.abs

/**
 * Where on a long route a ride begins, when the rider picks the place.
 *
 * A 120 km route is rarely ridden whole indoors. The rider wants the climb in
 * the middle, or the second half after riding the first last week, so before
 * a long route starts they see it whole and choose the start on the map or
 * the profile. The ride then starts there exactly as a resumed one does: the
 * engine is placed on the road and records from that point.
 */
object RouteStart {

    /** Routes at least this long ask where to start. Shorter ones start at the beginning. */
    const val CHOOSE_FROM_METERS = 20_000.0

    /**
     * Road left between a picked start and the finish, at the least. Picking
     * the last few hundred metres would be a ride that ends before the
     * rider has their feet clipped in.
     */
    const val MIN_LEFT_METERS = 1_000.0

    /**
     * A pick this close to the trailhead, or to where the last ride
     * stopped, is that place. A finger is a few hundred metres wide on a
     * long route's profile, and "from the start" should mean the start.
     */
    const val SNAP_METERS = 250.0

    fun asksWhereToStart(route: RouteTrack): Boolean = route.totalDistanceMeters >= CHOOSE_FROM_METERS

    /**
     * The start for a pick at [pickedMeters]: snapped to the trailhead or to
     * [saved] when near them, and never closer to the finish than
     * [MIN_LEFT_METERS].
     */
    fun startFor(route: RouteTrack, pickedMeters: Double, saved: RouteProgress? = null): Double {
        val latest = (route.totalDistanceMeters - MIN_LEFT_METERS).coerceAtLeast(0.0)
        val picked = pickedMeters.coerceIn(0.0, latest)
        if (picked <= SNAP_METERS) return 0.0
        val mark = saved?.distanceMeters
        if (mark != null && mark <= latest && abs(picked - mark) <= SNAP_METERS) return mark
        return picked
    }

    /**
     * The climbing the ride starts with, so "climbed" counts the whole road
     * as it does for a resumed ride: the saved figure at the saved mark,
     * otherwise the climb on the route's profile up to [startMeters].
     */
    fun elevationGainAt(route: RouteTrack, startMeters: Double, saved: RouteProgress? = null): Double =
        if (saved != null && saved.distanceMeters == startMeters) {
            saved.elevationGainMeters
        } else {
            route.ascentTo(startMeters)
        }
}
