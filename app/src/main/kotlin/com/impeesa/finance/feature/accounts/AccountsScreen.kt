package com.impeesa.finance.feature.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.data.AccountRepository
import com.impeesa.finance.core.data.AccountSuggestion
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.impeesa.finance.core.model.Account
import com.impeesa.finance.core.model.AccountType
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.ui.R as UiR
import com.impeesa.finance.core.ui.component.AmountText
import com.impeesa.finance.core.ui.component.CircleIconButton
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.component.SurfaceCard
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Immutable
data class AccountsUiState(
    val loading: Boolean = true,
    val net: Money = Money.ZERO,
    val assets: Money = Money.ZERO,
    val dues: Money = Money.ZERO,
    val sections: List<Pair<String, List<Account>>> = emptyList(),
    val suggestions: List<AccountSuggestion> = emptyList(),
)

class AccountsViewModel(private val repository: AccountRepository) : ViewModel() {
    fun accept(id: Long) = viewModelScope.launch { repository.acceptSuggestion(id) }
    fun dismiss(id: Long) = viewModelScope.launch { repository.dismissSuggestion(id) }

    val state: StateFlow<AccountsUiState> = combine(repository.observeAccounts(), repository.observeSuggestions()) { accounts, suggestions ->
        val counted = accounts.filter { it.includeInNet }
        val assets = counted.filter { it.type != AccountType.CARD }.fold(Money.ZERO) { sum, a -> sum + a.balance }
        val dues = counted.filter { it.type == AccountType.CARD }.fold(Money.ZERO) { sum, a -> sum + a.owed }
        AccountsUiState(
            loading = false,
            net = assets - dues,
            assets = assets,
            dues = dues,
            sections = listOf(
                "Bank accounts" to accounts.filter { it.type == AccountType.BANK },
                "Credit cards" to accounts.filter { it.type == AccountType.CARD },
                "Wallets" to accounts.filter { it.type == AccountType.WALLET },
                "Cash" to accounts.filter { it.type == AccountType.CASH },
            ).filter { it.second.isNotEmpty() },
            suggestions = suggestions,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())
}

@Composable
fun AccountsScreen(onOpen: (Long) -> Unit, onAdd: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { AccountsViewModel(container.accountRepository) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ImpeesaTheme.colors

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.ink).statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Accounts", style = ImpeesaTheme.type.display, color = colors.textPrimary, modifier = Modifier.weight(1f).semantics { heading() })
                CircleIconButton(
                    icon = UiR.drawable.ic_plus,
                    contentDescription = "Add account",
                    onClick = onAdd,
                    background = colors.lime,
                    tint = colors.onLime,
                )
            }
        }
        item(key = "net") { NetCard(state) }
        if (state.suggestions.isNotEmpty()) {
            item(key = "suggestions") {
                SuggestionsCard(state.suggestions, onAdd = viewModel::accept, onDismiss = viewModel::dismiss)
            }
        }
        state.sections.forEach { (title, accounts) ->
            item(key = "label-$title") { SectionLabel(title, Modifier.padding(start = 4.dp, top = 4.dp)) }
            item(key = "group-$title") {
                SurfaceCard(padding = 4.dp) {
                    accounts.forEach { account -> AccountRowItem(account, onClick = { onOpen(account.id) }) }
                }
            }
        }
    }
}

@Composable
private fun SuggestionsCard(suggestions: List<AccountSuggestion>, onAdd: (Long) -> Unit, onDismiss: (Long) -> Unit) {
    val colors = ImpeesaTheme.colors
    SurfaceCard(padding = 16.dp) {
        Text("Found in your messages", style = ImpeesaTheme.type.title, color = colors.textPrimary)
        Text(
            "These accounts sent you transaction alerts. Add them to track balances.",
            style = ImpeesaTheme.type.caption,
            color = colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        suggestions.forEach { suggestion ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${suggestion.institution} ••${suggestion.mask}", style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                    Text(
                        (if (suggestion.looksLikeCard) "Card" else "Account") + " · seen ${suggestion.sightings} " + if (suggestion.sightings == 1) "time" else "times",
                        style = ImpeesaTheme.type.caption,
                        color = colors.textTertiary,
                    )
                }
                Text(
                    "Dismiss",
                    style = ImpeesaTheme.type.label,
                    color = colors.textSecondary,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onDismiss(suggestion.id) }.padding(10.dp),
                )
                Text(
                    "Add",
                    style = ImpeesaTheme.type.label,
                    color = colors.onLime,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(colors.lime).clickable(role = Role.Button) { onAdd(suggestion.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun NetCard(state: AccountsUiState) {
    val colors = ImpeesaTheme.colors
    SurfaceCard(padding = 20.dp) {
        Text("Net balance", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
        AmountText(money = state.net, style = ImpeesaTheme.type.hero.copy(fontSize = ImpeesaTheme.type.hero.fontSize * 0.85f), modifier = Modifier.padding(vertical = 8.dp))
        val total = state.assets.paise + state.dues.paise
        if (total > 0) {
            val assetShare = (state.assets.paise.coerceAtLeast(0).toFloat() / total).coerceIn(0.02f, 1f)
            Row(Modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.weight(assetShare).fillMaxSize().clip(RoundedCornerShape(5.dp)).background(colors.lime))
                if (state.dues.paise > 0) Box(Modifier.weight((1f - assetShare).coerceAtLeast(0.02f)).fillMaxSize().clip(RoundedCornerShape(5.dp)).background(colors.coral))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            LegendValue("Banks, wallets & cash", state.assets, colors.lime)
            LegendValue("Card dues", state.dues, colors.coral)
        }
    }
}

@Composable
private fun LegendValue(label: String, value: Money, dot: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(dot))
        Text(label, style = ImpeesaTheme.type.caption, color = ImpeesaTheme.colors.textSecondary)
        AmountText(money = value, style = ImpeesaTheme.type.label)
    }
}

@Composable
private fun AccountRowItem(account: Account, onClick: () -> Unit) {
    val colors = ImpeesaTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color(account.color)),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (account.type == AccountType.CASH) MoneyFormat.symbol.trim().take(1) else account.name.take(1).uppercase(), style = ImpeesaTheme.type.title, color = Color.White)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(account.name, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                val sub = when (account.type) {
                    // The digits tell two cards from the same bank apart.
                    AccountType.CARD -> listOfNotNull(account.mask?.let { "••$it" }, account.creditLimit?.let { "Limit " + MoneyFormat.format(it) } ?: "Credit card").joinToString(" · ")
                    AccountType.CASH -> "Cash in hand"
                    else -> account.mask?.let { "••$it" } ?: account.type.name.lowercase().replaceFirstChar { it.uppercase() }
                }
                Text(sub, style = ImpeesaTheme.type.caption, color = colors.textTertiary)
            }
            if (account.type == AccountType.CARD) {
                Column(horizontalAlignment = Alignment.End) {
                    AmountText(money = account.owed)
                    Text("owed", style = ImpeesaTheme.type.caption, color = colors.textTertiary)
                }
            } else {
                AmountText(money = account.balance, color = if (account.balance.isNegative) colors.coral else colors.textPrimary)
            }
        }
        val limit = account.creditLimit
        if (account.type == AccountType.CARD && limit != null && limit.paise > 0) {
            val used = (account.owed.paise.toFloat() / limit.paise).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.raised)) {
                Box(Modifier.fillMaxWidth(used.coerceAtLeast(0.01f)).fillMaxSize().clip(RoundedCornerShape(3.dp)).background(if (used > 0.3f) colors.amber else colors.lime))
            }
        }
    }
}
