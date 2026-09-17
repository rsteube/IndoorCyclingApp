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

/**
 * Where each sample of a finished ride sits on two clocks: the wall clock, and
 * the ride clock that stops while the ride is paused.
 *
 * A saved ride stores its start as a wall-clock instant, its duration as ride
 * time, and its samples at wall-clock instants. Those agree only for a ride
 * that was never paused. The exporters used to measure every sample against
 * `start + duration`, so each paused second pushed a second of real riding off
 * the end of the file, and a ride resumed after a crash lost everything after
 * the crash. Nothing records when a pause began, but the samples say it: the
 * session takes one a second while riding and none while paused, so a gap
 * between two samples is a pause.
 *
 * Shared by the FIT and TCX writers so the two files describe the same ride.
 */
internal class RideTimeline private constructor(
    /** Kept samples in time order, at most one per wall-clock second. */
    val points: List<Point>,
    /** Stretches of riding between pauses, in order. Empty with no samples. */
    val phases: List<Phase>,
    /** Seconds from the ride's start to its end on the wall clock, pauses included. */
    val endWallSeconds: Int,
) {
    class Point(
        /** Index into the telemetry list the timeline was built from. */
        val index: Int,
        val wallSeconds: Int,
        val rideSeconds: Int,
        /** Index into [phases]. */
        val phase: Int,
    )

    class Phase(val firstWallSeconds: Int, val lastWallSeconds: Int, val rideStartSeconds: Int) {
        val rideEndSeconds: Int get() = rideStartSeconds + lastWallSeconds - firstWallSeconds
    }

    /**
     * The wall-clock second at which the ride clock read [rideSeconds].
     *
     * A moment that is both the end of one phase and the start of the next
     * belongs to the earlier one, so a lap that ends as the rider pauses ends
     * when they stopped rather than when they came back.
     */
    fun wallAt(rideSeconds: Int): Int {
        for (phase in phases) {
            if (rideSeconds <= phase.rideEndSeconds) {
                return if (rideSeconds < phase.rideStartSeconds) {
                    rideSeconds
                } else {
                    phase.firstWallSeconds + rideSeconds - phase.rideStartSeconds
                }
            }
        }
        val last = phases.lastOrNull() ?: return rideSeconds
        return last.lastWallSeconds + rideSeconds - last.rideEndSeconds
    }

    companion object {
        /**
         * A gap between samples longer than this is a pause.
         *
         * The session samples once a second and drifts by a few milliseconds a
         * tick, so a normal gap is a whisker over 1000 ms. Anything past two
         * seconds is a missed tick at the very least, and in practice the rider
         * pressing pause, the stop-pedalling watchdog, or the app dying.
         */
        const val PAUSE_GAP_MS = 2_000L

        fun of(startEpochMs: Long, durationSeconds: Int, timestampsEpochMs: List<Long>): RideTimeline {
            val order = timestampsEpochMs.indices.sortedBy { timestampsEpochMs[it] }
            val points = ArrayList<Point>(order.size)
            val phases = ArrayList<Phase>()
            val seenWallSeconds = HashSet<Int>()

            var previousMs: Long? = null
            var firstWall = 0
            var lastWall = 0
            var rideStart = 0
            for (index in order) {
                val ms = timestampsEpochMs[index]
                val wall = (ms - startEpochMs).floorDiv(1000L).toInt()
                if (wall < 0 || !seenWallSeconds.add(wall)) continue

                val previous = previousMs
                if (previous == null || ms - previous > PAUSE_GAP_MS) {
                    if (previous == null) {
                        // The first stretch of riding is measured from the
                        // start, so its ride clock is the wall clock.
                        rideStart = wall
                    } else {
                        phases += Phase(firstWall, lastWall, rideStart)
                        // The session takes a sample the moment it resumes,
                        // at the same ride second as the last one before the
                        // pause, so the ride clock carries on from there.
                        rideStart += lastWall - firstWall
                    }
                    firstWall = wall
                }
                lastWall = wall
                previousMs = ms
                points += Point(
                    index = index,
                    wallSeconds = wall,
                    rideSeconds = rideStart + wall - firstWall,
                    phase = phases.size,
                )
            }
            if (points.isEmpty()) {
                return RideTimeline(emptyList(), emptyList(), durationSeconds.coerceAtLeast(1))
            }
            phases += Phase(firstWall, lastWall, rideStart)

            // The ride clock can run past the last sample: a sample stands for
            // the second that follows it, and a ride can end between ticks.
            // Whatever the samples do not account for is added at the end
            // rather than lost, so an unpaused ride ends at start + duration
            // exactly as it always has.
            val last = phases.last()
            val unaccounted = (durationSeconds - last.rideEndSeconds).coerceAtLeast(0)
            return RideTimeline(points, phases, (last.lastWallSeconds + unaccounted).coerceAtLeast(1))
        }
    }
}
