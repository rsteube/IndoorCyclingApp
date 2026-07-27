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
import app.indoorbike.shared.db.TelemetryPoint
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
 */
suspend fun saveRideToHistory(
    container: AppContainer,
    workout: Workout,
    startTimeEpoch: Long,
    durationSeconds: Int,
    telemetry: List<TelemetryPoint>,
): Long {
    val avgPower = telemetry.averageOf { it.power }
    val avgCadence = telemetry.averageOf { it.cadence }
    val avgHeartRate = telemetry.averageOf { it.heartRate }

    val sessionId = container.historyRepository.insertWorkoutSession(
        workoutId = workout.id,
        workoutName = workout.name,
        startTimeEpoch = startTimeEpoch,
        durationSeconds = durationSeconds.toLong(),
        avgPower = avgPower,
        avgCadence = avgCadence,
        avgHeartRate = avgHeartRate,
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
