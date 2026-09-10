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
package app.indoorbike.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Slate palette: five slate blues for every surface, three cool whites for
 * every mark on them, and no accent colour at all. Emphasis comes from contrast
 * and weight, never hue.
 *
 * The one exception is the power zones, which are unchanged. They are the only
 * saturated colour in the app and they only ever mean a zone, so a rider can
 * read intensity from colour alone without first learning what green means
 * here as opposed to anywhere else.
 *
 * The object keeps its old name because every screen references it; the design
 * system it now carries is Slate, not Cadence.
 */
object CadenceColors {
    // Surfaces, ascending. Bg0 is the canvas; each step up is one level of lift.
    val Bg0 = Color(0xFF313E50) // canvas
    val Bg1 = Color(0xFF3A435E) // cards, sheets, tab bar
    val Bg2 = Color(0xFF455561) // icon wells, inputs, inactive bars, secondary buttons
    val Bg3 = Color(0xFF5C6672) // pressed state, dashed outlines
    val Bg4 = Color(0xFF6C6F7F) // disabled ink, unpaired status dot

    val Line1 = Color(0xFF455561) // 1 dp divider inside a card
    val Line2 = Color(0xFF5C6672) // 1 dp dashed outline on locked or empty cards
    val Line3 = Color(0xFF6C6F7F)

    // None of the slates is light enough to be text on Bg0 (Bg4 is 2.3:1), so
    // the foregrounds are cool-tinted neutrals rather than palette members.
    val Fg1 = Color(0xFFF2F4F7)
    val Fg2 = Color(0xFFC5CAD6)
    val Fg3 = Color(0xFF9AA1B0)
    val Fg4 = Color(0xFF6C6F7F)

    // The "brand" is the foreground. A primary button is an Fg1 fill with
    // OnBrand text. These aliases exist so old call sites keep compiling; new
    // code should say Fg1.
    val Brand500 = Fg1
    val Brand600 = Fg2
    val Brand700 = Color(0xFFA9AEBC)
    val Brand400 = Fg1
    val Brand300 = Fg1
    val OnBrand = Bg0

    // Gradient endpoints collapse to flat fills; these keep the old names alive.
    val Ember600 = Fg2
    val Ember700 = Fg3

    val Success = Color(0xFF1FB373) // Z3
    val Warning = Color(0xFFF0C419) // Z4
    val Danger = Color(0xFFEB3B3B)  // Z6
    val Info = Fg1
}
