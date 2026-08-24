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

import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout
import app.indoorbike.shared.ble.TrainerManager
import app.indoorbike.shared.workout.FREE_RIDE_MAX_WATTS
import app.indoorbike.shared.workout.FREE_RIDE_MIN_WATTS
import app.indoorbike.shared.workout.FREE_RIDE_STEP_WATTS
import app.indoorbike.shared.workout.FREE_RIDE_WORKOUT_ID
import app.indoorbike.shared.workout.freeRideWorkout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * "Just ride": an open-ended session whose ERG target is the rider's to set.
 *
 * The mode reuses every piece of ride machinery a structured workout uses, so
 * what needs cover here is specifically where it *diverges*: the opening
 * wattage, the stepper acting on watts rather than on a difficulty bias, and
 * that wattage surviving a crash the way a workout's position does.
 */
class FreeRideSessionTest {

    private val structured = Workout(
        id = "montee-tempo",
        name = "Montée tempo",
        segments = listOf(PowerZone(lengthInSeconds = 300, powerPercentFTP = 50, intervalType = "CONSTANT")),
    )

    private class FakeSessionStore(var current: ActiveSessionSnapshot?) : ActiveSessionStore {
        override suspend fun read(): ActiveSessionSnapshot? = current
        override suspend fun write(snapshot: ActiveSessionSnapshot) { current = snapshot }
        override suspend fun clear() { current = null }
    }

