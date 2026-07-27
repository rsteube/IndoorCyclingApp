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
import app.indoorbike.shared.db.TelemetryPoint
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
 * Regression cover for the crash-resume data loss: a ride interrupted an hour
 * in and resumed came back with only the post-resume minutes of telemetry, so
 * the saved session (and its exported .fit) showed a full-length duration
 * against a nine-minute power curve.
 */
class WorkoutSessionResumeTest {

    private val workout = Workout(
        id = "montee-tempo",
        name = "Montée tempo",
        segments = listOf(
            PowerZone(lengthInSeconds = 300, powerPercentFTP = 50, intervalType = "CONSTANT"),
            PowerZone(lengthInSeconds = 900, powerPercentFTP = 76, intervalType = "CONSTANT"),
        ),
    )

    private fun snapshot(
        elapsed: Int = 3_600,
        segment: Int = 1,
        state: WorkoutState = WorkoutState.Active,
    ) = ActiveSessionSnapshot(
        workoutId = workout.id,
        sessionStartTimeEpochMs = START_EPOCH,
        ftp = 230,
        targetBiasPercent = 0,
        elapsedSeconds = elapsed,
        currentSegmentIndex = segment,
        elapsedInSegment = 120,
        state = state,
        actualRideSeconds = elapsed,
    )

    /** Stands in for the pre-crash portion of the ride, already on disk. */
    private fun preCrashSamples(count: Int) = (0 until count).map {
        TelemetryPoint(
            timestampEpoch = START_EPOCH + it * 1000L,
            power = 175L,
            cadence = 75L,
            heartRate = 140L,
        )
    }

    private class FakeSessionStore(var current: ActiveSessionSnapshot?) : ActiveSessionStore {
        override suspend fun read(): ActiveSessionSnapshot? = current
        override suspend fun write(snapshot: ActiveSessionSnapshot) { current = snapshot }
        override suspend fun clear() { current = null }
    }

    /**
     * Copy-on-write so the test thread can read [points] while the manager's
     * dispatcher is still appending, without tripping over a shared mutable list.
     */
    private class FakeTelemetryStore(initial: List<TelemetryPoint> = emptyList()) : ActiveTelemetryStore {
        var points: List<TelemetryPoint> = initial
            private set
        var cleared = false
            private set
        override suspend fun append(points: List<TelemetryPoint>) { this.points = this.points + points }
        override suspend fun readAll(): List<TelemetryPoint> = points
        override suspend fun clear() { points = emptyList(); cleared = true }
    }

