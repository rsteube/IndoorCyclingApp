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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN = "8F3K2M"

private fun snapshot() = CompanionSnapshot(
    rider = RiderInfo(massKg = 78.0, ftpWatts = 250),
    bike = BikeInfo(massKg = 9.5, drivetrain = "road-double"),
    gears = listOf(
        GearInfo(0, 1.21, "34/28", 0.72),
        GearInfo(1, 1.36, "34/25", 0.79),
    ),
    gearIndex = 0,
    trainer = TrainerInfo(name = "KICKR CORE", connected = true, control = "sim"),
)

private fun session(
    token: String = TOKEN,
    controllerConnected: () -> Boolean = { false },
) = CompanionSession(
    config = CompanionConfig(appVersion = "2.1.0", sessionId = "3f9a1c", token = token),
    snapshot = ::snapshot,
    controllerConnected = controllerConnected,
)

private fun hello(
    min: String = "0.1",
    max: String = "0.1",
    token: String? = TOKEN,
    role: String? = null,
    name: String = "Alpine Climb",
): String {
    val tokenPart = if (token == null) "" else ""","token":"$token""""
    val rolePart = if (role == null) "" else ""","role":"$role""""
    return """{"t":"hello","protoMin":"$min","protoMax":"$max"$tokenPart,"client":{"name":"$name"}$rolePart}"""
}

private fun world(seq: Long, gradient: Double = 0.05, speed: Double = 8.0, extra: String = "") =
    """{"t":"world","seq":$seq,"gradient":$gradient,"speedMps":$speed$extra}"""

private inline fun <reified T : CompanionEffect> List<CompanionEffect>.pick(): T =
    filterIsInstance<T>().first()

private inline fun <reified T : ServerFrame> List<CompanionEffect>.sent(): T =
    filterIsInstance<CompanionEffect.Send>().map { it.frame }.filterIsInstance<T>().first()

private fun List<CompanionEffect>.sentFrames(): List<ServerFrame> =
    filterIsInstance<CompanionEffect.Send>().map { it.frame }

class CompanionHandshakeTest {

    @Test
    fun `a good hello is answered with a welcome`() {
        val s = session()
        val effects = s.onFrame(hello(), nowMs = 0)

        val welcome = effects.sent<WelcomeFrame>()
        assertEquals("0.1", welcome.proto)
        assertEquals("3f9a1c", welcome.sessionId)
        assertEquals(HelloFrame.ROLE_CONTROLLER, welcome.role)
        assertEquals(250L, welcome.tickMs)
        assertTrue(s.isController)
        assertEquals(ProtocolVersion(0, 1), s.version)
        assertEquals("Alpine Climb", s.clientName)
    }

    /**
     * An implementer should learn the envelope from the handshake rather than
     * discovering it by surprise halfway down a descent.
     */
    @Test
    fun `welcome publishes the clamps and the whole drivetrain`() {
        val welcome = session().onFrame(hello(), nowMs = 0).sent<WelcomeFrame>()
        assertEquals(listOf(-0.30, 0.30), welcome.limits.gradient)
        assertEquals(listOf(0.0, 30.0), welcome.limits.speedMps)
        assertEquals(listOf("34/28", "34/25"), welcome.gears.map { it.label })
        assertContains(welcome.honours, "gradient")
        assertContains(welcome.honours, "speedMps")
        assertTrue("cda" !in welcome.honours)
    }

    @Test
    fun `a client from the future is refused with both ranges and a close reason`() {
        val effects = session().onFrame(hello(min = "2.0", max = "2.3"), nowMs = 0)

        val error = effects.sent<ErrorFrame>()
        assertEquals(CompanionError.PROTO_UNSUPPORTED, error.code)
        assertEquals(CompanionProtocol.SUPPORTED.toString(), error.serverRange)
        assertEquals("2.0-2.3", error.clientRange)
        assertContains(error.message, "Update Indoor Bike")

        // The frame alone is not enough: a client may close before it reads
        // one, and then the close reason is all it ever sees.
        val close = effects.pick<CompanionEffect.Close>()
        assertEquals(CompanionClose.PROTOCOL_MISMATCH, close.code)
        assertContains(close.reason, CompanionProtocol.SUPPORTED.toString())
        assertContains(close.reason, "2.0-2.3")
    }

