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
package app.indoorbike.shared.companion

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolVersionTest {

    @Test
    fun `parses a well formed version`() {
        assertEquals(ProtocolVersion(0, 1), ProtocolVersion.parse("0.1"))
        assertEquals(ProtocolVersion(12, 345), ProtocolVersion.parse("12.345"))
        assertEquals(ProtocolVersion(1, 0), ProtocolVersion.parse("  1.0  "))
    }

    @Test
    fun `returns null rather than throwing on rubbish off the wire`() {
        assertNull(ProtocolVersion.parse(""))
        assertNull(ProtocolVersion.parse("1"))
        assertNull(ProtocolVersion.parse("1.2.3"))
        assertNull(ProtocolVersion.parse("one.two"))
        assertNull(ProtocolVersion.parse("-1.0"))
        assertNull(ProtocolVersion.parse("1.-2"))
    }

    @Test
    fun `orders by major then minor`() {
        assertTrue(ProtocolVersion(0, 9) < ProtocolVersion(1, 0))
        assertTrue(ProtocolVersion(1, 2) < ProtocolVersion(1, 10))
        assertEquals(ProtocolVersion(2, 3), maxOf(ProtocolVersion(2, 3), ProtocolVersion(2, 1)))
    }

    @Test
    fun `renders as major dot minor`() {
        assertEquals("0.1", ProtocolVersion(0, 1).toString())
        assertEquals("0.1-0.1", VersionRange(ProtocolVersion(0, 1), ProtocolVersion(0, 1)).toString())
    }
}

class NegotiationTest {

    private val v0_1 = ProtocolVersion(0, 1)
    private val v1_0 = ProtocolVersion(1, 0)
    private val v1_4 = ProtocolVersion(1, 4)
    private val v2_0 = ProtocolVersion(2, 0)

    @Test
    fun `agrees on the highest version both ends understand`() {
        val result = CompanionProtocol.negotiate(
            server = VersionRange(v1_0, v1_4),
            client = VersionRange(v1_0, v2_0),
            clientName = "Alpine Climb",
        )
        assertEquals(Negotiation.Agreed(v1_4), result)
    }

    @Test
    fun `agrees when the ranges touch at exactly one version`() {
        val result = CompanionProtocol.negotiate(
            server = VersionRange(v0_1, v1_0),
            client = VersionRange(v1_0, v2_0),
            clientName = "Alpine Climb",
        )
        assertEquals(Negotiation.Agreed(v1_0), result)
    }

    @Test
    fun `rejects when the ranges do not overlap`() {
        val result = CompanionProtocol.negotiate(
            server = VersionRange(v0_1, v0_1),
            client = VersionRange(v2_0, v2_0),
            clientName = "Alpine Climb",
        )
        assertTrue(result is Negotiation.Rejected)
    }

    @Test
    fun `rejects a range whose min is above its max`() {
        val result = CompanionProtocol.negotiate(
            server = VersionRange(v0_1, v0_1),
            client = VersionRange(v2_0, v1_0),
            clientName = "Alpine Climb",
        )
        assertTrue(result is Negotiation.Rejected)
        assertContains(result.message, "impossible protocol range")
    }

    /**
     * The whole point of the message. A rider staring at a game that will not
     * connect has to be told which of the two things they own is the old one,
     * and both version ranges have to appear so a developer can see them
     * without attaching a debugger.
     */
    @Test
    fun `a client from the future is told to update the app`() {
        val message = CompanionProtocol.mismatchMessage(
            clientName = "Alpine Climb",
            client = VersionRange(v2_0, v2_0),
            server = VersionRange(v0_1, v0_1),
        )
        assertContains(message, "Alpine Climb")
        assertContains(message, "2.0-2.0")
        assertContains(message, "0.1-0.1")
        assertContains(message, "Update Indoor Bike")
    }

    @Test
    fun `a client from the past is told to update itself`() {
        val message = CompanionProtocol.mismatchMessage(
            clientName = "Old Game",
            client = VersionRange(v0_1, v0_1),
            server = VersionRange(v1_0, v1_4),
        )
        assertContains(message, "0.1-0.1")
        assertContains(message, "1.0-1.4")
        assertContains(message, "Update the companion")
    }

    /**
     * Both ends must check. A server may legitimately choose a version the
     * client listed but has since deprecated, so the client needs its own
     * failure message rather than trusting `welcome` blindly.
     */
    @Test
    fun `a companion rejecting the app gets a message from the other side`() {
        val tooNew = CompanionProtocol.clientSideMismatchMessage(
            serverVersion = v2_0,
            clientRange = VersionRange(v0_1, v0_1),
        )
        assertContains(tooNew, "Update this companion")

        val tooOld = CompanionProtocol.clientSideMismatchMessage(
            serverVersion = v0_1,
            clientRange = VersionRange(v1_0, v1_4),
        )
        assertContains(tooOld, "Update Indoor Bike")
    }

    @Test
    fun `this build advertises what it actually honours`() {
        assertEquals(
            setOf("gradient", "speedMps", "distanceM", "ascentM"),
            CompanionProtocol.HONOURED_WORLD_FIELDS,
        )
        // Reserved names must not be honoured by accident: the companion owns
        // speed, so none of these has any way to be felt.
        CompanionProtocol.RESERVED_WORLD_FIELDS.forEach {
            assertTrue(it !in CompanionProtocol.HONOURED_WORLD_FIELDS, it)
        }
    }
}

class CloseReasonTest {

    /**
     * A close frame's payload is capped at 125 bytes, two of which are the
     * code. Overrun it and the peer never sees a close at all - it gets a bare
     * 1006 and loses the code the protocol went to the trouble of sending.
     */
    @Test
    fun `a short reason is left alone`() {
        val short = "protocol mismatch: server 0.1-0.1, client 2.0-2.3"
        assertEquals(short, CompanionProtocol.closeReason(short))
    }

    @Test
    fun `a long reason is trimmed to fit a close frame`() {
        val long = "Could not read a frame: not valid JSON: Unexpected JSON token at offset 1: " +
            "Expected beginning of the string, but got { at path: " + "$" + ".someLongPath"
        val fitted = CompanionProtocol.closeReason(long)
        assertTrue(long.encodeToByteArray().size > CompanionProtocol.MAX_CLOSE_REASON_BYTES)
        assertTrue(
            fitted.encodeToByteArray().size <= CompanionProtocol.MAX_CLOSE_REASON_BYTES,
            "was " + fitted.encodeToByteArray().size,
        )
        assertTrue(fitted.endsWith("..."))
    }

    @Test
    fun `trimming never cuts a multi-byte character in half`() {
        val fitted = CompanionProtocol.closeReason("ä".repeat(200))
        assertTrue(fitted.encodeToByteArray().size <= CompanionProtocol.MAX_CLOSE_REASON_BYTES)
        // Round-tripping proves nothing was left as a half-formed character.
        assertEquals(fitted, fitted.encodeToByteArray().decodeToString())
    }
}
