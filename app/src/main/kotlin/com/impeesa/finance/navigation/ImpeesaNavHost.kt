package com.impeesa.finance.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.R
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.feature.accounts.AccountFormScreen
import com.impeesa.finance.feature.accounts.AccountDetailScreen
import com.impeesa.finance.feature.accounts.AccountsScreen
import com.impeesa.finance.feature.alerts.AlertSettingsScreen
import com.impeesa.finance.feature.alerts.AlertsScreen
import com.impeesa.finance.feature.activity.ActivityScreen
import com.impeesa.finance.feature.add.AddTransactionScreen
import com.impeesa.finance.feature.detail.DetailScreen
import com.impeesa.finance.feature.home.HomeScreen
import com.impeesa.finance.feature.budgets.BudgetEditorScreen
import com.impeesa.finance.feature.budgets.BudgetsScreen
import com.impeesa.finance.feature.insights.HabitsScreen
import com.impeesa.finance.feature.insights.InsightsScreen
import com.impeesa.finance.feature.onboarding.ImportScreen
import com.impeesa.finance.feature.onboarding.SmsPermissionScreen
import com.impeesa.finance.feature.onboarding.WelcomeScreen
import com.impeesa.finance.feature.profile.EditProfileScreen
import com.impeesa.finance.feature.profile.ProfileSetupScreen
import com.impeesa.finance.feature.rules.CategoryEditorScreen
import com.impeesa.finance.feature.rules.RuleEditorScreen
import com.impeesa.finance.feature.rules.RulesScreen
import com.impeesa.finance.feature.settings.CurrencyScreen
import com.impeesa.finance.feature.settings.DataScreen
import com.impeesa.finance.feature.settings.PrivacyScreen
import com.impeesa.finance.feature.settings.SecurityScreen
import com.impeesa.finance.feature.settings.SettingsScreen
import com.impeesa.finance.feature.review.ReviewScreen
import com.impeesa.finance.feature.sources.SourcesScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import com.impeesa.finance.core.ui.R as UiR

@Serializable data object HomeRoute
@Serializable data object InsightsRoute
@Serializable data object ActivityRoute
@Serializable data object AccountsRoute
@Serializable data class AddTransactionRoute(val editId: Long = 0L)
@Serializable data class DetailRoute(val id: Long)
@Serializable data class AccountFormRoute(val id: Long = 0L)
@Serializable data object WelcomeRoute
@Serializable data object SmsPermissionRoute
@Serializable data object ImportRoute
@Serializable data object ReviewRoute
@Serializable data object SourcesRoute
@Serializable data object ProfileSetupRoute
@Serializable data object EditProfileRoute
@Serializable data object SettingsRoute
@Serializable data object RulesRoute
@Serializable data class RuleEditorRoute(val id: Long = 0L)
@Serializable data class CategoryEditorRoute(val id: Long = 0L)
@Serializable data object HabitsRoute
@Serializable data object BudgetsRoute
@Serializable data class BudgetEditorRoute(val id: Long = 0L)
@Serializable data class AccountDetailRoute(val id: Long)
@Serializable data object AlertsRoute
@Serializable data object AlertSettingsRoute
@Serializable data object SecurityRoute
@Serializable data object DataRoute
@Serializable data object PrivacyRoute
@Serializable data object CurrencyRoute

private data class Tab(val route: Any, @StringRes val label: Int, @DrawableRes val icon: Int)

private val tabs = listOf(
    Tab(HomeRoute, R.string.tab_home, UiR.drawable.ic_home),
    Tab(InsightsRoute, R.string.tab_insights, UiR.drawable.ic_insights),
    Tab(ActivityRoute, R.string.tab_activity, UiR.drawable.ic_activity),
    Tab(AccountsRoute, R.string.tab_accounts, UiR.drawable.ic_accounts),
)

