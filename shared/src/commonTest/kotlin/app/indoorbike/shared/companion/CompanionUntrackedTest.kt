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

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN = "8F3K2M"

private fun snapshot() = CompanionSnapshot(
    rider = RiderInfo(massKg = 78.0, ftpWatts = 250),
    bike = BikeInfo(massKg = 9.5, drivetrain = "road-double"),
    gears = listOf(GearInfo(0, 1.21, "34/28", 0.72)),
    gearIndex = 0,
    trainer = TrainerInfo(name = "KICKR CORE", connected = true, control = "sim"),
)

private fun hello(role: String? = null): String {
    val rolePart = if (role == null) "" else ""","role":"$role""""
    return """{"t":"hello","protoMin":"0.1","protoMax":"0.10","token":"$TOKEN",""" +
        """"client":{"name":"Col de Berg"}$rolePart}"""
}

private inline fun <reified T : CompanionEffect> List<CompanionEffect>.pick(): T =
    filterIsInstance<T>().first()

private inline fun <reified T : ServerFrame> List<CompanionEffect>.sent(): T =
    filterIsInstance<CompanionEffect.Send>().map { it.frame }.filterIsInstance<T>().first()

/**
 * A ride that is being ridden and not written down, and the power target that
 * only exists inside it.
 */
class CompanionUntrackedTest {

    private fun ready(
        state: () -> String = { CompanionState.UNTRACKED },
        role: String? = null,
    ): CompanionSession = CompanionSession(
        config = CompanionConfig(appVersion = "2.3.0", sessionId = "3f9a1c", token = TOKEN),
        snapshot = ::snapshot,
        rideState = state,
    ).also { it.onFrame(hello(role), nowMs = 0) }

    @Test
    fun `the welcome offers untracked riding and says what a target may be`() {
        val welcome = CompanionSession(
            config = CompanionConfig(appVersion = "2.3.0", sessionId = "3f9a1c", token = TOKEN),
            snapshot = ::snapshot,
        ).onFrame(hello(), nowMs = 0).sent<WelcomeFrame>()

        assertEquals("0.10", welcome.proto)
        assertContains(welcome.capabilities, "untracked")
        assertContains(welcome.capabilities, "setPower")
        assertEquals(listOf(0, 1_000), welcome.limits.powerWatts)
    }

    @Test
    fun `untracked is asked for like any other state, and carries a label`() {
        val s = ready(state = { CompanionState.ACTIVE })
        val effect = s.onFrame(
            """{"t":"setState","state":"untracked","label":"Pushing the bike"}""",
            nowMs = 1_000,
        ).pick<CompanionEffect.SetRideState>()

        assertEquals(CompanionState.UNTRACKED, effect.state)
        assertEquals("Pushing the bike", effect.label)
    }

    @Test
    fun `a label is trimmed to what a handlebar can be read over`() {
        val s = ready()
        val long = "x".repeat(80)
        val effect = s.onFrame(
            """{"t":"setState","state":"untracked","label":"$long"}""",
            nowMs = 1_000,
        ).pick<CompanionEffect.SetRideState>()

        assertEquals(CompanionProtocol.MAX_CONTROL_LABEL_CHARS, effect.label?.length)
    }

    @Test
    fun `a misspelt state still names all three of them`() {
        val warning = ready()
            .onFrame("""{"t":"setState","state":"untraked"}""", nowMs = 1_000)
            .sent<WarningFrame>()

        assertEquals(CompanionWarning.REJECTED, warning.code)
        assertContains(warning.message.orEmpty(), "untracked")
    }

    @Test
    fun `a power target is taken while untracked`() {
        val s = ready()
        val effects = s.onFrame("""{"t":"setPower","watts":125}""", nowMs = 1_000)

        assertEquals(125, effects.pick<CompanionEffect.SetPowerTarget>().watts)
        assertTrue(effects.filterIsInstance<CompanionEffect.Send>().isEmpty(), "nothing to warn about")
    }

    /** A power target that could reach a recorded ride would make its numbers negotiable. */
    @Test
    fun `a power target is refused while the ride is being recorded`() {
        val s = ready(state = { CompanionState.ACTIVE })
        val effects = s.onFrame("""{"t":"setPower","watts":125}""", nowMs = 1_000)

        assertTrue(effects.filterIsInstance<CompanionEffect.SetPowerTarget>().isEmpty())
        val warning = effects.sent<WarningFrame>()
        assertEquals(CompanionWarning.REJECTED, warning.code)
        assertEquals("state", warning.field)
    }

