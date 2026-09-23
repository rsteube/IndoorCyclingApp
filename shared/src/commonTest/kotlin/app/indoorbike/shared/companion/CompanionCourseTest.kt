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

import app.indoorbike.shared.route.RoutePolyline
import app.indoorbike.shared.route.courseTrackOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompanionCourseTest {

    /**
     * The evening that found it: Mittenwald was saved, then the game named the
     * Weilheim loop before starting it, and Mittenwald became "Weilheim loop"
     * with no map. A late shape for Mittenwald must still land on Mittenwald.
     */
    @Test
    fun `a course naming the next ride is not the saved ride's`() {
        val nextRide = CompanionCourse(name = "Weilheim loop", world = "Col de Berg", distanceMeters = 3426.0)
        assertFalse(nextRide.continuesSavedRide(savedName = "Mittenwald Gravel loop"))

        val lateShape = CompanionCourse(shape = "_p~iF~ps|U", profileMeters = listOf(921, 919))
        assertTrue(lateShape.continuesSavedRide(savedName = "Mittenwald Gravel loop"))

        val sameName = CompanionCourse(name = "Mittenwald Gravel loop", shape = "_p~iF~ps|U")
        assertTrue(sameName.continuesSavedRide(savedName = "Mittenwald Gravel loop"))
    }

    @Test
    fun `a ride the game never named takes the first name that arrives`() {
        assertTrue(CompanionCourse(name = "Probe lap 2").continuesSavedRide(savedName = null))
    }

    /**
     * The order a free-roaming game can actually manage: name the ride while it
     * is happening, hand over the road once the rider has taken it.
     */
    @Test
    fun `a later frame fills gaps without erasing what was known`() {
        val named = CompanionCourse().mergedWith(
            CompanionCourse(name = "Volcano Circuit", world = "Watopia")
        )
        val withShape = named.mergedWith(
            CompanionCourse(shape = "abc", profileMeters = listOf(10, 20, 30))
        )

        assertEquals("Volcano Circuit", withShape.name)
        assertEquals("Watopia", withShape.world)
        assertEquals("abc", withShape.shape)
        assertEquals(listOf(10, 20, 30), withShape.profileMeters)
    }

    @Test
    fun `a later frame that does say something wins`() {
        val merged = CompanionCourse(name = "Lap 1")
            .mergedWith(CompanionCourse(name = "Lap 2"))
        assertEquals("Lap 2", merged.name)
    }

    @Test
    fun `an empty course is empty and has no shape`() {
        assertTrue(CompanionCourse().isEmpty)
        assertFalse(CompanionCourse().hasShape)
        // A shape with one sample cannot be drawn as a line.
        assertFalse(CompanionCourse(shape = "abc", profileMeters = listOf(5)).hasShape)
        assertTrue(CompanionCourse(shape = "abc", profileMeters = listOf(5, 6)).hasShape)
    }

    @Test
    fun `the profile round-trips through its column`() {
        val profile = listOf(0, 12, 45, -3, 900)
        assertEquals(profile, CompanionCourse.decodeProfile(CompanionCourse.encodeProfile(profile)))
        assertEquals(emptyList(), CompanionCourse.decodeProfile(""))
        // Forgiving rather than throwing: a corrupt column should cost the ride
        // its elevation chart, not crash the history screen.
        assertEquals(listOf(1, 3), CompanionCourse.decodeProfile("1,nonsense,3"))
    }
}

/** Putting a stored course back together into a road anything can draw. */
class CourseTrackTest {

    private val shape = RoutePolyline.encode(
        listOf(
            60.1700 to 24.9400,
            60.1750 to 24.9450,
            60.1800 to 24.9500,
            60.1850 to 24.9600,
        )
    )

    @Test
    fun `a shape and a profile rebuild a rideable track`() {
        val track = assertNotNull(
            courseTrackOf(
                id = "session:7",
                name = "Volcano Circuit",
                shape = shape,
                profileMeters = listOf(0, 40, 80, 40),
            )
        )
        assertEquals("Volcano Circuit", track.name)
        assertFalse(track.isEmpty)
        assertTrue(track.totalDistanceMeters > 500.0, "got ${track.totalDistanceMeters} m")
        // Up 80 and back down 40, give or take the smoothing the track applies
        // to every road it is handed.
        assertTrue(track.totalAscentMeters > 40.0, "climbed ${track.totalAscentMeters} m")
        assertTrue(track.maxElevationMeters > track.minElevationMeters)
    }

    /**
     * The profile is spaced by distance and the shape by where the road bends,
     * so the two are matched on how far along each point is. Pairing them by
     * index instead would drape the climbs over the wrong corners.
     */
    @Test
    fun `elevation is placed by distance along the road not by point number`() {
        val track = assertNotNull(
            courseTrackOf("t", "t", shape, listOf(0, 100))
        )
        val half = track.totalDistanceMeters / 2.0
        assertEquals(50.0, track.elevationAt(half), absoluteTolerance = 12.0)
    }

    @Test
    fun `a course with no shape is not a road`() {
        assertNull(courseTrackOf("t", "t", "", listOf(1, 2, 3)))
        assertNull(courseTrackOf("t", "t", "not a polyline at all", listOf(1, 2)))
        // One point is a dot. Nothing to draw and nothing to ride.
        assertNull(courseTrackOf("t", "t", RoutePolyline.encode(listOf(60.17 to 24.94)), listOf(1, 2)))
    }

    @Test
    fun `a road with no profile still has a shape`() {
        val track = assertNotNull(courseTrackOf("t", "t", shape, emptyList()))
        assertTrue(track.totalDistanceMeters > 0.0)
        assertEquals(0.0, track.totalAscentMeters, absoluteTolerance = 1e-6)
    }
}
