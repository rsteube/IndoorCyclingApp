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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a companion that is not who it says, or not well behaved, can no
 * longer do.
 */
class CompanionHardeningTest {

    private val token = "8F3K2M"
    private val peer = "companion-device-1"

    private fun snapshot() = CompanionSnapshot(
        rider = RiderInfo(massKg = 78.0, ftpWatts = 250),
        bike = BikeInfo(massKg = 9.5, drivetrain = "road-double"),
        gears = listOf(GearInfo(0, 1.21, "34/28", 0.72)),
        gearIndex = 0,
        trainer = TrainerInfo(name = "KICKR CORE", connected = true, control = "sim"),
    )

    private fun session(keys: Map<String, String> = emptyMap(), rideState: String = CompanionState.PAUSED) =
        CompanionSession(
            config = CompanionConfig(appVersion = "2.1.0", deviceId = "app", appName = "Indoor Bike", sessionId = "s", token = token),
            snapshot = ::snapshot,
            pairedKeyFor = { keys[it] },
            rideState = { rideState },
        )

    private fun hello(deviceId: String? = null, token: String? = this.token, name: String = "Probe"): String {
        val d = if (deviceId == null) "" else ""","deviceId":"$deviceId""""
        val t = if (token == null) "" else ""","token":"$token""""
        return """{"t":"hello","protoMin":"0.1","protoMax":"0.6"$t$d,"client":{"name":"$name"}}"""
    }

    private fun List<CompanionEffect>.closed(): Boolean = any { it is CompanionEffect.Close }

    /**
     * The attack: a paired device's id goes past in the clear, and a hello
     * naming it, with no key, used to be given the role - and with it the
     * controller slot, the telemetry and a held ride. The role is nobody's
     * until the challenge is answered.
     */
    @Test
    fun `a claimed pairing holds no role until it is proved`() {
        val s = session(keys = mapOf(peer to CompanionSecurity.newKeyHex()))
        val effects = s.onFrame(hello(deviceId = peer, token = null), nowMs = 0)

        assertIs<ChallengeFrame>(effects.filterIsInstance<CompanionEffect.Send>().single().frame)
        assertNull(s.role)
        assertFalse(s.isController)
        assertFalse(s.isReady)
    }

    @Test
    fun `a stored key that is not a key cannot be proved with an empty proof`() {
        val s = session(keys = mapOf(peer to "not-hex-at-all"))
        s.onFrame(hello(deviceId = peer, token = null), nowMs = 0)
        val nonce = CompanionSecurity.newNonceHex()
        val effects = s.onFrame("""{"t":"pairProof","nonce":"$nonce","proof":""}""", nowMs = 1)

        assertTrue(effects.closed(), "an empty proof opened a connection whose stored key was corrupt")
        assertNull(s.role)
    }

    @Test
    fun `a frame this build does not know is fatal before hello`() {
        val s = session()
        assertTrue(s.onFrame("""{"t":"somethingNew","x":1}""", nowMs = 0).closed())
    }

    @Test
    fun `a frame this build does not know is still ignored after hello`() {
        val s = session()
        s.onFrame(hello(), nowMs = 0)
        assertEquals(emptyList(), s.onFrame("""{"t":"somethingNew","x":1}""", nowMs = 1))
    }

    @Test
    fun `a handshake that never finishes can be refused, and a finished one cannot`() {
        val waiting = session()
        assertTrue(waiting.refuseUnauthenticated("too slow").closed())

        val done = session()
        done.onFrame(hello(), nowMs = 0)
        assertEquals(emptyList(), done.refuseUnauthenticated("too slow"))
    }

