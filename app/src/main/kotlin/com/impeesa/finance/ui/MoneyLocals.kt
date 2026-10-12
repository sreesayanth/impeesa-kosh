package com.impeesa.finance.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.impeesa.finance.core.model.MoneySettings

/** The current display currency; changing it re-composes everything under it. */
val LocalMoneySettings = staticCompositionLocalOf { MoneySettings() }