    @Test
    fun `an unreadable version range is refused rather than guessed at`() {
        val effects = session().onFrame(hello(min = "banana", max = "0.1"), nowMs = 0)
        assertEquals(CompanionError.PROTO_UNSUPPORTED, effects.sent<ErrorFrame>().code)
        assertEquals(CompanionClose.PROTOCOL_MISMATCH, effects.pick<CompanionEffect.Close>().code)
    }

    @Test
    fun `a wrong pairing code is refused`() {
        val effects = session().onFrame(hello(token = "WRONG1"), nowMs = 0)
        assertEquals(CompanionError.UNAUTHORIZED, effects.sent<ErrorFrame>().code)
        assertEquals(CompanionClose.UNAUTHORIZED, effects.pick<CompanionEffect.Close>().code)
    }

    @Test
    fun `a missing pairing code is refused`() {
        val effects = session().onFrame(hello(token = null), nowMs = 0)
        assertEquals(CompanionClose.UNAUTHORIZED, effects.pick<CompanionEffect.Close>().code)
    }

    @Test
    fun `riding before the handshake is refused, not ignored`() {
        val effects = session().onFrame(world(1), nowMs = 0)
        val error = effects.sent<ErrorFrame>()
        assertEquals(CompanionError.UNAUTHORIZED, error.code)
        assertContains(error.message, "hello")
    }

    @Test
    fun `a second controller is turned away while the first holds the ride`() {
        val effects = session(controllerConnected = { true }).onFrame(hello(), nowMs = 0)
        assertEquals(CompanionError.CONTROLLER_BUSY, effects.sent<ErrorFrame>().code)
        assertEquals(CompanionClose.CONTROLLER_BUSY, effects.pick<CompanionEffect.Close>().code)
    }

    @Test
    fun `an observer is admitted even when a controller holds the ride`() {
        val s = session(controllerConnected = { true })
        val welcome = s.onFrame(hello(role = "observer"), nowMs = 0).sent<WelcomeFrame>()
        assertEquals(HelloFrame.ROLE_OBSERVER, welcome.role)
        assertTrue(!s.isController)
    }

    /**
     * An absent role means "did not say", which defaults to controller. An
     * explicit role this build cannot interpret means the opposite: it must not
     * be read as a request for control.
     */
    @Test
    fun `an unrecognised role is granted observer, not control`() {
        val s = session()
        val welcome = s.onFrame(hello(role = "supervisor"), nowMs = 0).sent<WelcomeFrame>()
        assertEquals(HelloFrame.ROLE_OBSERVER, welcome.role)
    }

    @Test
    fun `a malformed frame closes the socket with a reason`() {
        val effects = session().onFrame("{{{", nowMs = 0)
        assertEquals(CompanionError.MALFORMED, effects.sent<ErrorFrame>().code)
        assertEquals(CompanionClose.MALFORMED, effects.pick<CompanionEffect.Close>().code)
    }

    @Test
    fun `nothing more is processed once the socket has been closed`() {
        val s = session()
        s.onFrame(hello(token = "WRONG1"), nowMs = 0)
        assertEquals(emptyList(), s.onFrame(hello(), nowMs = 1))
    }
}

class CompanionRidingTest {

    private fun ready(): CompanionSession = session().also { it.onFrame(hello(), nowMs = 0) }

