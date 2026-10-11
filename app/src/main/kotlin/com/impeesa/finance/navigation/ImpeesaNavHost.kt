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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.impeesa.finance.R
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.feature.home.HomeScreen
import com.impeesa.finance.feature.placeholder.ComingSoonScreen
import kotlinx.serialization.Serializable
import com.impeesa.finance.core.ui.R as UiR

@Serializable data object HomeRoute
@Serializable data object InsightsRoute
@Serializable data object ActivityRoute
@Serializable data object AccountsRoute
@Serializable data object AddTransactionRoute

private data class Tab(val route: Any, @StringRes val label: Int, @DrawableRes val icon: Int)

private val tabs = listOf(
    Tab(HomeRoute, R.string.tab_home, UiR.drawable.ic_home),
    Tab(InsightsRoute, R.string.tab_insights, UiR.drawable.ic_insights),
    Tab(ActivityRoute, R.string.tab_activity, UiR.drawable.ic_activity),
    Tab(AccountsRoute, R.string.tab_accounts, UiR.drawable.ic_accounts),
)

@Composable
fun ImpeesaNavHost() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showBar = destination?.hasRoute(AddTransactionRoute::class) != true

    Box(Modifier.fillMaxSize().background(ImpeesaTheme.colors.ink)) {
        NavHost(navController = navController, startDestination = HomeRoute) {
            composable<HomeRoute> {
                HomeScreen(onAddTransaction = { navController.navigate(AddTransactionRoute) })
            }
            composable<InsightsRoute> { ComingSoonScreen(title = "Insights", phase = "Phase 6") }
            composable<ActivityRoute> { ComingSoonScreen(title = "Activity", phase = "Phase 1") }
            composable<AccountsRoute> { ComingSoonScreen(title = "Accounts", phase = "Phase 1") }
            composable<AddTransactionRoute> {
                ComingSoonScreen(title = "Add transaction", phase = "Phase 1", onClose = { navController.popBackStack() })
            }
        }
        if (showBar) {
            BottomBar(
                destination = destination,
                onTab = { navController.switchTab(it) },
                onAdd = { navController.navigate(AddTransactionRoute) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
