package com.impeesa.finance.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colour tokens from the Foundations board. */
@Immutable
data class ImpeesaColors(
    val ink: Color,
    val card: Color,
    val raised: Color,
    val line: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val lime: Color,
    val onLime: Color,
    val coral: Color,
    val amber: Color,
    val food: Color,
    val shopping: Color,
    val bills: Color,
    val travel: Color,
    val groceries: Color,
    val fun_: Color,
    val health: Color,
    val other: Color,
)

val DarkImpeesaColors = ImpeesaColors(
    ink = Color(0xFF0B0D10),
    card = Color(0xFF15181D),
    raised = Color(0xFF1D2127),
    line = Color(0xFF2A2F37),
    textPrimary = Color(0xFFF4F5F7),
    textSecondary = Color(0xFF9AA0AA),
    textTertiary = Color(0xFF8A919C),
    lime = Color(0xFFC5F26B),
    onLime = Color(0xFF0B0D10),
    coral = Color(0xFFFF8562),
    amber = Color(0xFFF5C94C),
    food = Color(0xFFFF9F5A),
    shopping = Color(0xFFA493FF),
    bills = Color(0xFFF5C94C),
    travel = Color(0xFF5CC8F5),
    groceries = Color(0xFF3FD0C9),
    fun_ = Color(0xFFF27BB5),
    health = Color(0xFF7FB2FF),
    other = Color(0xFF9AA0AA),
)

internal val LocalImpeesaColors = staticCompositionLocalOf { DarkImpeesaColors }
