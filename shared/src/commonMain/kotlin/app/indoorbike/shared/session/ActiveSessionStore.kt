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
package app.indoorbike.shared.session

import app.indoorbike.shared.companion.CompanionCourse
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
    /**
     * Where on the road *this ride* began, as distinct from where the crash
     * left it.
     *
     * The two are the same number only for a route started at the trailhead,
     * and reading one as the other is a bug this codebase has already had
     * once: resume at 42 km, ride 6, and the summary claims all 48 as this
     * ride's. Recovery reintroduced it by a different door - restoring put the
     * origin at the crash point, so everything ridden before the process died
     * stopped counting as part of the ride.
     *
     * Null in snapshots written before this field existed, and for those the
     * old behaviour is all that can be reconstructed.
     */
    val simulationStartMeters: Double? = null,
    val simulationStartElevationGainMeters: Double? = null,
    /**
     * The road a companion ride is on, which is the one road nothing else can
     * fetch back.
     *
     * A route ride names its course in [workoutId] and the catalogue still has
     * the GPX after a process death. A companion's course lived in a game on
     * another device, and the socket did not survive either, so if it is not
     * here it is gone - and a recovered ride would save as numbers with no
     * shape while the same ride finished normally saved with one.
     */
    val companionCourse: CompanionCourse? = null,
    /**
     * This ride's own id, fixed when it first went active. History keys the
     * saved ride on it, so saving the same ride twice - a double tap on the
     * banner, or a save that landed just before the app died and is offered
     * again - finds the first copy instead of making a second. Null in
     * snapshots written before it existed.
     */
    val rideId: String? = null,
    /**
     * Where each segment of the ride actually began, in samples. See
     * [SegmentMark]. Empty in snapshots written before it existed, and the
     * exports then fall back to the planned segment lengths.
     */
    val segmentMarks: List<SegmentMark> = emptyList(),
)

/**
 * A segment of a ride beginning: segment [segment] of the workout, from sample
 * [sample] of the ride's telemetry onwards.
 *
 * Recorded as the ride happens because the plan cannot reconstruct it. A
 * skipped segment, a ride ended early, or a pause that straddles a boundary
 * all move where the segments really fell, and exports cut on the planned
 * lengths put one interval's riding under the next one's target.
 */
@Serializable
data class SegmentMark(val segment: Int, val sample: Int) {
    companion object {
        /** `segment@sample` pairs joined by `;`, the form history stores. */
        fun encode(marks: List<SegmentMark>): String =
            marks.joinToString(";") { "${it.segment}@${it.sample}" }

        /** The inverse of [encode]; anything malformed is skipped rather than guessed at. */
        fun decode(text: String): List<SegmentMark> =
            text.split(';').mapNotNull { part ->
                val at = part.indexOf('@')
                if (at <= 0) return@mapNotNull null
                val segment = part.substring(0, at).toIntOrNull() ?: return@mapNotNull null
                val sample = part.substring(at + 1).toIntOrNull() ?: return@mapNotNull null
                if (segment < 0 || sample < 0) null else SegmentMark(segment, sample)
            }
    }
}

/**
 * What history needs to know about a ride beyond its samples and its road:
 * its own id, the FTP it was ridden at, and where its segments fell.
 */
data class RideRecord(
    val rideId: String?,
    val ftp: Int,
    val segmentMarks: List<SegmentMark>,
) {
    companion object {
        val NONE = RideRecord(rideId = null, ftp = 0, segmentMarks = emptyList())
    }
}

/**
 * Per-platform persistence for the active session snapshot. Implementations
 * are expected to be lightweight (single small JSON blob).
 */
interface ActiveSessionStore {
    suspend fun read(): ActiveSessionSnapshot?
    suspend fun write(snapshot: ActiveSessionSnapshot)
    suspend fun clear()
}