@Composable
fun ImpeesaNavHost(pendingLink: StateFlow<String?>, onLinkHandled: () -> Unit, onOpenLink: (String) -> Unit) {
    val container = LocalAppContainer.current
    val navController = rememberNavController()
    val startDestination: Any = remember { if (container.prefs.onboardingDone) HomeRoute else WelcomeRoute }
    val link by pendingLink.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        val target = link ?: return@LaunchedEffect
        if (!container.prefs.onboardingDone) return@LaunchedEffect
        when {
            target == "review" -> navController.navigate(ReviewRoute) { launchSingleTop = true }
            target.startsWith("txn:") -> target.removePrefix("txn:").toLongOrNull()?.let { navController.navigate(DetailRoute(it)) }
            target.startsWith("account:") -> target.removePrefix("account:").toLongOrNull()?.let { navController.navigate(AccountDetailRoute(it)) }
            target == "budgets" -> navController.navigate(BudgetsRoute) { launchSingleTop = true }
            target == "habits" -> navController.navigate(HabitsRoute) { launchSingleTop = true }
            target == "insights" -> navController.switchTab(InsightsRoute)
            target == "accounts" -> navController.switchTab(AccountsRoute)
            target == "sources" -> navController.navigate(SourcesRoute) { launchSingleTop = true }
            target == "alerts" -> navController.navigate(AlertsRoute) { launchSingleTop = true }
            target == "add" -> navController.navigate(AddTransactionRoute()) { launchSingleTop = true }
            target == "currency" -> {
                navController.navigate(SettingsRoute) { launchSingleTop = true }
                navController.navigate(CurrencyRoute) { launchSingleTop = true }
            }
        }
        onLinkHandled()
    }
    // Opening an alert from the list follows the same links as a notification.
    fun openLink(target: String) {
        when {
            target == "review" -> navController.navigate(ReviewRoute)
            target.startsWith("txn:") -> target.removePrefix("txn:").toLongOrNull()?.let { navController.navigate(DetailRoute(it)) }
            target.startsWith("account:") -> target.removePrefix("account:").toLongOrNull()?.let { navController.navigate(AccountDetailRoute(it)) }
            target == "budgets" -> navController.navigate(BudgetsRoute)
            target == "habits" -> navController.navigate(HabitsRoute)
            target == "insights" -> navController.switchTab(InsightsRoute)
            target == "accounts" -> navController.switchTab(AccountsRoute)
            target == "sources" -> navController.navigate(SourcesRoute)
        }
    }
    fun finishOnboarding(next: Any) {
        navController.navigate(next) { popUpTo(0) { inclusive = true } }
    }
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val onTab = tabs.any { destination?.hasRoute(it.route::class) == true }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        container.undo.flow.collect { event ->
            val undo = event.undo
            val result = snackbar.showSnackbar(event.message, actionLabel = if (undo != null) "Undo" else null, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed && undo != null) container.ioScope.launch { undo() }
        }
    }

    Box(Modifier.fillMaxSize().background(ImpeesaTheme.colors.ink)) {
        NavHost(navController = navController, startDestination = startDestination) {
            composable<WelcomeRoute> { WelcomeScreen(onStart = { navController.navigate(ProfileSetupRoute) }) }
            composable<ProfileSetupRoute> { ProfileSetupScreen(onContinue = { navController.navigate(SmsPermissionRoute) }) }
            composable<EditProfileRoute> { EditProfileScreen(onBack = { navController.popBackStack() }) }
            composable<SettingsRoute> {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onProfile = { navController.navigate(EditProfileRoute) },
                    onAutoCapture = { navController.navigate(SourcesRoute) },
                    onRules = { navController.navigate(RulesRoute) },
                    onBudgets = { navController.navigate(BudgetsRoute) },
                    onAlerts = { navController.navigate(AlertSettingsRoute) },
                    onAccounts = { navController.switchTab(AccountsRoute) },
                    onSecurity = { navController.navigate(SecurityRoute) },
                    onData = { navController.navigate(DataRoute) },
                    onPrivacy = { navController.navigate(PrivacyRoute) },
                    onCurrency = { navController.navigate(CurrencyRoute) },
                )
            }
            composable<CurrencyRoute> { CurrencyScreen(onBack = { navController.popBackStack() }, onChanged = { onOpenLink("currency") }) }
            composable<SecurityRoute> { SecurityScreen(onBack = { navController.popBackStack() }) }
            composable<DataRoute> { DataScreen(onBack = { navController.popBackStack() }) }
            composable<PrivacyRoute> { PrivacyScreen(onBack = { navController.popBackStack() }) }
            composable<RulesRoute> {
                RulesScreen(
                    onBack = { navController.popBackStack() },
                    onNewRule = { navController.navigate(RuleEditorRoute()) },
                    onEditRule = { navController.navigate(RuleEditorRoute(it)) },
                    onNewCategory = { navController.navigate(CategoryEditorRoute()) },
                    onEditCategory = { navController.navigate(CategoryEditorRoute(it)) },
                )
            }
            composable<RuleEditorRoute> { entry ->
                RuleEditorScreen(ruleId = entry.toRoute<RuleEditorRoute>().id, onBack = { navController.popBackStack() })
            }
            composable<CategoryEditorRoute> { entry ->
                CategoryEditorScreen(categoryId = entry.toRoute<CategoryEditorRoute>().id, onBack = { navController.popBackStack() })
            }
            composable<SmsPermissionRoute> {
                SmsPermissionScreen(
                    onGranted = {
                        navController.navigate(ImportRoute) { popUpTo<SmsPermissionRoute> { inclusive = true } }
                    },
                    onSkip = {
                        val inOnboarding = navController.previousBackStackEntry?.destination?.hasRoute(ProfileSetupRoute::class) == true
                        if (inOnboarding) finishOnboarding(HomeRoute) else navController.popBackStack()
                    },
                )
            }
            composable<ImportRoute> {
                ImportScreen(
                    onDone = { finishOnboarding(HomeRoute) },
                    onReview = {
                        finishOnboarding(HomeRoute)
                        // As a link, so it still opens if a currency picked during setup rebuilds the screens.
                        onOpenLink("review")
                    },
                )
            }
            composable<ReviewRoute> { ReviewScreen(onBack = { navController.popBackStack() }) }
            composable<SourcesRoute> {
                SourcesScreen(
                    onBack = { navController.popBackStack() },
                    onEnableSms = { navController.navigate(SmsPermissionRoute) },
                    onRescan = { navController.navigate(ImportRoute) },
                )
            }
            composable<HomeRoute> {
                HomeScreen(
                    onAddTransaction = { navController.navigate(AddTransactionRoute()) },
                    onOpenTransaction = { navController.navigate(DetailRoute(it)) },
                    onSeeAll = { navController.switchTab(ActivityRoute) },
                    onAccounts = { navController.switchTab(AccountsRoute) },
                    onReview = { navController.navigate(ReviewRoute) },
                    onEnableSms = { navController.navigate(SmsPermissionRoute) },
                    onAutoCapture = { navController.navigate(SourcesRoute) },
                    onSettings = { navController.navigate(SettingsRoute) },
                    onProfile = { navController.navigate(EditProfileRoute) },
                    onBudgets = { navController.navigate(BudgetsRoute) },
                    onInsights = { navController.switchTab(InsightsRoute) },
                    onAlerts = { navController.navigate(AlertsRoute) },
                )
            }
            composable<InsightsRoute> {
                InsightsScreen(onHabits = { navController.navigate(HabitsRoute) }, onBudgets = { navController.navigate(BudgetsRoute) })
            }
            composable<HabitsRoute> { HabitsScreen(onBack = { navController.popBackStack() }) }
            composable<BudgetsRoute> {
                BudgetsScreen(
                    onBack = { navController.popBackStack() },
                    onNew = { navController.navigate(BudgetEditorRoute()) },
                    onEdit = { navController.navigate(BudgetEditorRoute(it)) },
                )
            }
            composable<BudgetEditorRoute> { entry ->
                BudgetEditorScreen(budgetId = entry.toRoute<BudgetEditorRoute>().id, onBack = { navController.popBackStack() })
            }
            composable<ActivityRoute> {
                ActivityScreen(onOpen = { navController.navigate(DetailRoute(it)) })
            }
            composable<AccountsRoute> {
                AccountsScreen(
                    onOpen = { navController.navigate(AccountDetailRoute(it)) },
                    onAdd = { navController.navigate(AccountFormRoute()) },
                )
            }
            composable<AccountDetailRoute> { entry ->
                val accountId = entry.toRoute<AccountDetailRoute>().id
                AccountDetailScreen(
                    id = accountId,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate(AccountFormRoute(accountId)) },
                    onOpenTransaction = { navController.navigate(DetailRoute(it)) },
                )
            }
            composable<AlertsRoute> {
                AlertsScreen(
                    onBack = { navController.popBackStack() },
                    onOpen = ::openLink,
                    onSettings = { navController.navigate(AlertSettingsRoute) },
                )
            }
            composable<AlertSettingsRoute> { AlertSettingsScreen(onBack = { navController.popBackStack() }) }
            composable<AddTransactionRoute> { entry ->
                val route = entry.toRoute<AddTransactionRoute>()
                AddTransactionScreen(editId = route.editId.takeIf { it > 0 }, onClose = { navController.popBackStack() })
            }
            composable<DetailRoute> { entry ->
                val route = entry.toRoute<DetailRoute>()
                DetailScreen(
                    id = route.id,
                    onBack = { navController.popBackStack() },
                    onReview = { navController.navigate(ReviewRoute) },
                    onEdit = { id ->
                        // Editing can replace the rows (e.g. expense -> transfer), so return to the list afterwards.
                        navController.navigate(AddTransactionRoute(editId = id)) {
                            popUpTo<DetailRoute> { inclusive = true }
                        }
                    },
                )
            }
            composable<AccountFormRoute> { entry ->
                val route = entry.toRoute<AccountFormRoute>()
                AccountFormScreen(
                    id = route.id.takeIf { it > 0 },
                    onBack = { navController.popBackStack() },
                    // The merged account is gone; show the one it went into instead of its detail.
                    onMerged = { into ->
                        if (!navController.popBackStack<AccountDetailRoute>(inclusive = true)) navController.popBackStack()
                        navController.navigate(AccountDetailRoute(into))
                    },
                )
            }
        }

        Column(Modifier.align(Alignment.BottomCenter)) {
            SnackbarHost(snackbar, modifier = Modifier.padding(horizontal = 16.dp)) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = ImpeesaTheme.colors.raised,
                    contentColor = ImpeesaTheme.colors.textPrimary,
                    actionColor = ImpeesaTheme.colors.lime,
                    shape = RoundedCornerShape(16.dp),
                )
            }
            if (onTab) {
                BottomBar(
                    destination = destination,
                    onTab = { navController.switchTab(it) },
                    onAdd = { navController.navigate(AddTransactionRoute()) },
                )
            } else {
                Box(Modifier.navigationBarsPadding().height(12.dp))
            }
        }
    }
}

private fun NavHostController.switchTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Floating pill tab bar with the lime add button in the middle, as designed. */
@Composable
private fun BottomBar(
    destination: NavDestination?,
    onTab: (Any) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ImpeesaTheme.colors
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth()
            // Grows with large text instead of clipping the labels (§11.4).
            .heightIn(min = 68.dp)
            .clip(shape)
            .background(colors.card.copy(alpha = 0.96f))
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            if (index == 2) AddButton(onAdd)
            val selected = destination?.hasRoute(tab.route::class) == true
            TabItem(tab, selected) { onTab(tab.route) }
        }
    }
}

@Composable
private fun TabItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val colors = ImpeesaTheme.colors
    val tint = if (selected) colors.lime else colors.textTertiary
    Column(
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(painterResource(tab.icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(stringResource(tab.label), style = ImpeesaTheme.type.caption.copy(fontSize = ImpeesaTheme.type.caption.fontSize * 0.88f), color = tint)
    }
}

@Composable
private fun AddButton(onClick: () -> Unit) {
    val colors = ImpeesaTheme.colors
    val label = stringResource(R.string.action_add_transaction)
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.lime)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(UiR.drawable.ic_plus), contentDescription = null, tint = colors.onLime)
    }
}
