@file:OptIn(ExperimentalTextApi::class)

package com.impeesa.finance.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.impeesa.finance.core.ui.R

private fun sora(weight: Int) =
    Font(R.font.sora, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private fun jakarta(weight: Int) =
    Font(R.font.plus_jakarta_sans, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val SoraFamily = FontFamily(sora(500), sora(600), sora(700))
val JakartaFamily = FontFamily(jakarta(400), jakarta(500), jakarta(600), jakarta(700))

private const val TABULAR = "tnum"

@Immutable
data class ImpeesaTypography(
    val hero: TextStyle,
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val amount: TextStyle,
    val amountLarge: TextStyle,
)

val DefaultTypography = ImpeesaTypography(
    hero = TextStyle(fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 44.sp, letterSpacing = (-1.5).sp, fontFeatureSettings = TABULAR),
    display = TextStyle(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = (-0.6).sp),
    headline = TextStyle(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.4).sp),
    title = TextStyle(fontFamily = JakartaFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    body = TextStyle(fontFamily = JakartaFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyStrong = TextStyle(fontFamily = JakartaFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    label = TextStyle(fontFamily = JakartaFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    caption = TextStyle(fontFamily = JakartaFamily, fontWeight = FontWeight.Medium, fontSize = 12.5.sp),
    amount = TextStyle(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, fontFeatureSettings = TABULAR),
    amountLarge = TextStyle(fontFamily = SoraFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.5).sp, fontFeatureSettings = TABULAR),
)

internal val LocalImpeesaTypography = staticCompositionLocalOf { DefaultTypography }
