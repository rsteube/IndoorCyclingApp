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

import app.indoorbike.shared.util.getCurrentEpochMillis
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A ride's three clocks, kept in milliseconds on a monotonic source.
 *
 *  - [elapsedMs]: the planned timeline, which a skipped segment jumps forward.
 *  - [segmentMs]: time into the current segment.
 *  - [rideMs]: time actually ridden, which a skip does not touch.
 *
 * All three run together while the ride is [running] and stand still while it
 * is not.
 *
 * What this replaced measured each riding stretch on the wall clock and
 * re-anchored it, in whole seconds, at every segment change and every pause.
 * Each re-anchor dropped whatever fraction of a second had passed since the
 * last whole one: a forty-interval workout ran about twenty seconds longer
 * than planned, and the saved ride was shorter than the time ridden. And the
 * wall clock is not a stopwatch: a network time correction stepping it back
 * ran the ride clock backwards, and one stepping it forward three seconds
 * auto-paused a rider who was pedalling. Carrying milliseconds across every
 * boundary fixes the first, and a monotonic source fixes the second.
 *
 * The wall clock is still what a sample is stamped with, because a ride file
 * says when things happened. [wallNow] gives that stamp without a step being
 * able to reorder two samples: it is the wall time the stretch began at, plus
 * the monotonic time since.
 */
internal class RideClock(private val source: TimeSource = TimeSource.Monotonic) {

    private var mark: TimeMark? = null
    private var wallAtStart = 0L

    // Totals as of the last time the clock stopped or was set.
    private var elapsedBase = 0L
    private var segmentBase = 0L
    private var rideBase = 0L

    val running: Boolean get() = mark != null

    private fun live(): Long = mark?.elapsedNow()?.inWholeMilliseconds ?: 0L

    val elapsedMs: Long get() = elapsedBase + live()
    val segmentMs: Long get() = segmentBase + live()
    val rideMs: Long get() = rideBase + live()

    /** Wall time now, as far as samples in this stretch are concerned. */
    fun wallNow(): Long = if (mark == null) getCurrentEpochMillis() else wallAtStart + live()

    /** Start a riding stretch. A no-op if one is running. */
    fun start() {
        if (mark != null) return
        mark = source.markNow()
        wallAtStart = getCurrentEpochMillis()
    }

    /** End the riding stretch, keeping everything it counted. */
    fun stop() {
        val d = live()
        elapsedBase += d
        segmentBase += d
        rideBase += d
        mark = null
    }

    /** Put the clock at a known position, stopped. For a new ride or a restored one. */
    fun set(elapsedMs: Long, segmentMs: Long, rideMs: Long) {
        mark = null
        elapsedBase = elapsedMs
        segmentBase = segmentMs
        rideBase = rideMs
    }

    /**
     * The segment of [lengthMs] has run out: the next begins with whatever
     * overran it, rather than at zero, so no time falls between the two.
     */
    fun nextSegment(lengthMs: Long) {
        segmentBase -= lengthMs
    }

    /**
     * Skip the rest of a segment of [lengthMs]: the planned timeline jumps to
     * its end, the ride clock does not, and the next segment starts at zero.
     */
    fun skipSegment(lengthMs: Long) {
        val into = segmentMs
        elapsedBase += (lengthMs - into).coerceAtLeast(0L)
        segmentBase -= into
    }
}
