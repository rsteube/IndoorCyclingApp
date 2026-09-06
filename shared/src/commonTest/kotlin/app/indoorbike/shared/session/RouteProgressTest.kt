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
import app.indoorbike.shared.simulation.Drivetrain
import app.indoorbike.shared.route.RawRoutePoint
import app.indoorbike.shared.route.RouteTrack
import app.indoorbike.shared.route.routeTrackOf
import app.indoorbike.shared.simulation.MIN_RESUMABLE_METERS
import app.indoorbike.shared.simulation.RouteProgress
import app.indoorbike.shared.simulation.RouteProgressStore
import app.indoorbike.shared.simulation.SimulationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A Tour stage is eight hours, and almost nobody rides one in a sitting. These
 * cover the mark that lets the next sitting carry on: it is kept when a ride
 * ends part-way, and dropped when there is nothing to carry on from.
 */
class RouteProgressTest {

    private class FakeProgressStore(
        initial: Map<String, RouteProgress> = emptyMap(),
    ) : RouteProgressStore {
        val saved = initial.toMutableMap()
        var clears = 0

        override suspend fun read(): Map<String, RouteProgress> = saved.toMap()

        override suspend fun save(progress: RouteProgress) {
            saved[progress.routeId] = progress
        }

        override suspend fun clear(routeId: String) {
            clears++
            saved.remove(routeId)
        }
    }

    private fun flatRoad(lengthMeters: Double, id: String = "stage-2"): RouteTrack {
        val points = ArrayList<RawRoutePoint>()
        var d = 0.0
        while (d <= lengthMeters) {
            points.add(RawRoutePoint(60.0 + d / 111_320.0, 24.9, 0.0))
            d += 10.0
        }
        return routeTrackOf(id, "Stage 2 Parcours", points)
    }

    /** Writes go to the manager's own dispatcher, so assertions have to wait for them. */
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
        store: RouteProgressStore,
        power: () -> Int = { 250 },
        /**
         * Drives the ride to Active through the real countdown. Needed only by
         * the "End & save" path, which refuses a ride that never started.
         */
        mockTrainer: Boolean = false,
        body: suspend (WorkoutSessionManager, SimulationEngine) -> Unit,
    ) {
        val trainerManager = TrainerManager()
        if (mockTrainer) {
            trainerManager.connectMock()
            trainerManager.toggleMockPedaling()
        }
        val engine = SimulationEngine(
            powerWatts = power,
            applyRoad = {},
            riderMassKg = { 75f },
            bikeMassKg = { 9f },
            drivetrainProvider = { Drivetrain.ROAD },
        )
        val sessionManager = WorkoutSessionManager(
            trainerManager = trainerManager,
            routeProgressStore = store,
            simulationEngine = engine,
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
    fun `backing out of a long route remembers where the rider got to`() = runTest {
        val store = FakeProgressStore()
        withManager(store) { sessionManager, engine ->
            val road = flatRoad(60_000.0)
            sessionManager.prepareSimulation(road, ftp = 200)
            // Twenty minutes of riding, in the engine's own quarter-second steps.
            repeat(20 * 60 * 4) { engine.advance(0.25) }
            val ridden = engine.distanceMeters.value
            assertTrue(ridden > MIN_RESUMABLE_METERS, "should have covered ground, got $ridden m")

            sessionManager.stopWorkout()

            awaitUntil("progress to be saved") { store.saved.containsKey("stage-2") }
            val saved = assertNotNull(store.saved["stage-2"])
            assertEquals(ridden, saved.distanceMeters, absoluteTolerance = 1.0)
        }
    }

    @Test
    fun `ending and saving a ride part-way keeps the mark too`() = runTest {
        // "End & save" writes the ride to history. That is a different question
        // from whether the road is finished, and the rider is still part-way
        // along it.
        val store = FakeProgressStore()
        withManager(store, mockTrainer = true) { sessionManager, engine ->
            sessionManager.prepareSimulation(flatRoad(60_000.0), ftp = 200)
            awaitUntil("the ride to go active", 20_000) {
                sessionManager.state.value == WorkoutState.Active
            }
            repeat(20 * 60 * 4) { engine.advance(0.25) }
            sessionManager.endWorkoutEarly()

            awaitUntil("progress to be saved") { store.saved.containsKey("stage-2") }
            assertTrue(store.saved.getValue("stage-2").distanceMeters > MIN_RESUMABLE_METERS)
        }
    }

    @Test
    fun `reaching the end clears the mark instead of moving it`() = runTest {
        // A rider who picks a finished route again means to ride it, not to be
        // put back at the line they already crossed.
        val store = FakeProgressStore(
            mapOf("stage-2" to RouteProgress("stage-2", distanceMeters = 900.0)),
        )
        withManager(store) { sessionManager, engine ->
            val road = flatRoad(400.0)
            sessionManager.prepareSimulation(road, ftp = 200)
            repeat(10 * 60 * 4) { engine.advance(0.25) }
            assertTrue(engine.finished.value, "should have reached the end of a 400 m road")

            awaitUntil("the mark to be cleared") { !store.saved.containsKey("stage-2") }
        }
    }

    @Test
    fun `a few metres is not worth offering to continue from`() = runTest {
        val store = FakeProgressStore()
        withManager(store) { sessionManager, engine ->
            sessionManager.prepareSimulation(flatRoad(60_000.0), ftp = 200)
            // Two seconds: the rider rolled a few metres and stopped.
            repeat(8) { engine.advance(0.25) }
            assertTrue(engine.distanceMeters.value < MIN_RESUMABLE_METERS)

            sessionManager.stopWorkout()

            awaitUntil("the store to be asked") { store.clears > 0 }
            assertNull(store.saved["stage-2"], "nothing worth carrying on from")
        }
    }

    @Test
    fun `continuing starts the road where it was left`() = runTest {
        val store = FakeProgressStore()
        withManager(store) { sessionManager, engine ->
            val road = flatRoad(60_000.0)
            sessionManager.prepareSimulation(
                route = road,
                ftp = 200,
                startDistanceMeters = 42_000.0,
                startElevationGainMeters = 610.0,
            )

            assertEquals(42_000.0, engine.distanceMeters.value)
            // The climbing already done carries too, so the resumed ride's
            // total covers the whole route rather than only today's part.
            assertEquals(610.0, engine.elevationGainMeters.value)
        }
    }

    @Test
    fun `a resume point past the end of the road is clamped to it`() = runTest {
        // A route republished shorter than the one the rider started leaves a
        // mark beyond its end. Riding from past the finish is not a thing.
        val store = FakeProgressStore()
        withManager(store) { _, engine ->
            val road = flatRoad(5_000.0)
            engine.load(road, startDistanceMeters = 99_000.0)
            assertEquals(road.totalDistanceMeters, engine.distanceMeters.value)
        }
    }
}
