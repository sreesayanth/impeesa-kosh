package com.impeesa.finance

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.impeesa.finance.backup.BackupService
import com.impeesa.finance.security.AppLock
import com.impeesa.finance.security.PassphraseVault
import java.io.File
import androidx.compose.runtime.staticCompositionLocalOf
import com.impeesa.finance.capture.CaptureNotifier
import com.impeesa.finance.alerts.AlertEngine
import com.impeesa.finance.alerts.AlertNotifier
import com.impeesa.finance.core.data.DataGraph
import com.impeesa.finance.core.model.alerts.AlertKind
import com.impeesa.finance.feature.profile.Profile
import com.impeesa.finance.ui.UndoBus
import com.impeesa.finance.ui.analytics.MoneyOverview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val prefs = AppPrefs(appContext)

    private val data = DataGraph(appContext, clock)

    val categoryRepository get() = data.categoryRepository
    val accountRepository get() = data.accountRepository
    val transactionRepository get() = data.transactionRepository
    val reviewRepository get() = data.reviewRepository
    val captureRepository get() = data.captureRepository
    val categorizationRepository get() = data.categorizationRepository
    val analyticsRepository get() = data.analyticsRepository
    val budgetRepository get() = data.budgetRepository
    val ledgerRepository get() = data.ledgerRepository
    val moneyOverview by lazy { MoneyOverview(data.analyticsRepository, data.budgetRepository, data.categoryRepository) }

    val backupRepository get() = data.backupRepository

    val notifier = CaptureNotifier(appContext) { data.reviewRepository }
    val alertNotifier = AlertNotifier(appContext, notifier)
    val alertEngine by lazy { AlertEngine(appContext, this) }
    val appLock = AppLock(appContext)
    val passphraseVault = PassphraseVault(appContext)
    val backups by lazy { BackupService(appContext, this) }

    suspend fun seed() = data.seed()

    /**
     * "Delete all data" (IMPLEMENTATION_PLAN.md §5.12): stops background work, removes the database,
     * its key, the backup passphrase and every preference file, then restarts the app fresh.
     */
    fun wipeEverything() {
        WorkManager.getInstance(appContext).cancelAllWork()
        WorkManager.getInstance(appContext).pruneWork()
        appContext.getSystemService(NotificationManager::class.java).cancelAll()
        data.wipe()
        passphraseVault.clear()
        File(appContext.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach { file ->
            appContext.deleteSharedPreferences(file.nameWithoutExtension)
        }
        val restart = Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        appContext.startActivity(restart)
        Runtime.getRuntime().exit(0)
    }

    /** Anything [wipeEverything] would leave behind; empty after a wipe (tested). */
    fun leftovers(): List<String> = data.leftovers() +
        (if (passphraseVault.exists()) listOf("backup passphrase") else emptyList()) +
        (File(appContext.applicationInfo.dataDir, "shared_prefs").listFiles()?.map { "prefs: ${it.name}" }.orEmpty())
}

/** Small app settings kept in SharedPreferences. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(value) = prefs.edit().putBoolean("onboarding_done", value).apply()

    private val _profile = MutableStateFlow(
        Profile(
            name = prefs.getString("profile_name", "").orEmpty(),
            colorIndex = prefs.getInt("profile_color", 0),
        ),
    )
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    fun saveProfile(name: String, colorIndex: Int) {
        val profile = Profile(Profile.cleanName(name), colorIndex)
        prefs.edit().putString("profile_name", profile.name).putInt("profile_color", profile.colorIndex).apply()
        _profile.value = profile
    }

    private val _monthStartDay = MutableStateFlow(prefs.getInt("month_start_day", 1))

    /** Budget month start day (salary cycle): 1–28, or 31 for the last day (IMPLEMENTATION_PLAN.md §5.8). */
    val monthStartDay: StateFlow<Int> = _monthStartDay.asStateFlow()

    fun setMonthStartDay(day: Int) {
        val clean = if (day >= 29) 31 else day.coerceAtLeast(1)
        prefs.edit().putInt("month_start_day", clean).apply()
        _monthStartDay.value = clean
    }

    private val _alertKinds = MutableStateFlow(readAlertKinds())

    /** Alert types the user has switched on (§5.10); low balance starts off. */
    val enabledAlerts: StateFlow<Set<AlertKind>> = _alertKinds.asStateFlow()

    fun setAlertEnabled(kind: AlertKind, enabled: Boolean) {
        val next = if (enabled) _alertKinds.value + kind else _alertKinds.value - kind
        prefs.edit().putStringSet("alert_kinds", next.map { it.name }.toSet()).apply()
        _alertKinds.value = next
    }

    private fun readAlertKinds(): Set<AlertKind> {
        val stored = prefs.getStringSet("alert_kinds", null) ?: return AlertKind.entries.filter { it.defaultOn }.toSet()
        return stored.mapNotNull { name -> AlertKind.entries.firstOrNull { it.name == name } }.toSet()
    }

    private val _dismissed = MutableStateFlow(readDismissed())

    /** Insight keys the user dismissed, with when they may show again (cooldowns, §10.3). */
    val dismissedInsights: StateFlow<Map<String, Long>> = _dismissed.asStateFlow()

    fun dismissInsight(key: String, untilMillis: Long) {
        val now = System.currentTimeMillis()
        val next = _dismissed.value.filterValues { it > now } + (key to untilMillis)
        prefs.edit().putStringSet("dismissed_insights", next.map { (k, v) -> "$v|$k" }.toSet()).apply()
        _dismissed.value = next
    }

    private fun readDismissed(): Map<String, Long> =
        prefs.getStringSet("dismissed_insights", emptySet()).orEmpty().mapNotNull { entry ->
            val until = entry.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
            entry.substringAfter('|') to until
        }.toMap()
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
