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
package app.indoorbike.shared.companion

import app.indoorbike.shared.physics.VirtualSpeed
import app.indoorbike.shared.simulation.Drivetrain
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN = "8F3K2M"
private const val RIDER_KG = 78f
private const val BIKE_KG = 9f

private val RUN_PHONE = CompanionProtocol.CAPABILITIES + CompanionProtocol.RUN_CAPABILITY

private fun snapshot() = CompanionSnapshot(
    rider = RiderInfo(massKg = 78.0, ftpWatts = 250),
    bike = BikeInfo(massKg = 9.0, drivetrain = "road-double"),
    gears = listOf(GearInfo(0, 1.21, "34/28", 0.72)),
    gearIndex = 0,
    trainer = TrainerInfo(name = "KICKR CORE", connected = true, control = "sim"),
)

private fun session(
    phone: List<String> = RUN_PHONE,
    game: String = """["run"]""",
    running: Boolean = false,
): Pair<CompanionSession, WelcomeFrame> {
    val s = CompanionSession(
        config = CompanionConfig(appVersion = "2.12.0", sessionId = "3f9a1c", token = TOKEN, capabilities = phone),
        snapshot = ::snapshot,
        rideState = { CompanionState.ACTIVE },
        running = { running },
    )
    val hello = """{"t":"hello","protoMin":"0.1","protoMax":"0.14","token":"$TOKEN","client":{"name":"Col de Berg"},""" +
        """"capabilities":$game}"""
    val welcome = s.onFrame(hello, nowMs = 0).sent().filterIsInstance<WelcomeFrame>().single()
    return s to welcome
}

private fun List<CompanionEffect>.sent(): List<ServerFrame> =
    filterIsInstance<CompanionEffect.Send>().map { it.frame }

private fun List<CompanionEffect>.warnings(): List<WarningFrame> = sent().filterIsInstance<WarningFrame>()

private fun List<CompanionEffect>.course(): CompanionEffect.SetCourse =
    filterIsInstance<CompanionEffect.SetCourse>().single()

private const val K110 =
    """{"t":"course","mode":"course","sport":"run","name":"K110 run practice: Innsbruck to Birgitz",""" +
        """"world":"Col de Berg","distanceM":23400,"ascentM":1180}"""

/** The one gear Col de Berg gives its runner. */
private val RUNNER = Drivetrain.custom("Runner", listOf(42), listOf(17), 2105)

private class RunRecorder(private val cadence: Int = 90) {
    val engine = CompanionEngine(
        powerWatts = { 200 },
        cadenceRpm = { cadence },
        applyRoad = {},
        riderMassKg = { RIDER_KG },
        bikeMassKg = { BIKE_KG },
        drivetrainProvider = { Drivetrain.DEFAULT },
    ).also { it.load() }

    fun startRun() {
        engine.setCourse(CompanionCourse(name = "K110", run = true), replace = true)
        engine.setDrivetrain(RUNNER)
    }
}

/** The force 0.14 promises a game for a run, written out independently of the engine. */
private fun runForce(gradient: Double, crr: Double = CompanionProtocol.DEFAULT_CRR): Double {
    val weight = (RIDER_KG + BIKE_KG) * VirtualSpeed.GRAVITY_MS2
    return weight * (gradient + crr) / sqrt(1.0 + gradient * gradient) * RUNNER.loadFactor(0)
}

/**
 * Companion protocol 0.14, the phone's side: a run on a game's course, driven
 * from the bike. Agreed with Col de Berg, whose K110 course is the first.
 */
class CompanionRunTest {

    // Negotiation ────────────────────────────────────────────────────────────

    @Test
    fun `0_14 is spoken, and run is listed only by a phone that shows one`() {
        val (_, plain) = session(phone = CompanionProtocol.CAPABILITIES)
        assertEquals("0.14", plain.proto)
        assertFalse(CompanionProtocol.RUN_CAPABILITY in plain.capabilities)

        val (s, showing) = session()
        assertTrue(CompanionProtocol.RUN_CAPABILITY in showing.capabilities)
        assertTrue(s.runs)
        assertTrue("courseGradient" in showing.honours)
    }

    // course.sport ───────────────────────────────────────────────────────────

    @Test
    fun `a course with a mode and sport run describes a run`() {
        val (s, _) = session()
        val set = s.onFrame(K110, nowMs = 100).course()
        assertTrue(set.replace)
        assertTrue(set.course.run)
        assertEquals("K110 run practice: Innsbruck to Birgitz", set.course.name)
        assertTrue(set.course.mergedWith(CompanionCourse(shape = "abc")).run, "an update keeps the sport")
    }

