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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN = "8F3K2M"
private const val APP_DEVICE = "app-device-1"
private const val PEER = "companion-device-1"

private fun pairSnapshot() = CompanionSnapshot(
    rider = RiderInfo(massKg = 78.0, ftpWatts = 250),
    bike = BikeInfo(massKg = 9.5, drivetrain = "road-double"),
    gears = listOf(GearInfo(0, 1.21, "34/28", 0.72)),
    gearIndex = 0,
    trainer = TrainerInfo(name = "KICKR CORE", connected = true, control = "sim"),
)

private fun pairSession(keys: Map<String, String> = emptyMap()) = CompanionSession(
    config = CompanionConfig(
        appVersion = "2.1.0",
        deviceId = APP_DEVICE,
        appName = "Indoor Bike",
        sessionId = "3f9a1c",
        token = TOKEN,
    ),
    snapshot = ::pairSnapshot,
    pairedKeyFor = { keys[it] },
)

private fun helloFrame(deviceId: String? = PEER, token: String? = TOKEN): String {
    val d = if (deviceId == null) "" else ""","deviceId":"$deviceId""""
    val t = if (token == null) "" else ""","token":"$token""""
    return """{"t":"hello","protoMin":"0.1","protoMax":"0.6"$t$d,"client":{"name":"Probe"}}"""
}

private inline fun <reified T : CompanionEffect> List<CompanionEffect>.only(): T =
    filterIsInstance<T>().first()

private inline fun <reified T : ServerFrame> List<CompanionEffect>.frame(): T =
    filterIsInstance<CompanionEffect.Send>().map { it.frame }.filterIsInstance<T>().first()

/** The maths, on its own, before any of the state machine. */
class CompanionSecurityTest {

    @Test
    fun `hex round-trips and rejects anything that is not hex`() {
        val bytes = byteArrayOf(0, 1, 15, 16, -1, 127, -128)
        assertEquals(bytes.toList(), bytes.toHex().hexToBytes()!!.toList())
        assertEquals("00010f10ff7f80", bytes.toHex())
        assertNull("odd".hexToBytes())
        assertNull("zz".hexToBytes())
    }

    @Test
    fun `keys and nonces are the advertised size and never repeat`() {
        val keys = List(64) { CompanionSecurity.newKeyHex() }
        keys.forEach { assertTrue(CompanionSecurity.looksLikeKey(it), it) }
        assertEquals(64, keys.toSet().size, "a key repeated in 64 draws")

        val nonces = List(64) { CompanionSecurity.newNonceHex() }
        nonces.forEach { assertTrue(CompanionSecurity.looksLikeNonce(it), it) }
        assertEquals(64, nonces.toSet().size, "a nonce repeated in 64 draws")
    }

    /**
     * The property that stops a companion replaying the app's own proof back at
     * it. Same key, same nonces, different role: the tags must differ.
     */
    @Test
    fun `the two roles produce different tags over the same input`() {
        val key = CompanionSecurity.newKeyHex()
        val a = CompanionSecurity.proof(key, CompanionSecurity.ROLE_APP, "aa", "bb")
        val c = CompanionSecurity.proof(key, CompanionSecurity.ROLE_COMPANION, "aa", "bb")
        assertNotEquals(a, c)
    }

    @Test
    fun `a different key or a different nonce is a different tag`() {
        val key = CompanionSecurity.newKeyHex()
        val base = CompanionSecurity.proof(key, CompanionSecurity.ROLE_APP, "aa", "bb")
        assertNotEquals(
            base,
            CompanionSecurity.proof(CompanionSecurity.newKeyHex(), CompanionSecurity.ROLE_APP, "aa", "bb"),
        )
        assertNotEquals(base, CompanionSecurity.proof(key, CompanionSecurity.ROLE_APP, "ac", "bb"))
        assertNotEquals(base, CompanionSecurity.proof(key, CompanionSecurity.ROLE_APP, "aa", "bc"))
    }

    @Test
    fun `comparison survives a null, a short string and a long one`() {
        val tag = CompanionSecurity.proof(CompanionSecurity.newKeyHex(), "app", "aa", "bb")
        assertTrue(CompanionSecurity.proofMatches(tag, tag))
        assertFalse(CompanionSecurity.proofMatches(tag, null))
        assertFalse(CompanionSecurity.proofMatches(tag, ""))
        assertFalse(CompanionSecurity.proofMatches(tag, tag.dropLast(1)))
        assertFalse(CompanionSecurity.proofMatches(tag, tag + "0"))
    }
}

/** Meeting for the first time. */
class CompanionPairingCeremonyTest {

    @Test
    fun `a stranger is asked for the code, as before`() {
        val s = pairSession()
        val welcome = s.onFrame(helloFrame(), nowMs = 0).frame<WelcomeFrame>()
        assertEquals(APP_DEVICE, welcome.deviceId)
        // Nothing was proved, so nothing is claimed.
        assertNull(welcome.proof)
        assertFalse(s.authenticatedByPairing)
    }

