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
package app.indoorbike.shared.activity

import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout
import app.indoorbike.shared.db.WorkoutSession
import app.indoorbike.shared.db.WorkoutTelemetry
import app.indoorbike.shared.physics.VirtualSpeed
import app.indoorbike.shared.session.SegmentMark

/**
 * One ride laid out for export, the same way for every format: its samples on
 * the two clocks, its laps, its distance, and the FTP it was ridden at.
 *
 * The FIT and TCX writers each used to work these out for themselves, from the
 * ride's plan and today's settings, and both got them wrong the same ways:
 *
 *  - **Laps** were cut on the planned segment lengths. Skip a segment and the
 *    next one's riding landed in the skipped one's lap, under its target; end
 *    early and the TCX still wrote every planned lap, the last of them empty,
 *    which strict importers reject. Laps are now cut where the ride recorded
 *    its segments changing ([SegmentMark]), and on the plan only for rides
 *    saved before that was recorded, without the empty tail.
 *  - **FTP** was whatever was set on the day of the export, so re-exporting an
 *    old ride after an FTP test rewrote its targets and its training stress.
 *    It is the ride's own now, where history has it.
 *  - **Distance** on a route or companion ride was the flat-road estimate from
 *    power, not the road ridden: a 12 km climb exported as however far those
 *    watts would have gone on the flat, and nothing at all without a body
 *    weight. The road's own distance is shared out over the ride now, and on
 *    a companion ride whose game reported its distance as it went, each
 *    second's metres are simply what the game said they were.
 *
 * Where the rider was, on a companion ride whose game reported it, is read
 * straight off the samples by the writers: see [hasPosition].
 */
