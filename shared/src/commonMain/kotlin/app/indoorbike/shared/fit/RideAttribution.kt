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
package app.indoorbike.shared.fit

import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.workout.COMPANION_NAME
import app.indoorbike.shared.workout.COMPANION_WORKOUT_ID

/**
 * Who made a ride, said where other services look for it.
 *
 * ## Why this matters
 *
 * A ride exported from Indoor Bike used to name its maker nowhere a reader
 * could find. The FIT file said manufacturer 255 ("development"), product 1, and
 * nothing else, so Intervals.icu listed the device as "1" and every service
 * downstream of it, VertMatch among them, credited the ride to "1". And a ride
 * ridden in a companion game said nothing about the game at all.
 *
 * So each is now said in the standard place for it:
 *
 *  - The app, by name, in `file_id.product_name` and in a creator `device_info`
 *    (device index 0, "the creator of the file"), which is what FitTrackee and
 *    VertMatch read; and as Intervals.icu's `device_name` on upload, which is
 *    the one place Intervals.icu documents.
 *  - The game, as two developer fields on the session, `virtual_platform` and
 *    `virtual_route`. FIT has no native field for a virtual world, and Strava
 *    and Intervals.icu have none either; a reader that wants them finds them by
 *    name, and any other app recording a virtual ride can use the same two.
 *
 * Manufacturer 255 stays. Claiming to be a Garmin device would bring Garmin's
 * attribution duties with it on Intervals.icu and Strava.
 */
object RideAttribution {

    /** What the app calls itself in every file it writes and every upload it makes. */
    const val PRODUCT_NAME = "Indoor Bike"

    /**
     * An app version as FIT's `software_version`, which readers show divided by
     * 100: "2.8.1" is 281 and reads as 2.81, "2.9" is 290. Minor and patch are
     * one digit each in that scheme, so a larger one is held at 9 rather than
     * carried into the next column, where 2.10 would read as 3.00. Null when
     * there is no version to give, which the file writes as "not known".
     */
    fun softwareVersion(appVersion: String): Int? {
        val parts = Regex("""^\s*(\d+)(?:\.(\d+))?(?:\.(\d+))?""").find(appVersion) ?: return null
        val major = parts.groupValues[1].toIntOrNull() ?: return null
        val minor = parts.groupValues[2].toIntOrNull() ?: 0
        val patch = parts.groupValues[3].toIntOrNull() ?: 0
        return (major * 100 + minor.coerceAtMost(9) * 10 + patch.coerceAtMost(9)).coerceIn(0, 65534)
    }
}

/**
 * The virtual world a ride was ridden in: the game that drove it and the course
 * it named, when it named one.
 */
data class VirtualWorld(
    /**
     * The game's name exactly as it calls itself in its `brand` message, e.g.
     * "Col de Berg". Readers key their credit, link and logo on this string.
     */
    val platform: String,
    /** The course, or null when the game named none. */
    val route: String? = null,
) {
    companion object {
        /**
         * The world [session] was ridden in, given the name of the game that
         * drove it as the ride recorded it. Null for a ride no game drove, and
         * for one whose game never said who it was: a world with no name is
         * nothing a reader can credit.
         *
         * The course is the ride's own name, because that is where a companion
         * ride keeps it; the app's fallback name means the game named none.
         */
        fun of(session: WorkoutSession, companionName: String?): VirtualWorld? {
            if (session.workoutId != COMPANION_WORKOUT_ID) return null
            val platform = companionName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val route = session.workoutName.trim().takeIf { it.isNotEmpty() && it != COMPANION_NAME }
            return VirtualWorld(platform = platform, route = route)
        }
    }
}
