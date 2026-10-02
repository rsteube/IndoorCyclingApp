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

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
private fun b64(bytes: ByteArray) = Base64.encode(bytes)

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

/** A PNG's signature and IHDR, which is all the check reads. */
private fun pngHeader(width: Int, height: Int): ByteArray =
    bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        bytes(0, 0, 0, 13) + "IHDR".encodeToByteArray() +
        bytes(width ushr 24, width ushr 16 and 0xFF, width ushr 8 and 0xFF, width and 0xFF) +
        bytes(height ushr 24, height ushr 16 and 0xFF, height ushr 8 and 0xFF, height and 0xFF) +
        bytes(8, 2, 0, 0, 0)

/** SOI, an APP0 and an APP1 to walk past, then a start-of-frame of the given kind. */
private fun jpegHeader(width: Int, height: Int, sof: Int = 0xC0): ByteArray =
    bytes(0xFF, 0xD8) +
        bytes(0xFF, 0xE0, 0x00, 0x10) + "JFIF".encodeToByteArray() + ByteArray(10) +
        bytes(0xFF, 0xE1, 0x00, 0x08) + ByteArray(6) +
        // Huffman tables are C4, which must not be mistaken for a frame.
        bytes(0xFF, 0xC4, 0x00, 0x04, 0x00, 0x00) +
        bytes(0xFF, sof, 0x00, 0x11, 0x08, height ushr 8, height and 0xFF, width ushr 8, width and 0xFF, 0x03) +
        ByteArray(9)

private fun session() = CompanionSession(
    config = CompanionConfig(appVersion = "2.3.0", sessionId = "s", token = "T0KEN1"),
    snapshot = {
        CompanionSnapshot(
            rider = RiderInfo(massKg = 75.0, ftpWatts = 250),
            bike = BikeInfo(massKg = 9.0, drivetrain = "road"),
            gears = listOf(GearInfo(0, 1.0, "50/50", 0.5)),
            gearIndex = 0,
            trainer = TrainerInfo(name = "Mock", connected = true, control = "sim"),
        )
    },
    controllerConnected = { false },
)

private fun hello(capabilities: String = "\"screenshot\"", role: String = "controller") =
    """{"t":"hello","protoMin":"0.1","protoMax":"0.9","token":"T0KEN1","client":{"name":"Col de Berg"},""" +
        """"role":"$role","capabilities":[$capabilities]}"""

private fun ready(capabilities: String = "\"screenshot\"") = session().also { it.onFrame(hello(capabilities), 0) }

private inline fun <reified T : ServerFrame> List<CompanionEffect>.sent(): T =
    filterIsInstance<CompanionEffect.Send>().map { it.frame }.filterIsInstance<T>().first()

class ScreenshotImageTest {

    @Test
    fun `a PNG is read from its header`() {
        val ok = assertIs<ScreenshotImage.Companion.Checked.Ok>(ScreenshotImage.check(b64(pngHeader(1920, 1080))))
        assertEquals(ScreenshotImage.PNG, ok.image.format)
        assertEquals(1920, ok.image.widthPx)
        assertEquals(1080, ok.image.heightPx)
    }

    @Test
    fun `a JPEG is read from its frame header past the segments before it`() {
        val ok = assertIs<ScreenshotImage.Companion.Checked.Ok>(ScreenshotImage.check(b64(jpegHeader(1280, 720))))
        assertEquals(ScreenshotImage.JPEG, ok.image.format)
        assertEquals(1280, ok.image.widthPx)
        assertEquals(720, ok.image.heightPx)
    }

    @Test
    fun `a progressive JPEG counts too`() {
        val ok = assertIs<ScreenshotImage.Companion.Checked.Ok>(
            ScreenshotImage.check(b64(jpegHeader(800, 600, sof = 0xC2)))
        )
        assertEquals(800, ok.image.widthPx)
    }

    /** The size comes from the header precisely so a file cannot declare a picture that would not fit in memory. */
    @Test
    fun `a picture too big to look at is refused without being decoded`() {
        val rejected = assertIs<ScreenshotImage.Companion.Checked.Rejected>(
            ScreenshotImage.check(b64(pngHeader(40_000, 40_000)))
        )
        assertContains(rejected.reason, "40000x40000")
    }

