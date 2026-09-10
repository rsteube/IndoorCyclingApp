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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompanionCodecTest {

    @Test
    fun `decodes a world frame`() {
        val decoded = CompanionCodec.decodeClientFrame(
            """{"t":"world","seq":412,"gradient":0.062,"speedMps":7.42,"distanceM":8412.5,"ascentM":214.0}"""
        )
        val ok = assertIs<Decoded.Ok>(decoded)
        val frame = assertIs<WorldFrame>(ok.frame)
        assertEquals(412L, frame.seq)
        assertEquals(0.062, frame.gradient)
        assertEquals(7.42, frame.speedMps)
        assertEquals(8412.5, frame.distanceM)
        assertEquals(214.0, frame.ascentM)
    }

    @Test
    fun `omitted optional fields decode as null rather than zero`() {
        val decoded = CompanionCodec.decodeClientFrame(
            """{"t":"world","seq":1,"gradient":0.0,"speedMps":0.0}"""
        )
        val frame = assertIs<WorldFrame>(assertIs<Decoded.Ok>(decoded).frame)
        assertEquals(null, frame.distanceM)
        assertEquals(null, frame.ascentM)
    }

    /**
     * The tolerance that lets the protocol grow. A 0.1 build must sit on a
     * socket with a peer that has learned to say something new and simply not
     * care.
     */
    @Test
    fun `an unknown frame type is reported as unknown, not as an error`() {
        val decoded = CompanionCodec.decodeClientFrame("""{"t":"steering","angle":0.4}""")
        assertEquals(Decoded.Unknown("steering"), decoded)
    }

    @Test
    fun `an unknown field does not stop a frame decoding`() {
        val decoded = CompanionCodec.decodeClientFrame(
            """{"t":"world","seq":5,"gradient":0.02,"speedMps":6.0,"cda":0.24,"surface":"gravel"}"""
        )
        val ok = assertIs<Decoded.Ok>(decoded)
        assertIs<WorldFrame>(ok.frame)
        // The raw keys survive, which is the only way the session can tell a
        // field that was sent-and-ignored from one that was never sent.
        assertContains(ok.fields, "cda")
        assertContains(ok.fields, "surface")
    }

    @Test
    fun `malformed input is reported with a readable reason`() {
        assertIs<Decoded.Malformed>(CompanionCodec.decodeClientFrame("not json at all"))
        assertIs<Decoded.Malformed>(CompanionCodec.decodeClientFrame("[1,2,3]"))
        assertIs<Decoded.Malformed>(CompanionCodec.decodeClientFrame("""{"seq":1}"""))

        val wrongType = CompanionCodec.decodeClientFrame(
            """{"t":"world","seq":"not a number","gradient":0.0,"speedMps":0.0}"""
        )
        val malformed = assertIs<Decoded.Malformed>(wrongType)
        assertContains(malformed.reason, "world")
    }

    @Test
    fun `decodes a hello with defaults for what it did not say`() {
        val decoded = CompanionCodec.decodeClientFrame(
            """{"t":"hello","protoMin":"0.1","protoMax":"0.1","token":"8F3K2M","client":{"name":"Alpine Climb"}}"""
        )
        val frame = assertIs<HelloFrame>(assertIs<Decoded.Ok>(decoded).frame)
        assertEquals("Alpine Climb", frame.client.name)
        // An absent role means "did not say", which is a request for control.
        assertEquals(HelloFrame.ROLE_CONTROLLER, frame.role)
        assertEquals(emptyList(), frame.capabilities)
    }

    @Test
    fun `encodes a server frame with its discriminator`() {
        val json = CompanionCodec.encode(
            TelemetryFrame(
                seq = 1043,
                tMono = 128500,
                state = CompanionState.ACTIVE,
                power = 243,
                cadence = 88,
                hr = 152,
                gearIndex = 11,
                loadFactor = 1.18,
                appliedGradient = 0.062,
                trainerForceN = 96.4,
                trainerGradePercent = 11.7,
                elapsedS = 512,
            )
        )
        assertContains(json, """"t":"telemetry"""")
        assertContains(json, """"trainerForceN":96.4""")
        assertContains(json, """"hr":152""")
    }

    /**
     * A missing reading means "no strap", not "a heart rate of zero". The same
     * discipline the FIT writer applies with its invalid sentinels.
     */
    @Test
    fun `a reading the app does not have is omitted rather than zeroed`() {
        val json = CompanionCodec.encode(
            TelemetryFrame(
                seq = 1,
                tMono = 0,
                state = CompanionState.ACTIVE,
                power = 200,
                cadence = null,
                hr = null,
                gearIndex = 6,
                loadFactor = 1.0,
                appliedGradient = 0.0,
                trainerForceN = 12.0,
                trainerGradePercent = 1.4,
                elapsedS = 0,
            )
        )
        assertFalse(json.contains("\"hr\""), json)
        assertFalse(json.contains("\"cadence\""), json)
        assertTrue(json.contains("\"power\":200"), json)
    }

    /**
     * A gradient that has been through arithmetic is a double like
     * `0.06199999999999999`, which serialises to fifteen characters where five
     * would do and makes the wire much harder to read.
     */
    @Test
    fun `doubles are rounded to wire precision on the way out`() {
        val json = CompanionCodec.encode(
            TelemetryFrame(
                seq = 1,
                tMono = 0,
                state = CompanionState.ACTIVE,
                gearIndex = 11,
                loadFactor = 1.1800000000000002,
                appliedGradient = 0.06199999999999999,
                trainerForceN = 96.44999999999999,
                trainerGradePercent = 11.700000000000001,
                elapsedS = 0,
            )
        )
        assertContains(json, """"appliedGradient":0.062""")
        assertContains(json, """"trainerForceN":96.4""")
        assertContains(json, """"trainerGradePercent":11.7""")
        assertContains(json, """"loadFactor":1.18""")
    }

    /**
     * Neither `NaN` nor `Infinity` is valid JSON, and a frame that fails to
     * encode mid-ride is worse than a zero the receiver can see is wrong.
     */
    @Test
    fun `a non-finite value encodes as zero rather than breaking the frame`() {
        val json = CompanionCodec.encode(
            TelemetryFrame(
                seq = 1,
                tMono = 0,
                state = CompanionState.ACTIVE,
                gearIndex = 0,
                loadFactor = Double.NaN,
                appliedGradient = Double.POSITIVE_INFINITY,
                trainerForceN = 0.0,
                trainerGradePercent = 0.0,
                elapsedS = 0,
            )
        )
        assertFalse(json.contains("NaN"), json)
        assertFalse(json.contains("Infinity"), json)
        assertContains(json, """"loadFactor":0.0""")
    }

    @Test
    fun `a clamp warning reports both values at wire precision`() {
        val json = CompanionCodec.encode(
            WarningFrame(
                code = CompanionWarning.CLAMPED,
                field = "gradient",
                sent = 0.42000000000000004,
                applied = 0.30000000000000004,
            )
        )
        assertContains(json, """"sent":0.42""")
        assertContains(json, """"applied":0.3""")
    }

    @Test
    fun `gear ratios are rounded so a gear display is readable`() {
        val json = CompanionCodec.encode(
            WelcomeFrame(
                proto = "0.1",
                app = "2.1.0",
                sessionId = "3f9a1c",
                honours = listOf("gradient"),
                capabilities = emptyList(),
                role = HelloFrame.ROLE_CONTROLLER,
                // 34/28, straight off the drivetrain, is 1.2142857142857142.
                rider = RiderInfo(78.0, 250),
                bike = BikeInfo(9.5, "road-double"),
                gears = listOf(GearInfo(0, 34.0 / 28.0, "34/28", 0.7200000000000001)),
                gearIndex = 0,
                trainer = TrainerInfo("KICKR CORE", true, "sim"),
                limits = Limits(),
            )
        )
        assertContains(json, """"ratio":1.21""")
        assertContains(json, """"loadFactor":0.72""")
    }

    @Test
    fun `an error frame carries both ranges so neither end has to guess`() {
        val json = CompanionCodec.encode(
            ErrorFrame(
                code = CompanionError.PROTO_UNSUPPORTED,
                message = "…",
                serverRange = "0.1-0.1",
                clientRange = "2.0-2.3",
            )
        )
        assertContains(json, """"serverRange":"0.1-0.1"""")
        assertContains(json, """"clientRange":"2.0-2.3"""")
    }
}

class CompanionDiscoveryTest {

    private val announcement = CompanionAnnouncement(
        port = 47612,
        app = "2.0.2",
        session = "3f9a1c",
        name = "Indoor Bike",
        paired = false,
    )

    @Test
    fun `an announcement round trips`() {
        val decoded = CompanionDiscovery.decode(CompanionDiscovery.encode(announcement))
        assertEquals(announcement, decoded)
    }

    @Test
    fun `the announcement carries what is needed to connect`() {
        val json = CompanionDiscovery.encode(announcement)
        assertContains(json, """"t":"indoorbike.companion"""")
        assertContains(json, """"port":47612""")
        // Spelled from the constant rather than pinned: the announcement
        // carries whatever this build supports, and a version bump is not
        // supposed to cost a test edit.
        assertContains(json, """"proto":"""" + CompanionProtocol.SUPPORTED + """"""")
        assertContains(json, """"path":"/companion"""")
    }

    /**
     * Discovery answers "which phone"; pairing answers "may you drive it".
     * Broadcasting the code to the whole subnet would collapse the two and hand
     * a trainer to anything listening.
     */
    @Test
    fun `the pairing code is never broadcast`() {
        val json = CompanionDiscovery.encode(announcement)
        assertFalse(json.contains("token"), json)
    }

    /**
     * The listener already knows the phone's address from the datagram's
     * source, and that one is reachable by construction - which is more than
     * can be said for any address the phone might nominate.
     */
    @Test
    fun `the announcement does not nominate an address`() {
        val json = CompanionDiscovery.encode(announcement)
        assertFalse(json.contains("host"), json)
        assertFalse(json.contains("address"), json)
    }

    @Test
    fun `stray traffic on the discovery port is not an announcement`() {
        assertNull(CompanionDiscovery.decode("not json"))
        assertNull(CompanionDiscovery.decode("""{"t":"something.else","port":1}"""))
        assertNull(CompanionDiscovery.decode("""{"hello":"world"}"""))
        // Right magic, impossible port.
        assertNull(
            CompanionDiscovery.decode(
                """{"t":"indoorbike.companion","port":0,"app":"1","session":"a","name":"n"}"""
            )
        )
    }

    @Test
    fun `an announcement from a later version still parses`() {
        val decoded = CompanionDiscovery.decode(
            """{"t":"indoorbike.companion","proto":"0.1-0.4","path":"/companion","port":47612,
               "app":"9.9","session":"abc","name":"Indoor Bike","paired":true,"somethingNew":42}"""
        )
        assertEquals(47612, decoded?.port)
        assertEquals(true, decoded?.paired)
    }

    // Added in 0.2 ───────────────────────────────────────────────────────────

    @Test
    fun `a course frame decodes`() {
        val decoded = CompanionCodec.decodeClientFrame(
            """{"t":"course","name":"Volcano Circuit","shape":"abc","profile":[0,40,80]}"""
        )
        val frame = assertIs<CourseFrame>(assertIs<Decoded.Ok>(decoded).frame)
        assertEquals("Volcano Circuit", frame.name)
        assertEquals("abc", frame.shape)
        assertEquals(listOf(0, 40, 80), frame.profile)
    }

    @Test
    fun `a setState frame decodes`() {
        val decoded = CompanionCodec.decodeClientFrame("""{"t":"setState","state":"paused"}""")
        val frame = assertIs<SetStateFrame>(assertIs<Decoded.Ok>(decoded).frame)
        assertEquals(CompanionState.PAUSED, frame.state)
    }

    /**
     * "No drivetrain speed to be had" and "a speed of zero" are different
     * things, and the protocol keeps them different the way it keeps a missing
     * heart rate different from a heart rate of zero.
     */
    @Test
    fun `an unknown drivetrain speed is left out of telemetry entirely`() {
        val json = CompanionCodec.encode(telemetry(speedMps = null))
        assertFalse(json.contains("speedMps"), json)
    }

    @Test
    fun `a known drivetrain speed is sent rounded`() {
        val json = CompanionCodec.encode(telemetry(speedMps = 8.33333333))
        assertContains(json, """"speedMps":8.33""")
    }

    private fun telemetry(speedMps: Double?) = TelemetryFrame(
        seq = 1,
        tMono = 1_000,
        state = CompanionState.ACTIVE,
        power = 210,
        cadence = 88,
        speedMps = speedMps,
        gearIndex = 11,
        loadFactor = 1.18,
        appliedGradient = 0.062,
        trainerForceN = 74.2,
        trainerGradePercent = 9.0,
        elapsedS = 61,
    )

    @Test
    fun `a controls frame decodes`() {
        val decoded = CompanionCodec.decodeClientFrame(
            """{"t":"controls","visible":true,"title":"Menu","enabled":["up","enter"]}"""
        )
        val frame = assertIs<ControlsFrame>(assertIs<Decoded.Ok>(decoded).frame)
        assertTrue(frame.visible)
        assertEquals("Menu", frame.title)
        assertEquals(listOf("up", "enter"), frame.enabled)
    }

    @Test
    fun `a control press encodes with the control named`() {
        val json = CompanionCodec.encode(
            EventFrame(event = EventFrame.CONTROL_PRESSED, control = CompanionControl.ENTER)
        )
        assertContains(json, """"event":"controlPressed"""")
        assertContains(json, """"control":"enter"""")
    }
}
