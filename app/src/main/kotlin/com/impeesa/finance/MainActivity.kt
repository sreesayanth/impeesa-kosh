package com.impeesa.finance

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.impeesa.finance.capture.CaptureHealth
import com.impeesa.finance.capture.InboxReconcileWorker
import com.impeesa.finance.capture.SmsPermission
import com.impeesa.finance.core.ui.component.LocalAmountsHidden
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.navigation.ImpeesaNavHost
import com.impeesa.finance.security.LockScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The only activity. A FragmentActivity because the system biometric prompt needs one.
 * The lock screen is drawn above everything, so a notification tap can't skip it (§12.4).
 */
class MainActivity : FragmentActivity() {
    /** Where a notification tap wants to go: "review", "txn:<id>", "account:<id>"… Consumed by the nav host. */
    private val pendingLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as ImpeesaApp).container
        if (savedInstanceState == null) pendingLink.value = intent.getStringExtra(EXTRA_OPEN)
        lifecycleScope.launch { container.appLock.hideInRecents.collect(::protectRecents) }
        setContent {
            val locked by container.appLock.locked.collectAsStateWithLifecycle()
            val hideBalances by container.appLock.hideBalances.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalAppContainer provides container, LocalAmountsHidden provides hideBalances) {
                ImpeesaTheme {
                    Box(Modifier.fillMaxSize()) {
                        ImpeesaNavHost(pendingLink = pendingLink, onLinkHandled = { pendingLink.value = null })
                        if (locked) LockScreen()
                    }
                }
            }
        }
    }

    /** Keeps balances out of the recent-apps preview (§12.1). */
    private fun protectRecents(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!on)
        } else if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { pendingLink.value = it }
    }

    override fun onStart() {
        super.onStart()
        val container = (application as ImpeesaApp).container
        container.appLock.onForeground(System.currentTimeMillis())
        // Catch messages that arrived while the app was closed.
        if (SmsPermission.granted(this)) InboxReconcileWorker.runNow(this)
        CaptureHealth.requestRebind(this)
    }

    override fun onStop() {
        super.onStop()
        // Rotating the screen isn't leaving the app.
        if (!isChangingConfigurations) (application as ImpeesaApp).container.appLock.onBackground(System.currentTimeMillis())
    }

    companion object {
        const val EXTRA_OPEN = "open"
    }
}
