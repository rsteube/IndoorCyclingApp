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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How a companion looks on the phone while it is driving: a name, an icon and
 * three colours.
 *
 * The rider has two screens in front of them, the game's and this one, and
 * until now nothing on the phone said they belonged together. A companion that
 * sends this gets its own identity on the ride header, so the two ends read as
 * one thing.
 *
 * ## What it may style, and what it may not
 *
 * The companion owns decoration: the header band, its icon, and the brief flash
 * of a control being pressed. The app keeps everything that carries meaning.
 * Power and heart-rate zone colours, the gradient's colour, warnings, and the
 * app's own navigation stay exactly as they are in every other mode, because a
 * rider who has learnt that amber means "look at this" must not find a game has
 * made everything amber. The pairing dialog is never styled either: it is where
 * the rider decides whether to trust the thing asking, and the thing asking
 * does not get to dress the question.
 *
 * Nothing here is persisted. It lives for the session and is gone when the
 * rider leaves Companion mode.
 */
data class CompanionBrand(
    /** What to call the companion on the header. Absent falls back to `hello.client.name`. */
    val name: String? = null,
    /** A validated PNG, still encoded. The app decodes it; this side only checks it. */
    val iconPng: ByteArray? = null,
    /** Colours after the app has made them legible. Null keeps the app's own. */
    val palette: BrandPalette? = null,
) {
    val isEmpty: Boolean get() = name == null && iconPng == null && palette == null

    // A ByteArray's equals is identity, which would make two identical brands
    // unequal and redraw the header on every frame that merely repeats itself.
    override fun equals(other: Any?): Boolean =
        other is CompanionBrand && name == other.name && palette == other.palette &&
            (iconPng?.contentEquals(other.iconPng) ?: (other.iconPng == null))

    override fun hashCode(): Int =
        31 * (31 * (name?.hashCode() ?: 0) + (iconPng?.contentHashCode() ?: 0)) +
            (palette?.hashCode() ?: 0)
}

/**
 * A companion's colours, as the app will actually draw them. ARGB, alpha always opaque.
 *
 * @param accent highlights: the line under the header, the icon's fallback
 *   monogram, and the fill of a paddle or a remote button while it is pressed.
 * @param onAccent text and glyphs drawn on [accent].
 * @param header the header band's background, which always carries the app's
 *   light text.
 */
data class BrandPalette(
    val accent: Int,
    val onAccent: Int,
    val header: Int,
)

/** A colour the app changed to keep the screen legible, reported back as a warning. */
data class BrandAdjustment(val field: String, val requested: String, val used: String, val why: String)

object BrandColors {

    /**
     * The surfaces a companion's colours are drawn against, mirrored from the
     * app's Slate palette. Held here, rather than read from the Android theme,
     * so the legibility rules are the same wherever the protocol runs and can
     * be tested without a device.
     */
    const val SURFACE: Int = 0xFF3A435E.toInt()
    const val CANVAS: Int = 0xFF313E50.toInt()
    const val TEXT: Int = 0xFFF2F4F7.toInt()
    const val TEXT_DARK: Int = 0xFF14181F.toInt()

    /**
     * Minimum luminance contrast of [BrandPalette.accent] against [SURFACE].
     *
     * 2.5:1, deliberately under WCAG's 3:1 for graphical objects. That figure is
     * for thin strokes and boundaries; the accent only ever fills large areas, a
     * thick line under the header, a pressed paddle, a monogram, where hue does
     * much of the work luminance cannot measure. At 3:1 an ordinary brand red,
     * #E4572E at 2.66, was lightened and warned about, which dilutes a game's
     * identity to fix a problem the rider does not have. A navy or a deep purple,
     * which genuinely disappear on the app's blue-grey, are still corrected.
     */
    const val ACCENT_CONTRAST = 2.5

    /** Minimum contrast for text: [BrandPalette.onAccent] on the accent, and the app's text on the header. */
    const val TEXT_CONTRAST = 4.5

    /** How much of the accent a header derived from it carries, over [SURFACE]. */
    const val DERIVED_HEADER_MIX = 0.30

    /** `#RRGGBB`, either case. Null for anything else, including `#RGB` and alpha. */
    fun parse(hex: String?): Int? {
        val s = hex?.trim() ?: return null
        if (s.length != 7 || s[0] != '#') return null
        val rgb = s.substring(1).toIntOrNull(16) ?: return null
        return (0xFF shl 24) or rgb
    }

    fun format(argb: Int): String {
        val rgb = argb and 0xFFFFFF
        return "#" + rgb.toString(16).uppercase().padStart(6, '0')
    }

