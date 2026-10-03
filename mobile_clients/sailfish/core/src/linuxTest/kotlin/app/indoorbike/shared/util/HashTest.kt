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
package app.indoorbike.shared.util

import app.indoorbike.shared.ble.zwift.hmacSha256
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The hand-written SHA-256 and HMAC the Sailfish core uses in place of the
 * platform's, against the published vectors: FIPS 180-4's examples and the
 * NIST byte-oriented set for the hash, RFC 4231 for HMAC.
 */
class HashTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun sha256OfNothing() = assertEquals(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        hex(sha256(ByteArray(0))),
    )

    @Test
    fun sha256OfAbc() = assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        hex(sha256("abc".encodeToByteArray())),
    )

    /** Two blocks: the padding spills into a second one. */
    @Test
    fun sha256OfTheTwoBlockMessage() = assertEquals(
        "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
        hex(sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
    )

    /** 55 and 56 bytes straddle the length field, the classic place for a padding bug. */
    @Test
    fun sha256AroundTheLengthField() {
        assertEquals(
            "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318",
            hex(sha256(ByteArray(55) { 'a'.code.toByte() })),
        )
        assertEquals(
            "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a",
            hex(sha256(ByteArray(56) { 'a'.code.toByte() })),
        )
    }

    @Test
    fun sha256OfAMillionAs() = assertEquals(
        "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
        hex(sha256(ByteArray(1_000_000) { 'a'.code.toByte() })),
    )

    /** RFC 4231 test case 1. */
    @Test
    fun hmacCase1() = assertEquals(
        "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
        hex(hmacSha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray())),
    )

    /** RFC 4231 test case 2: a key shorter than the block. */
    @Test
    fun hmacCase2() = assertEquals(
        "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
        hex(hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())),
    )

    /** RFC 4231 test case 6: a key longer than the block is hashed first. */
    @Test
    fun hmacCase6() = assertEquals(
        "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
        hex(
            hmacSha256(
                ByteArray(131) { 0xaa.toByte() },
                "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray(),
            ),
        ),
    )

    @Test
    fun unhexRoundTrips() = assertEquals("00ff10", hex(unhex("00ff10")))
}
