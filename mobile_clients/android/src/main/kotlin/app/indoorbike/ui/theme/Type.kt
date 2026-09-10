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
@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.indoorbike.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.indoorbike.R

// One bundled OFL family: Manrope, variable on wght. No Google Fonts CDN, no
// downloadable-font provider, no runtime fetch. The app has to render on a
// device with no Google services.
//
// Slate has no display face and no mono. A single family across the whole app
// means the only typographic variables left are size and weight, which is what
// the design leans on: 800 for anything that carries a number, 500 for prose.
private fun manrope(weight: FontWeight) = Font(
    R.font.manrope_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

val SlateFontFamily: FontFamily = FontFamily(
    manrope(FontWeight.Normal),
    manrope(FontWeight.Medium),
    manrope(FontWeight.SemiBold),
    manrope(FontWeight.Bold),
    manrope(FontWeight.ExtraBold)
)

// The old three-family names, kept so call sites outside the theme compile.
// They all resolve to Manrope now.
val DisplayFontFamily: FontFamily = SlateFontFamily
val BodyFontFamily: FontFamily = SlateFontFamily
val MonoFontFamily: FontFamily = SlateFontFamily

// Tabular figures on every style that carries a number, so a ticking clock or a
// changing power reading does not shuffle the digits beside it.
private const val TNUM = "tnum"

object CadenceType {
    /** Ride HUD power number, landscape. The biggest thing in the app. */
    val displayXl = TextStyle(
        fontFamily = SlateFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 132.sp,
        lineHeight = 132.sp,
        letterSpacing = (-0.04).em,
        fontFeatureSettings = TNUM
    )

    /** Ride-summary hero: duration, average power. */
    val displayL = TextStyle(
        fontFamily = SlateFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 48.sp,
        lineHeight = 53.sp,
        letterSpacing = (-0.02).em,
        fontFeatureSettings = TNUM
    )

    /** HUD flank values: cadence, heart rate. */
    val displayM = TextStyle(
        fontFamily = SlateFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.02).em,
        fontFeatureSettings = TNUM
    )

    /** Screen titles. Sentence case. */
    val h1 = TextStyle(
        fontFamily = SlateFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.02).em
    )

    /** Stat values, sheet titles. */
    val h2 = TextStyle(
        fontFamily = SlateFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp,
        lineHeight = 25.sp,
        fontFeatureSettings = TNUM
    )

    /** Card titles, HUD workout name. */
    val h3 = TextStyle(
        fontFamily = SlateFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 22.sp
    )

    val body = TextStyle(
        fontFamily = SlateFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp
    )

    /** Row titles, button labels. */
    val bodyStrong = TextStyle(
        fontFamily = SlateFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 22.sp
    )

    /** Row sub-lines. */
    val small = TextStyle(
        fontFamily = SlateFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
    )

    /** Tab labels, chart axis. */
    val micro = TextStyle(
        fontFamily = SlateFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )

    /** Section labels. Sentence case now: no capitals, no tracking. */
    val eyebrow = TextStyle(
        fontFamily = SlateFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp
    )

    /** Stat labels, week-header totals, units. Formerly JetBrains Mono. */
    val tick = TextStyle(
        fontFamily = SlateFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = TNUM
    )

    val mono = TextStyle(
        fontFamily = SlateFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = TNUM
    )
}

internal val CadenceTypography = Typography(
    displayLarge = CadenceType.displayL,
    displayMedium = CadenceType.displayM,
    headlineLarge = CadenceType.h1,
    headlineMedium = CadenceType.h2,
    headlineSmall = CadenceType.h3,
    titleLarge = CadenceType.h2,
    titleMedium = CadenceType.h3,
    bodyLarge = CadenceType.body,
    bodyMedium = CadenceType.body,
    bodySmall = CadenceType.small,
    labelLarge = CadenceType.bodyStrong,
    labelMedium = CadenceType.micro,
    labelSmall = CadenceType.eyebrow
)