    @Test
    fun `a world frame becomes a road and a progress report`() {
        val s = ready()
        val effects = s.onFrame(
            """{"t":"world","seq":1,"gradient":0.062,"speedMps":7.42,"distanceM":8412.5,"ascentM":214.0}""",
            nowMs = 100,
        )
        assertEquals(CompanionEffect.ApplyRoad(0.062, 7.42), effects.pick<CompanionEffect.ApplyRoad>())
        assertEquals(
            CompanionEffect.ReportProgress(8412.5, 214.0),
            effects.pick<CompanionEffect.ReportProgress>(),
        )
    }

    @Test
    fun `a clamped value is applied and reported`() {
        val s = ready()
        val effects = s.onFrame(world(1, gradient = 0.42), nowMs = 100)

        val warning = effects.sent<WarningFrame>()
        assertEquals(CompanionWarning.CLAMPED, warning.code)
        assertEquals("gradient", warning.field)
        assertEquals(0.42, warning.sent)
        assertEquals(0.30, warning.applied)
        assertEquals(0.30, effects.pick<CompanionEffect.ApplyRoad>().gradient)
    }

    @Test
    fun `an out of order frame changes nothing`() {
        val s = ready()
        s.onFrame(world(10, gradient = 0.10), nowMs = 100)
        val late = s.onFrame(world(9, gradient = 0.20), nowMs = 150)
        assertTrue(late.filterIsInstance<CompanionEffect.ApplyRoad>().isEmpty())
    }

