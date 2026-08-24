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

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActiveSessionSnapshotTest {

    private val json = Json {
        // The same configuration the persistence layer uses — `actualRideSeconds`
        // was added later, so older snapshots shouldn't fail to decode.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun roundTripPreservesEveryField() {
        val snapshot = ActiveSessionSnapshot(
            workoutId = "first-spin",
            sessionStartTimeEpochMs = 1_700_000_000_000L,
            ftp = 220,
            targetBiasPercent = -5,
            elapsedSeconds = 1234,
            currentSegmentIndex = 3,
            elapsedInSegment = 42,
            state = WorkoutState.Active,
            actualRideSeconds = 1100,
        )
        val encoded = json.encodeToString(ActiveSessionSnapshot.serializer(), snapshot)
        val decoded = json.decodeFromString(ActiveSessionSnapshot.serializer(), encoded)
        assertEquals(snapshot, decoded)
    }

    @Test
    fun olderSnapshotWithoutActualRideSecondsDecodesWithDefault() {
        // Persisted JSON written before the field existed must still load.
        val legacy = """
            {
              "workoutId": "legacy",
              "sessionStartTimeEpochMs": 1700000000000,
              "ftp": 200,
              "targetBiasPercent": 0,
              "elapsedSeconds": 60,
              "currentSegmentIndex": 1,
              "elapsedInSegment": 10,
              "state": "Active"
            }
        """.trimIndent()
        val decoded = json.decodeFromString(ActiveSessionSnapshot.serializer(), legacy)
        assertEquals(0, decoded.actualRideSeconds)
        assertEquals(WorkoutState.Active, decoded.state)
        assertEquals("legacy", decoded.workoutId)
    }

    @Test
    fun allWorkoutStateValuesRoundTrip() {
        // Snapshot may persist any non-Idle state — ensure none break encoding.
        for (state in WorkoutState.values()) {
            val snap = ActiveSessionSnapshot(
                workoutId = "x",
                sessionStartTimeEpochMs = 1L,
                ftp = 200,
                targetBiasPercent = 0,
                elapsedSeconds = 0,
                currentSegmentIndex = 0,
                elapsedInSegment = 0,
                state = state,
            )
            val text = json.encodeToString(ActiveSessionSnapshot.serializer(), snap)
            val back = json.decodeFromString(ActiveSessionSnapshot.serializer(), text)
            assertEquals(state, back.state, "state $state did not round-trip")
        }
    }

    @Test
    fun aFreeRidesWattageRoundTrips() {
        // The free-ride target is the position for a ride with no plan behind
        // it: lose it and the rider resumes at the opening 40 % of FTP.
        val snapshot = ActiveSessionSnapshot(
            workoutId = "free-ride",
            sessionStartTimeEpochMs = 1_700_000_000_000L,
            ftp = 250,
            targetBiasPercent = 0,
            elapsedSeconds = 900,
            currentSegmentIndex = 0,
            elapsedInSegment = 900,
            state = WorkoutState.Active,
            actualRideSeconds = 900,
            freeRideTargetWatts = 185,
        )
        val encoded = json.encodeToString(ActiveSessionSnapshot.serializer(), snapshot)
        val decoded = json.decodeFromString(ActiveSessionSnapshot.serializer(), encoded)
        assertEquals(185, decoded.freeRideTargetWatts)
        assertEquals(snapshot, decoded)
    }

    @Test
    fun olderSnapshotWithoutTheFreeRideWattageDecodesWithDefault() {
        // Written before "just ride" existed, so it can only be a structured
        // ride - and those carry no wattage of their own.
        val legacy = """
            {
              "workoutId": "legacy",
              "sessionStartTimeEpochMs": 1700000000000,
              "ftp": 200,
              "targetBiasPercent": 0,
              "elapsedSeconds": 60,
              "currentSegmentIndex": 1,
              "elapsedInSegment": 10,
              "state": "Active",
              "actualRideSeconds": 60
            }
        """.trimIndent()
        val decoded = json.decodeFromString(ActiveSessionSnapshot.serializer(), legacy)
        assertNull(decoded.freeRideTargetWatts)
    }
}
