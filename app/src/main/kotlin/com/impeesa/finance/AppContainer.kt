package com.impeesa.finance

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.impeesa.finance.core.data.DataGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock

/**
 * Manual dependency injection: one place that builds the app's long-lived objects.
 * Everything is lazy so start-up stays fast.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val clock: Clock = Clock.systemDefaultZone()

    private val data = DataGraph(appContext)

    val categoryRepository get() = data.categoryRepository
    val transactionRepository get() = data.transactionRepository
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
