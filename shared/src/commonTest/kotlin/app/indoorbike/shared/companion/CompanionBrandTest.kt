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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A companion's colours are its own until they would make the screen unreadable.
 *
 * The rider is on a bike, often in a bright room, glancing at a dark screen.
 * A game's brand red chosen on a light desktop can vanish there, and the person
 * choosing it will not find out, so the app corrects it and says what it used.
 */
class CompanionBrandTest {

    @Test
    fun `hex parses in either case and nothing else parses`() {
        assertEquals(0xFFE4572E.toInt(), BrandColors.parse("#E4572E"))
        assertEquals(0xFFE4572E.toInt(), BrandColors.parse("#e4572e"))
        assertNull(BrandColors.parse("E4572E"), "no hash")
        assertNull(BrandColors.parse("#E45"), "short form is not accepted")
        assertNull(BrandColors.parse("#E4572EFF"), "no alpha: the app decides transparency")
        assertNull(BrandColors.parse("#GGGGGG"))
        assertNull(BrandColors.parse(null))
        assertEquals("#E4572E", BrandColors.format(0xFFE4572E.toInt()))
    }

    @Test
    fun `a legible brand colour is used exactly as sent`() {
        // The red is the one that matters: at WCAG's 3:1 it was lightened and
        // warned about, though red on the app's blue-grey is plainly visible.
        for (accent in listOf("#F2A541", "#E4572E", "#00A36C")) {
            val (palette, adjustments) = BrandColors.resolve(accent, null, null)
            assertNotNull(palette)
            assertEquals(accent, BrandColors.format(palette.accent))
            assertTrue(adjustments.isEmpty(), "$accent needed nothing changing: $adjustments")
        }
    }

    @Test
    fun `an accent too dark for a dark screen is lightened, and the change is reported`() {
        val (palette, adjustments) = BrandColors.resolve("#1B2A5A", null, null)
        assertNotNull(palette)
        assertTrue(
            BrandColors.contrast(palette.accent, BrandColors.SURFACE) >= BrandColors.ACCENT_CONTRAST,
            "the accent can now be seen",
        )
        val adjusted = adjustments.single { it.field == "colors.accent" }
        assertEquals("#1B2A5A", adjusted.requested)
        assertEquals(BrandColors.format(palette.accent), adjusted.used, "the warning names the colour drawn")
    }

    @Test
    fun `text on the accent is always readable, chosen when not sent`() {
        for (accent in listOf("#E4572E", "#F2F4F7", "#FFD400", "#00A36C", "#7B2CBF")) {
            val (palette, _) = BrandColors.resolve(accent, null, null)
            assertNotNull(palette)
            assertTrue(
                BrandColors.contrast(palette.onAccent, palette.accent) >= BrandColors.TEXT_CONTRAST ||
                    // A mid-tone can defeat both light and dark text; the app then
                    // still picks whichever of the two reads better.
                    palette.onAccent in listOf(BrandColors.TEXT, BrandColors.TEXT_DARK),
                "readable text on $accent",
            )
        }
    }

    @Test
    fun `an onAccent that cannot be read is replaced and reported`() {
        val (palette, adjustments) = BrandColors.resolve("#FFD400", "#FFFFFF", null)
        assertNotNull(palette)
        assertEquals(BrandColors.TEXT_DARK, palette.onAccent, "dark text on yellow")
        assertEquals("#FFFFFF", adjustments.single { it.field == "colors.onAccent" }.requested)
    }

    @Test
    fun `the header always carries the app's light text`() {
        for (header in listOf("#FFFFFF", "#F2A541", "#3B1F2B", null)) {
            val (palette, _) = BrandColors.resolve("#E4572E", null, header)
            assertNotNull(palette)
            assertTrue(
                BrandColors.contrast(BrandColors.TEXT, palette.header) >= BrandColors.TEXT_CONTRAST,
                "light text reads on header $header",
            )
        }
    }

    @Test
    fun `a dark header that already reads is left alone`() {
        val (palette, adjustments) = BrandColors.resolve("#E4572E", null, "#3B1F2B")
        assertNotNull(palette)
        assertEquals("#3B1F2B", BrandColors.format(palette.header))
        assertTrue(adjustments.none { it.field == "colors.header" })
    }

    @Test
    fun `a header derived from the accent keeps its hue`() {
        val (palette, _) = BrandColors.resolve("#E4572E", null, null)
        assertNotNull(palette)
        val r = (palette.header shr 16) and 0xFF
        val b = palette.header and 0xFF
        assertTrue(r > b, "a red brand gets a warm header, not the app's blue-grey")
    }

    @Test
    fun `no accent means no palette at all`() {
        assertNull(BrandColors.resolve(null, "#FFFFFF", "#000000").first)
        assertNull(BrandColors.resolve("red", null, null).first)
    }

    // Icons ─────────────────────────────────────────────────────────────────

    /** The smallest thing that passes for a PNG header: signature, then an IHDR. */
    private fun pngHeader(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(33)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(bytes)
        byteArrayOf(0, 0, 0, 13).copyInto(bytes, 8)
        "IHDR".encodeToByteArray().copyInto(bytes, 12)
        fun put(at: Int, v: Int) {
            bytes[at] = (v ushr 24).toByte(); bytes[at + 1] = (v ushr 16).toByte()
            bytes[at + 2] = (v ushr 8).toByte(); bytes[at + 3] = v.toByte()
        }
        put(16, width)
        put(20, height)
        return bytes
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun b64(bytes: ByteArray) = Base64.encode(bytes)

    @Test
    fun `a square PNG in range is accepted`() {
        val checked = BrandIcon.check(b64(pngHeader(256, 256)))
        assertIs<BrandIcon.Checked.Ok>(checked)
        assertEquals(256, checked.sidePx)
    }

    @Test
    fun `size is judged from the header, before anything is decoded`() {
        // Forty thousand pixels declared in thirty-three bytes. Decoding it to
        // find out would be the expensive mistake this check exists to avoid.
        val checked = BrandIcon.check(b64(pngHeader(40_000, 40_000)))
        assertIs<BrandIcon.Checked.Rejected>(checked)
        assertTrue("512" in checked.reason)
    }

    @Test
    fun `icons that are not square, too small, not PNG or not base64 are refused by name`() {
        assertTrue("square" in (BrandIcon.check(b64(pngHeader(256, 128))) as BrandIcon.Checked.Rejected).reason)
        assertTrue("48" in (BrandIcon.check(b64(pngHeader(16, 16))) as BrandIcon.Checked.Rejected).reason)
        assertTrue(
            "PNG" in (BrandIcon.check(b64("GIF89a-not-a-png-at-all-really".encodeToByteArray()))
                as BrandIcon.Checked.Rejected).reason,
        )
        assertTrue("base64" in (BrandIcon.check("%%% not base64 %%%") as BrandIcon.Checked.Rejected).reason)
    }

    @Test
    fun `an icon over the size limit is refused before it is decoded`() {
        val tooLong = "A".repeat(BrandIcon.MAX_BASE64_CHARS + 4)
        assertTrue("limit" in (BrandIcon.check(tooLong) as BrandIcon.Checked.Rejected).reason)
    }

    @Test
    fun `two brands with the same icon bytes are equal`() {
        val a = CompanionBrand(name = "x", iconPng = byteArrayOf(1, 2, 3))
        val b = CompanionBrand(name = "x", iconPng = byteArrayOf(1, 2, 3))
        assertEquals(a, b, "or the header redraws every time a frame repeats itself")
        assertEquals(a.hashCode(), b.hashCode())
    }
}
