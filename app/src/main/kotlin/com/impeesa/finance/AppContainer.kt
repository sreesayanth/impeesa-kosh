package com.impeesa.finance

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.impeesa.finance.capture.CaptureNotifier
import com.impeesa.finance.core.data.DataGraph
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
    val moneyOverview by lazy { MoneyOverview(data.analyticsRepository, data.budgetRepository, data.categoryRepository) }

    val notifier = CaptureNotifier(appContext) { data.reviewRepository }

    suspend fun seed() = data.seed()
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
