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

/**
 * Text a companion sends that the app shows or keeps: its name, a ride's
 * label and world, the name it asks to be paired under.
 *
 * All of it came in unbounded and unfiltered, and it reaches the HUD, the
 * pairing dialog, the log, the paired list and the share card. A name a
 * megabyte long, one full of control characters, or one using bidirectional
 * overrides to show the rider a different name from the one stored, is not
 * something a legitimate companion sends. So every such string is trimmed,
 * stripped of control and bidi-override characters, and capped.
 */
object CompanionText {

    /** A companion's name, or a ride's label or world. */
    const val MAX_NAME_CHARS = 64

    /**
     * [text] trimmed, with control and bidirectional-formatting characters
     * removed, cut to at most [max] characters without splitting a surrogate
     * pair; null when nothing is left.
     */
    fun clean(text: String?, max: Int = MAX_NAME_CHARS): String? {
        if (text == null) return null
        val kept = StringBuilder(minOf(text.length, max + 1))
        for (c in text) {
            if (c.isISOControl() || c.isBidiFormatting()) continue
            kept.append(c)
        }
        var out = kept.toString().trim()
        if (out.length > max) {
            out = out.substring(0, max)
            if (out.last().isHighSurrogate()) out = out.dropLast(1)
            out = out.trimEnd()
        }
        return out.ifEmpty { null }
    }

    private fun Char.isBidiFormatting(): Boolean =
        this in '‪'..'‮' || this in '⁦'..'⁩' ||
            this == '‎' || this == '‏' || this == '؜'
}
