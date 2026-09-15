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

/** A picture of the companion's screen, checked and ready to keep with the ride. */
class ScreenshotImage(
    val bytes: ByteArray,
    /** [PNG] or [JPEG], read from the bytes rather than from anything the sender claimed. */
    val format: String,
    val widthPx: Int,
    val heightPx: Int,
) {
    companion object {
        const val PNG = "png"
        const val JPEG = "jpeg"

        /**
         * Longest base64 string a screenshot may be: about 4.5 MB of image.
         *
         * Generous on purpose. A JPEG of a 1080p game frame is a few hundred
         * kilobytes, but a PNG of the same frame can be several megabytes, and
         * a rider who asked for a picture of the view should get one. It is
         * one frame the rider asked for, not a stream.
         */
        const val MAX_BASE64_CHARS = 6_000_000

        /** Longest edge the phone asks for. A hint: see [MAX_EDGE_PX] for what it accepts. */
        const val REQUESTED_EDGE_PX = 1920

        /** Longest edge accepted, and shortest. Beyond 4K is a picture nobody can look at on a phone. */
        const val MAX_EDGE_PX = 4096
        const val MIN_EDGE_PX = 16

        /** Longest reason a companion may give for not taking one. */
        const val MAX_ERROR_CHARS = 200

        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )

        sealed interface Checked {
            class Ok(val image: ScreenshotImage) : Checked
            data class Rejected(val reason: String) : Checked
        }

        /**
         * Check a screenshot before anything tries to draw it.
         *
         * PNG or JPEG, told apart by their first bytes, with the size read from
         * the header alone. Decoding is the step a careless image makes
         * expensive: a small file can declare a picture forty thousand pixels
         * wide, and a phone that decoded it to find out would find out by
         * running out of memory.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun check(base64: String): Checked {
            if (base64.length > MAX_BASE64_CHARS) {
                return Checked.Rejected(
                    "The screenshot is " + base64.length + " base64 characters; the limit is " +
                        MAX_BASE64_CHARS + ". Send a JPEG, scaled to the maxEdgePx the request asked for.",
                )
            }
            val bytes = try {
                Base64.decode(base64.trim())
            } catch (e: IllegalArgumentException) {
                return Checked.Rejected("The screenshot is not valid base64: " + (e.message ?: "decode failed"))
            }
            val size = when {
                bytes.size >= 24 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) ->
                    pngSize(bytes)?.let { PNG to it }
                    ?: return Checked.Rejected("The screenshot PNG has no IHDR header.")
                bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() ->
                    jpegSize(bytes)?.let { JPEG to it }
                    ?: return Checked.Rejected("The screenshot JPEG has no frame header saying how big it is.")
                else -> return Checked.Rejected("The screenshot is neither a PNG nor a JPEG.")
            }
            val (format, dimensions) = size
            val (width, height) = dimensions
            if (maxOf(width, height) > MAX_EDGE_PX || minOf(width, height) < MIN_EDGE_PX) {
                return Checked.Rejected(
                    "The screenshot is " + width + "x" + height + " px; each edge must be " +
                        MIN_EDGE_PX + " to " + MAX_EDGE_PX + " px. Scale it to the request's maxEdgePx.",
                )
            }
            return Checked.Ok(ScreenshotImage(bytes, format, width, height))
        }

        private fun u16(bytes: ByteArray, at: Int): Int =
            ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

        private fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
            if (bytes.copyOfRange(12, 16).decodeToString() != "IHDR") return null
            fun u32(at: Int): Long = (u16(bytes, at).toLong() shl 16) or u16(bytes, at + 2).toLong()
            val width = u32(16)
            val height = u32(20)
            if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) return null
            return width.toInt() to height.toInt()
        }

        /**
         * The width and height from a JPEG's start-of-frame segment.
         *
         * Walks the segments from the top: every marker but the standalone ones
         * carries its own length, so each is skipped whole until a SOF turns
         * up. SOF is C0 to CF, less C4 (Huffman tables), C8 (reserved) and CC
         * (arithmetic coding), and it says height before width.
         */
        private fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
            var at = 2
            while (at + 3 < bytes.size) {
                if (bytes[at] != 0xFF.toByte()) return null
                val marker = bytes[at + 1].toInt() and 0xFF
                // Fill bytes: any number of FFs may pad before a marker.
                if (marker == 0xFF) {
                    at += 1
                    continue
                }
                // Standalone markers carry no length.
                if (marker == 0x01 || marker in 0xD0..0xD7) {
                    at += 2
                    continue
                }
                // Start of scan or end of image, and still no frame header.
                if (marker == 0xDA || marker == 0xD9) return null
                val length = u16(bytes, at + 2)
                if (length < 2) return null
                val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (isFrame) {
                    if (at + 8 >= bytes.size) return null
                    val height = u16(bytes, at + 5)
                    val width = u16(bytes, at + 7)
                    return width to height
                }
                at += 2 + length
            }
            return null
        }
    }
}