    /**
     * Persistence is fire-and-forget onto the manager's own dispatcher, so
     * assertions have to wait for it. Real time, not the test scheduler's
     * virtual clock, because that work isn't running on the test dispatcher.
     */
    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            try {
                withTimeout(5_000) {
                    while (!condition()) delay(10)
                }
            } catch (e: TimeoutCancellationException) {
                fail("timed out waiting for $what")
            }
        }
    }

    /**
     * Runs [body] against a manager wired to the given fakes, tearing down both
     * it and its trainer afterwards so the background BLE supervisor and
     * pedaling-detection loops don't outlive the test.
     */
    private suspend fun withManager(
        sessionStore: ActiveSessionStore?,
        telemetryStore: ActiveTelemetryStore?,
        body: suspend (WorkoutSessionManager) -> Unit,
    ) {
        val trainerManager = TrainerManager()
        val sessionManager = WorkoutSessionManager(
            trainerManager = trainerManager,
            activeSessionStore = sessionStore,
            activeTelemetryStore = telemetryStore,
        )
        try {
            body(sessionManager)
        } finally {
            sessionManager.dispose()
            trainerManager.dispose()
        }
    }

    @Test
    fun resumingAfterACrashKeepsThePreCrashTelemetry() = runTest {
        val recorded = preCrashSamples(3_668)
        val telemetryStore = FakeTelemetryStore(recorded)

        withManager(FakeSessionStore(snapshot()), telemetryStore) { sessionManager ->
            sessionManager.loadPersistedSnapshot()
            val persisted = assertNotNull(
                sessionManager.persistedSnapshot.value,
                "snapshot should be offered for resume",
            )
            sessionManager.restoreFromSnapshot(persisted, workout)

            // Before the fix this was 0: restore cleared the buffer outright, so
            // the saved ride only contained samples captured after the resume.
            assertEquals(recorded, sessionManager.telemetrySnapshot())
        }
    }

    @Test
    fun resumeWithoutADurableLogStillWorks() = runTest {
        // Rides started before this fix shipped have a snapshot but no log. The
        // pre-crash data is unrecoverable, but resuming must not break.
        withManager(FakeSessionStore(snapshot()), FakeTelemetryStore()) { sessionManager ->
            sessionManager.loadPersistedSnapshot()
            sessionManager.restoreFromSnapshot(snapshot(), workout)

            assertEquals(emptyList(), sessionManager.telemetrySnapshot())
            assertEquals(3_600, sessionManager.elapsedSeconds.value)
            assertEquals(WorkoutState.Standby, sessionManager.state.value)
        }
    }

    @Test
    fun resumeIsSafeWhenNoTelemetryStoreIsWired() = runTest {
        // The store is optional; a null one must degrade to the old behaviour
        // rather than throw on the resume path.
        withManager(FakeSessionStore(snapshot()), null) { sessionManager ->
            sessionManager.loadPersistedSnapshot()
            sessionManager.restoreFromSnapshot(snapshot(), workout)

            assertEquals(emptyList(), sessionManager.telemetrySnapshot())
            assertEquals(WorkoutState.Standby, sessionManager.state.value)
        }
    }

    @Test
    fun snapshotMismatchDoesNotGraftAnotherRidesTelemetry() = runTest {
        // The banner offers a specific workout; if the ids disagree we bail out
        // rather than splice one ride's samples onto another.
        val telemetryStore = FakeTelemetryStore(preCrashSamples(120))
        val otherWorkout = workout.copy(id = "some-other-ride")

        withManager(FakeSessionStore(snapshot()), telemetryStore) { sessionManager ->
            sessionManager.loadPersistedSnapshot()
            sessionManager.restoreFromSnapshot(snapshot(), otherWorkout)

            assertEquals(emptyList(), sessionManager.telemetrySnapshot())
            assertEquals(null, sessionManager.activeWorkout.value)
        }
    }

    @Test
    fun noUnfinishedRideReapsAnOrphanedLog() = runTest {
        // A log with no snapshot belongs to a ride that already ended. Left in
        // place it would prepend itself to whatever ride is resumed next.
        val telemetryStore = FakeTelemetryStore(preCrashSamples(500))

        withManager(FakeSessionStore(null), telemetryStore) { sessionManager ->
            sessionManager.loadPersistedSnapshot()

            assertTrue(telemetryStore.cleared, "orphaned telemetry log should be cleared")
            assertEquals(emptyList(), telemetryStore.points)
        }
    }

    @Test
    fun startingAFreshRideDiscardsTheRecoveredLog() = runTest {
        val telemetryStore = FakeTelemetryStore(preCrashSamples(900))

        withManager(FakeSessionStore(snapshot()), telemetryStore) { sessionManager ->
            sessionManager.loadPersistedSnapshot()

            // Rider ignores the banner and starts something new: the old ride is
            // implicitly abandoned and must not leak into the new session.
            sessionManager.prepareWorkout(workout, ftp = 230)

            assertEquals(emptyList(), sessionManager.telemetrySnapshot())
            assertEquals(0, sessionManager.elapsedSeconds.value)
        }
    }

    // A finished ride is only durable once it reaches history. Until then the
    // snapshot and log have to hold it, or a kill on the completion screen (or
    // the rider simply walking away from it) loses everything they just rode.

    @Test
    fun endingARideKeepsItRecoverableUntilItIsSaved() = runTest {
        val recorded = preCrashSamples(1_200)
        val sessionStore = FakeSessionStore(snapshot())
        val telemetryStore = FakeTelemetryStore(recorded)

        withManager(sessionStore, telemetryStore) { sessionManager ->
            sessionManager.loadPersistedSnapshot()
            sessionManager.restoreFromSnapshot(snapshot(), workout)
            sessionManager.endWorkoutEarly()

            assertEquals(WorkoutState.Completed, sessionManager.state.value)
            awaitUntil("completed ride to be persisted") {
                sessionStore.current?.state == WorkoutState.Completed
            }
            // Before this fix, completion cleared the snapshot outright and the
            // ride existed only in memory until the rider tapped through.
            val pending = assertNotNull(sessionManager.persistedSnapshot.value)
            assertEquals(WorkoutState.Completed, pending.state)
            assertEquals(recorded, telemetryStore.points, "samples must survive completion")
        }
    }

    @Test
    fun markRideSavedReleasesBothRecoveryCopies() = runTest {
        val sessionStore = FakeSessionStore(snapshot())
        val telemetryStore = FakeTelemetryStore(preCrashSamples(300))

        withManager(sessionStore, telemetryStore) { sessionManager ->
            sessionManager.loadPersistedSnapshot()
            sessionManager.restoreFromSnapshot(snapshot(), workout)
            sessionManager.endWorkoutEarly()
            awaitUntil("completed ride to be persisted") {
                sessionStore.current?.state == WorkoutState.Completed
            }

            sessionManager.markRideSaved()

            assertEquals(null, sessionManager.persistedSnapshot.value)
            awaitUntil("recovery copies to be released") {
                sessionStore.current == null && telemetryStore.points.isEmpty()
            }
        }
    }

    @Test
    fun aFinishedRideStillLoadedInMemoryOffersItsLiveBuffer() = runTest {
        // The rider backed out of the completion screen without saving, so the
        // banner will offer it back. The live buffer is the complete ride and
        // must win over whatever was recovered at startup.
        val recorded = preCrashSamples(600)
        val telemetryStore = FakeTelemetryStore(recorded)

        withManager(FakeSessionStore(snapshot()), telemetryStore) { sessionManager ->
            sessionManager.loadPersistedSnapshot()
            sessionManager.restoreFromSnapshot(snapshot(), workout)
            sessionManager.endWorkoutEarly()

            val pending = assertNotNull(sessionManager.persistedSnapshot.value)
            assertEquals(recorded, sessionManager.pendingRideTelemetry(pending))
        }
    }

    @Test
    fun aFinishedRideRecoveredInAFreshProcessOffersTheLoggedSamples() = runTest {
        // App died on the completion screen. Nothing is loaded in memory, so
        // the durable log is all there is - and it has to be enough to save.
        val recorded = preCrashSamples(2_400)
        val completed = snapshot(state = WorkoutState.Completed)

        withManager(FakeSessionStore(completed), FakeTelemetryStore(recorded)) { sessionManager ->
            sessionManager.loadPersistedSnapshot()

            val pending = assertNotNull(sessionManager.persistedSnapshot.value)
            assertEquals(WorkoutState.Completed, pending.state)
            assertEquals(recorded, sessionManager.pendingRideTelemetry(pending))
        }
    }

    @Test
    fun aRideThatNeverStartedLeavesNothingBehind() = runTest {
        // Prepared, never pedalled, then ended. There's no ride to recover, so
        // the banner must not offer one.
        val sessionStore = FakeSessionStore(null)
        val telemetryStore = FakeTelemetryStore()

        withManager(sessionStore, telemetryStore) { sessionManager ->
            sessionManager.loadPersistedSnapshot()
            sessionManager.prepareWorkout(workout, ftp = 230)
            sessionManager.endWorkoutEarly()

            assertEquals(null, sessionManager.persistedSnapshot.value)
            assertEquals(WorkoutState.Standby, sessionManager.state.value)
        }
    }

    private companion object {
        const val START_EPOCH = 1_753_000_000_000L
    }
}