    /**
     * The manager's own dispatchers (and, in the ride-through test, the mock
     * trainer's 1 Hz stream) run on real time rather than the test scheduler's
     * virtual clock, so assertions have to wait for them.
     */
    private suspend fun awaitUntil(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            try {
                withTimeout(timeoutMs) {
                    while (!condition()) delay(10)
                }
            } catch (e: TimeoutCancellationException) {
                fail("timed out waiting for $what")
            }
        }
    }

    private suspend fun withManager(
        sessionStore: ActiveSessionStore? = null,
        mockTrainer: Boolean = false,
        body: suspend (WorkoutSessionManager, TrainerManager) -> Unit,
    ) {
        val trainerManager = TrainerManager()
        if (mockTrainer) {
            trainerManager.connectMock()
            trainerManager.toggleMockPedaling()
        }
        val sessionManager = WorkoutSessionManager(
            trainerManager = trainerManager,
            activeSessionStore = sessionStore,
        )
        try {
            body(sessionManager, trainerManager)
        } finally {
            sessionManager.dispose()
            trainerManager.dispose()
        }
    }

    @Test
    fun aFreeRideOpensAt40PercentOfFtp() = runTest {
        withManager { sessionManager, _ ->
            sessionManager.prepareFreeRide(ftp = 250)

            assertEquals(FREE_RIDE_WORKOUT_ID, sessionManager.activeWorkout.value?.id)
            assertEquals(WorkoutState.Standby, sessionManager.state.value)
            assertEquals(100, sessionManager.freeRideTargetWatts.value)
            assertEquals(0, sessionManager.elapsedSeconds.value)
        }
    }

    @Test
    fun theStepperMovesWattsAndStopsAtTheEnds() = runTest {
        withManager { sessionManager, _ ->
            sessionManager.prepareFreeRide(ftp = 250)

            sessionManager.adjustFreeRideTarget(FREE_RIDE_STEP_WATTS)
            assertEquals(110, sessionManager.freeRideTargetWatts.value)
            sessionManager.adjustFreeRideTarget(-FREE_RIDE_STEP_WATTS * 2)
            assertEquals(90, sessionManager.freeRideTargetWatts.value)

            // Held down past either end, the target parks on the bound rather
            // than running off to a wattage no trainer will track.
            sessionManager.adjustFreeRideTarget(-10_000)
            assertEquals(FREE_RIDE_MIN_WATTS, sessionManager.freeRideTargetWatts.value)
            sessionManager.adjustFreeRideTarget(10_000)
            assertEquals(FREE_RIDE_MAX_WATTS, sessionManager.freeRideTargetWatts.value)
        }
    }

    @Test
    fun theStepperIsSettableBeforeTheRideStarts() = runTest {
        // The rider picks their opening effort while the HUD still says "pedal
        // to start", so unlike the difficulty bias this can't be Active-only.
        withManager { sessionManager, _ ->
            sessionManager.prepareFreeRide(ftp = 200)
            assertEquals(WorkoutState.Standby, sessionManager.state.value)

            sessionManager.adjustFreeRideTarget(FREE_RIDE_STEP_WATTS * 5)

            assertEquals(130, sessionManager.freeRideTargetWatts.value)
        }
    }

    @Test
    fun aStructuredWorkoutIgnoresTheWattStepper() = runTest {
        // Its target comes from the interval and the difficulty bias; letting
        // a stray watt step through would silently override the plan.
        withManager { sessionManager, _ ->
            sessionManager.prepareWorkout(structured, ftp = 250)

            sessionManager.adjustFreeRideTarget(FREE_RIDE_STEP_WATTS * 4)

            assertEquals(0, sessionManager.freeRideTargetWatts.value)
        }
    }

    @Test
    fun startingAWorkoutAfterAFreeRideClearsTheStaleTarget() = runTest {
        withManager { sessionManager, _ ->
            sessionManager.prepareFreeRide(ftp = 250)
            sessionManager.adjustFreeRideTarget(FREE_RIDE_STEP_WATTS * 5)

            sessionManager.prepareWorkout(structured, ftp = 250)

            assertEquals(0, sessionManager.freeRideTargetWatts.value)
        }
    }

    @Test
    fun theTrainerHoldsWhateverTheRiderDialsIn() = runTest {
        // The whole point of the mode: what the ± buttons set is what the
        // trainer is commanded to, in place of any segment's own target.
        withManager(mockTrainer = true) { sessionManager, trainerManager ->
            sessionManager.prepareFreeRide(ftp = 250)

            // Pedaling on the mock trainer carries the ride through the
            // 3-2-1-GO countdown the same as a structured one.
            awaitUntil("free ride to go active", timeoutMs = 20_000) {
                sessionManager.state.value == WorkoutState.Active
            }
            awaitUntil("opening target to reach the trainer") {
                trainerManager.targetPower.value == 100
            }

            sessionManager.adjustFreeRideTarget(FREE_RIDE_STEP_WATTS * 3)

            awaitUntil("stepped target to reach the trainer") {
                trainerManager.targetPower.value == 130
            }
        }
    }

    @Test
    fun aCrashedFreeRideComesBackOnTheWattageItWasHolding() = runTest {
        val snapshot = ActiveSessionSnapshot(
            workoutId = FREE_RIDE_WORKOUT_ID,
            sessionStartTimeEpochMs = 1_753_000_000_000L,
            ftp = 250,
            targetBiasPercent = 0,
            elapsedSeconds = 1_800,
            currentSegmentIndex = 0,
            elapsedInSegment = 1_800,
            state = WorkoutState.Active,
            actualRideSeconds = 1_800,
            freeRideTargetWatts = 215,
        )

        withManager(FakeSessionStore(snapshot)) { sessionManager, _ ->
            sessionManager.loadPersistedSnapshot()
            val persisted = assertNotNull(sessionManager.persistedSnapshot.value)
            sessionManager.restoreFromSnapshot(persisted, freeRideWorkout())

            assertEquals(WorkoutState.Standby, sessionManager.state.value)
            assertEquals(1_800, sessionManager.elapsedSeconds.value)
            // Resuming at the opening 100 W would quietly undo half an hour of
            // the rider building up to where they were.
            assertEquals(215, sessionManager.freeRideTargetWatts.value)
        }
    }

    @Test
    fun aFreeRideSnapshotWithoutAWattageFallsBackToTheOpeningTarget() = runTest {
        // Defensive: a snapshot written by a build that predates the field, or
        // truncated, must still resume onto something rideable.
        val snapshot = ActiveSessionSnapshot(
            workoutId = FREE_RIDE_WORKOUT_ID,
            sessionStartTimeEpochMs = 1_753_000_000_000L,
            ftp = 300,
            targetBiasPercent = 0,
            elapsedSeconds = 600,
            currentSegmentIndex = 0,
            elapsedInSegment = 600,
            state = WorkoutState.Active,
            actualRideSeconds = 600,
            freeRideTargetWatts = null,
        )

        withManager(FakeSessionStore(snapshot)) { sessionManager, _ ->
            sessionManager.loadPersistedSnapshot()
            sessionManager.restoreFromSnapshot(snapshot, freeRideWorkout())

            assertEquals(120, sessionManager.freeRideTargetWatts.value)
        }
    }

    @Test
    fun anInFlightFreeRidePersistsItsWattage() = runTest {
        val store = FakeSessionStore(null)

        withManager(store, mockTrainer = true) { sessionManager, _ ->
            sessionManager.prepareFreeRide(ftp = 250)
            sessionManager.adjustFreeRideTarget(FREE_RIDE_STEP_WATTS * 2)
            awaitUntil("free ride to go active", timeoutMs = 20_000) {
                sessionManager.state.value == WorkoutState.Active
            }

            awaitUntil("snapshot to carry the free-ride target", timeoutMs = 15_000) {
                store.current?.freeRideTargetWatts == 120
            }
            assertEquals(FREE_RIDE_WORKOUT_ID, store.current?.workoutId)
        }
    }

    @Test
    fun aStructuredWorkoutsSnapshotCarriesNoWattage() = runTest {
        val store = FakeSessionStore(null)

        withManager(store, mockTrainer = true) { sessionManager, _ ->
            sessionManager.prepareWorkout(structured, ftp = 250)
            awaitUntil("workout to go active", timeoutMs = 20_000) {
                sessionManager.state.value == WorkoutState.Active
            }

            awaitUntil("a snapshot to be written", timeoutMs = 15_000) { store.current != null }
            assertEquals(null, store.current?.freeRideTargetWatts)
        }
    }
}
