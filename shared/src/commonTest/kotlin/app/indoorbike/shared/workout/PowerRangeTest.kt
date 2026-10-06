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
package app.indoorbike.shared.workout

import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout
import app.indoorbike.shared.workoutSchemaVersionFor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PowerRangeTest {

    private fun segment(pct: Int = 95, min: Int? = null, max: Int? = null) = PowerZone(
        lengthInSeconds = 1200,
        powerPercentFTP = pct,
        intervalType = "CONSTANT",
        powerMinPercentFTP = min,
        powerMaxPercentFTP = max,
    )

    // Building the range ─────────────────────────────────────────────────────

    @Test
    fun noBoundsIsNoRange() = assertNull(segment().powerRange)

    @Test
    fun oneBoundAloneIsNoRange() {
        assertNull(segment(min = 90).powerRange)
        assertNull(segment(max = 105).powerRange)
    }

    @Test
    fun equalBoundsAreNoRange() = assertNull(segment(min = 95, max = 95).powerRange)

    @Test
    fun aReversedPairIsReadAsTheRangeMeant() =
        assertEquals(PowerRange(90, 105), segment(min = 105, max = 90).powerRange)

    @Test
    fun boundsOutsideWhatASegmentCanAskForAreIgnored() = assertNull(segment(min = 90, max = 400).powerRange)

    @Test
    fun aStartOutsideTheRangeIsPulledIn() {
        assertEquals(90, segment(pct = 50, min = 90, max = 105).startPercent)
        assertEquals(105, segment(pct = 120, min = 90, max = 105).startPercent)
        assertEquals(120, segment(pct = 120).startPercent)
    }

    // The target ─────────────────────────────────────────────────────────────

    @Test
    fun aConstantSegmentIsScaledByTheBiasAsBefore() {
        assertEquals(190, segment(pct = 95).targetWatts(ftp = 200, biasPercent = 0))
        assertEquals(209, segment(pct = 95).targetWatts(ftp = 200, biasPercent = 10))
        assertEquals(133, segment(pct = 95).targetWatts(ftp = 200, biasPercent = -30))
    }

    @Test
    fun aRangeStartsAtTheSegmentsPower() =
        assertEquals(190, segment(pct = 95, min = 90, max = 105).targetWatts(ftp = 200, biasPercent = 0))

    @Test
    fun aBiasCarriedInIsHeldInsideTheRange() {
        val seg = segment(pct = 95, min = 90, max = 105)
        assertEquals(210, seg.targetWatts(ftp = 200, biasPercent = 30))
        assertEquals(180, seg.targetWatts(ftp = 200, biasPercent = -30))
    }

    // The taps ───────────────────────────────────────────────────────────────

    @Test
    fun withoutARangeTapsStopAtThirtyPercent() {
        assertEquals(30, nextBiasPercent(segment(), currentBias = 30, deltaPercent = 5))
        assertEquals(-30, nextBiasPercent(segment(), currentBias = -30, deltaPercent = -5))
        assertEquals(30, nextBiasPercent(null, currentBias = 30, deltaPercent = 5))
    }

    @Test
    fun tapsStopAtTheTopOfTheRange() {
        // 95% start, 105% top: +10% reaches 104.5%, +15% would be 109.25%.
        val seg = segment(pct = 95, min = 90, max = 105)
        assertEquals(5, nextBiasPercent(seg, currentBias = 0, deltaPercent = 5))
        assertEquals(10, nextBiasPercent(seg, currentBias = 5, deltaPercent = 5))
        assertEquals(10, nextBiasPercent(seg, currentBias = 10, deltaPercent = 5))
    }

    @Test
    fun tapsStopAtTheBottomOfTheRange() {
        // 95% start, 90% bottom: -5% is 90.25%, and the window ends at -5.
        val seg = segment(pct = 95, min = 90, max = 105)
        assertEquals(-5, nextBiasPercent(seg, currentBias = 0, deltaPercent = -5))
        assertEquals(-5, nextBiasPercent(seg, currentBias = -5, deltaPercent = -5))
    }

    @Test
    fun theEdgeOfTheRangeIsReachableExactly() {
        val seg = segment(pct = 100, min = 90, max = 105)
        assertEquals(5, nextBiasPercent(seg, currentBias = 0, deltaPercent = 5))
        assertEquals(210, seg.targetWatts(ftp = 200, biasPercent = 5))
    }

    @Test
    fun theFirstTapAfterArrivingOutsideTheRangeMovesTheTarget() {
        // Arriving at +20% clamps the target to the top. One tap down has to
        // leave the top, not spend itself getting back to it.
        val seg = segment(pct = 95, min = 90, max = 105)
        val bias = nextBiasPercent(seg, currentBias = 20, deltaPercent = -5)
        assertEquals(5, bias)
        assertEquals(200, seg.targetWatts(ftp = 200, biasPercent = bias))
    }

    @Test
    fun aWideRangeIsStillHeldToThirtyPercent() {
        val seg = segment(pct = 100, min = 50, max = 150)
        assertEquals(30, nextBiasPercent(seg, currentBias = 30, deltaPercent = 5))
        assertEquals(-30, nextBiasPercent(seg, currentBias = -30, deltaPercent = -5))
    }

    // The file ───────────────────────────────────────────────────────────────

    @Test
    fun aRangeRoundTripsThroughJson() {
        val json = Json { ignoreUnknownKeys = true }
        val text = """{"lengthInSeconds":1200,"powerPercentFTP":95,"intervalType":"CONSTANT","powerMinPercentFTP":90,"powerMaxPercentFTP":105}"""
        val seg = json.decodeFromString(PowerZone.serializer(), text)
        assertEquals(PowerRange(90, 105), seg.powerRange)
        assertEquals(seg, json.decodeFromString(PowerZone.serializer(), json.encodeToString(PowerZone.serializer(), seg)))
    }

    @Test
    fun aSegmentWithoutARangeWritesNoBounds() {
        val text = Json.encodeToString(PowerZone.serializer(), segment())
        assertEquals(false, "powerMin" in text || "powerMax" in text)
    }

    @Test
    fun aRangeAloneClaimsNoNewSchemaVersion() =
        assertEquals(1, workoutSchemaVersionFor(listOf(segment(min = 90, max = 105))))

    /** A segment as an app from before ranges reads it. */
    @Serializable
    private data class OlderPowerZone(val lengthInSeconds: Int, val powerPercentFTP: Int, val intervalType: String)

    @Test
    fun anOlderAppRidesTheStart() {
        val workout = Workout(id = "w", name = "FTP test", segments = listOf(segment(min = 90, max = 105)))
        val text = Json.encodeToString(PowerZone.serializer(), workout.segments.single())
        val older = Json { ignoreUnknownKeys = true }.decodeFromString(OlderPowerZone.serializer(), text)
        assertEquals(95, older.powerPercentFTP)
    }
}
