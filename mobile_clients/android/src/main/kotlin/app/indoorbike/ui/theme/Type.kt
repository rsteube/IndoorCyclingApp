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
@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.indoorbike.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.indoorbike.R

// Bundled OFL fonts. F-Droid clean — no Google Fonts CDN, no runtime download.
// Bebas Neue (static), Inter (variable wght), JetBrains Mono (variable wght).
val DisplayFontFamily: FontFamily = FontFamily(
    Font(R.font.bebas_neue_regular, FontWeight.Normal)
)

val BodyFontFamily: FontFamily = FontFamily(
    Font(
        R.font.inter_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    ),
    Font(
        R.font.inter_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))
    ),
    Font(
        R.font.inter_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))
    ),
    Font(
        R.font.inter_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))
    )
)

val MonoFontFamily: FontFamily = FontFamily(
    Font(
        R.font.jetbrains_mono_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    ),
    Font(
        R.font.jetbrains_mono_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))
    )
)

object CadenceType {
    val displayXl = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 72.sp,
        lineHeight = (72 * 1.05f).sp,
        letterSpacing = 0.01.em
    )
    val displayL = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 56.sp,
        lineHeight = (56 * 1.05f).sp,
        letterSpacing = 0.01.em
    )
    val displayM = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 40.sp,
        lineHeight = (40 * 1.2f).sp,
        letterSpacing = 0.02.em
    )
    val h1 = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = (32 * 1.2f).sp,
        letterSpacing = 0.02.em
    )
    val h2 = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = (24 * 1.2f).sp,
        letterSpacing = 0.03.em
    )
    val h3 = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = (20 * 1.2f).sp,
        letterSpacing = (-0.01).em
    )
    val body = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = (16 * 1.45f).sp
    )
    val bodyStrong = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = (16 * 1.45f).sp
    )
    val small = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = (14 * 1.45f).sp
    )
    val micro = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = (12 * 1.2f).sp,
        letterSpacing = 0.04.em
    )
    val eyebrow = TextStyle(
        fontFamily = BodyFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = (11 * 1.2f).sp,
        letterSpacing = 0.12.em,
        textDecoration = TextDecoration.None
    )
    val tick = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.04.em
    )
    val mono = TextStyle(
        fontFamily = MonoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.04.em
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
