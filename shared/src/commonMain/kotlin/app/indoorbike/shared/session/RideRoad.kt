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
package app.indoorbike.shared.session

import app.indoorbike.shared.companion.CompanionCourse

/**
 * What a finished ride did on its road, gathered in one place because there
 * are two ways to ask and they used to disagree.
 *
 * A ride reaches history down one of two paths. Usually the rider taps through
 * the completion screen, the engines are still loaded, and every one of these
 * numbers can simply be read off them. The other path is the unfinished-ride
 * banner: the app has just started, the snapshot on disk is all that is left of
 * a ride the process died during, and the engines hold nothing at all.
 *
 * The second path was reading the engines anyway. A recovered route ride
 * therefore saved with zero distance and zero climbing against a road it had
 * plainly ridden, and the post-ride screen drew the whole course with "0.00 km"
 * beside it. The bug was invisible from the normal path, which is exactly the
 * kind that survives a long time.
 *
 * So the road is a value rather than something each caller digs out for
 * itself, and [WorkoutSessionManager.pendingRideRoad] is the single place that
 * decides which of the two sources to believe - the same shape, and for the
 * same reason, as `pendingRideTelemetry`.
 *
 * All zeroes is the honest answer for a structured workout or a free ride:
 * there was no road under them, and that is what the post-ride screen reads as
 * "this ride had no road".
 */
data class RideRoad(
    /** Position along the road at the end, in metres. Not a distance covered. */
    val distanceMeters: Double = 0.0,
    /** Metres climbed, measured on the smoothed profile. */
    val elevationGainMeters: Double = 0.0,
    /**
     * Where *this ride* began, which is what makes the two above readable as
     * what the ride did rather than only as where it ended up. Non-zero for a
     * route the rider picked up part way through.
     */
    val startedAtMeters: Double = 0.0,
    val startedAtElevationGainMeters: Double = 0.0,
    /**
     * The road itself, for the one mode whose road is not in the catalogue.
     * Null for every other, which means "ask the catalogue" or "there was
     * never a road".
     */
    val course: CompanionCourse? = null,
) {
    companion object {
        /** A workout or a free ride: no road, and nothing to say about one. */
        val NONE = RideRoad()
    }
}
