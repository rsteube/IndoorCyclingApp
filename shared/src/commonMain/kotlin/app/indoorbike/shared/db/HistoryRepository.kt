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

    suspend fun insertWorkoutSession(
        workoutId: String,
        workoutName: String,
        startTimeEpoch: Long,
        durationSeconds: Long,
        avgPower: Long,
        avgCadence: Long,
        avgHeartRate: Long,
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
                    avgHeartRate = avgHeartRate
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