    /**
     * The difference between an implementer seeing "drafting does not work" and
     * seeing "this build ignores cda".
     */
    @Test
    fun `a field this build does not honour is complained about exactly once`() {
        val s = ready()

        val first = s.onFrame(world(1, extra = ""","cda":0.24"""), nowMs = 100)
        val warning = first.sent<WarningFrame>()
        assertEquals(CompanionWarning.FIELD_NOT_HONOURED, warning.code)
        assertEquals("cda", warning.field)
        assertContains(warning.message ?: "", "welcome.honours")
        // The road is still applied: an unhonoured field is not a broken frame.
        assertEquals(0.05, first.pick<CompanionEffect.ApplyRoad>().gradient)

        val second = s.onFrame(world(2, extra = ""","cda":0.24"""), nowMs = 350)
        assertTrue(second.sentFrames().filterIsInstance<WarningFrame>().isEmpty())

        // A different field earns its own single warning.
        val third = s.onFrame(world(3, extra = ""","surface":"gravel""""), nowMs = 600)
        assertEquals("surface", third.sent<WarningFrame>().field)
    }

    @Test
    fun `a reserved name is explained differently from a typo`() {
        val reserved = ready().onFrame(world(1, extra = ""","headwindMps":3.0"""), nowMs = 100)
        assertContains(reserved.sent<WarningFrame>().message ?: "", "companion owns speed")

        val typo = ready().onFrame(world(1, extra = ""","gradiant":0.1"""), nowMs = 100)
        assertContains(typo.sent<WarningFrame>().message ?: "", "Unknown field")
    }

    @Test
    fun `a shift request is passed to the drivetrain unchanged`() {
        val effects = ready().onFrame("""{"t":"requestShift","steps":-1}""", nowMs = 100)
        assertEquals(CompanionEffect.Shift(-1), effects.pick<CompanionEffect.Shift>())
    }

    /**
     * A held gamepad button must not dump the rider into the top gear, and must
     * not kill the connection either.
     */
    @Test
    fun `shift requests beyond the limit are refused without closing`() {
        val s = ready()
        repeat(CompanionProtocol.MAX_SHIFT_REQUESTS_PER_SECOND) { i ->
            val effects = s.onFrame("""{"t":"requestShift","steps":1}""", nowMs = 100L + i)
            assertEquals(CompanionEffect.Shift(1), effects.pick<CompanionEffect.Shift>(), "request $i")
        }

        val refused = s.onFrame("""{"t":"requestShift","steps":1}""", nowMs = 120)
        assertEquals(CompanionWarning.RATE_LIMITED, refused.sent<WarningFrame>().code)
        assertTrue(refused.filterIsInstance<CompanionEffect.Shift>().isEmpty())
        assertTrue(refused.filterIsInstance<CompanionEffect.Close>().isEmpty())

        // A second later the window has moved on and shifting works again.
        val later = s.onFrame("""{"t":"requestShift","steps":1}""", nowMs = 1_200)
        assertEquals(CompanionEffect.Shift(1), later.pick<CompanionEffect.Shift>())
    }

    @Test
    fun `a label and a finish reach the ride`() {
        val s = ready()
        val labelled = s.onFrame(
            """{"t":"label","name":"Stelvio, north side","world":"Alpine Tour"}""",
            nowMs = 100,
        )
        assertEquals(
            CompanionEffect.SetLabel("Stelvio, north side", "Alpine Tour"),
            labelled.pick<CompanionEffect.SetLabel>(),
        )

        val finished = s.onFrame(
            """{"t":"finish","summary":{"shape":"_p~iF~ps|U","profile":[412,418]}}""",
            nowMs = 200,
        )
        val finish = finished.pick<CompanionEffect.Finish>()
        assertEquals("_p~iF~ps|U", finish.summary?.shape)
        assertEquals(listOf(412, 418), finish.summary?.profile)
    }

    @Test
    fun `a finish without a summary is still a finish`() {
        val finish = ready().onFrame("""{"t":"finish"}""", nowMs = 100).pick<CompanionEffect.Finish>()
        assertEquals(null, finish.summary)
    }

    @Test
    fun `a ping is answered`() {
        val pong = ready().onFrame("""{"t":"ping","id":17}""", nowMs = 100).sent<PongFrame>()
        assertEquals(17L, pong.id)
    }

    @Test
    fun `a frame from a later version is ignored and the socket stays up`() {
        val s = ready()
        assertEquals(emptyList(), s.onFrame("""{"t":"steering","angle":0.4}""", nowMs = 100))
        // Still riding.
        assertEquals(
            0.05,
            s.onFrame(world(1), nowMs = 150).pick<CompanionEffect.ApplyRoad>().gradient,
        )
    }
}

class CompanionObserverTest {

    private fun observer(): CompanionSession =
        session(controllerConnected = { true })
            .also { it.onFrame(hello(role = "observer"), nowMs = 0) }

    @Test
    fun `an observer may not steer, and is told so rather than dropped`() {
        val s = observer()
        val effects = s.onFrame(world(1, gradient = 0.2), nowMs = 100)

        assertEquals(CompanionError.NOT_CONTROLLER, effects.sent<ErrorFrame>().code)
        assertTrue(effects.filterIsInstance<CompanionEffect.ApplyRoad>().isEmpty())
        // A confused dashboard is not a dangerous one: the socket stays open.
        assertTrue(effects.filterIsInstance<CompanionEffect.Close>().isEmpty())
    }

    @Test
    fun `an observer may not shift, label or finish either`() {
        val s = observer()
        listOf(
            """{"t":"requestShift","steps":1}""",
            """{"t":"label","name":"x"}""",
            """{"t":"finish"}""",
        ).forEach { frame ->
            val effects = s.onFrame(frame, nowMs = 100)
            assertEquals(CompanionError.NOT_CONTROLLER, effects.sent<ErrorFrame>().code, frame)
        }
    }

    @Test
    fun `an observer can still be pinged`() {
        assertEquals(1L, observer().onFrame("""{"t":"ping","id":1}""", nowMs = 100).sent<PongFrame>().id)
    }
}

class CompanionShutdownTest {

    private fun ready(): CompanionSession = session().also { it.onFrame(hello(), nowMs = 0) }

    @Test
    fun `taking back control tells the companion and closes`() {
        val s = ready()
        s.onFrame(world(1, gradient = 0.12), nowMs = 100)

        val effects = s.revoke()
        assertEquals(EventFrame.CONTROL_REVOKED, effects.sent<EventFrame>().event)
        assertEquals(CompanionClose.CONTROL_REVOKED, effects.pick<CompanionEffect.Close>().code)
        // The road is released so nothing off the socket is steering any more.
        assertEquals(0.0, s.road.gradient)
    }

    @Test
    fun `the end of a ride closes with its own code`() {
        val effects = ready().rideEnded()
        val event = effects.sent<EventFrame>()
        assertEquals(EventFrame.STATE_CHANGED, event.event)
        assertEquals(CompanionState.FINISHED, event.state)
        assertEquals(CompanionClose.RIDE_ENDED, effects.pick<CompanionEffect.Close>().code)
    }

    @Test
    fun `shutting down twice is harmless`() {
        val s = ready()
        s.rideEnded()
        assertEquals(emptyList(), s.rideEnded())
        assertEquals(emptyList(), s.revoke())
    }
}


/**
 * The course and the pause switch: everything 0.2 added to the client side of
 * the protocol.
 */
class CompanionCourseFrameTest {

    private fun ready(): CompanionSession = session().also { it.onFrame(hello(), nowMs = 0) }

    @Test
    fun `a course frame becomes a course`() {
        val s = ready()
        val effects = s.onFrame(
            """{"t":"course","name":"Volcano Circuit","world":"Watopia",""" +
                """"distanceM":12400.0,"ascentM":180.0,"shape":"abc","profile":[0,40,80]}""",
            nowMs = 1_000,
        )
        val course = effects.pick<CompanionEffect.SetCourse>().course
        assertEquals("Volcano Circuit", course.name)
        assertEquals("Watopia", course.world)
        assertEquals(12_400.0, course.distanceMeters)
        assertEquals(180.0, course.ascentMeters)
        assertEquals("abc", course.shape)
        assertEquals(listOf(0, 40, 80), course.profileMeters)
    }

    @Test
    fun `a course frame carrying nothing produces nothing`() {
        val s = ready()
        assertEquals(emptyList(), s.onFrame("""{"t":"course"}""", nowMs = 1_000))
    }

    /**
     * Half a polyline is not a shorter road, it is one that leaves the map. So
     * an over-long shape is dropped whole and said so, while the rest of the
     * frame still lands: a companion that sends an enormous track alongside a
     * perfectly good name should still get the name.
     */
    @Test
    fun `an over-long shape is refused rather than truncated`() {
        val s = ready()
        val huge = "a".repeat(CompanionProtocol.MAX_COURSE_SHAPE_CHARS + 1)
        val effects = s.onFrame(
            """{"t":"course","name":"Too much","shape":"$huge"}""",
            nowMs = 1_000,
        )
        val warning = effects.sent<WarningFrame>()
        assertEquals(CompanionWarning.REJECTED, warning.code)
        assertEquals("shape", warning.field)

        val course = effects.pick<CompanionEffect.SetCourse>().course
        assertEquals("", course.shape)
        assertEquals("Too much", course.name)
    }

    @Test
    fun `an over-long profile is refused the same way`() {
        val s = ready()
        val samples = (0..CompanionProtocol.MAX_COURSE_PROFILE_SAMPLES).joinToString(",")
        val effects = s.onFrame("""{"t":"course","profile":[$samples]}""", nowMs = 1_000)
        assertEquals("profile", effects.sent<WarningFrame>().field)
        assertTrue(effects.filterIsInstance<CompanionEffect.SetCourse>().isEmpty())
    }

    /**
     * A summary is the short form of a course, and the order is load-bearing:
     * ending the ride is what writes the row, so the shape has to arrive first
     * or it arrives too late to be saved.
     */
    @Test
    fun `a finish summary reaches the course before the ride ends`() {
        val s = ready()
        val effects = s.onFrame(
            """{"t":"finish","summary":{"shape":"abc","profile":[1,2,3]}}""",
            nowMs = 1_000,
        )
        val courseAt = effects.indexOfFirst { it is CompanionEffect.SetCourse }
        val finishAt = effects.indexOfFirst { it is CompanionEffect.Finish }
        assertTrue(courseAt >= 0 && courseAt < finishAt, effects.toString())
        assertEquals("abc", effects.pick<CompanionEffect.SetCourse>().course.shape)
    }

    @Test
    fun `a finish with no summary is still a finish`() {
        val s = ready()
        val effects = s.onFrame("""{"t":"finish"}""", nowMs = 1_000)
        assertTrue(effects.filterIsInstance<CompanionEffect.SetCourse>().isEmpty())
        assertIs<CompanionEffect.Finish>(effects.single())
    }

    @Test
    fun `an observer may not describe the course`() {
        val s = session()
        s.onFrame(hello(role = HelloFrame.ROLE_OBSERVER), nowMs = 0)
        val error = s.onFrame("""{"t":"course","name":"Nope"}""", nowMs = 1_000).sent<ErrorFrame>()
        assertEquals(CompanionError.NOT_CONTROLLER, error.code)
    }
}

class CompanionSetStateTest {

    private fun ready(): CompanionSession = session().also { it.onFrame(hello(), nowMs = 0) }

    @Test
    fun `pause and resume ask for what they say`() {
        val s = ready()
        assertTrue(
            s.onFrame("""{"t":"setState","state":"paused"}""", nowMs = 1_000)
                .pick<CompanionEffect.SetRideState>().paused
        )
        assertFalse(
            s.onFrame("""{"t":"setState","state":"active"}""", nowMs = 1_100)
                .pick<CompanionEffect.SetRideState>().paused
        )
    }

    /**
     * Guessing here has an asymmetric cost: reading a misspelt word as "paused"
     * silently stops recording a ride the rider is still doing, and they find
     * out afterwards.
     */
    @Test
    fun `an unknown state is refused rather than guessed at`() {
        val s = ready()
        val effects = s.onFrame("""{"t":"setState","state":"pasued"}""", nowMs = 1_000)
        assertTrue(effects.filterIsInstance<CompanionEffect.SetRideState>().isEmpty())
        val warning = effects.sent<WarningFrame>()
        assertEquals(CompanionWarning.REJECTED, warning.code)
        assertEquals("state", warning.field)
        assertContains(warning.message.orEmpty(), "pasued")
    }

    @Test
    fun `an observer may not pause the ride`() {
        val s = session()
        s.onFrame(hello(role = HelloFrame.ROLE_OBSERVER), nowMs = 0)
        val error = s.onFrame("""{"t":"setState","state":"paused"}""", nowMs = 1_000)
            .sent<ErrorFrame>()
        assertEquals(CompanionError.NOT_CONTROLLER, error.code)
    }
}

/**
 * A 0.1 companion has to keep working. Everything 0.2 added is optional in both
 * directions, so an older client connects, negotiates down, and rides.
 */
class CompanionVersionCompatibilityTest {

    @Test
    fun `an older companion negotiates down and is welcomed`() {
        val s = session()
        val welcome = s.onFrame(hello(min = "0.1", max = "0.1"), nowMs = 0).sent<WelcomeFrame>()
        assertEquals("0.1", welcome.proto)
        assertTrue(s.isController)
    }

    @Test
    fun `a current companion gets the current version`() {
        val s = session()
        // Spelled from the constant on both sides. Pinning the client's max at
        // whatever today's version happens to be turns every future bump into a
        // failing test that says nothing about compatibility.
        val current = CompanionProtocol.VERSION.toString()
        val welcome = s.onFrame(hello(min = "0.1", max = current), nowMs = 0).sent<WelcomeFrame>()
        assertEquals(current, welcome.proto)
    }

    /**
     * A companion should branch on what the app will act on, not on the version
     * number it happened to negotiate.
     */
    @Test
    fun `the welcome advertises the new frames`() {
        val s = session()
        val welcome = s.onFrame(hello(), nowMs = 0).sent<WelcomeFrame>()
        assertContains(welcome.capabilities, "course")
        assertContains(welcome.capabilities, "setState")
    }
}

/**
 * Borrowing the phone as a remote for a screen the app cannot see.
 */
class CompanionControlsTest {

    private fun ready(): CompanionSession = session().also { it.onFrame(hello(), nowMs = 0) }

    @Test
    fun `a controllable screen arrives with what will answer on it`() {
        val s = ready()
        val effects = s.onFrame(
            """{"t":"controls","visible":true,"title":"Choose a route",""" +
                """"hint":"Pick a lap count","enabled":["up","down","enter","back"]}""",
            nowMs = 1_000,
        )
        val controls = assertNotNull(effects.pick<CompanionEffect.SetControls>().controls)
        assertEquals("Choose a route", controls.title)
        assertEquals("Pick a lap count", controls.hint)
        assertEquals(setOf("up", "down", "enter", "back"), controls.enabled)
        assertTrue(controls.hasAnyEnabled)
        assertFalse(controls.isEnabled(CompanionControl.LEFT))
    }

    @Test
    fun `dismissing takes the pad away`() {
        val s = ready()
        s.onFrame("""{"t":"controls","visible":true,"enabled":["enter"]}""", nowMs = 1_000)
        val effects = s.onFrame("""{"t":"controls","visible":false}""", nowMs = 1_100)
        assertNull(effects.pick<CompanionEffect.SetControls>().controls)
    }

    /**
     * The failure this prevents is an implementer spending an afternoon
     * wondering why their Enter never appeared on the phone.
     */
    @Test
    fun `a misspelt control is named in a warning rather than dropped in silence`() {
        val s = ready()
        val effects = s.onFrame(
            """{"t":"controls","visible":true,"enabled":["up","entre"]}""",
            nowMs = 1_000,
        )
        val warning = effects.sent<WarningFrame>()
        assertEquals(CompanionWarning.REJECTED, warning.code)
        assertEquals("enabled", warning.field)
        assertContains(warning.message.orEmpty(), "entre")

        // The rest of the frame still lands.
        val controls = assertNotNull(effects.pick<CompanionEffect.SetControls>().controls)
        assertEquals(setOf("up"), controls.enabled)
    }

    @Test
    fun `a screen with nothing selectable is still a screen`() {
        // A loading state is a legitimate thing to be showing, and the pad
        // going dead is more honest than the pad vanishing.
        val s = ready()
        val controls = assertNotNull(
            s.onFrame("""{"t":"controls","visible":true,"title":"Loading"}""", nowMs = 1_000)
                .pick<CompanionEffect.SetControls>().controls
        )
        assertFalse(controls.hasAnyEnabled)
        assertEquals("Loading", controls.title)
    }

    @Test
    fun `labels too long to read at arm's length are trimmed`() {
        val s = ready()
        val controls = assertNotNull(
            s.onFrame(
                """{"t":"controls","visible":true,"title":"${"x".repeat(400)}"}""",
                nowMs = 1_000,
            ).pick<CompanionEffect.SetControls>().controls
        )
        assertEquals(
            CompanionProtocol.MAX_CONTROL_LABEL_CHARS,
            assertNotNull(controls.title).length,
        )
    }

    @Test
    fun `an observer may not put buttons on the rider's phone`() {
        val s = session()
        s.onFrame(hello(role = HelloFrame.ROLE_OBSERVER), nowMs = 0)
        val error = s.onFrame("""{"t":"controls","visible":true}""", nowMs = 1_000)
            .sent<ErrorFrame>()
        assertEquals(CompanionError.NOT_CONTROLLER, error.code)
    }

    @Test
    fun `the welcome advertises the capability`() {
        val s = session()
        assertContains(s.onFrame(hello(), nowMs = 0).sent<WelcomeFrame>().capabilities, "controls")
    }
}
