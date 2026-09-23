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
import app.indoorbike.shared.Workout
import app.indoorbike.shared.activity.averageHeartRate
import app.indoorbike.shared.companion.CompanionCourse
import app.indoorbike.shared.db.TelemetryPoint
import app.indoorbike.shared.session.RideRecord
import app.indoorbike.shared.session.RideRoad
import app.indoorbike.shared.session.SegmentMark
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * One save at a time. Two at once, from a double tap on the banner, could each
 * look for the ride, each find nothing, and each insert it.
 */
private val saveLock = Mutex()

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
 * that knows which source still has the answer, and
 * `WorkoutSessionManager.pendingRideRecord` the same for [record].
 *
 * Saving the same ride twice returns the first copy. A ride carries its own id
 * from the moment it starts ([RideRecord.rideId]), and history is keyed on it,
 * so a double tap, or a save that reached history just before the app died and
 * is offered again from the banner, no longer makes a second row that then
 * syncs to every other device as a second ride.
 */
suspend fun saveRideToHistory(
    container: AppContainer,
    workout: Workout,
    startTimeEpoch: Long,
    durationSeconds: Int,
    telemetry: List<TelemetryPoint>,
    road: RideRoad,
    record: RideRecord,
): Long = saveLock.withLock {
    // The road itself, for the one mode whose road is not in the catalogue.
    // Optional in the protocol and therefore usually absent; when it is there,
    // it is what lets a companion ride draw its shape in history and in the
    // share image the way a route ride does.
    val course = road.course

    val avgPower = telemetry.averageOf { it.power }
    val avgCadence = telemetry.averageOf { it.cadence }
    val avgHeartRate = averageHeartRate(telemetry.map { it.heartRate })

    // Globally-unique stable id for the backup-sync layer: the device-id
    // namespace means two phones can never collide without any cross-device
    // coordination. The ride's own id when it has one, which is what makes a
    // second save of it find the first.
    val deviceId = container.userSettings.deviceId
    val externalId = "$deviceId:${record.rideId ?: UUID.randomUUID()}"
    val existing = record.rideId?.let { container.historyRepository.getSessionByExternalId(externalId) }

    val sessionId = existing?.id ?: container.historyRepository.insertWorkoutSession(
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
        // What the ride was ridden against, so an export made later cuts its
        // laps where the segments really fell and uses the FTP it was ridden
        // at, not the one set on the day of the export.
        ftp = record.ftp,
        segmentMarks = SegmentMark.encode(record.segmentMarks),
        externalId = externalId,
        telemetry = telemetry,
    )

    // Durable in history now, so the snapshot and telemetry log have done their
    // job. Only after the insert returns: until then they're the sole copy.
    // Named by its start, so the recovery copies of a ride begun since are left
    // alone.
    container.sessionManager.markRideSaved(startTimeEpoch)
    // Every way a ride reaches history comes through here, so this is where
    // automatic upload starts: companion rides included, which never pass the
    // summary screen that used to be the only thing that uploaded.
    container.rideUploads.autoUpload(
        sessionId = sessionId,
        workout = workout,
        ftp = record.ftp.takeIf { it > 0 } ?: container.userSettings.ftp.value,
    )
    sessionId
}

private inline fun List<TelemetryPoint>.averageOf(selector: (TelemetryPoint) -> Long): Long =
    if (isEmpty()) 0L else map(selector).average().toLong()
