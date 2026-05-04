package app.indoorbike.shared.session

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