    @Test
    fun `anything that is not a PNG or JPEG is refused`() {
        assertIs<ScreenshotImage.Companion.Checked.Rejected>(ScreenshotImage.check(b64("GIF89a....".encodeToByteArray())))
        assertIs<ScreenshotImage.Companion.Checked.Rejected>(ScreenshotImage.check("not base64 at all!"))
        assertIs<ScreenshotImage.Companion.Checked.Rejected>(ScreenshotImage.check(b64(bytes(0xFF, 0xD8, 0xFF, 0xD9))))
    }

    @Test
    fun `an enormous string is refused before it is decoded`() {
        val huge = "A".repeat(ScreenshotImage.MAX_BASE64_CHARS + 1)
        assertIs<ScreenshotImage.Companion.Checked.Rejected>(ScreenshotImage.check(huge))
    }
}

class CompanionScreenshotSessionTest {

    @Test
    fun `a companion that answers screenshots is asked for one`() {
        val s = ready()
        assertTrue(s.answersScreenshots)
        val request = s.requestScreenshot(7).sent<ScreenshotRequestFrame>()
        assertEquals(7L, request.id)
        assertEquals(ScreenshotImage.REQUESTED_EDGE_PX, request.maxEdgePx)
        assertEquals(
            """{"id":7,"maxEdgePx":1920,"t":"requestScreenshot"}""",
            CompanionCodec.encode(request),
        )
    }

    /** Asking one that never said it answers would leave the rider waiting on nothing. */
    @Test
    fun `a companion that did not list screenshot is never asked`() {
        val s = ready(capabilities = "\"course\"")
        assertEquals(false, s.answersScreenshots)
        assertEquals(emptyList(), s.requestScreenshot(1))
    }

    @Test
    fun `an answer with the id asked for is kept`() {
        val s = ready()
        s.requestScreenshot(3)
        val effects = s.onFrame("""{"t":"screenshot","id":3,"image":"${b64(jpegHeader(1920, 1080))}"}""", 1_000)
        val save = assertIs<CompanionEffect.SaveScreenshot>(effects.single())
        assertEquals(3L, save.id)
        assertEquals(ScreenshotImage.JPEG, save.image.format)
    }

    @Test
    fun `a screenshot nobody asked for is refused and so is a second answer`() {
        val s = ready()
        val unasked = s.onFrame("""{"t":"screenshot","id":9,"image":"${b64(pngHeader(64, 64))}"}""", 1_000)
        assertEquals("id", unasked.sent<WarningFrame>().field)
        assertTrue(unasked.none { it is CompanionEffect.SaveScreenshot })

        s.requestScreenshot(4)
        s.onFrame("""{"t":"screenshot","id":4,"image":"${b64(pngHeader(64, 64))}"}""", 1_000)
        val again = s.onFrame("""{"t":"screenshot","id":4,"image":"${b64(pngHeader(64, 64))}"}""", 1_100)
        assertEquals("id", again.sent<WarningFrame>().field)
    }

    @Test
    fun `a companion that cannot take one says why and the rider can be told`() {
        val s = ready()
        s.requestScreenshot(5)
        val failed = assertIs<CompanionEffect.ScreenshotFailed>(
            s.onFrame("""{"t":"screenshot","id":5,"error":"The game is loading"}""", 1_000).single()
        )
        assertEquals("The game is loading", failed.reason)
    }

    @Test
    fun `a broken image is refused both ways`() {
        val s = ready()
        s.requestScreenshot(6)
        val effects = s.onFrame("""{"t":"screenshot","id":6,"image":"${b64("hello".encodeToByteArray())}"}""", 1_000)
        assertEquals("image", effects.sent<WarningFrame>().field)
        assertIs<CompanionEffect.ScreenshotFailed>(effects.last())
    }

    @Test
    fun `an observer may not send one`() {
        val s = session()
        s.onFrame(hello(role = "observer"), 0)
        assertEquals(false, s.answersScreenshots)
        val error = s.onFrame("""{"t":"screenshot","id":1,"error":"x"}""", 1_000).sent<ErrorFrame>()
        assertEquals(CompanionError.NOT_CONTROLLER, error.code)
    }

    @Test
    fun `the welcome says this build asks for screenshots`() {
        val welcome = session().onFrame(hello(), 0).sent<WelcomeFrame>()
        assertContains(welcome.capabilities, CompanionProtocol.SCREENSHOT_CAPABILITY)
        assertEquals("0.9", welcome.proto)
    }
}
