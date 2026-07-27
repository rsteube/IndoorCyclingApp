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

import app.indoorbike.shared.db.TelemetryPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActiveTelemetryStoreTest {

    private fun point(t: Long, p: Long = 180, c: Long = 88, hr: Long = 142) =
        TelemetryPoint(timestampEpoch = t, power = p, cadence = c, heartRate = hr)

    @Test
    fun roundTripPreservesEveryField() {
        val original = point(t = 1_700_000_000_000L, p = 247, c = 93, hr = 168)
        val decoded = ActiveTelemetryCodec.decode(ActiveTelemetryCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun blockRoundTripPreservesOrder() {
        val points = (0 until 200).map { point(t = 1_700_000_000_000L + it * 1000L, p = it.toLong()) }
        val decoded = ActiveTelemetryCodec.decodeBlock(ActiveTelemetryCodec.encodeBlock(points))
        assertEquals(points, decoded)
    }

    @Test
    fun tornFinalRecordIsDroppedAndEverythingBeforeItSurvives() {
        // What a crash mid-append actually leaves on disk: a complete log plus a
        // partial line. Losing that one sample is fine; losing the ride is not.
        val points = (0 until 50).map { point(t = 1_700_000_000_000L + it * 1000L) }
        val torn = ActiveTelemetryCodec.encodeBlock(points) + "1700000050000,183,8"

        val decoded = ActiveTelemetryCodec.decodeBlock(torn)

        assertEquals(points, decoded)
    }

    @Test
    fun malformedLinesAreSkippedNotFatal() {
        val good = point(t = 1_700_000_000_000L)
        val text = buildString {
            append("garbage\n")
            append("1,2,3\n")            // too few fields
            append("1,2,3,4,5\n")        // too many fields
            append("a,b,c,d\n")          // non-numeric
            append("\n")                 // blank
            append(ActiveTelemetryCodec.encode(good)).append('\n')
        }

        assertEquals(listOf(good), ActiveTelemetryCodec.decodeBlock(text))
    }

    @Test
    fun decodeRejectsIncompleteRecords() {
        assertNull(ActiveTelemetryCodec.decode(""))
        assertNull(ActiveTelemetryCodec.decode("   "))
        assertNull(ActiveTelemetryCodec.decode("1700000000000,180,88"))
        assertNull(ActiveTelemetryCodec.decode("1700000000000,180,88,"))
    }

    @Test
    fun emptyBlockEncodesToEmptyString() {
        assertEquals("", ActiveTelemetryCodec.encodeBlock(emptyList()))
        assertEquals(emptyList(), ActiveTelemetryCodec.decodeBlock(""))
    }

    @Test
    fun zeroAndNegativeValuesSurvive() {
        // Coasting reads 0 W / 0 rpm and a missing HRM reads 0. None of those
        // may be silently dropped or the recovered curve won't match the ride.
        val coasting = point(t = 1_700_000_000_000L, p = 0, c = 0, hr = 0)
        assertEquals(coasting, ActiveTelemetryCodec.decode(ActiveTelemetryCodec.encode(coasting)))
    }
}
