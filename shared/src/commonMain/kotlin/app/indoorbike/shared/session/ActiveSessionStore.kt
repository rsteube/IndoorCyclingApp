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
package app.indoorbike.shared.session

import kotlinx.serialization.Serializable

/**
 * Snapshot of an in-flight workout session — written periodically to disk so
 * a process death (OS kill, crash, reboot mid-ride) doesn't lose the user's
 * progress. On app start we check for one and offer to resume.
 */
@Serializable
data class ActiveSessionSnapshot(
    val workoutId: String,
    val sessionStartTimeEpochMs: Long,
    val ftp: Int,
    val targetBiasPercent: Int,
    val elapsedSeconds: Int,
    val currentSegmentIndex: Int,
    val elapsedInSegment: Int,
    val state: WorkoutState,
    /**
     * Wall-clock seconds the rider has actually spent in [WorkoutState.Active]
     * — distinct from [elapsedSeconds], which advances when segments are
     * skipped. Defaulted for backward-compatibility with snapshots written
     * before this field existed.
     */
    val actualRideSeconds: Int = 0,
    /**
     * The ERG target a free ride was holding, in watts. Null for a structured
     * workout, whose target is derived from the segment and
     * [targetBiasPercent] instead. Defaulted so snapshots written before
     * "just ride" existed still decode.
     */
    val freeRideTargetWatts: Int? = null,
    /**
     * Where a simulated ride had got to along its route, in metres, and which
     * gear it was in. Null for every other mode. Without these a recovered
     * route ride would restart the rider at the trailhead with an hour of
     * telemetry behind them, which is worse than not offering the resume.
     *
     * The route itself isn't stored: it is named by the workout id
     * (`simulation:<routeId>`) and reloaded from the platform's route catalog.
     */
    val simulationDistanceMeters: Double? = null,
    val simulationGearIndex: Int? = null,
    /** Metres climbed so far, so a resumed ride doesn't reset its ascent. */
    val simulationElevationGainMeters: Double? = null,
)

/**
 * Per-platform persistence for the active session snapshot. Implementations
 * are expected to be lightweight (single small JSON blob).
 */
interface ActiveSessionStore {
    suspend fun read(): ActiveSessionSnapshot?
    suspend fun write(snapshot: ActiveSessionSnapshot)
    suspend fun clear()
}
