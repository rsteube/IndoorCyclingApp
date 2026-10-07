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
package app.indoorbike.shared.companion

import kotlinx.serialization.Serializable

/**
 * The road a companion says its rider is on.
 *
 * ## Why this exists at all
 *
 * A route ride's record can be thin because the route is not: the session's
 * `workoutId` names a course in the catalogue, and the post-ride screen, the
 * share image and the history entry all go and fetch it. A companion ride has
 * no catalogue entry to fetch, so a finished one used to be a column of
 * numbers with no shape - the same ride, saved worse, purely because of where
 * the road came from. This is the course travelling with the ride instead.
 *
 * ## What is and is not in it
 *
 * Everything here is about the *course*, never about the rider's position on
 * it. A companion may hand over the shape of the whole road and its elevation
 * profile; nothing in the protocol ever asks where on that road the rider is,
 * and the app could not work it out from this. The live ride still travels as
 * a gradient and a speed, which is the same design decision stated twice.
 *
 * It is also entirely optional. A companion that would rather disclose nothing
 * sends nothing, and the ride saves exactly as it did before: real numbers, no
 * shape. A companion that wants its rides to look like route rides in history
 * sends this once, whenever it likes.
 *
 * ## Merging
 *
 * A companion is expected to send the name early (so the ride is called
 * something useful while it is happening) and may only know the shape at the
 * end. So updates merge field by field rather than replacing: a later frame
 * that omits a field leaves whatever was already known alone. That is the same
 * "omitted, not zeroed" discipline the rest of the protocol follows.
 *
 * ## Why it is serialisable
 *
 * It goes into the crash-recovery snapshot as well as onto the wire. A route
 * ride can afford to lose its road to a process death because the catalogue
 * still has it; this road exists nowhere the app can ask for it twice, so
 * losing it means the recovered ride saves as numbers with no shape while the
 * same ride finished normally saves with one.
 */
@Serializable
data class CompanionCourse(
    /** What to call the ride. Ends up as the saved session's name. */
    val name: String? = null,
    /** The world or game it was in, e.g. "Watopia". Shown beside the name. */
    val world: String? = null,
    /** The course's own length, which is not how far the rider got. */
    val distanceMeters: Double? = null,
    /** The course's own total ascent. */
    val ascentMeters: Double? = null,
    /** The road as a precision-5 encoded polyline, or empty for none. */
    val shape: String = "",
    /** Elevation in whole metres, evenly spaced along [shape]. */
    val profileMeters: List<Int> = emptyList(),
    /**
     * A free ride (0.12): no length or climb known in advance, junctions
     * chosen as they come, and somewhere to be taken if the game navigates.
     * Set only by a `course` that says so, and kept through later updates.
     */
    val freeRide: Boolean = false,
) {

    /**
     * True when there is enough here to draw a road. Two points and two
     * samples is the minimum that produces a line rather than a dot.
     */
    val hasShape: Boolean get() = shape.isNotBlank() && profileMeters.size >= 2

    /** True when this carries nothing worth remembering. */
    val isEmpty: Boolean
        get() = name == null && world == null && distanceMeters == null &&
            ascentMeters == null && shape.isBlank() && profileMeters.isEmpty()

    /**
     * Whether this course, arriving between two rides, is still about the ride
     * just saved rather than the next one.
     *
     * Both are legitimate, and they arrive in the same gap. A free-roaming game
     * only knows the road it drew once the rider has stopped drawing it, so it
     * sends the shape after the `finish`. A game with a route menu names the
     * next ride before starting it. Folding the second kind into the ride
     * already saved renamed that ride after the next one, and replaced its map
     * with the nothing a course sent before a ride carries.
     *
     * The name tells them apart. A course that names no ride, or names the
     * ride that was saved, continues it. One that names a different ride is
     * the next ride's. [savedName] is the name the game gave the saved ride,
     * or null when it never gave one, in which case the first name to arrive
     * is the one it was waiting for.
     */
    fun continuesSavedRide(savedName: String?): Boolean {
        val named = name?.takeIf { it.isNotBlank() } ?: return true
        return savedName == null || named == savedName
    }

    /**
     * [update] laid over this one, field by field. Anything the update leaves
     * out keeps the value it already had.
     */
    fun mergedWith(update: CompanionCourse): CompanionCourse = CompanionCourse(
        name = update.name ?: name,
        world = update.world ?: world,
        distanceMeters = update.distanceMeters ?: distanceMeters,
        ascentMeters = update.ascentMeters ?: ascentMeters,
        shape = update.shape.ifBlank { shape },
        profileMeters = update.profileMeters.ifEmpty { profileMeters },
        // Only a course with a mode says what kind of ride it is, and that
        // replaces rather than merges, so an update never changes it.
        freeRide = freeRide,
    )

    companion object {
        /**
         * Elevation samples as one string, for the single column the session
         * record keeps them in.
         *
         * Comma-separated whole metres rather than JSON: it is a list of small
         * integers, a schema would buy nothing, and the column is read by
         * exactly one function.
         */
        fun encodeProfile(profileMeters: List<Int>): String =
            profileMeters.joinToString(",")

        /** The inverse, forgiving of anything that is not a number. */
        fun decodeProfile(encoded: String): List<Int> {
            if (encoded.isBlank()) return emptyList()
            return encoded.split(',').mapNotNull { it.trim().toIntOrNull() }
        }
    }
}