    @Test
    fun `sport without a mode changes nothing`() {
        val (s, _) = session()
        val set = s.onFrame("""{"t":"course","sport":"run","name":"K110"}""", nowMs = 100).course()
        assertFalse(set.replace)
        assertFalse(set.course.run)
    }

    @Test
    fun `sport is not read from a game, or for a phone, that did not list run`() {
        val (game, _) = session(game = "[]")
        assertFalse(game.runs)
        assertFalse(game.onFrame(K110, nowMs = 100).course().course.run)

        val (phone, _) = session(phone = CompanionProtocol.CAPABILITIES)
        val effects = phone.onFrame(K110, nowMs = 100)
        assertFalse(effects.course().course.run)
        assertTrue(effects.warnings().isEmpty(), "got ${effects.warnings()}")
    }

    @Test
    fun `ride is a bike ride, and an unknown sport is one too, said once`() {
        val (s, _) = session()
        val ride = s.onFrame("""{"t":"course","mode":"course","sport":"ride","name":"Stelvio"}""", nowMs = 100)
        assertFalse(ride.course().course.run)
        assertTrue(ride.warnings().isEmpty())

        val swim = s.onFrame("""{"t":"course","mode":"course","sport":"swim","name":"Lake"}""", nowMs = 200)
        assertFalse(swim.course().course.run)
        assertEquals(listOf("sport"), swim.warnings().map { it.field })
        assertEquals(CompanionWarning.REJECTED, swim.warnings().single().code)

        val again = s.onFrame("""{"t":"course","mode":"course","sport":"swim","name":"Lake"}""", nowMs = 300)
        assertTrue(again.warnings().isEmpty(), "said once per connection")
    }

    // requestShift ───────────────────────────────────────────────────────────

    @Test
    fun `a run answers requestShift with one warning and never shifts`() {
        val (s, _) = session(running = true)
        val first = s.onFrame("""{"t":"requestShift","steps":1}""", nowMs = 100)
        assertTrue(first.none { it is CompanionEffect.Shift })
        assertEquals("requestShift", first.warnings().single().frame)
        assertEquals(CompanionWarning.REJECTED, first.warnings().single().code)

        val second = s.onFrame("""{"t":"requestShift","steps":-1}""", nowMs = 200)
        assertTrue(second.isEmpty(), "got $second")
    }

    @Test
    fun `a bike ride still shifts`() {
        val (s, _) = session(running = false)
        val effects = s.onFrame("""{"t":"requestShift","steps":1}""", nowMs = 100)
        assertEquals(1, effects.filterIsInstance<CompanionEffect.Shift>().single().steps)
    }

    // world.courseGradient ───────────────────────────────────────────────────

    @Test
    fun `the course's grade travels beside the load, and is shown rather than felt`() {
        val (s, _) = session()
        val effects = s.onFrame(
            """{"t":"world","seq":1,"gradient":0.04,"courseGradient":0.27,"speedMps":2.1}""",
            nowMs = 100,
        )
        val road = effects.filterIsInstance<CompanionEffect.ApplyRoad>().single()
        assertEquals(0.04, road.gradient)
        assertEquals(0.27, road.courseGradient)
        assertTrue(effects.warnings().isEmpty(), "courseGradient is honoured: ${effects.warnings()}")

        val without = s.onFrame("""{"t":"world","seq":2,"gradient":0.04,"speedMps":2.1}""", nowMs = 200)
        assertNull(without.filterIsInstance<CompanionEffect.ApplyRoad>().single().courseGradient)
    }

    @Test
    fun `a grade that is not one is refused, once, and the road is still ridden`() {
        val (s, _) = session()
        val effects = s.onFrame(
            """{"t":"world","seq":1,"gradient":0.04,"courseGradient":1.7,"speedMps":2.1}""",
            nowMs = 100,
        )
        val road = effects.filterIsInstance<CompanionEffect.ApplyRoad>().single()
        assertEquals(0.04, road.gradient)
        assertNull(road.courseGradient)
        assertEquals("courseGradient", effects.warnings().single().field)

        val again = s.onFrame(
            """{"t":"world","seq":2,"gradient":0.04,"courseGradient":-3.0,"speedMps":2.1}""",
            nowMs = 200,
        )
        assertTrue(again.warnings().isEmpty())
    }

    // The engine ─────────────────────────────────────────────────────────────

