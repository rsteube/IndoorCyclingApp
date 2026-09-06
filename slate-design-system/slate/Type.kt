@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package app.indoorbike.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.indoorbike.R

// One bundled OFL family: Manrope (variable wght). Drop res/font/manrope_variable.ttf.
// Bebas Neue, Inter and JetBrains Mono are no longer referenced.
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
    manrope(FontWeight.ExtraBold),
)

// Kept the old names so screens compile unchanged.
val DisplayFontFamily: FontFamily = SlateFontFamily
val BodyFontFamily: FontFamily = SlateFontFamily
val MonoFontFamily: FontFamily = SlateFontFamily

private const val TNUM = "tnum"

object CadenceType {
    val displayXl = TextStyle(fontFamily = SlateFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 64.sp, lineHeight = 68.sp, letterSpacing = (-0.03).em, fontFeatureSettings = TNUM)
    val displayL  = TextStyle(fontFamily = SlateFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-0.02).em, fontFeatureSettings = TNUM)
    val displayM  = TextStyle(fontFamily = SlateFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.02).em, fontFeatureSettings = TNUM)
    val h1        = TextStyle(fontFamily = SlateFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.02).em)
    val h2        = TextStyle(fontFamily = SlateFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 24.sp, fontFeatureSettings = TNUM)
    val h3        = TextStyle(fontFamily = SlateFontFamily, fontWeight = FontWeight.Bold,      fontSize = 18.sp, lineHeight = 22.sp)
    val body      = TextStyle(fontFamily = SlateFontFamily, fontWeight = FontWeight.Medium,    fontSize = 15.sp, lineHeight = 22.sp)
    val bodyStrong= TextStyle(fontFamily = SlateFontFamily, fontWeight = FontWeight.Bold,      fontSize = 15.sp, lineHeight = 22.sp)
    val small     = TextStyle(fontFamily = SlateFontFamily, fontWeight = FontWeight.Medium,    fontSize = 13.sp, lineHeight = 18.sp)
    val micro     = TextStyle(fontFamily = SlateFontFamily, fontWeight = FontWeight.SemiBold,  fontSize = 11.sp, lineHeight = 14.sp)
    /** Section labels. Sentence case now: no uppercase, no tracking. */
    val eyebrow   = TextStyle(fontFamily = SlateFontFamily, fontWeight = FontWeight.SemiBold,  fontSize = 13.sp, lineHeight = 16.sp)
    /** Formerly JetBrains Mono. Tabular Manrope keeps columns aligned. */
    val tick      = TextStyle(fontFamily = SlateFontFamily, fontWeight = FontWeight.SemiBold,  fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = TNUM)
    val mono      = TextStyle(fontFamily = SlateFontFamily, fontWeight = FontWeight.Medium,    fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = TNUM)
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
