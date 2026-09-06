package app.indoorbike.ui.theme

import androidx.compose.ui.graphics.Color

/** Slate design system. Replaces CadenceColors 1:1 so call sites keep compiling. */
object CadenceColors {
    // Surfaces: the five slates, ascending
    val Bg0 = Color(0xFF313E50)  // canvas
    val Bg1 = Color(0xFF3A435E)  // cards, sheets, tab bar
    val Bg2 = Color(0xFF455561)  // icon wells, inputs, inactive bars
    val Bg3 = Color(0xFF5C6672)  // pressed, strong outline
    val Bg4 = Color(0xFF6C6F7F)  // disabled ink, inactive dot

    val Line1 = Color(0xFF455561)  // divider inside a card
    val Line2 = Color(0xFF5C6672)  // card outline (locked / dashed states)
    val Line3 = Color(0xFF6C6F7F)

    val Fg1 = Color(0xFFF2F4F7)
    val Fg2 = Color(0xFFC5CAD6)
    val Fg3 = Color(0xFF9AA1B0)
    val Fg4 = Color(0xFF6C6F7F)

    // Monochrome: the "brand" is the foreground. Primary buttons are Fg1 with OnBrand text.
    val Brand500 = Fg1
    val Brand600 = Fg2
    val Brand700 = Color(0xFFA9AEBC)
    val Brand400 = Fg1
    val Brand300 = Fg1
    val OnBrand = Bg0

    val Ember600 = Fg2   // legacy aliases, gradient endpoints collapse to flat fills
    val Ember700 = Fg3

    val Success = Color(0xFF1FB373)  // Z3
    val Warning = Color(0xFFF0C419)  // Z4
    val Danger = Color(0xFFEB3B3B)   // Z6
    val Info = Fg1
}
