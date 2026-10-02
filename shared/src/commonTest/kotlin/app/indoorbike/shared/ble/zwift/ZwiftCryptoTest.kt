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
package app.indoorbike.shared.ble.zwift

import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hex(s: String) = ByteArray(s.length / 2) {
    s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
}

private fun ByteArray.hex() = joinToString("") {
    val v = it.toInt() and 0xFF
    "0123456789abcdef"[v shr 4].toString() + "0123456789abcdef"[v and 0x0F]
}

/**
 * Vectors generated with a reference AES-CCM and HKDF implementation at the
 * exact parameters the controller uses: 256-bit key, 8-byte nonce, 4-byte MIC,
 * no associated data. Hand-rolled crypto is only worth having if it is pinned
 * to something that was not hand-rolled.
 */
class AesCcmTest {

    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `decrypts reference vectors`() {
        val vectors = listOf(
            Triple("a0a0a0a000000000", "00070e", "06baeee6d3853a"),
            Triple("a1a1a1a101000000", "01080f161d", "feeceee1bb596c650f"),
            Triple(
                "a2a2a2a202000000",
                "020910171e252c333a41484f565d646b",
                "95f6b82fbdf576836f7518335dc3322bf9a37ecc",
            ),
            Triple(
                "a3a3a3a303000000",
                "030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d14",
                "7cbea0110e18be8501c5b213b134d1c83bedfd61ea2da48c37014e4c565033375472f9e68a3900dd11ce3f55",
            ),
        )
        for ((nonce, plaintext, ciphertext) in vectors) {
            val decrypted = AesCcm.decrypt(key, hex(nonce), hex(ciphertext), macLength = 4)
            assertEquals(plaintext, decrypted?.hex(), "nonce $nonce")
        }
    }

    @Test
    fun `rejects a corrupted mic`() {
        val ciphertext = hex("06baeee6d3853a")
        ciphertext[ciphertext.lastIndex] = (ciphertext[ciphertext.lastIndex] + 1).toByte()
        assertNull(AesCcm.decrypt(key, hex("a0a0a0a000000000"), ciphertext, macLength = 4))
    }

    @Test
    fun `rejects a corrupted payload`() {
        val ciphertext = hex("06baeee6d3853a")
        ciphertext[0] = (ciphertext[0] + 1).toByte()
        assertNull(AesCcm.decrypt(key, hex("a0a0a0a000000000"), ciphertext, macLength = 4))
    }

    @Test
    fun `rejects a frame shorter than its mic`() {
        assertNull(AesCcm.decrypt(key, hex("a0a0a0a000000000"), hex("0011"), macLength = 4))
    }
}

class HkdfTest {
    @Test
    fun `matches a reference vector`() {
        val out = hkdfSha256(
            salt = ByteArray(128) { (it + 64).toByte() },
            ikm = ByteArray(32) { (it + 100).toByte() },
            length = 36,
        )
        assertEquals(
            "9d8452b538663bd40f74539f4a58e829832ce59bfc5c5e52be8510bd4c562797b548a54c",
            out.hex(),
        )
    }
}

class ZwiftHandshakeTest {

    private val deviceKey = ByteArray(64) { (it + 1).toByte() }

    @Test
    fun `hello is RideOn plus version plus key`() {
        val hello = ZwiftHandshake.hello(ByteArray(64) { 0x11 })
        assertEquals(72, hello.size)
        assertContentEquals(ZwiftHandshake.RIDE_ON, hello.copyOfRange(0, 6))
        assertContentEquals(byteArrayOf(0x01, 0x02), hello.copyOfRange(6, 8))
    }

    @Test
    fun `reads the device key out of a reply`() {
        val reply = ZwiftHandshake.RIDE_ON + byteArrayOf(0x01, 0x03) + deviceKey
        assertContentEquals(deviceKey, ZwiftHandshake.devicePublicKey(reply))
    }

    @Test
    fun `version bytes are not policed`() {
        val reply = ZwiftHandshake.RIDE_ON + byteArrayOf(0x00, 0x09) + deviceKey
        assertContentEquals(deviceKey, ZwiftHandshake.devicePublicKey(reply))
    }

    @Test
    fun `a bare RideOn is a plaintext ack not a key exchange`() {
        assertNull(ZwiftHandshake.devicePublicKey(ZwiftHandshake.RIDE_ON))
        assertTrue(ZwiftHandshake.isPlaintextAck(ZwiftHandshake.RIDE_ON))
    }

    @Test
    fun `something else entirely is neither`() {
        val junk = byteArrayOf(0x02)
        assertNull(ZwiftHandshake.devicePublicKey(junk))
        assertTrue(!ZwiftHandshake.isPlaintextAck(junk))
    }
}

class ZwiftSessionTest {

    /** Nonce prefix and counter concatenate to the reference vector's nonce. */
    private val session = ZwiftSession(
        key = ByteArray(32) { (it + 1).toByte() },
        noncePrefix = hex("a0a0a0a0"),
    )

    @Test
    fun `decrypts a framed message`() {
        val frame = hex("00000000") + hex("06baeee6d3853a")
        assertEquals("00070e", session.decrypt(frame)?.hex())
    }

    @Test
    fun `rejects a frame with nothing in it`() {
        assertNull(session.decrypt(hex("0000000000000000")))
    }

    @Test
    fun `rejects a frame from a different counter`() {
        val frame = hex("01000000") + hex("06baeee6d3853a")
        assertNull(session.decrypt(frame))
    }
}

class ZwiftKeyExchangeTest {

    @Test
    fun `a public key is the bare 64-byte point`() {
        assertEquals(64, generateZwiftKeyPair().publicKey.size)
    }

    @Test
    fun `two sides arrive at the same 32-byte secret`() {
        val phone = generateZwiftKeyPair()
        val controller = generateZwiftKeyPair()
        val ours = phone.sharedSecret(controller.publicKey)
        val theirs = controller.sharedSecret(phone.publicKey)
        assertEquals(32, ours.size)
        assertTrue(ours.contentEquals(theirs))
    }

    @Test
    fun `a new key pair makes a new secret`() {
        val controller = generateZwiftKeyPair()
        val first = generateZwiftKeyPair().sharedSecret(controller.publicKey)
        val second = generateZwiftKeyPair().sharedSecret(controller.publicKey)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `a key of the wrong length is refused`() {
        assertFailsWith<IllegalArgumentException> { generateZwiftKeyPair().sharedSecret(ByteArray(65)) }
    }
}
