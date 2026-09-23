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
package app.indoorbike.shared.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class HistoryRepository(databaseDriverFactory: DatabaseDriverFactory) {
    private val database = HistoryDatabase(databaseDriverFactory.createDriver())
    private val dbQuery = database.historyQueries

    /**
     * Persists a finished ride along with its 1Hz telemetry.
     *
     * [externalId] is the globally-unique stable id used by the backup-sync
     * layer to dedupe sessions across devices. Callers compose it as
     * `"$deviceId:${UUID}"` so it stays unique without any cross-device
     * coordination. Stored at insert time — never derived from the
     * autoincrement [id] (the autoincrement isn't known until after insert,
     * and we want to keep `externalId` independent of in-DB row ids in case
     * sessions ever migrate across DBs).
     */
    suspend fun insertWorkoutSession(
        workoutId: String,
        workoutName: String,
        startTimeEpoch: Long,
        durationSeconds: Long,
        avgPower: Long,
        avgCadence: Long,
        avgHeartRate: Long,
        /**
         * How far along its route a simulated ride got, in metres, and how
         * much of the route's climbing it collected. Zero for every other
         * mode - a workout and a free ride have no road under them.
         */
        routeDistanceMeters: Double = 0.0,
        routeElevationGainMeters: Double = 0.0,
        /**
         * Where on the road the ride began. Non-zero only when the rider chose
         * to carry on from a previous sitting, and what makes the two values
         * above readable as a distance rather than only as a position.
         */
        routeStartMeters: Double = 0.0,
        routeStartElevationGainMeters: Double = 0.0,
        /**
         * The road itself, for a ride whose road was not in the route
         * catalogue. Companion rides only: a game's course exists nowhere else
         * once the socket closes, so it travels with the ride. A precision-5
         * encoded polyline and comma-separated whole-metre elevations, the
         * same two formats the route listings use.
         */
        routeShape: String = "",
        routeProfile: String = "",
        /** The FTP the ride was ridden at; 0 when not known (a ride from another device). */
        ftp: Int = 0,
        /** Where each segment began, encoded by `SegmentMark.encode`; empty when not known. */
        segmentMarks: String = "",
        externalId: String,
        telemetry: List<TelemetryPoint>
    ): Long {
        return withContext(Dispatchers.IO) {
            database.transactionWithResult {
                dbQuery.insertSession(
                    workoutId = workoutId,
                    workoutName = workoutName,
                    startTimeEpoch = startTimeEpoch,
                    durationSeconds = durationSeconds,
                    avgPower = avgPower,
                    avgCadence = avgCadence,
                    avgHeartRate = avgHeartRate,
                    routeDistanceMeters = routeDistanceMeters,
                    routeElevationGainMeters = routeElevationGainMeters,
                    routeStartMeters = routeStartMeters,
                    routeStartElevationGainMeters = routeStartElevationGainMeters,
                    routeShape = routeShape,
                    routeProfile = routeProfile,
                    ftp = ftp.toLong(),
                    segmentMarks = segmentMarks,
                    externalId = externalId
                )

                val sessionId = dbQuery.lastInsertRowId().executeAsOne()

                for (point in telemetry) {
                    dbQuery.insertTelemetry(
                        sessionId = sessionId,
                        timestampEpoch = point.timestampEpoch,
                        power = point.power,
                        cadence = point.cadence,
                        heartRate = point.heartRate
                    )
                }

                sessionId
            }
        }
    }

    fun getAllSessions(): Flow<List<WorkoutSession>> {
        return dbQuery.selectAllSessions()
            .asFlow()
            .mapToList(Dispatchers.IO)
    }

    suspend fun getTelemetryForSession(sessionId: Long): List<WorkoutTelemetry> {
        return withContext(Dispatchers.IO) {
            dbQuery.selectTelemetryForSession(sessionId).executeAsList()
        }
    }

    suspend fun getSessionById(sessionId: Long): WorkoutSession? {
        return withContext(Dispatchers.IO) {
            dbQuery.selectSessionById(sessionId).executeAsOneOrNull()
        }
    }

    /**
     * Looks up a session by its globally-unique [externalId]. Used by the
     * sync reconciler to ask "do I already have this session locally?"
     * before applying a remote slice's row.
     */
    suspend fun getSessionByExternalId(externalId: String): WorkoutSession? {
        return withContext(Dispatchers.IO) {
            dbQuery.selectSessionByExternalId(externalId).executeAsOneOrNull()
        }
    }

    /**
     * One-shot post-upgrade migration helper: fills in [WorkoutSession.externalId]
     * for every row that still has the empty-string default (left over from
     * the v1 → v2 schema migration). [generate] is invoked once per row and
     * must return a fresh, globally-unique id — typically
     * `"$deviceId:${UUID.randomUUID()}"`.
     *
     * Idempotent: re-running on a fully-backfilled DB is a no-op (the
     * `selectSessionsNeedingExternalIdBackfill` query returns zero rows).
     * Wrapped in a single transaction so a partial backfill rolls back
     * cleanly if interrupted.
     *
     * @return number of rows backfilled (zero on a fresh install or after
     * the first successful run).
     */
    suspend fun backfillExternalIds(generate: () -> String): Int {
        return withContext(Dispatchers.IO) {
            val needing = dbQuery.selectSessionsNeedingExternalIdBackfill().executeAsList()
            if (needing.isEmpty()) return@withContext 0
            database.transaction {
                for (row in needing) {
                    dbQuery.updateSessionExternalId(externalId = generate(), id = row.id)
                }
            }
            needing.size
        }
    }

    /**
     * Record that Intervals.icu accepted [sessionId] at [epochMillis].
     *
     * Written only on a successful upload, so the ride's row is the single
     * answer to "has this been sent?" rather than a screen remembering it for
     * as long as it happens to be composed.
     */
    suspend fun markIntervalsUploaded(sessionId: Long, epochMillis: Long) {
        withContext(Dispatchers.IO) {
            dbQuery.markIntervalsUploaded(intervalsUploadedEpoch = epochMillis, id = sessionId)
        }
    }

    /** As [markIntervalsUploaded], for the rider's FitTrackee server. */
    suspend fun markFitTrackeeUploaded(sessionId: Long, epochMillis: Long) {
        withContext(Dispatchers.IO) {
            dbQuery.markFitTrackeeUploaded(fitTrackeeUploadedEpoch = epochMillis, id = sessionId)
        }
    }

    /**
     * Fill in a companion ride's road after the row has already been written.
     *
     * A companion ride is saved when its game says the ride is over, because
     * that is the only moment anything knows it is over. The road's shape can
     * still be in flight at that point: a free-roaming game only knows the
     * polyline it drew once the rider has stopped drawing it, and the protocol
     * allows the `course` to arrive after the `finish` for exactly that reason.
     *
     * Widening only, which the SQL enforces with MAX on the two totals. A late
     * frame reporting less than the ride already holds is a game repeating
     * itself, and a ride that shrank after it was saved would be a worse answer
     * than the one already on the screen.
     */
    suspend fun completeSessionCourse(
        sessionId: Long,
        workoutName: String,
        routeShape: String,
        routeProfile: String,
        routeDistanceMeters: Double,
        routeElevationGainMeters: Double,
    ) {
        withContext(Dispatchers.IO) {
            dbQuery.updateSessionCourse(
                workoutName = workoutName,
                routeShape = routeShape,
                routeProfile = routeProfile,
                routeDistanceMeters = routeDistanceMeters,
                routeElevationGainMeters = routeElevationGainMeters,
                id = sessionId,
            )
        }
    }

    suspend fun deleteSession(sessionId: Long) {
        withContext(Dispatchers.IO) {
            database.transaction {
                dbQuery.deleteTelemetryForSession(sessionId)
                dbQuery.deleteSession(sessionId)
            }
        }
    }
}

data class TelemetryPoint(
    val timestampEpoch: Long,
    val power: Long,
    val cadence: Long,
    val heartRate: Long
)
