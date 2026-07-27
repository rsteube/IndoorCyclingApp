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
package app.indoorbike.shared.workout

import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.WORKOUT_SCHEMA_VERSION_MAX_SUPPORTED
import app.indoorbike.shared.Workout
import app.indoorbike.shared.workoutSchemaVersionFor
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CadenceTargetTest {

    private fun segment(min: Int? = null, max: Int? = null) = PowerZone(
        lengthInSeconds = 300,
        powerPercentFTP = 76,
        intervalType = "CONSTANT",
        cadenceMinRpm = min,
        cadenceMaxRpm = max,
    )

    // Building the target ────────────────────────────────────────────────────

    @Test
    fun noCadenceFieldsMeansNoTarget() {
        assertNull(segment().cadenceTarget)
    }

    @Test
    fun aPairBecomesARange() {
        val target = segment(min = 85, max = 95).cadenceTarget
        assertEquals(CadenceTarget(85, 95), target)
        assertEquals(false, target!!.isSingleValue)
        assertEquals("85-95", target.label)
    }

    @Test
    fun eitherBoundAloneBecomesASingleTarget() {
        // Hand-written JSON and third-party links won't always send both.
        assertEquals(CadenceTarget(90, 90), segment(min = 90).cadenceTarget)
        assertEquals(CadenceTarget(90, 90), segment(max = 90).cadenceTarget)
        assertEquals("90", segment(min = 90).cadenceTarget!!.label)
    }

    @Test
    fun aReversedPairIsReadAsTheRangeTheAuthorMeant() {
        assertEquals(CadenceTarget(85, 95), segment(min = 95, max = 85).cadenceTarget)
    }

    @Test
    fun implausibleValuesAreTreatedAsAbsent() {
        assertNull(segment(min = 0).cadenceTarget)
        assertNull(segment(min = 5).cadenceTarget)
        assertNull(segment(min = 400).cadenceTarget)
        // A junk bound next to a good one leaves the good one standing alone.
        assertEquals(CadenceTarget(90, 90), segment(min = 90, max = 999).cadenceTarget)
    }

    // Judging the rider ──────────────────────────────────────────────────────

    @Test
    fun aRangeIsJudgedAtFaceValue() {
        val target = CadenceTarget(85, 95)
        assertEquals(CadenceCue.Below, target.cueFor(84))
        assertEquals(CadenceCue.OnTarget, target.cueFor(85))
        assertEquals(CadenceCue.OnTarget, target.cueFor(90))
        assertEquals(CadenceCue.OnTarget, target.cueFor(95))
        assertEquals(CadenceCue.Above, target.cueFor(96))
    }

    @Test
    fun aSingleValueGetsATolerance() {
        // Judged exactly, the cue would flip several times a second and the
        // rider would stop trusting it.
        val target = CadenceTarget(90, 90)
        assertEquals(CadenceCue.OnTarget, target.cueFor(90))
        assertEquals(CadenceCue.OnTarget, target.cueFor(87))
        assertEquals(CadenceCue.OnTarget, target.cueFor(93))
        assertEquals(CadenceCue.Below, target.cueFor(86))
        assertEquals(CadenceCue.Above, target.cueFor(94))
    }

    @Test
    fun noCadenceReadingGivesNoVerdict() {
        // Coasting, or a trainer that doesn't report cadence at all. Neither is
        // a miss worth nagging the rider about.
        val target = CadenceTarget(85, 95)
        assertNull(target.cueFor(0))
        assertNull(target.cueFor(-1))
    }

    // Bar geometry ───────────────────────────────────────────────────────────

    @Test
    fun theBarSpansWiderThanTheTarget() {
        val target = CadenceTarget(85, 95)
        val range = target.displayRangeRpm
        assertTrue(range.first < 85, "bar should start below the target, was ${range.first}")
        assertTrue(range.last > 95, "bar should end above the target, was ${range.last}")
    }

    @Test
    fun theBarNeverStartsBelowZeroRpm() {
        // A very low target would otherwise produce a negative axis.
        assertTrue(CadenceTarget(20, 20).displayRangeRpm.first >= 0)
    }

    @Test
    fun barPositionIsClampedToTheTrack() {
        val target = CadenceTarget(85, 95)
        assertEquals(0f, target.barPositionOf(0))
        assertEquals(1f, target.barPositionOf(500))
        val mid = target.barPositionOf(90)
        assertTrue(mid > 0f && mid < 1f, "an on-target rpm should sit inside the track, was $mid")
    }

    // Schema versioning ──────────────────────────────────────────────────────

    @Test
    fun onlyWorkoutsWithACadenceTargetClaimV2() {
        // A plain power workout must stay v1 or older builds would refuse to
        // load a workout they could ride perfectly well.
        assertEquals(1, workoutSchemaVersionFor(listOf(segment(), segment())))
        assertEquals(2, workoutSchemaVersionFor(listOf(segment(), segment(min = 85, max = 95))))
        assertTrue(WORKOUT_SCHEMA_VERSION_MAX_SUPPORTED >= 2)
    }

    @Test
    fun cadenceFieldsRoundTripThroughJson() {
        val json = Json { ignoreUnknownKeys = true }
        val workout = Workout(
            schemaVersion = 2,
            id = "w",
            name = "Cadence work",
            segments = listOf(segment(min = 85, max = 95), segment()),
        )
        val decoded = json.decodeFromString(Workout.serializer(), json.encodeToString(Workout.serializer(), workout))
        assertEquals(workout, decoded)
        assertEquals(CadenceTarget(85, 95), decoded.segments[0].cadenceTarget)
        assertNull(decoded.segments[1].cadenceTarget)
    }

    @Test
    fun aV1WorkoutWithoutCadenceFieldsStillParses() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = """
            {
              "id": "legacy",
              "name": "Old workout",
              "segments": [
                { "lengthInSeconds": 300, "powerPercentFTP": 50, "intervalType": "CONSTANT" }
              ]
            }
        """.trimIndent()
        val decoded = json.decodeFromString(Workout.serializer(), legacy)
        assertEquals(1, decoded.schemaVersion)
        assertNull(decoded.segments.single().cadenceTarget)
    }
}