    @Test
    fun `a wrong code is still refused even with a device id`() {
        val s = pairSession()
        val error = s.onFrame(helloFrame(token = "NOPE"), nowMs = 0).frame<ErrorFrame>()
        assertEquals(CompanionError.UNAUTHORIZED, error.code)
    }

    @Test
    fun `asking to be remembered puts the question to the rider`() {
        val s = pairSession()
        s.onFrame(helloFrame(), nowMs = 0)
        val request = s.onFrame("""{"t":"pairRequest","name":"Alpine Climb"}""", nowMs = 1)
            .only<CompanionEffect.RequestPairing>()
        assertEquals(PEER, request.deviceId)
        assertEquals("Alpine Climb", request.name)
    }

    @Test
    fun `accepting mints a key, writes it down, and sends it once`() {
        val s = pairSession()
        s.onFrame(helloFrame(), nowMs = 0)
        s.onFrame("""{"t":"pairRequest"}""", nowMs = 1)

        val effects = s.acceptPairing(nowMs = 1_700_000_000_000)
        val saved = effects.only<CompanionEffect.SavePairing>().companion
        val sent = effects.frame<PairedFrame>()

        assertEquals(PEER, saved.deviceId)
        assertTrue(CompanionSecurity.looksLikeKey(saved.keyHex))
        assertEquals(saved.keyHex, sent.key, "the key stored and the key sent must be the one key")
        assertEquals(APP_DEVICE, sent.deviceId)
        // Written down before it goes out, so a lost send cannot leave the
        // companion holding a key the app has never heard of.
        assertTrue(
            effects.indexOfFirst { it is CompanionEffect.SavePairing } <
                effects.indexOfFirst { it is CompanionEffect.Send },
        )
        // And only once, however many times the rider taps.
        assertEquals(emptyList(), s.acceptPairing(nowMs = 1_700_000_000_001))
    }

    @Test
    fun `declining says so and changes nothing`() {
        val s = pairSession()
        s.onFrame(helloFrame(), nowMs = 0)
        s.onFrame("""{"t":"pairRequest"}""", nowMs = 1)
        val declined = s.declinePairing("no").frame<PairDeclinedFrame>()
        assertEquals("no", declined.reason)
        assertEquals(emptyList(), s.acceptPairing(nowMs = 2))
    }

    @Test
    fun `asking twice when already paired is answered, not acted on`() {
        val key = CompanionSecurity.newKeyHex()
        val s = pairSession(mapOf(PEER to key))
        // Authenticate by pairing first, so we are in a ready state.
        val challenge = s.onFrame(helloFrame(token = null), nowMs = 0).frame<ChallengeFrame>()
        val nonceC = CompanionSecurity.newNonceHex()
        val proof = CompanionSecurity.proof(
            key, CompanionSecurity.ROLE_COMPANION, challenge.nonce, nonceC,
        )
        s.onFrame("""{"t":"pairProof","nonce":"$nonceC","proof":"$proof"}""", nowMs = 1)

        val effects = s.onFrame("""{"t":"pairRequest"}""", nowMs = 2)
        assertTrue(effects.filterIsInstance<CompanionEffect.RequestPairing>().isEmpty())
        assertEquals("Already paired.", effects.frame<PairDeclinedFrame>().reason)
    }

    @Test
    fun `a companion with no identity has nothing to remember it by`() {
        val s = pairSession()
        s.onFrame(helloFrame(deviceId = null), nowMs = 0)
        val declined = s.onFrame("""{"t":"pairRequest"}""", nowMs = 1).frame<PairDeclinedFrame>()
        assertTrue(declined.reason.orEmpty().contains("deviceId"))
    }

    @Test
    fun `an observer may not ask to be trusted`() {
        val s = pairSession()
        s.onFrame(
            """{"t":"hello","protoMin":"0.1","protoMax":"0.6","token":"$TOKEN",""" +
                """"deviceId":"$PEER","role":"observer","client":{"name":"Dash"}}""",
            nowMs = 0,
        )
        val error = s.onFrame("""{"t":"pairRequest"}""", nowMs = 1).frame<ErrorFrame>()
        assertEquals(CompanionError.NOT_CONTROLLER, error.code)
    }
}

/** Meeting again, which is the point of all of it. */
class CompanionPairedHandshakeTest {

    private val key = CompanionSecurity.newKeyHex()

    private fun paired() = pairSession(mapOf(PEER to key))

    @Test
    fun `a known companion is challenged rather than asked for a code`() {
        val s = paired()
        val effects = s.onFrame(helloFrame(token = null), nowMs = 0)
        val challenge = effects.frame<ChallengeFrame>()
        assertEquals(APP_DEVICE, challenge.deviceId)
        assertTrue(CompanionSecurity.looksLikeNonce(challenge.nonce))
        // No welcome yet. Claiming an identity is not proving one.
        assertTrue(effects.filterIsInstance<CompanionEffect.Send>()
            .map { it.frame }.filterIsInstance<WelcomeFrame>().isEmpty())
    }

