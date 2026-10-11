package com.impeesa.finance

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.navigation.ImpeesaNavHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as ImpeesaApp).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                ImpeesaTheme {
                    ImpeesaNavHost()
                }
            }
        }
    }
}
