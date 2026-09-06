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
package app.indoorbike.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * The three durations and the one curve the whole app moves on.
 *
 * Slate inherited these from the previous system unchanged, and they are worth
 * naming rather than retyping: a screen transition, a header collapsing and a
 * button press should all feel like the same hand moved them.
 *
 * The curve leaves fast and settles slowly, which is what makes a push feel
 * like it was thrown rather than dragged.
 */
object SlateMotion {
    val Easing: Easing = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)

    /** A press, a ripple, something leaving. */
    const val Fast = 120

    /** The default: a screen arriving, a header settling. */
    const val Base = 200

    /** Something large moving a long way. */
    const val Slow = 320
}
