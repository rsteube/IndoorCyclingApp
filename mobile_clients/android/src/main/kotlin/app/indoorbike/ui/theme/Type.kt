package app.indoorbike.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// TODO: bundle Bebas Neue, Inter, JetBrains Mono OFL TTFs into res/font/
// and replace these defaults. F-Droid requires local fonts, no Google Fonts CDN.
val DisplayFontFamily: FontFamily = FontFamily.Default
val BodyFontFamily: FontFamily = FontFamily.Default
val MonoFontFamily: FontFamily = FontFamily.Monospace

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