    @Test
    fun `a correct proof is welcomed, and the app proves itself back`() {
        val s = paired()
        val challenge = s.onFrame(helloFrame(token = null), nowMs = 0).frame<ChallengeFrame>()
        val nonceC = CompanionSecurity.newNonceHex()
        val proof = CompanionSecurity.proof(
            key, CompanionSecurity.ROLE_COMPANION, challenge.nonce, nonceC,
        )

        val effects = s.onFrame("""{"t":"pairProof","nonce":"$nonceC","proof":"$proof"}""", nowMs = 1)
        val welcome = effects.frame<WelcomeFrame>()

        assertTrue(s.authenticatedByPairing)
        assertEquals(PEER, effects.only<CompanionEffect.PairingProved>().deviceId)
        assertEquals(
            CompanionSecurity.proof(key, CompanionSecurity.ROLE_APP, challenge.nonce, nonceC),
            welcome.proof,
            "the companion must be able to check the app too",
        )
    }

    @Test
    fun `a wrong proof is refused and the socket closes`() {
        val s = paired()
        val challenge = s.onFrame(helloFrame(token = null), nowMs = 0).frame<ChallengeFrame>()
        val wrong = CompanionSecurity.proof(
            CompanionSecurity.newKeyHex(), CompanionSecurity.ROLE_COMPANION, challenge.nonce, "aa",
        )
        val nonceC = CompanionSecurity.newNonceHex()
        val effects = s.onFrame("""{"t":"pairProof","nonce":"$nonceC","proof":"$wrong"}""", nowMs = 1)
        assertEquals(CompanionError.UNAUTHORIZED, effects.frame<ErrorFrame>().code)
        assertEquals(
            CompanionClose.UNAUTHORIZED,
            effects.filterIsInstance<CompanionEffect.Close>().single().code,
        )
        assertFalse(s.authenticatedByPairing)
    }

    /**
     * The reason both sides contribute a nonce. A recording of a whole valid
     * exchange proves nothing tomorrow, because tomorrow's challenge is a
     * number the recorder never saw.
     */
    @Test
    fun `yesterday's exchange does not open today's connection`() {
        val yesterday = paired()
        val oldChallenge = yesterday.onFrame(helloFrame(token = null), nowMs = 0)
            .frame<ChallengeFrame>()
        val oldNonce = CompanionSecurity.newNonceHex()
        val oldProof = CompanionSecurity.proof(
            key, CompanionSecurity.ROLE_COMPANION, oldChallenge.nonce, oldNonce,
        )

        val today = paired()
        val newChallenge = today.onFrame(helloFrame(token = null), nowMs = 100).frame<ChallengeFrame>()
        assertNotEquals(oldChallenge.nonce, newChallenge.nonce)

        val effects = today.onFrame(
            """{"t":"pairProof","nonce":"$oldNonce","proof":"$oldProof"}""", nowMs = 101,
        )
        assertEquals(CompanionError.UNAUTHORIZED, effects.frame<ErrorFrame>().code)
    }

    /**
     * A companion that reuses one nonce forever is signing something only the
     * app chose, which is most of the way back to a replay being useful.
     */
    @Test
    fun `a nonce that is not a nonce is refused`() {
        val s = paired()
        val challenge = s.onFrame(helloFrame(token = null), nowMs = 0).frame<ChallengeFrame>()
        val proof = CompanionSecurity.proof(key, CompanionSecurity.ROLE_COMPANION, challenge.nonce, "")
        val effects = s.onFrame("""{"t":"pairProof","nonce":"","proof":"$proof"}""", nowMs = 1)
        assertEquals(CompanionError.UNAUTHORIZED, effects.frame<ErrorFrame>().code)
    }

    @Test
    fun `nothing but a proof is answered while a challenge is outstanding`() {
        val s = paired()
        s.onFrame(helloFrame(token = null), nowMs = 0)
        val effects = s.onFrame(
            """{"t":"world","seq":1,"gradient":0.05,"speedMps":8.0}""", nowMs = 1,
        )
        assertEquals(CompanionError.UNAUTHORIZED, effects.frame<ErrorFrame>().code)
        assertTrue(effects.filterIsInstance<CompanionEffect.ApplyRoad>().isEmpty())
    }

    @Test
    fun `a device id nobody has paired still needs the code`() {
        val s = pairSession(mapOf("somebody-else" to key))
        val error = s.onFrame(helloFrame(token = null), nowMs = 0).frame<ErrorFrame>()
        assertEquals(CompanionError.UNAUTHORIZED, error.code)
    }

    @Test
    fun `a forgotten pairing falls back to the code rather than failing shut`() {
        // The app forgot; the companion still has its key and tries to use it.
        val s = pairSession(emptyMap())
        val welcome = s.onFrame(helloFrame(), nowMs = 0).frame<WelcomeFrame>()
        assertNull(welcome.proof)
        assertFalse(s.authenticatedByPairing)
    }
}
