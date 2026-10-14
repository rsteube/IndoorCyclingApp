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

import app.indoorbike.shared.simulation.Drivetrain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN = "8F3K2M"

private val ERG_PHONE = CompanionProtocol.CAPABILITIES +
    CompanionProtocol.RUN_CAPABILITY + CompanionProtocol.RUN_POWER_CAPABILITY

private fun snapshot() = CompanionSnapshot(
    rider = RiderInfo(massKg = 75.0, ftpWatts = 250),
    bike = BikeInfo(massKg = 9.0, drivetrain = "road-double"),
    gears = listOf(GearInfo(0, 1.21, "34/28", 0.72)),
    gearIndex = 0,
    trainer = TrainerInfo(name = "KICKR CORE", connected = true, control = "sim"),
)

private fun ergSession(
    phone: List<String> = ERG_PHONE,
    game: String = """["run","runPower"]""",
    running: Boolean = false,
    onPower: Boolean = false,
    state: String = CompanionState.ACTIVE,
): Pair<CompanionSession, WelcomeFrame> {
    val s = CompanionSession(
        config = CompanionConfig(appVersion = "2.13.0", sessionId = "3f9a1c", token = TOKEN, capabilities = phone),
        snapshot = ::snapshot,
        rideState = { state },
        running = { running },
        runningOnPower = { onPower },
    )
    val hello = """{"t":"hello","protoMin":"0.1","protoMax":"0.15","token":"$TOKEN","client":{"name":"Col de Berg"},""" +
        """"capabilities":$game}"""
    val welcome = s.onFrame(hello, nowMs = 0)
        .filterIsInstance<CompanionEffect.Send>().map { it.frame }.filterIsInstance<WelcomeFrame>().single()
    return s to welcome
}

private const val K110 =
    """{"t":"course","mode":"course","sport":"run","name":"K110 run practice","world":"Col de Berg"}"""

private class ErgRecorder {
    val events = mutableListOf<Pair<Int?, String>>()
    val gearEvents = mutableListOf<Int>()
    val engine = CompanionEngine(
        powerWatts = { 150 },
        cadenceRpm = { 90 },
        applyRoad = {},
        riderMassKg = { 75f },
        bikeMassKg = { 9f },
        drivetrainProvider = { Drivetrain.DEFAULT },
    ).also {
        it.load()
        it.onPowerTargetChanged = { w, source -> events += w to source }
        it.onGearChanged = { i, _, _ -> gearEvents += i }
    }

    fun ergRun(watts: Int? = 180) {
        engine.setCourse(CompanionCourse(name = "K110", run = true, runPower = true), replace = true)
        engine.setDrivetrain(Drivetrain.custom("Runner", listOf(42), listOf(17), 2105))
        watts?.let { engine.setPowerTarget(it, CompanionEngine.SOURCE_COMPANION) }
    }
}

/**
 * Companion protocol 0.15, the phone's side: a run held in ERG at a target the
 * rider moves with the buttons that shift on a bike. Agreed with Col de Berg.
 */
class CompanionRunPowerTest {

    // Negotiation ────────────────────────────────────────────────────────────

    @Test
    fun `0_15 is spoken, and runPower needs both ends and a run`() {
        val (s, welcome) = ergSession()
        assertEquals("0.15", welcome.proto)
        assertTrue(CompanionProtocol.RUN_POWER_CAPABILITY in welcome.capabilities)
        assertTrue(s.runsOnPower)

        val (old, _) = ergSession(game = """["run"]""")
        assertFalse(old.runsOnPower)
        val (plain, _) = ergSession(phone = CompanionProtocol.CAPABILITIES + CompanionProtocol.RUN_CAPABILITY)
        assertFalse(plain.runsOnPower)
    }

    @Test
    fun `a run is held in ERG only when both listed runPower`() {
        val (s, _) = ergSession()
        val course = s.onFrame(K110, nowMs = 100).filterIsInstance<CompanionEffect.SetCourse>().single().course
        assertTrue(course.run)
        assertTrue(course.runPower)

        val (old, _) = ergSession(game = """["run"]""")
        val fallback = old.onFrame(K110, nowMs = 100).filterIsInstance<CompanionEffect.SetCourse>().single().course
        assertTrue(fallback.run)
        assertFalse(fallback.runPower, "a 0.14 game's run is the 0.14 load")
    }

    // setPower and requestShift ──────────────────────────────────────────────

    @Test
    fun `a run held in ERG takes a target in a recorded ride`() {
        val (s, _) = ergSession(running = true, onPower = true, state = CompanionState.ACTIVE)
        val effects = s.onFrame("""{"t":"setPower","watts":180}""", nowMs = 100)
        assertEquals(180, effects.filterIsInstance<CompanionEffect.SetPowerTarget>().single().watts)
    }

    @Test
    fun `any other recorded ride still refuses one`() {
        val (s, _) = ergSession(running = true, onPower = false, state = CompanionState.ACTIVE)
        val effects = s.onFrame("""{"t":"setPower","watts":180}""", nowMs = 100)
        assertTrue(effects.none { it is CompanionEffect.SetPowerTarget })
        assertEquals(CompanionWarning.REJECTED, effects.filterIsInstance<CompanionEffect.Send>()
            .map { it.frame }.filterIsInstance<WarningFrame>().single().code)
    }

