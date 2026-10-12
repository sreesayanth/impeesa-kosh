package com.impeesa.finance

import android.app.Application
import com.impeesa.finance.alerts.AlertsWorker
import com.impeesa.finance.capture.InboxReconcileWorker
import com.impeesa.finance.capture.SmsPermission
import kotlinx.coroutines.launch

class ImpeesaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        container.alertNotifier.createChannels()
        container.ioScope.launch { container.seed() }
        if (SmsPermission.granted(this)) InboxReconcileWorker.scheduleDaily(this)
        AlertsWorker.scheduleDaily(this)
        AlertsWorker.runNow(this)
    }
}
