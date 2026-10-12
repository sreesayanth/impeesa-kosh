package com.impeesa.finance.core.ui.component

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True when the phone's "Remove animations" is on (IMPLEMENTATION_PLAN.md §11.2). Charts then appear
 * at their final state instead of sweeping and rising in.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

object ReduceMotion {
    fun enabled(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
