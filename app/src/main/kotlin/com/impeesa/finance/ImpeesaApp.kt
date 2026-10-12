package com.impeesa.finance

import android.app.Application
import com.impeesa.finance.alerts.AlertsWorker
import com.impeesa.finance.backup.MaintenanceWorker
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
        container.ioScope.launch {
            container.seed()
            // Capture flags messages in other currencies; keep it in step with the display currency.
            if (container.databaseError.value == null) {
                runCatching { container.captureRepository.homeCurrency = container.prefs.money.value.currency.code }
            }
        }
        if (SmsPermission.granted(this)) InboxReconcileWorker.scheduleDaily(this)
        AlertsWorker.scheduleDaily(this)
        AlertsWorker.runNow(this)
        MaintenanceWorker.schedule(this)
    }
}
