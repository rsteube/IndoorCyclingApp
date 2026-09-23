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

import app.indoorbike.shared.PowerZone
import app.indoorbike.shared.Workout
import app.indoorbike.shared.ble.TrainerManager
import app.indoorbike.shared.companion.CompanionEngine
import app.indoorbike.shared.db.TelemetryPoint
import app.indoorbike.shared.workout.companionWorkout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The ride's state machine at the moments it used to lose rides: backing out
 * and carrying on, backing out of a countdown, and saving while one ran.
 *
 * Real time throughout, because the manager keeps its own clock and loops on
 * its own dispatcher.
 */
class SessionLifecycleTest {

    private class FakeSessionStore : ActiveSessionStore {
        var current: ActiveSessionSnapshot? = null
        override suspend fun read(): ActiveSessionSnapshot? = current
        override suspend fun write(snapshot: ActiveSessionSnapshot) { current = snapshot }
        override suspend fun clear() { current = null }
    }

    private class FakeTelemetryStore : ActiveTelemetryStore {
        var points: List<TelemetryPoint> = emptyList()
        override suspend fun append(points: List<TelemetryPoint>) { this.points = this.points + points }
        override suspend fun readAll(): List<TelemetryPoint> = points
        override suspend fun clear() { points = emptyList() }
    }

    private val workout = Workout(
        id = "lifecycle",
        name = "Lifecycle",
        segments = listOf(
            PowerZone(lengthInSeconds = 600, powerPercentFTP = 60, intervalType = "CONSTANT"),
            PowerZone(lengthInSeconds = 600, powerPercentFTP = 80, intervalType = "CONSTANT"),
        ),
    )

    private suspend fun realDelay(ms: Long) = withContext(Dispatchers.Default) { delay(ms) }

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            try {
                withTimeout(timeoutMs) { while (!condition()) delay(10) }
            } catch (e: TimeoutCancellationException) {
                fail("timed out waiting for $what")
            }
        }
    }

    private suspend fun withManager(
        mockTrainer: Boolean = false,
        body: suspend (WorkoutSessionManager, FakeSessionStore) -> Unit,
    ) {
        val trainerManager = TrainerManager()
        if (mockTrainer) {
            trainerManager.connectMock()
            trainerManager.toggleMockPedaling()
        }
        val engine = CompanionEngine(powerWatts = { 0 }, applyRoad = { }, riderMassKg = { 75f })
        val store = FakeSessionStore()
        val sessionManager = WorkoutSessionManager(
            trainerManager = trainerManager,
            activeSessionStore = store,
            activeTelemetryStore = FakeTelemetryStore(),
            companionEngine = engine,
        )
        try {
            body(sessionManager, store)
        } finally {
            sessionManager.dispose()
            engine.dispose()
            trainerManager.dispose()
        }
    }

    /**
     * Back out of a ride, tap Continue on the banner: the ride so far is still
     * there. Continue used to replace the samples with whatever had been
     * recovered at app start, which for a ride backed out of in the same
     * process was nothing, so the saved ride lost everything before the back-out.
     */
    @Test
    fun continuingARideBackedOutOfKeepsWhatWasRidden() = runTest {
        withManager { sessions, _ ->
            sessions.prepareCompanion(ftp = 200)
            sessions.startOrResumeCompanionRide()
            realDelay(2_500)
            val ridden = sessions.telemetrySnapshot()
            assertTrue(ridden.size >= 2, "the ride never recorded anything")

            sessions.stopWorkout()
            val offered = assertNotNull(sessions.persistedSnapshot.value, "the back-out left nothing to continue")
            sessions.restoreFromSnapshot(offered, companionWorkout())

            assertEquals(ridden, sessions.telemetrySnapshot())
        }
    }

    /**
     * Back out during 3-2-1-GO. The countdown used to finish regardless and
     * start a ride with no workout, holding the service and the wake lock with
     * it, and nothing ever left that state.
     */
    @Test
    fun backingOutOfTheCountdownStartsNothingLater() = runTest {
        withManager(mockTrainer = true) { sessions, _ ->
            sessions.prepareWorkout(workout, ftp = 250)
            awaitUntil("the countdown to start") { sessions.state.value == WorkoutState.Countdown }

            sessions.stopWorkout()
            realDelay(6_000)

            assertEquals(WorkoutState.Idle, sessions.state.value)
        }
    }

    /**
     * A pause asked for mid-countdown holds. It is what the foreground service
     * asks for when it cannot start, and a countdown that ignored it started
     * the ride anyway with nothing keeping the app alive.
     */
    @Test
    fun aCountdownCanBePausedAndResumesIntoTheRide() = runTest {
        withManager(mockTrainer = true) { sessions, _ ->
            sessions.prepareWorkout(workout, ftp = 250)
            awaitUntil("the countdown to start") { sessions.state.value == WorkoutState.Countdown }

            sessions.pauseWorkout()
            assertEquals(WorkoutState.Paused, sessions.state.value)
            realDelay(6_000)
            assertEquals(WorkoutState.Paused, sessions.state.value, "the countdown started the ride anyway")

            sessions.resumeWorkout()
            assertEquals(WorkoutState.Active, sessions.state.value)
            assertTrue(sessions.sessionStartTimeEpoch > 0, "a ride started from a pause has no start time")
        }
    }

    /** A paused ride writes where it stopped at once, not up to five seconds later. */
    @Test
    fun pausingWritesThePositionStraightAway() = runTest {
        withManager { sessions, store ->
            sessions.prepareCompanion(ftp = 200)
            sessions.startOrResumeCompanionRide()
            realDelay(1_500)
            sessions.pauseWorkout()

            awaitUntil("the paused position to reach disk") { store.current?.state == WorkoutState.Paused }
            assertEquals(sessions.actualRideSeconds.value, store.current?.actualRideSeconds)
        }
    }

    /** Every ride that starts gets its own id and its first segment mark. */
    @Test
    fun aStartedRideCarriesItsOwnIdAndWhereItBegan() = runTest {
        withManager { sessions, _ ->
            sessions.prepareCompanion(ftp = 180)
            assertEquals(null, sessions.pendingRideRecord().rideId)
            sessions.startOrResumeCompanionRide()

            val record = sessions.pendingRideRecord()
            assertNotNull(record.rideId)
            assertEquals(180, record.ftp)
            assertEquals(listOf(SegmentMark(segment = 0, sample = 0)), record.segmentMarks)
        }
    }
}
