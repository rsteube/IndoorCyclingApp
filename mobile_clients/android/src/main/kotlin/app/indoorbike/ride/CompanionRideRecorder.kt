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
package app.indoorbike.ride

import app.indoorbike.AppContainer
import app.indoorbike.shared.companion.CompanionCourse
import app.indoorbike.shared.session.RideRoad
import app.indoorbike.shared.workout.isCompanion

/**
 * One companion ride, as it went into history.
 *
 * Enough to list it at the end of a session without going back to the database
 * for every row, and the id to open the real thing with.
 */
data class SavedCompanionRide(
    val sessionId: Long,
    val name: String,
    val durationSeconds: Int,
    val distanceMeters: Double,
    val averagePowerWatts: Int = 0,
    /** When the ride started, which is how its screenshots find it. */
    val startTimeEpoch: Long = 0L,
    /**
     * The name the game gave this ride, or null when it gave none and [name] is
     * the app's own. How a course arriving after the ride tells whether it is
     * about this ride or the next one.
     */
    val courseName: String? = null,
)

/**
 * Writes a companion ride to history the moment its game says the ride is over.
 *
 * ## Why this exists at all
 *
 * Every other mode saves from the ride screen, when the rider taps through to
 * the summary. That works because in every other mode the end of the ride is
 * also the end of the session: the rider is done, they look at their numbers,
 * the row is written on the way past.
 *
 * A companion session is not shaped like that. The game decides when a ride
 * ends, the rider is on a bike looking at another screen, and they may well
 * start another ride thirty seconds later. Waiting for a tap meant the ride was
 * never written at all: the rider carried on into the next lap, the HUD was
 * reused, and an hour of riding existed only as a trophy nobody pressed.
 *
 * So saving moved off the screen and onto the event that actually knows. The
 * screen still shows what happened; it is no longer what makes it durable.
 */
class CompanionRideRecorder(private val container: AppContainer) {

    /**
     * Write the ride that has just completed.
     *
     * Returns null when there is nothing worth writing, which is a normal
     * outcome rather than a failure: a game can call an end on a ride the rider
     * never actually started, and a session with no elapsed time and no samples
     * in it is not a ride, it is an intention.
     */
    suspend fun save(
        /**
         * The road as it stood when the game said the ride was over, captured
         * then rather than read now: frames that arrive between the finish and
         * this save belong to the next ride, and reading the engine here gave
         * them to this one. Null reads the engine, for an ending the game did
         * not ask for.
         */
        roadAtFinish: RideRoad? = null,
    ): SavedCompanionRide? {
        val sessions = container.sessionManager
        val workout = sessions.activeWorkout.value ?: return null
        if (!workout.isCompanion) return null
        val startedAt = sessions.sessionStartTimeEpoch
        if (startedAt == 0L) return null

        val road = roadAtFinish ?: sessions.pendingRideRoad()
        // Read before the insert: `saveRideToHistory` clears the pending-ride
        // snapshot on its way out, and the name is the course's if the game
        // named one, which is the same rule the list at the end of the session
        // has to agree with.
        val name = road.course?.name?.takeIf { it.isNotBlank() } ?: workout.name
        val duration = sessions.actualRideSeconds.value
        val telemetry = sessions.telemetrySnapshot()

        val sessionId = saveRideToHistory(
            container = container,
            workout = workout,
            startTimeEpoch = startedAt,
            durationSeconds = duration,
            telemetry = telemetry,
            road = road,
            record = sessions.pendingRideRecord(),
        )
        return SavedCompanionRide(
            sessionId = sessionId,
            name = name,
            durationSeconds = duration,
            distanceMeters = road.distanceMeters - road.startedAtMeters,
            averagePowerWatts = if (telemetry.isEmpty()) 0
                else telemetry.map { it.power }.average().toInt(),
            startTimeEpoch = startedAt,
            courseName = road.course?.name?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Fold a course that arrived after the ride was already written.
     *
     * The protocol puts `course` before `finish` when a game has the road in
     * hand, and the ordinary case needs nothing from this. But a game that only
     * knows its polyline once the rider has stopped drawing it is allowed to
     * send the shape afterwards, and a ride that saved without its map because
     * the frame was four hundred milliseconds late would be a poor reward for
     * following the specification.
     */
    suspend fun complement(ride: SavedCompanionRide, course: CompanionCourse): SavedCompanionRide {
        val name = course.name?.takeIf { it.isNotBlank() } ?: ride.name
        container.historyRepository.completeSessionCourse(
            sessionId = ride.sessionId,
            workoutName = name,
            routeShape = course.shape,
            routeProfile = CompanionCourse.encodeProfile(course.profileMeters),
            routeDistanceMeters = course.distanceMeters ?: 0.0,
            routeElevationGainMeters = course.ascentMeters ?: 0.0,
        )
        return ride.copy(name = name, courseName = course.name?.takeIf { it.isNotBlank() } ?: ride.courseName)
    }
}
