package com.impeesa.finance

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.impeesa.finance.core.data.DataGraph
import com.impeesa.finance.ui.UndoBus
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
    val undo = UndoBus()

    private val data = DataGraph(appContext, clock)

    val categoryRepository get() = data.categoryRepository
    val accountRepository get() = data.accountRepository
    val transactionRepository get() = data.transactionRepository

    suspend fun seed() = data.seed()
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
