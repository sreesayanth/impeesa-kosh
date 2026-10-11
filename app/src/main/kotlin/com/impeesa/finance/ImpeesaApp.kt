package com.impeesa.finance

import android.app.Application
import kotlinx.coroutines.launch

class ImpeesaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.ioScope.launch { container.seed() }
    }
}
