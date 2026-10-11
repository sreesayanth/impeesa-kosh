package com.impeesa.finance.core.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** The single easing curve used everywhere: quick start, soft landing. */
val ImpeesaEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

object ImpeesaTheme {
    val colors: ImpeesaColors
        @Composable @ReadOnlyComposable get() = LocalImpeesaColors.current

    val type: ImpeesaTypography
        @Composable @ReadOnlyComposable get() = LocalImpeesaTypography.current
}

@Composable
fun ImpeesaTheme(content: @Composable () -> Unit) {
    val colors = DarkImpeesaColors
    val type = DefaultTypography
    val scheme = darkColorScheme(
        primary = colors.lime,
        onPrimary = colors.onLime,
        background = colors.ink,
        onBackground = colors.textPrimary,
        surface = colors.card,
        onSurface = colors.textPrimary,
        surfaceVariant = colors.raised,
        onSurfaceVariant = colors.textSecondary,
        error = colors.coral,
        outline = colors.line,
    )
    val materialType = Typography(
        bodyLarge = type.body,
        bodyMedium = type.body,
        titleMedium = type.title,
        labelLarge = type.label,
        headlineSmall = type.headline,
    )
    CompositionLocalProvider(
        LocalImpeesaColors provides colors,
        LocalImpeesaTypography provides type,
    ) {
        MaterialTheme(colorScheme = scheme, typography = materialType, content = content)
    }
}
