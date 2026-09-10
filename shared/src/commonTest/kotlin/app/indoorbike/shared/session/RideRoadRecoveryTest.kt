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

import app.indoorbike.shared.ble.TrainerManager
import app.indoorbike.shared.companion.CompanionCourse
import app.indoorbike.shared.workout.COMPANION_WORKOUT_ID
import app.indoorbike.shared.workout.simulationWorkoutId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What a recovered ride still knows about its road.
 *
 * The bug this covers was invisible from the ordinary path. Tapping through the
 * completion screen leaves the engines loaded, so every road number can be read
 * off them and everything looked right. Saving from the unfinished-ride banner
 * does not: the app has just started, the engines hold nothing, and reading
 * them returned zeroes. A recovered route ride saved with no distance and no
 * climbing against a road it had plainly ridden, and the post-ride screen drew
 * the whole course with "0.00 km" beside it.
 */
class RideRoadRecoveryTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun snapshot(
        workoutId: String,
        distance: Double? = null,
        climb: Double? = null,
        start: Double? = null,
        startClimb: Double? = null,
        course: CompanionCourse? = null,
    ) = ActiveSessionSnapshot(
        workoutId = workoutId,
        sessionStartTimeEpochMs = 1_700_000_000_000L,
        ftp = 240,
        targetBiasPercent = 0,
        elapsedSeconds = 3_600,
        currentSegmentIndex = 0,
        elapsedInSegment = 3_600,
        state = WorkoutState.Active,
        actualRideSeconds = 3_500,
        simulationDistanceMeters = distance,
        simulationElevationGainMeters = climb,
        simulationStartMeters = start,
        simulationStartElevationGainMeters = startClimb,
        companionCourse = course,
    )

    private suspend fun withManager(body: suspend (WorkoutSessionManager) -> Unit) {
        val trainerManager = TrainerManager()
        val sessionManager = WorkoutSessionManager(
            trainerManager = trainerManager,
            activeSessionStore = null,
            activeTelemetryStore = null,
        )
        try {
            body(sessionManager)
        } finally {
            sessionManager.dispose()
            trainerManager.dispose()
        }
    }

    @Test
    fun aRecoveredRouteRideKeepsTheGroundItCovered() = runTest {
        withManager { sessionManager ->
            // Nothing is loaded: this is the app one second after starting,
            // with a snapshot on disk and an offer to save it.
            val road = sessionManager.pendingRideRoad(
                snapshot(
                    workoutId = simulationWorkoutId("stelvio"),
                    distance = 21_400.0,
                    climb = 1_480.0,
                    start = 6_200.0,
                    startClimb = 310.0,
                )
            )
            assertEquals(21_400.0, road.distanceMeters)
            assertEquals(1_480.0, road.elevationGainMeters)
            // And where the ride began, so the numbers above read as what this
            // ride did rather than only as where it stopped.
            assertEquals(6_200.0, road.startedAtMeters)
            assertEquals(310.0, road.startedAtElevationGainMeters)
        }
    }

    @Test
    fun aRecoveredCompanionRideKeepsItsCourse() = runTest {
        withManager { sessionManager ->
            val course = CompanionCourse(
                name = "Volcano Circuit",
                world = "Watopia",
                shape = "_p~iF~ps|U",
                profileMeters = listOf(12, 40, 18),
            )
            val road = sessionManager.pendingRideRoad(
                snapshot(
                    workoutId = COMPANION_WORKOUT_ID,
                    distance = 18_900.0,
                    climb = 240.0,
                    course = course,
                )
            )
            assertEquals(course, road.course)
            assertEquals(18_900.0, road.distanceMeters)
        }
    }

    @Test
    fun aWorkoutHasNoRoadToRecover() = runTest {
        withManager { sessionManager ->
            val road = sessionManager.pendingRideRoad(snapshot(workoutId = "montee-tempo"))
            assertEquals(RideRoad.NONE, road)
            assertNull(road.course)
        }
    }

    /**
     * The ordinary path, with no recovery in it at all: nothing is loaded here
     * either, and asking without a snapshot must not invent a road.
     */
    @Test
    fun noRideAndNoSnapshotIsNoRoad() = runTest {
        withManager { sessionManager ->
            assertEquals(RideRoad.NONE, sessionManager.pendingRideRoad())
        }
    }

    @Test
    fun aCourseSurvivesTheTripThroughDisk() {
        val snapshot = snapshot(
            workoutId = COMPANION_WORKOUT_ID,
            distance = 8_000.0,
            course = CompanionCourse(
                name = "Volcano Circuit",
                world = "Watopia",
                distanceMeters = 12_400.0,
                ascentMeters = 180.0,
                shape = "_p~iF~ps|U",
                profileMeters = listOf(412, 418, 431),
            ),
        )
        val decoded = json.decodeFromString(
            ActiveSessionSnapshot.serializer(),
            json.encodeToString(ActiveSessionSnapshot.serializer(), snapshot),
        )
        assertEquals(snapshot, decoded)
        assertEquals("Volcano Circuit", assertNotNull(decoded.companionCourse).name)
    }

    /**
     * Snapshots written before any of this existed have to keep loading. What
     * they cannot say, they say by being absent rather than by being zero.
     */
    @Test
    fun anOlderSnapshotStillDecodes() {
        val legacy = """
            {
              "workoutId": "simulation:stelvio",
              "sessionStartTimeEpochMs": 1700000000000,
              "ftp": 200,
              "targetBiasPercent": 0,
              "elapsedSeconds": 60,
              "currentSegmentIndex": 0,
              "elapsedInSegment": 60,
              "state": "Active",
              "simulationDistanceMeters": 4200.0
            }
        """.trimIndent()
        val decoded = json.decodeFromString(ActiveSessionSnapshot.serializer(), legacy)
        assertEquals(4_200.0, decoded.simulationDistanceMeters)
        assertNull(decoded.simulationStartMeters)
        assertNull(decoded.companionCourse)
    }
}
