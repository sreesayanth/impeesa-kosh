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
import com.impeesa.finance.feature.accounts.AccountsScreen
import com.impeesa.finance.feature.activity.ActivityScreen
import com.impeesa.finance.feature.add.AddTransactionScreen
import com.impeesa.finance.feature.detail.DetailScreen
import com.impeesa.finance.feature.home.HomeScreen
import com.impeesa.finance.feature.placeholder.ComingSoonScreen
import com.impeesa.finance.feature.onboarding.ImportScreen
import com.impeesa.finance.feature.onboarding.SmsPermissionScreen
import com.impeesa.finance.feature.onboarding.WelcomeScreen
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

private data class Tab(val route: Any, @StringRes val label: Int, @DrawableRes val icon: Int)

private val tabs = listOf(
    Tab(HomeRoute, R.string.tab_home, UiR.drawable.ic_home),
    Tab(InsightsRoute, R.string.tab_insights, UiR.drawable.ic_insights),
    Tab(ActivityRoute, R.string.tab_activity, UiR.drawable.ic_activity),
    Tab(AccountsRoute, R.string.tab_accounts, UiR.drawable.ic_accounts),
)

@Composable
fun ImpeesaNavHost(pendingLink: StateFlow<String?>, onLinkHandled: () -> Unit) {
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
        }
        onLinkHandled()
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
            composable<WelcomeRoute> { WelcomeScreen(onStart = { navController.navigate(SmsPermissionRoute) }) }
            composable<SmsPermissionRoute> {
                SmsPermissionScreen(
                    onGranted = {
                        navController.navigate(ImportRoute) { popUpTo<SmsPermissionRoute> { inclusive = true } }
                    },
                    onSkip = {
                        val fromWelcome = navController.previousBackStackEntry?.destination?.hasRoute(WelcomeRoute::class) == true
                        if (fromWelcome) finishOnboarding(HomeRoute) else navController.popBackStack()
                    },
                )
            }
            composable<ImportRoute> {
                ImportScreen(
                    onDone = { finishOnboarding(HomeRoute) },
                    onReview = {
                        finishOnboarding(HomeRoute)
                        navController.navigate(ReviewRoute)
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
                )
            }
            composable<InsightsRoute> { ComingSoonScreen(title = "Insights", phase = "Phase 6") }
            composable<ActivityRoute> {
                ActivityScreen(onOpen = { navController.navigate(DetailRoute(it)) })
            }
            composable<AccountsRoute> {
                AccountsScreen(
                    onOpen = { navController.navigate(AccountFormRoute(it)) },
                    onAdd = { navController.navigate(AccountFormRoute()) },
                )
            }
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
                AccountFormScreen(id = route.id.takeIf { it > 0 }, onBack = { navController.popBackStack() })
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
            .height(68.dp)
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
