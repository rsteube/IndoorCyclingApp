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

import app.indoorbike.shared.ble.TrainerManager
import app.indoorbike.shared.companion.CompanionEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Who decides when a companion ride starts recording.
 *
 * The answer is the companion, and only the companion. The rider is looking at
 * a game on another screen with its own idea of when the ride begins - a menu
 * to get through, a start line to roll up to, a countdown of its own - and the
 * app cannot see any of it. So the app connects, streams telemetry and applies
 * the road straight away, and leaves the clock stopped until it is told.
 */
class CompanionRideStartTest {

    private suspend fun withManager(body: suspend (WorkoutSessionManager, CompanionEngine) -> Unit) {
        val trainerManager = TrainerManager()
        val engine = CompanionEngine(
            powerWatts = { 0 },
            applyRoad = { },
            riderMassKg = { 75f },
        )
        val sessionManager = WorkoutSessionManager(
            trainerManager = trainerManager,
            activeSessionStore = null,
            activeTelemetryStore = null,
            companionEngine = engine,
        )
        try {
            body(sessionManager, engine)
        } finally {
            sessionManager.dispose()
            engine.dispose()
            trainerManager.dispose()
        }
    }

    @Test
    fun aPreparedCompanionRideWaitsRatherThanCountingDown() = runTest {
        withManager { sessionManager, _ ->
            sessionManager.prepareCompanion(ftp = 240)
            assertEquals(WorkoutState.Standby, sessionManager.state.value)
            // Nothing is being written down yet, which is the whole point: a
            // rider spinning up in a game's menus should not be recording.
            assertEquals(0L, sessionManager.sessionStartTimeEpoch)
        }
    }

    @Test
    fun theCompanionStartsTheRideWithNoCountdown() = runTest {
        withManager { sessionManager, _ ->
            sessionManager.prepareCompanion(ftp = 240)
            sessionManager.startOrResumeCompanionRide()

            // Straight to Active. No Countdown state, and no three seconds of
            // ceremony over a game that has already started.
            assertEquals(WorkoutState.Active, sessionManager.state.value)
            assertNotEquals(0L, sessionManager.sessionStartTimeEpoch)
        }
    }

    /**
     * Skip means "the next interval", and a companion ride has none. Rolling
     * past its one synthetic segment used to end the ride from under the game
     * still driving it, so it is refused rather than merely hidden.
     */
    @Test
    fun skippingACompanionRideDoesNothing() = runTest {
        withManager { sessionManager, _ ->
            sessionManager.prepareCompanion(ftp = 240)
            sessionManager.startOrResumeCompanionRide()
            val segment = sessionManager.currentSegmentIndex.value
            val elapsed = sessionManager.elapsedSeconds.value

            sessionManager.skipSegment()

            assertEquals(WorkoutState.Active, sessionManager.state.value)
            assertEquals(segment, sessionManager.currentSegmentIndex.value)
            assertEquals(elapsed, sessionManager.elapsedSeconds.value)

            sessionManager.pauseWorkout()
            sessionManager.skipSegment()
            assertEquals(WorkoutState.Paused, sessionManager.state.value)
            assertEquals(segment, sessionManager.currentSegmentIndex.value)
        }
    }

    /**
     * A game that drops mid-ride has stopped driving the ride, not ended it.
     * The ride pauses hard, and resumes as the same ride.
     */
    @Test
    fun aCompanionThatDropsPausesTheRideAndItResumesAsTheSameRide() = runTest {
        withManager { sessionManager, _ ->
            sessionManager.prepareCompanion(ftp = 240)
            sessionManager.startOrResumeCompanionRide()
            val startedAt = sessionManager.sessionStartTimeEpoch

            assertTrue(sessionManager.holdCompanionRide(), "a riding ride was held")
            assertEquals(WorkoutState.Paused, sessionManager.state.value)

            sessionManager.resumeWorkout()
            assertEquals(WorkoutState.Active, sessionManager.state.value)
            assertEquals(startedAt, sessionManager.sessionStartTimeEpoch, "the same ride, not a new one")
        }
    }

    @Test
    fun aRideTheGameHadPausedIsNotReportedAsHeld() = runTest {
        withManager { sessionManager, _ ->
            sessionManager.prepareCompanion(ftp = 240)
            sessionManager.startOrResumeCompanionRide()
            sessionManager.pauseWorkout()

            // False, so the host does not resume it when the game returns: the
            // game paused it, and the game can resume it.
            assertFalse(sessionManager.holdCompanionRide())
            assertEquals(WorkoutState.Paused, sessionManager.state.value)
        }
    }

    @Test
    fun thereIsNothingToHoldBeforeTheRideStarts() = runTest {
        withManager { sessionManager, _ ->
            sessionManager.prepareCompanion(ftp = 240)
            assertFalse(sessionManager.holdCompanionRide())
            assertEquals(WorkoutState.Standby, sessionManager.state.value)
        }
    }

    @Test
    fun startingTwiceChangesNothing() = runTest {
        withManager { sessionManager, _ ->
            sessionManager.prepareCompanion(ftp = 240)
            sessionManager.startOrResumeCompanionRide()
            val startedAt = sessionManager.sessionStartTimeEpoch

            sessionManager.startOrResumeCompanionRide()
            assertEquals(WorkoutState.Active, sessionManager.state.value)
            assertEquals(startedAt, sessionManager.sessionStartTimeEpoch)
        }
    }

    /**
     * The same frame resumes. A companion asks "should the ride be running
     * now", and should not have to know whether the app thinks of this as a
     * start or a continuation.
     */
    @Test
    fun theSameCallResumesAPausedRide() = runTest {
        withManager { sessionManager, _ ->
            sessionManager.prepareCompanion(ftp = 240)
            sessionManager.startOrResumeCompanionRide()
            val startedAt = sessionManager.sessionStartTimeEpoch

            sessionManager.pauseWorkout()
            assertEquals(WorkoutState.Paused, sessionManager.state.value)

            sessionManager.startOrResumeCompanionRide()
            assertEquals(WorkoutState.Active, sessionManager.state.value)
            // Resumed, not restarted: the ride keeps the clock it already had.
            assertEquals(startedAt, sessionManager.sessionStartTimeEpoch)
        }
    }

    @Test
    fun itDoesNothingToARideThatIsNotACompanionRide() = runTest {
        withManager { sessionManager, _ ->
            sessionManager.prepareFreeRide(ftp = 240)
            assertEquals(WorkoutState.Standby, sessionManager.state.value)

            sessionManager.startOrResumeCompanionRide()
            // A free ride still starts the way it always did, by pedalling.
            assertEquals(WorkoutState.Standby, sessionManager.state.value)
            assertTrue(sessionManager.sessionStartTimeEpoch == 0L)
        }
    }
}