    /** WCAG relative luminance, 0 for black to 1 for white. */
    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** [from] moved [amount] of the way to [to], per channel. */
    fun mix(from: Int, to: Int, amount: Double): Int {
        val t = amount.coerceIn(0.0, 1.0)
        fun ch(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Turn what a companion asked for into colours that are safe to draw.
     *
     * Adjusted rather than refused. A brand colour that is slightly too dark
     * for a dark screen is still recognisably that brand when lightened a
     * little, and a header that keeps most of its hue is worth more to a game
     * than the app's grey. Every change is reported, with the value actually
     * used, so a developer who wonders why their red looks pink can see why.
     *
     * Returns null when there is no usable accent, because the other two are
     * defined relative to it.
     */
    fun resolve(
        accent: String?,
        onAccent: String?,
        header: String?,
    ): Pair<BrandPalette?, List<BrandAdjustment>> {
        val adjustments = mutableListOf<BrandAdjustment>()
        val requestedAccent = parse(accent) ?: return null to adjustments

        // Lighten towards white until it can be seen on the app's surface. A
        // dark accent on a dark UI is the one failure that is easy to make and
        // invisible to the person making it, on their own light-themed desktop.
        var usedAccent = requestedAccent
        var step = 0
        while (contrast(usedAccent, SURFACE) < ACCENT_CONTRAST && step < 20) {
            step++
            usedAccent = mix(requestedAccent, 0xFFFFFFFF.toInt(), step * 0.05)
        }
        if (usedAccent != requestedAccent) {
            adjustments += BrandAdjustment(
                field = "colors.accent",
                requested = format(requestedAccent),
                used = format(usedAccent),
                why = "too dark to see on the app's dark surface; lightened to reach 2.5:1",
            )
        }

        val light = TEXT
        val dark = TEXT_DARK
        val bestOnAccent = if (contrast(light, usedAccent) >= contrast(dark, usedAccent)) light else dark
        val requestedOnAccent = parse(onAccent)
        val usedOnAccent = when {
            requestedOnAccent == null -> bestOnAccent
            contrast(requestedOnAccent, usedAccent) >= TEXT_CONTRAST -> requestedOnAccent
            else -> {
                adjustments += BrandAdjustment(
                    field = "colors.onAccent",
                    requested = format(requestedOnAccent),
                    used = format(bestOnAccent),
                    why = "unreadable on the accent; replaced with the more legible of light and dark",
                )
                bestOnAccent
            }
        }

        // The header always carries the app's own light text, so it is darkened
        // until that text reads, whether the companion chose it or not.
        val requestedHeader = parse(header)
        val baseHeader = requestedHeader ?: mix(SURFACE, usedAccent, DERIVED_HEADER_MIX)
        var usedHeader = baseHeader
        step = 0
        while (contrast(TEXT, usedHeader) < TEXT_CONTRAST && step < 20) {
            step++
            usedHeader = mix(baseHeader, 0xFF000000.toInt(), step * 0.05)
        }
        if (requestedHeader != null && usedHeader != requestedHeader) {
            adjustments += BrandAdjustment(
                field = "colors.header",
                requested = format(requestedHeader),
                used = format(usedHeader),
                why = "too light for the header's light text; darkened to reach 4.5:1",
            )
        }

        return BrandPalette(usedAccent, usedOnAccent, usedHeader) to adjustments
    }
}

/** What is wrong with an icon, in words a developer can act on. Null when it is fine. */
object BrandIcon {

    /**
     * Longest base64 string an icon may be: about 72 kB of PNG, which is a
     * generous 256 px icon. The whole frame travels on the same socket as the
     * road, and a megabyte of artwork arriving while a rider is on a climb is a
     * megabyte the road waits behind.
     */
    const val MAX_BASE64_CHARS = 96_000
    const val MIN_SIDE_PX = 48
    const val MAX_SIDE_PX = 512

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    sealed interface Checked {
        class Ok(val png: ByteArray, val sidePx: Int) : Checked
        data class Rejected(val reason: String) : Checked
    }

    /**
     * Check an icon before anything tries to draw it.
     *
     * PNG only, square, 48 to 512 px, read from the header alone. The size is
     * checked from the IHDR chunk rather than by decoding, because decoding is
     * exactly the step a hostile or careless image makes expensive: a small
     * file can declare a picture forty thousand pixels wide.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun check(base64: String): Checked {
        if (base64.length > MAX_BASE64_CHARS) {
            return Checked.Rejected(
                "The icon is " + base64.length + " base64 characters; the limit is " +
                    MAX_BASE64_CHARS + ". A 256 px PNG fits comfortably.",
            )
        }
        val bytes = try {
            Base64.decode(base64.trim())
        } catch (e: IllegalArgumentException) {
            return Checked.Rejected("The icon is not valid base64: " + (e.message ?: "decode failed"))
        }
        if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
            return Checked.Rejected("The icon is not a PNG. Only PNG is accepted, with transparency if you like.")
        }
        fun u32(at: Int): Long =
            ((bytes[at].toLong() and 0xFF) shl 24) or ((bytes[at + 1].toLong() and 0xFF) shl 16) or
                ((bytes[at + 2].toLong() and 0xFF) shl 8) or (bytes[at + 3].toLong() and 0xFF)
        val chunkType = bytes.copyOfRange(12, 16).decodeToString()
        if (chunkType != "IHDR") return Checked.Rejected("The icon PNG has no IHDR header.")
        val width = u32(16)
        val height = u32(20)
        if (width != height) {
            return Checked.Rejected("The icon is " + width + "x" + height + "; it must be square.")
        }
        if (width < MIN_SIDE_PX || width > MAX_SIDE_PX) {
            return Checked.Rejected(
                "The icon is " + width + " px; it must be " + MIN_SIDE_PX + " to " + MAX_SIDE_PX +
                    " px. 256 px is a good size.",
            )
        }
        return Checked.Ok(bytes, width.toInt())
    }
}
