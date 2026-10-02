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
import app.indoorbike.shared.db.TelemetryPoint
import app.indoorbike.shared.session.RideRecord
import app.indoorbike.shared.session.RideRoad
import app.indoorbike.shared.session.writeRideToHistory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One save at a time. Two at once, from a double tap on the banner, could each
 * look for the ride, each find nothing, and each insert it.
 */
private val saveLock = Mutex()

/**
 * Write a finished ride to history, then start its automatic uploads.
 *
 * The write itself is [writeRideToHistory], shared with the iOS app; see there
 * for how a ride is stored and why saving one twice returns the first copy.
 * Returns the new session's row id.
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
    val sessionId = writeRideToHistory(
        history = container.historyRepository,
        sessionManager = container.sessionManager,
        deviceId = container.userSettings.deviceId,
        workout = workout,
        startTimeEpoch = startTimeEpoch,
        durationSeconds = durationSeconds,
        telemetry = telemetry,
        road = road,
        record = record,
    )
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