    @Test
    fun `requestShift on a run held in ERG is a step, without a warning`() {
        val (s, _) = ergSession(running = true, onPower = true)
        val effects = s.onFrame("""{"t":"requestShift","steps":2}""", nowMs = 100)
        assertEquals(2, effects.filterIsInstance<CompanionEffect.Shift>().single().steps)
        assertTrue(effects.none { it is CompanionEffect.Send })
    }

    // The engine ─────────────────────────────────────────────────────────────

    @Test
    fun `plus and minus move the target five watts, from every source, and say so`() {
        val r = ErgRecorder()
        r.ergRun(180)
        r.events.clear()
        r.engine.shiftUp(CompanionEngine.SOURCE_SCREEN)
        r.engine.shiftUp(CompanionEngine.SOURCE_CLICK)
        r.engine.shiftBy(-3, CompanionEngine.SOURCE_COMPANION)
        assertEquals(175, r.engine.powerTarget.value)
        assertEquals(
            listOf<Pair<Int?, String>>(
                185 to CompanionEngine.SOURCE_SCREEN,
                190 to CompanionEngine.SOURCE_CLICK,
                175 to CompanionEngine.SOURCE_COMPANION,
            ),
            r.events,
        )
        assertTrue(r.gearEvents.isEmpty(), "no gearChanged on a run")
        assertEquals(0, r.engine.gearIndex.value)
    }

    @Test
    fun `the target is clamped, and a press before one exists does nothing`() {
        val r = ErgRecorder()
        r.ergRun(watts = null)
        r.engine.shiftUp()
        assertNull(r.engine.powerTarget.value)

        r.engine.setPowerTarget(5, CompanionEngine.SOURCE_COMPANION)
        r.engine.shiftBy(-3)
        assertEquals(0, r.engine.powerTarget.value)
        r.engine.setPowerTarget(998, CompanionEngine.SOURCE_COMPANION)
        r.engine.shiftUp()
        assertEquals(CompanionProtocol.MAX_POWER_WATTS, r.engine.powerTarget.value)
    }

    @Test
    fun `a 0_14 run does nothing with a press`() {
        val r = ErgRecorder()
        r.engine.setCourse(CompanionCourse(name = "K110", run = true), replace = true)
        r.engine.shiftUp()
        assertNull(r.engine.powerTarget.value)
        assertTrue(r.events.isEmpty())
    }

    @Test
    fun `the run keeps its target through a pause`() {
        val r = ErgRecorder()
        r.ergRun(180)
        r.engine.start()
        r.engine.shiftUp()
        r.events.clear()
        r.engine.stop()
        assertEquals(185, r.engine.powerTarget.value, "kept through the pause")
        assertTrue(r.events.isEmpty(), "and nothing said about it")
        r.engine.start()
        assertEquals(185, r.engine.powerTarget.value)
        r.engine.stop()
    }

    @Test
    fun `a bike ride's target still goes with the stop`() {
        val r = ErgRecorder()
        r.engine.setPowerTarget(150, CompanionEngine.SOURCE_COMPANION)
        r.engine.start()
        r.engine.stop()
        assertNull(r.engine.powerTarget.value)
    }

    @Test
    fun `the run's target ends with the run`() {
        val r = ErgRecorder()
        r.ergRun(180)
        r.events.clear()
        r.engine.setCourse(CompanionCourse(name = "K110 again", run = true, runPower = true), replace = true)
        assertNull(r.engine.powerTarget.value, "the next ride starts without one")
        assertEquals(listOf<Pair<Int?, String>>(null to CompanionEngine.SOURCE_STATE), r.events)

        r.engine.setPowerTarget(180, CompanionEngine.SOURCE_COMPANION)
        r.events.clear()
        r.engine.load()
        assertNull(r.engine.powerTarget.value)
        assertEquals(listOf<Pair<Int?, String>>(null to CompanionEngine.SOURCE_STATE), r.events)
    }

    // The ramp ───────────────────────────────────────────────────────────────

    @Test
    fun `a step ramps from where the trainer is, 25 W in half a second`() {
        val hold = PowerHold()
        hold.set(180, riderWatts = 180, nowMs = 0)
        hold.update(nowMs = 3_000, cadenceRpm = 90, worldAgeMs = 0)
        assertEquals(180, hold.commanded)

        hold.adjust(185, nowMs = 3_000)
        hold.update(nowMs = 3_050, cadenceRpm = 90, worldAgeMs = 0)
        assertTrue(hold.commanded in 181..184, "half way: ${hold.commanded}")
        hold.update(nowMs = 3_100, cadenceRpm = 90, worldAgeMs = 0)
        assertEquals(185, hold.commanded)

        hold.adjust(135, nowMs = 4_000)
        hold.update(nowMs = 4_500, cadenceRpm = 90, worldAgeMs = 0)
        assertEquals(160, hold.commanded)
        hold.update(nowMs = 5_000, cadenceRpm = 90, worldAgeMs = 0)
        assertEquals(135, hold.commanded)
    }

    @Test
    fun `a step does not take back a rider who was let go`() {
        val hold = PowerHold()
        hold.set(180, riderWatts = 180, nowMs = 0)
        hold.update(nowMs = 0, cadenceRpm = 10, worldAgeMs = 0)
        hold.update(nowMs = 2_500, cadenceRpm = 10, worldAgeMs = 0)
        assertTrue(hold.released)
        hold.adjust(185, nowMs = 2_500)
        hold.update(nowMs = 3_000, cadenceRpm = 10, worldAgeMs = 0)
        assertEquals(0, hold.commanded)
    }
}