    @Test
    fun `a run's road is a pure load, the same at any speed`() {
        val r = RunRecorder()
        r.startRun()
        assertTrue(r.engine.isRun)

        r.engine.setRoad(gradient = 0.05, speedMps = 1.0, nowMs = 0)
        val slow = r.engine.trainerForceNewtons(0)
        r.engine.setRoad(gradient = 0.05, speedMps = 5.0, nowMs = 0)
        val fast = r.engine.trainerForceNewtons(0)

        assertTrue(abs(slow - runForce(0.05)) < 1e-6, "slow $slow, expected ${runForce(0.05)}")
        assertTrue(abs(fast - runForce(0.05)) < 1e-6, "fast $fast, expected ${runForce(0.05)}")
    }

    @Test
    fun `a slow climb after a stop gets no launch assist`() {
        val r = RunRecorder()
        r.startRun()
        var now = 0L
        // Stopped, then hiking a wall at 1 m/s, under the assist's threshold.
        repeat(8) {
            r.engine.setRoad(gradient = 0.06, speedMps = if (it < 4) 0.0 else 1.0, nowMs = now)
            r.engine.advance(now)
            now += 250
        }
        assertTrue(abs(r.engine.trainerForceNewtons(0) - runForce(0.06)) < 1e-6)
    }

    @Test
    fun `the surface still counts on a run`() {
        val r = RunRecorder()
        r.startRun()
        r.engine.setRoad(gradient = 0.03, speedMps = 3.0, crr = 0.012, nowMs = 0)
        assertTrue(abs(r.engine.trainerForceNewtons(0) - runForce(0.03, crr = 0.012)) < 1e-6)
    }

    @Test
    fun `a runner's one gear does not shift, from anywhere, and says nothing`() {
        val r = RunRecorder()
        r.startRun()
        val events = mutableListOf<Int>()
        r.engine.onGearChanged = { i, _, _ -> events += i }
        r.engine.shiftUp(CompanionEngine.SOURCE_CLICK)
        r.engine.shiftDown(CompanionEngine.SOURCE_SCREEN)
        r.engine.shiftBy(3, CompanionEngine.SOURCE_COMPANION)
        assertEquals(0, r.engine.gearIndex.value)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `the next ride is a bike ride again`() {
        val r = RunRecorder()
        r.startRun()
        r.engine.setCourse(CompanionCourse(name = "Stelvio"), replace = true)
        assertFalse(r.engine.isRun)
        r.engine.setRoad(gradient = 0.05, speedMps = 2.0, nowMs = 0)
        val slow = r.engine.trainerForceNewtons(0)
        r.engine.setRoad(gradient = 0.05, speedMps = 8.0, nowMs = 0)
        assertTrue(r.engine.trainerForceNewtons(0) > slow, "air is back on a bike")

        r.startRun()
        r.engine.load()
        assertFalse(r.engine.isRun, "a prepared ride is no run until one is described")
    }

    @Test
    fun `the rider's own bike comes back on request`() {
        val r = RunRecorder()
        r.startRun()
        assertEquals(1, r.engine.drivetrain.value.gearCount)
        r.engine.restoreRiderDrivetrain()
        assertEquals(Drivetrain.DEFAULT.gearCount, r.engine.drivetrain.value.gearCount)
    }

    @Test
    fun `the course's grade is kept for the HUD and forgotten with the ride`() {
        val r = RunRecorder()
        r.engine.setRoad(gradient = 0.04, speedMps = 2.0, nowMs = 0, courseGradient = 0.27)
        assertEquals(0.27, r.engine.courseGradient.value)
        assertEquals(0.04, r.engine.gradient.value)
        r.engine.setRoad(gradient = 0.04, speedMps = 2.0, nowMs = 100)
        assertNull(r.engine.courseGradient.value)
        r.engine.setRoad(gradient = 0.04, speedMps = 2.0, nowMs = 200, courseGradient = 0.1)
        r.engine.load()
        assertNull(r.engine.courseGradient.value)
    }

    // Pace ───────────────────────────────────────────────────────────────────

    @Test
    fun `pace is minutes and seconds a kilometre, and nothing when stopped`() {
        assertEquals("5:00", RunPace.format(1000.0 / 300.0))
        assertEquals("4:10", RunPace.format(4.0))
        assertEquals("10:00", RunPace.format(1000.0 / 600.0))
        assertEquals("1:00", RunPace.format(1000.0 / 59.6))
        assertNull(RunPace.format(0.0))
        assertNull(RunPace.format(0.2))
        assertNull(RunPace.format(Double.NaN))
    }
}
