package com.impeesa.finance

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.impeesa.finance.capture.InboxReconcileWorker
import com.impeesa.finance.capture.SmsPermission
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.navigation.ImpeesaNavHost
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Where a notification tap wants to go: "review" or "txn:<id>". Consumed by the nav host. */
    private val pendingLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as ImpeesaApp).container
        if (savedInstanceState == null) pendingLink.value = intent.getStringExtra(EXTRA_OPEN)
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                ImpeesaTheme {
                    ImpeesaNavHost(pendingLink = pendingLink, onLinkHandled = { pendingLink.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { pendingLink.value = it }
    }

    override fun onStart() {
        super.onStart()
        // Catch messages that arrived while the app was closed.
        if (SmsPermission.granted(this)) InboxReconcileWorker.runNow(this)
    }

    companion object {
        const val EXTRA_OPEN = "open"
    }
}