    @Test
    fun `ride-state requests are rate limited`() {
        val s = session()
        s.onFrame(hello(), nowMs = 0)
        val answers = (0 until 5).map { s.onFrame("""{"t":"setState","state":"paused"}""", nowMs = 100L + it) }

        assertEquals(2, answers.count { a -> a.any { it is CompanionEffect.SetRideState } })
        val warning = answers.last().filterIsInstance<CompanionEffect.Send>().map { it.frame }.filterIsInstance<WarningFrame>().single()
        assertEquals(CompanionWarning.RATE_LIMITED, warning.code)
    }

    @Test
    fun `a name is trimmed, capped and cleaned of control and bidi characters`() {
        val s = session()
        s.onFrame(hello(name = "  Evil\\u202egame\\u0007 " + "x".repeat(200)), nowMs = 0)

        assertEquals(CompanionText.MAX_NAME_CHARS, s.clientName.length)
        assertTrue(s.clientName.startsWith("Evilgame "), s.clientName)
        assertEquals(null, CompanionText.clean("‎ \u0000 "))
    }

    /**
     * Twenty kilobytes of brackets used to take the codec's tree walk past the
     * end of the stack: an Error, which nothing on the socket caught.
     */
    @Test
    fun `a frame nested too deep is malformed, not a crash`() {
        val deep = """{"t":"label","name":"x","world":""" + "[".repeat(20_000) + "]".repeat(20_000) + "}"
        assertIs<Decoded.Malformed>(CompanionCodec.decodeClientFrame(deep))
        // A bracket inside a string is text, not nesting.
        val bracketsInText = """{"t":"label","name":"${"[".repeat(100)}"}"""
        assertIs<Decoded.Ok>(CompanionCodec.decodeClientFrame(bracketsInText))
    }

    @Test
    fun `progress nobody could have ridden is ignored`() {
        val engine = CompanionEngine(powerWatts = { 0 }, applyRoad = { }, riderMassKg = { 75f })
        try {
            engine.setProgress(distanceM = 12_000.0, ascentM = 300.0)
            engine.setProgress(distanceM = 1e300, ascentM = Double.NaN)
            engine.setProgress(distanceM = -5.0, ascentM = -1.0)

            assertEquals(12_000.0, engine.distanceMeters.value)
            assertEquals(300.0, engine.elevationGainMeters.value)
        } finally {
            engine.dispose()
        }
    }

    /** Int.MAX_VALUE added to a gear index wrapped round to the easiest gear. */
    @Test
    fun `an absurd shift lands in the hardest gear, not the easiest`() {
        val engine = CompanionEngine(powerWatts = { 0 }, applyRoad = { }, riderMassKg = { 75f })
        try {
            engine.load()
            val gear = engine.shiftBy(Int.MAX_VALUE)
            assertEquals(engine.drivetrain.value.gearCount - 1, gear)
            assertEquals(0, engine.shiftBy(Int.MIN_VALUE))
        } finally {
            engine.dispose()
        }
    }

    @Test
    fun `the pairing is saved under the name the rider was shown`() {
        val s = session()
        s.onFrame(hello(deviceId = peer, name = "Hello Name"), nowMs = 0)
        val asked = s.onFrame("""{"t":"pairRequest","name":"Shown Name"}""", nowMs = 1)
            .filterIsInstance<CompanionEffect.RequestPairing>().single()
        val saved = s.acceptPairing(nowMs = 2).filterIsInstance<CompanionEffect.SavePairing>().single()

        assertEquals("Shown Name", asked.name)
        assertEquals("Shown Name", saved.companion.name)
    }

    @Test
    fun `a controller that loses the slot is refused with the busy code`() {
        val s = session()
        s.onFrame(hello(), nowMs = 0)
        val close = s.refuseControl().filterIsInstance<CompanionEffect.Close>().single()
        assertEquals(CompanionClose.CONTROLLER_BUSY, close.code)
        assertNull(s.role)
    }

    @Test
    fun `the drivetrain an engine starts on is the default`() {
        // Guards the test above: gearCount must be more than one for it to mean anything.
        assertTrue(Drivetrain.DEFAULT.gearCount > 1)
    }
}
