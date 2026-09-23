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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class RideClockTest {

    private val time = TestTimeSource()
    private val clock = RideClock(time)

    /**
     * The fault this replaced: re-anchoring on whole seconds at every boundary
     * dropped the fraction since the last one, about half a second per segment.
     */
    @Test
    fun `a segment change carries the overrun into the next segment`() {
        clock.start()
        time += 1_700.milliseconds
        clock.nextSegment(1_000)

        assertEquals(700, clock.segmentMs)
        assertEquals(1_700, clock.rideMs)
        assertEquals(1_700, clock.elapsedMs)
    }

    @Test
    fun `a pause keeps every millisecond ridden before it`() {
        clock.start()
        time += 2_300.milliseconds
        clock.stop()
        time += 60_000.milliseconds // the pause
        clock.start()
        time += 800.milliseconds

        assertEquals(3_100, clock.rideMs)
    }

    @Test
    fun `a skip moves the plan but not the ride`() {
        clock.start()
        time += 40_000.milliseconds
        clock.skipSegment(300_000)

        assertEquals(300_000, clock.elapsedMs)
        assertEquals(0, clock.segmentMs)
        assertEquals(40_000, clock.rideMs)
    }

    @Test
    fun `a restored ride picks up from where it was put`() {
        clock.set(elapsedMs = 90_000, segmentMs = 30_000, rideMs = 85_000)
        clock.start()
        time += 1_000.milliseconds

        assertEquals(91_000, clock.elapsedMs)
        assertEquals(31_000, clock.segmentMs)
        assertEquals(86_000, clock.rideMs)
    }

    @Test
    fun `segment marks survive the round trip through history`() {
        val marks = listOf(SegmentMark(0, 0), SegmentMark(1, 312), SegmentMark(3, 540))
        assertEquals(marks, SegmentMark.decode(SegmentMark.encode(marks)))
        assertEquals(emptyList(), SegmentMark.decode(""))
        assertEquals(listOf(SegmentMark(2, 9)), SegmentMark.decode("x@1;2@9;@;3@-1"))
    }
}
