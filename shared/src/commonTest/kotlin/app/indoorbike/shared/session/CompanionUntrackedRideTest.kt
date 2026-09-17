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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Riding that is not written down.
 *
 * The rider is on the trainer and working - pushing the bike up a climb they
 * stalled on, warming up before a start line - and none of it may reach the
 * ride record. For the record this is a pause; for the trainer it is a ride.
 */
class CompanionUntrackedRideTest {

    /**
     * Wait in real time.
     *
     * The session manager keeps its own clock on its own dispatcher, so a test
     * that wants to watch the ride's seconds actually pass has to spend them.
     * Only the tests about time do.
     */
    private suspend fun realDelay(ms: Long) = withContext(Dispatchers.Default) { delay(ms) }

    private suspend fun withManager(
        body: suspend (WorkoutSessionManager, CompanionEngine) -> Unit,
    ) {
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
    fun theClockStopsAndNothingIsSampled() = runTest {
        withManager { sessions, _ ->
            sessions.prepareCompanion(ftp = 240)
            sessions.startOrResumeCompanionRide()
            realDelay(1_200)
            val ridden = sessions.elapsedSeconds.value
            val samples = sessions.telemetrySnapshot().size
            assertTrue(samples > 0, "a recorded ride keeps samples")

            assertTrue(sessions.startUntracked("Pushing the bike"))
            assertEquals(WorkoutState.Untracked, sessions.state.value)
            realDelay(1_500)

            assertEquals(ridden, sessions.elapsedSeconds.value, "the clock holds")
            assertEquals(samples, sessions.telemetrySnapshot().size, "no sample is kept")
            assertEquals("Pushing the bike", sessions.untrackedLabel.value)
        }
    }

    @Test
    fun goingBackOnTheRecordCarriesOnFromWhereItStopped() = runTest {
        withManager { sessions, _ ->
            sessions.prepareCompanion(ftp = 240)
            sessions.startOrResumeCompanionRide()
            realDelay(1_200)
            val ridden = sessions.elapsedSeconds.value

            sessions.startUntracked("Pushing the bike")
            realDelay(1_500)
            sessions.startOrResumeCompanionRide()

            assertEquals(WorkoutState.Active, sessions.state.value)
            assertNull(sessions.untrackedLabel.value)
            // The clock picks up where it stopped rather than counting the
            // walk, which is the whole of the claim: nothing here waits for it
            // to run on, because with no pedals turning the stop-pedalling
            // watchdog would take this ride to standby within three seconds,
            // exactly as it should.
            assertEquals(ridden, sessions.elapsedSeconds.value, "continues, rather than counting the walk")
        }
    }

    /** A warm-up is untracked riding on a ride that has not begun. */
    @Test
    fun aRideThatNeverStartedCanGoUntrackedAndStillStartLater() = runTest {
        withManager { sessions, _ ->
            sessions.prepareCompanion(ftp = 240)

            assertTrue(sessions.startUntracked("Warming up"))
            assertEquals(WorkoutState.Untracked, sessions.state.value)
            assertEquals(0L, sessions.sessionStartTimeEpoch, "a warm-up is not a ride")
            assertEquals(0, sessions.elapsedSeconds.value)

            sessions.startOrResumeCompanionRide()
            assertEquals(WorkoutState.Active, sessions.state.value)
            assertNotEquals(0L, sessions.sessionStartTimeEpoch, "the ride begins here")
        }
    }

    @Test
    fun endingAWarmUpSavesNothing() = runTest {
        withManager { sessions, _ ->
            sessions.prepareCompanion(ftp = 240)
            sessions.startUntracked("Warming up")

            sessions.endWorkoutEarly()

            assertEquals(WorkoutState.Untracked, sessions.state.value, "there was no ride to end")
            assertNull(sessions.persistedSnapshot.value)
        }
    }

    @Test
    fun endingFromUntrackedSavesWhatWasRecordedBefore() = runTest {
        withManager { sessions, _ ->
            sessions.prepareCompanion(ftp = 240)
            sessions.startOrResumeCompanionRide()
            realDelay(1_200)
            val ridden = sessions.actualRideSeconds.value

            sessions.startUntracked("Pushing the bike")
            realDelay(1_200)
            sessions.endWorkoutEarly()

            assertEquals(WorkoutState.Completed, sessions.state.value)
            assertEquals(ridden, sessions.actualRideSeconds.value, "the walk is not in the ride")
        }
    }

    /**
     * The rider's own pause button, mid-walk. Coming back must not start
     * recording them: they are still pushing the bike.
     */
    @Test
    fun aPauseThatInterruptedUntrackedResumesIntoUntracked() = runTest {
        withManager { sessions, _ ->
            sessions.prepareCompanion(ftp = 240)
            sessions.startOrResumeCompanionRide()
            sessions.startUntracked("Pushing the bike")

            sessions.pauseWorkout()
            assertEquals(WorkoutState.Paused, sessions.state.value)

            sessions.resumeWorkout()
            assertEquals(WorkoutState.Untracked, sessions.state.value)
            assertNull(sessions.untrackedLabel.value, "the companion says again what the rider is doing")
        }
    }

    @Test
    fun anActiveRequestFromAPausedWalkRecordsRatherThanWalking() = runTest {
        withManager { sessions, _ ->
            sessions.prepareCompanion(ftp = 240)
            sessions.startOrResumeCompanionRide()
            sessions.startUntracked("Pushing the bike")
            sessions.pauseWorkout()

            sessions.startOrResumeCompanionRide()

            assertEquals(WorkoutState.Active, sessions.state.value)
        }
    }

    @Test
    fun aCompanionThatGoesAwayMidWalkHoldsTheRideAndGivesItBack() = runTest {
        withManager { sessions, _ ->
            sessions.prepareCompanion(ftp = 240)
            sessions.startOrResumeCompanionRide()
            sessions.startUntracked("Pushing the bike")

            assertTrue(sessions.holdCompanionRide(), "a ride that was under way is held")
            assertEquals(WorkoutState.Paused, sessions.state.value)

            // The same companion comes back: the ride returns to the walk it
            // was on, not to recording.
            sessions.resumeWorkout()
            assertEquals(WorkoutState.Untracked, sessions.state.value)
        }
    }

    /**
     * Nothing to hold, and nothing to leave running either. The lease would
     * have flattened the road and left the rider turning it over anyway.
     */
    @Test
    fun aCompanionThatGoesAwayMidWarmUpPutsTheTrainerDown() = runTest {
        withManager { sessions, engine ->
            sessions.prepareCompanion(ftp = 240)
            sessions.startUntracked("Warming up")
            engine.setPowerTarget(125)
            assertTrue(engine.running.value)

            assertFalse(sessions.holdCompanionRide(), "there is no ride to resume")

            assertEquals(WorkoutState.Standby, sessions.state.value)
            assertFalse(engine.running.value, "nothing is driving the trainer")
            assertNull(engine.powerTarget.value, "and nothing is held at a wattage")
            assertEquals(0L, sessions.sessionStartTimeEpoch)
        }
    }

    @Test
    fun theStopPedallingWatchdogDoesNotTouchAnUntrackedRide() = runTest {
        withManager { sessions, _ ->
            sessions.prepareCompanion(ftp = 240)
            sessions.startOrResumeCompanionRide()
            realDelay(1_200)
            sessions.startUntracked("Pushing the bike")

            // Well past the three seconds that auto-pauses a recorded ride.
            // There is nothing to protect in a record that is not being kept.
            realDelay(4_500)

            assertEquals(WorkoutState.Untracked, sessions.state.value)
        }
    }

    @Test
    fun leavingUntrackedTakesThePowerTargetWithIt() = runTest {
        withManager { sessions, engine ->
            sessions.prepareCompanion(ftp = 240)
            sessions.startOrResumeCompanionRide()
            sessions.startUntracked("Pushing the bike")
            engine.setPowerTarget(125)
            assertEquals(125, engine.powerTarget.value)

            sessions.startOrResumeCompanionRide()

            assertNull(engine.powerTarget.value)
        }
    }

    @Test
    fun onlyACompanionRideCanGoUntracked() = runTest {
        withManager { sessions, _ ->
            sessions.prepareFreeRide(ftp = 240)

            assertFalse(sessions.startUntracked("Pushing the bike"))
            assertNotEquals(WorkoutState.Untracked, sessions.state.value)
        }
    }
}