    @Test
    fun `a target out of range is clamped and said so, like a road`() {
        val effects = ready().onFrame("""{"t":"setPower","watts":1500}""", nowMs = 1_000)

        val warning = effects.sent<WarningFrame>()
        assertEquals(CompanionWarning.CLAMPED, warning.code)
        assertEquals("watts", warning.field)
        assertEquals(1500.0, warning.sent)
        assertEquals(1000.0, warning.applied)
        assertEquals(1000, effects.pick<CompanionEffect.SetPowerTarget>().watts)
    }

    @Test
    fun `null hands the trainer back to the road`() {
        val effect = ready()
            .onFrame("""{"t":"setPower","watts":null}""", nowMs = 1_000)
            .pick<CompanionEffect.SetPowerTarget>()

        assertNull(effect.watts)
    }

    /** Godot writes every number as a float, and a whole one is still a wattage. */
    @Test
    fun `a wattage spelled with a fraction is read for its value`() {
        val effect = ready()
            .onFrame("""{"t":"setPower","watts":125.0}""", nowMs = 1_000)
            .pick<CompanionEffect.SetPowerTarget>()

        assertEquals(125, effect.watts)
    }

    @Test
    fun `a setPower with no wattage at all is malformed`() {
        val effects = ready().onFrame("""{"t":"setPower"}""", nowMs = 1_000)

        val error = effects.sent<ErrorFrame>()
        assertEquals(CompanionError.MALFORMED, error.code)
    }

    @Test
    fun `an observer may not hold the rider at a wattage`() {
        val s = ready(role = HelloFrame.ROLE_OBSERVER)
        val error = s.onFrame("""{"t":"setPower","watts":125}""", nowMs = 1_000).sent<ErrorFrame>()

        assertEquals(CompanionError.NOT_CONTROLLER, error.code)
    }

    @Test
    fun `a companion ramping a target in a loop has its surplus dropped`() {
        val s = ready()
        repeat(CompanionProtocol.MAX_SHIFT_REQUESTS_PER_SECOND) {
            s.onFrame("""{"t":"setPower","watts":${100 + it}}""", nowMs = 1_000L + it)
        }
        val effects = s.onFrame("""{"t":"setPower","watts":200}""", nowMs = 1_050)

        assertTrue(effects.filterIsInstance<CompanionEffect.SetPowerTarget>().isEmpty())
        assertEquals(CompanionWarning.RATE_LIMITED, effects.sent<WarningFrame>().code)

        // A second later there is room again.
        val after = s.onFrame("""{"t":"setPower","watts":200}""", nowMs = 2_100)
        assertEquals(200, after.pick<CompanionEffect.SetPowerTarget>().watts)
    }

    /**
     * The walk up the climb is not ridden, and must not arrive later either:
     * a total kept as a maximum would come back whole on the first frame after
     * the rider starts riding again.
     */
    @Test
    fun `ground covered while untracked is neither banked nor remembered`() {
        var state = CompanionState.ACTIVE
        val s = ready(state = { state })

        s.onFrame(
            """{"t":"world","seq":1,"gradient":0.05,"speedMps":8.0,"distanceM":475.0,"ascentM":61.0}""",
            nowMs = 1_000,
        ).pick<CompanionEffect.ReportProgress>().let {
            assertEquals(475.0, it.distanceM)
        }

        state = CompanionState.UNTRACKED
        val walked = s.onFrame(
            """{"t":"world","seq":2,"gradient":0.0,"speedMps":1.94,"distanceM":900.0,"ascentM":140.0}""",
            nowMs = 2_000,
        )
        assertTrue(
            walked.filterIsInstance<CompanionEffect.ReportProgress>().isEmpty(),
            "nothing covered while untracked is this ride's",
        )
        // The road itself is still applied: the rider is riding, whatever the
        // record says about it.
        assertEquals(1.94, walked.pick<CompanionEffect.ApplyRoad>().speedMps)

        state = CompanionState.ACTIVE
        val back = s.onFrame(
            """{"t":"world","seq":3,"gradient":0.021,"speedMps":0.0,"distanceM":475.0,"ascentM":61.0}""",
            nowMs = 3_000,
        ).pick<CompanionEffect.ReportProgress>()
        assertEquals(475.0, back.distanceM, "the walk did not raise the kept maximum")
        assertEquals(61.0, back.ascentM)
    }
}
