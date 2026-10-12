package com.impeesa.finance.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.impeesa.finance.capture.CaptureHealth
import com.impeesa.finance.capture.SmsPermission
import com.impeesa.finance.core.ui.component.CircleIconButton
import com.impeesa.finance.core.ui.R as UiR
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.R
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.model.Account
import com.impeesa.finance.core.model.AccountType
import com.impeesa.finance.core.ui.component.AmountText
import com.impeesa.finance.core.ui.component.EmptyState
import com.impeesa.finance.core.ui.component.SurfaceCard
import com.impeesa.finance.core.ui.component.TxnRow
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.feature.profile.Profile
import com.impeesa.finance.feature.profile.ProfileAvatar

@Composable
fun HomeScreen(
    onAddTransaction: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    onSeeAll: () -> Unit,
    onAccounts: () -> Unit,
    onReview: () -> Unit,
    onEnableSms: () -> Unit,
    onAutoCapture: () -> Unit,
    onSettings: () -> Unit,
    onProfile: () -> Unit,
    onBudgets: () -> Unit,
    onInsights: () -> Unit,
    onAlerts: () -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val viewModel = viewModel {
        HomeViewModel(container.transactionRepository, container.accountRepository, container.reviewRepository, container.analyticsRepository, container.moneyOverview, container.prefs, container.clock)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile by container.prefs.profile.collectAsStateWithLifecycle()
    val unreadAlerts by container.ledgerRepository.observeUnreadAlerts().collectAsStateWithLifecycle(initialValue = 0)
    val colors = ImpeesaTheme.colors
    var smsAllowed by remember { mutableStateOf(SmsPermission.granted(context)) }
    var listenerPaused by remember { mutableStateOf(CaptureHealth.listenerPaused(context)) }
    LifecycleResumeEffect(Unit) {
        smsAllowed = SmsPermission.granted(context)
        listenerPaused = CaptureHealth.listenerPaused(context)
        onPauseOrDispose { }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.ink).statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "header") { Header(state.greeting, profile, unreadAlerts, onProfile, onAlerts, onSettings) }
        if (state.loading) return@LazyColumn

        if (!smsAllowed) {
            item(key = "manual") {
                Banner(
                    title = "Automatic tracking is off",
                    body = "Allow SMS access and Impeesa will log bank spends for you.",
                    action = "Turn on",
                    tint = colors.coral,
                    onClick = onEnableSms,
                )
            }
        }
        if (listenerPaused) {
            item(key = "paused") {
                Banner(
                    title = "Payment-app alerts are paused",
                    body = "Your phone stopped Impeesa from reading GPay/PhonePe notifications. Bank SMS still work.",
                    action = "Fix",
                    tint = colors.amber,
                    onClick = onAutoCapture,
                )
            }
        }
        if (state.reviewCount > 0) {
            item(key = "review") {
                Banner(
                    title = if (state.reviewCount == 1) "1 new transaction found" else "${state.reviewCount} new transactions found",
                    body = "From your messages · tap to review",
                    action = null,
                    tint = colors.lime,
                    onClick = onReview,
                )
            }
        }
        item(key = "hero") { BudgetHero(state, onBudgets) }
        if (state.accounts.isNotEmpty()) {
            item(key = "accounts") { AccountStrip(state.accounts, onAccounts) }
        }
        if (!state.isEmpty) {
            item(key = "week") { WeekCard(state, onInsights, onDismissInsight = viewModel::dismissInsight) }
        }
        if (state.isEmpty) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.home_empty_title),
                    body = stringResource(R.string.home_empty_body),
                    actionLabel = stringResource(R.string.action_add_transaction),
                    onAction = onAddTransaction,
                )
            }
        } else {
            item(key = "recent") {
                SurfaceCard(padding = 18.dp) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.home_recent), style = ImpeesaTheme.type.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
                        Text(
                            "See all",
                            style = ImpeesaTheme.type.label,
                            color = colors.lime,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onSeeAll).padding(8.dp),
                        )
                    }
                    state.recent.forEach { txn ->
                        TxnRow(
                            title = txn.title,
                            subtitle = txn.subtitle,
                            amount = txn.amount,
                            tileColor = txn.color?.let { Color(it) } ?: colors.other,
                            neutral = txn.isTransfer,
                            onClick = { onOpenTransaction(txn.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Banner(title: String, body: String, action: String?, tint: Color, onClick: () -> Unit) {
    val colors = ImpeesaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(tint.copy(alpha = 0.08f))
            .border(1.dp, tint.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(tint))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
            Text(body, style = ImpeesaTheme.type.caption, color = colors.textSecondary)
        }
        if (action != null) Text(action, style = ImpeesaTheme.type.label, color = colors.lime)
    }
}

@Composable
private fun Header(greeting: Greeting, profile: Profile, unreadAlerts: Int, onProfile: () -> Unit, onAlerts: () -> Unit, onSettings: () -> Unit) {
    val colors = ImpeesaTheme.colors
    val text = when (greeting) {
        Greeting.MORNING -> R.string.greeting_morning
        Greeting.AFTERNOON -> R.string.greeting_afternoon
        Greeting.EVENING -> R.string.greeting_evening
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProfileAvatar(
            profile,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Edit profile", onClick = onProfile),
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(text), style = ImpeesaTheme.type.caption, color = colors.textSecondary)
            Text(
                if (profile.hasName) profile.firstName else stringResource(R.string.app_name),
                style = ImpeesaTheme.type.title,
                color = colors.textPrimary,
                maxLines = 1,
            )
        }
        Box {
            CircleIconButton(
                icon = UiR.drawable.ic_bell,
                contentDescription = if (unreadAlerts > 0) "Alerts, $unreadAlerts unread" else "Alerts",
                onClick = onAlerts,
            )
            if (unreadAlerts > 0) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 11.dp).size(9.dp).clip(CircleShape).background(colors.coral))
            }
        }
        CircleIconButton(icon = UiR.drawable.ic_tune, contentDescription = "Settings", onClick = onSettings)
    }
}

@Composable
private fun AccountStrip(accounts: List<Account>, onAccounts: () -> Unit) {
    val colors = ImpeesaTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Accounts", style = ImpeesaTheme.type.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text(
                "Manage",
                style = ImpeesaTheme.type.label,
                color = colors.lime,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onAccounts).padding(8.dp),
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(accounts, key = { it.id }) { account ->
                Column(
                    Modifier
                        .width(140.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.card)
                        .clickable(role = Role.Button, onClick = onAccounts)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(Color(account.color)), contentAlignment = Alignment.Center) {
                        Text(if (account.type == AccountType.CASH) MoneyFormat.symbol.trim().take(1) else account.name.take(1).uppercase(), style = ImpeesaTheme.type.caption, color = Color.White)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (account.type == AccountType.CARD) "${account.name} owed" else account.name,
                            style = ImpeesaTheme.type.caption,
                            color = colors.textSecondary,
                            maxLines = 1,
                        )
                        AmountText(
                            money = if (account.type == AccountType.CARD) account.owed else account.balance,
                            style = ImpeesaTheme.type.amount.copy(fontSize = ImpeesaTheme.type.title.fontSize),
                        )
                    }
                }
            }
        }
    }
}
