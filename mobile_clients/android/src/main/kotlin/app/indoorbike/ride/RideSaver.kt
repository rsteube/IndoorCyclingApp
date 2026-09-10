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
package app.indoorbike.ride

import app.indoorbike.AppContainer
import app.indoorbike.shared.Workout
import app.indoorbike.shared.companion.CompanionCourse
import app.indoorbike.shared.db.TelemetryPoint
import app.indoorbike.shared.session.RideRoad
import java.util.UUID

/**
 * Write a finished ride to history and retire its crash-recovery copies.
 *
 * Shared by the two ways a ride reaches history: the rider tapping through the
 * completion screen, and the unfinished-ride banner offering to save a session
 * whose app died (or that was backed out of) before it got that far. Keeping
 * one implementation means the recovered ride is stored identically to one that
 * finished normally, and that neither path can forget to release the recovery
 * copies afterwards.
 *
 * Returns the new session's row id.
 *
 * [road] is handed in rather than read off the engines here, because on one of
 * the two paths there are no engines to read. A ride saved from the
 * unfinished-ride banner arrives with the app freshly started and everything
 * in memory empty, and this used to read those empty engines: a recovered
 * route ride saved with zero distance and zero climbing against a road it had
 * plainly ridden. `WorkoutSessionManager.pendingRideRoad` is the one place
 * that knows which source still has the answer.
 */
suspend fun saveRideToHistory(
    container: AppContainer,
    workout: Workout,
    startTimeEpoch: Long,
    durationSeconds: Int,
    telemetry: List<TelemetryPoint>,
    road: RideRoad,
): Long {
    // The road itself, for the one mode whose road is not in the catalogue.
    // Optional in the protocol and therefore usually absent; when it is there,
    // it is what lets a companion ride draw its shape in history and in the
    // share image the way a route ride does.
    val course = road.course

    val avgPower = telemetry.averageOf { it.power }
    val avgCadence = telemetry.averageOf { it.cadence }
    val avgHeartRate = telemetry.averageOf { it.heartRate }

    val sessionId = container.historyRepository.insertWorkoutSession(
        workoutId = workout.id,
        // A companion that named its course names the ride. "Watopia Volcano
        // Circuit" in the history list is worth a great deal more than twenty
        // rows all called "Companion ride", and it is the same courtesy a
        // route ride already gets from its GPX.
        workoutName = course?.name?.takeIf { it.isNotBlank() } ?: workout.name,
        startTimeEpoch = startTimeEpoch,
        durationSeconds = durationSeconds.toLong(),
        avgPower = avgPower,
        avgCadence = avgCadence,
        avgHeartRate = avgHeartRate,
        // Both simulated modes fill these in, and that is the point: a
        // companion ride's distance and climbing are the totals its game
        // reported, and storing them in the same two columns is what makes a
        // companion ride read, sort and render exactly like a route ride
        // everywhere downstream.
        routeDistanceMeters = road.distanceMeters,
        routeElevationGainMeters = road.elevationGainMeters,
        routeStartMeters = road.startedAtMeters,
        routeStartElevationGainMeters = road.startedAtElevationGainMeters,
        routeShape = course?.shape.orEmpty(),
        routeProfile = course?.let { CompanionCourse.encodeProfile(it.profileMeters) }.orEmpty(),
        // Globally-unique stable id for the backup-sync layer. The device-id
        // namespace + a fresh UUID per session means two phones can never
        // collide on a session id without any cross-device coordination.
        externalId = "${container.userSettings.deviceId}:${UUID.randomUUID()}",
        telemetry = telemetry,
    )

    // Durable in history now, so the snapshot and telemetry log have done their
    // job. Only after the insert returns: until then they're the sole copy.
    container.sessionManager.markRideSaved()
    return sessionId
}

private inline fun List<TelemetryPoint>.averageOf(selector: (TelemetryPoint) -> Long): Long =
    if (isEmpty()) 0L else map(selector).average().toLong()