internal class ExportRide(
    session: WorkoutSession,
    val telemetry: List<WorkoutTelemetry>,
    workout: Workout?,
    /** The FTP to use when history did not record the ride's own. */
    fallbackFtp: Int,
    riderKg: Float,
) {
    val totalSeconds: Int = session.durationSeconds.toInt().coerceAtLeast(1)

    val timeline: RideTimeline =
        RideTimeline.of(session.startTimeEpoch, totalSeconds, telemetry.map { it.timestampEpoch })

    /** The FTP the ride was ridden at when history recorded it; [fallbackFtp] otherwise. */
    val ftp: Int = session.ftp.toInt().takeIf { it > 0 } ?: fallbackFtp

    /**
     * Metres covered in each timeline point's second, index for index with
     * [RideTimeline.points]. All zero when there is no way to tell ([hasDistance]).
     */
    val metres: DoubleArray

    /** Whether [metres] means anything: a road was ridden, or a body weight is known. */
    val hasDistance: Boolean

    /**
     * Whether any sample carries a position the companion reported, so the
     * writers know to declare one. Samples without one write "no position",
     * never a guess.
     */
    val hasPosition: Boolean = telemetry.any { it.latitude != null && it.longitude != null }

    /** Whether any sample carries an altitude the companion reported. */
    val hasAltitude: Boolean = telemetry.any { it.altitudeMeters != null }

    val laps: List<ExportLap>

    init {
        val points = timeline.points
        val roadMetres = (session.routeDistanceMeters - session.routeStartMeters).coerceAtLeast(0.0)
        metres = DoubleArray(points.size)
        if (points.any { telemetry[it.index].distanceMeters != null }) {
            // The game counted the ride's distance as it went, and every
            // second's share is the difference between its counts. A second it
            // reported nothing for covered nothing as far as the record knows,
            // and the next report carries the ground in between. Measured from
            // where this ride began on the game's count, which a session of many
            // rides need not have started at zero.
            var counted = session.routeStartMeters
            for (i in points.indices) {
                val reported = telemetry[points[i].index].distanceMeters ?: continue
                metres[i] = (reported - counted).coerceAtLeast(0.0)
                if (reported > counted) counted = reported
            }
            hasDistance = true
        } else if (roadMetres > 0.0 && points.isNotEmpty()) {
            // The road's distance is the fact; power only says how it was shared
            // out over the ride. Weighted by the speed each second's watts
            // would give, with a stand-in rider when no weight is set, since
            // only the proportions are used.
            val kg = if (riderKg > 0f) riderKg else STAND_IN_RIDER_KG
            var sum = 0.0
            for (i in points.indices) {
                metres[i] = VirtualSpeed.speedMps(telemetry[points[i].index].power.toDouble(), kg)
                sum += metres[i]
            }
            for (i in points.indices) {
                metres[i] = if (sum > 0.0) metres[i] * roadMetres / sum else roadMetres / points.size
            }
            hasDistance = true
        } else if (riderKg > 0f) {
            for (i in points.indices) {
                metres[i] = VirtualSpeed.speedMps(telemetry[points[i].index].power.toDouble(), riderKg)
            }
            hasDistance = true
        } else {
            hasDistance = false
        }

        val marks = SegmentMark.decode(session.segmentMarks).sortedBy { it.sample }
        laps = when {
            workout != null && marks.isNotEmpty() -> recordedLaps(workout, marks)
            workout != null -> plannedLaps(workout)
            else -> listOf(ExportLap(null, 0, points.size, 0, totalSeconds))
        }
    }

    /** Laps where the ride recorded its segments changing. */
    private fun recordedLaps(workout: Workout, marks: List<SegmentMark>): List<ExportLap> {
        val points = timeline.points
        // Which mark each point falls under: the last one at or before its sample.
        val starts = ArrayList<Pair<SegmentMark, Int>>() // mark, first point
        var m = 0
        for (pi in points.indices) {
            val sample = points[pi].index
            var moved = pi == 0
            while (m + 1 < marks.size && marks[m + 1].sample <= sample) {
                m += 1
                moved = true
            }
            if (moved && (starts.isEmpty() || starts.last().first !== marks[m])) starts += marks[m] to pi
        }
        return starts.mapIndexed { i, (mark, first) ->
            val end = if (i + 1 < starts.size) starts[i + 1].second else points.size
            val startRide = if (i == 0) 0 else points[first].rideSeconds
            val endRide = if (i + 1 < starts.size) points[starts[i + 1].second].rideSeconds else totalSeconds
            ExportLap(workout.segments.getOrNull(mark.segment), first, end, startRide, endRide)
        }
    }

    /**
     * Laps on the planned segment lengths, for rides saved before the real
     * segment changes were recorded. Stops at the last segment the ride
     * reached: a lap with no riding in it after the end is not a lap.
     */
    private fun plannedLaps(workout: Workout): List<ExportLap> {
        val points = timeline.points
        val lengths = workout.segments.map { it.lengthInSeconds.coerceAtLeast(1) }.ifEmpty { listOf(totalSeconds) }
        val laps = ArrayList<ExportLap>()
        var start = 0
        var pi = 0
        for ((si, length) in lengths.withIndex()) {
            val isLastPlanned = si == lengths.lastIndex
            val end = if (isLastPlanned) Int.MAX_VALUE else start + length
            val first = pi
            while (pi < points.size && points[pi].rideSeconds < end) pi += 1
            laps += ExportLap(workout.segments.getOrNull(si), first, pi, start, end)
            if (pi >= points.size) break
            start = end
        }
        // The last lap runs to the end of the ride, however the plan put it.
        val last = laps.removeAt(laps.lastIndex)
        laps += ExportLap(last.segment, last.firstPoint, last.endPoint, last.startRideSeconds, totalSeconds)
        return laps
    }

    private companion object {
        /** Only proportions come from it, so any plausible rider will do. */
        const val STAND_IN_RIDER_KG = 75f
    }
}

/**
 * One lap: timeline points [firstPoint] until [endPoint], ridden between
 * [startRideSeconds] and [endRideSeconds] on the ride clock, against
 * [segment] (null for a ride with no plan).
 */
internal class ExportLap(
    val segment: PowerZone?,
    val firstPoint: Int,
    val endPoint: Int,
    val startRideSeconds: Int,
    val endRideSeconds: Int,
) {
    val timerSeconds: Int get() = (endRideSeconds - startRideSeconds).coerceAtLeast(1)
}
