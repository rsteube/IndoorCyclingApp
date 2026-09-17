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

import kotlin.test.Test
import kotlin.test.assertEquals

class RideTimelineTest {

    private val start = 1_700_000_000_000L

    private fun seconds(range: IntRange): List<Long> = range.map { start + it * 1000L }

    @Test
    fun anUnpausedRideRunsBothClocksTogether() {
        val timeline = RideTimeline.of(start, durationSeconds = 60, seconds(0 until 60))

        assertEquals(1, timeline.phases.size)
        assertEquals(60, timeline.points.size)
        assertEquals(timeline.points.map { it.wallSeconds }, timeline.points.map { it.rideSeconds })
        // A sample stands for the second after it, so the ride still ends at
        // start + duration rather than on its last sample.
        assertEquals(60, timeline.endWallSeconds)
    }

    @Test
    fun tickDriftIsNotAPause() {
        // The session's timer loop runs a few milliseconds slow every tick, so
        // now and then a whole second has no sample in it.
        val stamps = (0 until 600).map { start + it * 1_004L }
        val timeline = RideTimeline.of(start, durationSeconds = 602, stamps)

        assertEquals(1, timeline.phases.size)
        assertEquals(600, timeline.points.size)
    }

    @Test
    fun aPauseStopsTheRideClockAndKeepsEverySample() {
        // What the session writes: 300 s of riding, a two-minute pause, then a
        // sample the moment it resumes at the same ride second, and 300 s more.
        val stamps = seconds(0..300) + seconds(420..720)
        val timeline = RideTimeline.of(start, durationSeconds = 600, stamps)

        assertEquals(2, timeline.phases.size)
        assertEquals(602, timeline.points.size)
        val resumed = timeline.points[301]
        assertEquals(420, resumed.wallSeconds)
        assertEquals(300, resumed.rideSeconds)
        assertEquals(1, resumed.phase)
        assertEquals(600, timeline.points.last().rideSeconds)
        assertEquals(720, timeline.endWallSeconds)
    }

    @Test
    fun wallAtMapsRideTimeAcrossThePause() {
        val timeline = RideTimeline.of(start, durationSeconds = 600, seconds(0..300) + seconds(420..720))

        assertEquals(0, timeline.wallAt(0))
        assertEquals(150, timeline.wallAt(150))
        // The moment the rider paused belongs to the stretch they paused.
        assertEquals(300, timeline.wallAt(300))
        assertEquals(421, timeline.wallAt(301))
        assertEquals(720, timeline.wallAt(600))
        assertEquals(770, timeline.wallAt(650))
    }

    @Test
    fun aGapOfTwoSecondsIsNotAPauseButLongerIs() {
        val twoSeconds = RideTimeline.of(start, 10, listOf(start, start + 2_000L))
        assertEquals(1, twoSeconds.phases.size)

        val longer = RideTimeline.of(start, 10, listOf(start, start + 2_500L))
        assertEquals(2, longer.phases.size)
    }

    @Test
    fun aRideResumedAfterACrashKeepsWhatCameAfter() {
        // Two hours between the crash and the rider tapping Continue. The
        // exports used to cut everything after the crash.
        val stamps = seconds(0..600) + seconds(7_800..8_400)
        val timeline = RideTimeline.of(start, durationSeconds = 1_200, stamps)

        assertEquals(1_202, timeline.points.size)
        assertEquals(8_400, timeline.endWallSeconds)
    }

    @Test
    fun samplesAreSortedDedupedAndIndexedBackToTheInput() {
        val stamps = listOf(start + 2_000L, start, start + 1_000L, start + 1_400L, start - 5_000L)
        val timeline = RideTimeline.of(start, durationSeconds = 3, stamps)

        assertEquals(listOf(0, 1, 2), timeline.points.map { it.wallSeconds })
        // First in time wins within a second; before the start is dropped.
        assertEquals(listOf(1, 2, 0), timeline.points.map { it.index })
    }

    @Test
    fun noSamplesFallsBackToTheDuration() {
        val timeline = RideTimeline.of(start, durationSeconds = 90, emptyList())

        assertEquals(0, timeline.phases.size)
        assertEquals(90, timeline.endWallSeconds)
        assertEquals(45, timeline.wallAt(45))
    }
}
