package com.impeesa.finance.feature.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.R
import com.impeesa.finance.core.ui.component.AmountText
import com.impeesa.finance.core.ui.component.EmptyState
import com.impeesa.finance.core.ui.component.TxnRow
import com.impeesa.finance.core.ui.theme.ImpeesaTheme

@Composable
fun HomeScreen(onAddTransaction: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { HomeViewModel(container.transactionRepository, container.clock) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeContent(state = state, onAddTransaction = onAddTransaction)
}

@Composable
private fun HomeContent(state: HomeUiState, onAddTransaction: () -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ImpeesaTheme.colors.ink)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "header") { Header(state.greeting) }
        if (state.isEmpty) {
            item(key = "empty") {
                EmptyState(
                    title = stringResource(R.string.home_empty_title),
                    body = stringResource(R.string.home_empty_body),
                    actionLabel = stringResource(R.string.action_add_transaction),
                    onAction = onAddTransaction,
                )
            }
        } else if (!state.loading) {
            item(key = "hero") { SpentCard(state) }
            item(key = "recentTitle") {
                Text(stringResource(R.string.home_recent), style = ImpeesaTheme.type.title, color = ImpeesaTheme.colors.textPrimary)
            }
            items(state.recent, key = { it.id }) { row ->
                TxnRow(
                    title = row.title,
                    subtitle = row.subtitle,
                    amount = row.amount,
                    tileColor = row.color?.let { Color(it) } ?: ImpeesaTheme.colors.other,
                )
            }
        }
    }
}

@Composable
private fun Header(greeting: Greeting) {
    val colors = ImpeesaTheme.colors
    val text = when (greeting) {
        Greeting.MORNING -> R.string.greeting_morning
        Greeting.AFTERNOON -> R.string.greeting_afternoon
        Greeting.EVENING -> R.string.greeting_evening
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(colors.raised),
            contentAlignment = Alignment.Center,
        ) {
            Text("I", style = ImpeesaTheme.type.title, color = colors.lime)
        }
        Column {
            Text(stringResource(text), style = ImpeesaTheme.type.caption, color = colors.textSecondary)
            Text(stringResource(R.string.app_name), style = ImpeesaTheme.type.title, color = colors.textPrimary)
        }
    }
}

@Composable
private fun SpentCard(state: HomeUiState) {
    val colors = ImpeesaTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.lime)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.home_spent_in, state.monthName), style = ImpeesaTheme.type.label, color = colors.onLime)
        AmountText(money = state.spent, style = ImpeesaTheme.type.hero, color = colors.onLime)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_income), style = ImpeesaTheme.type.label, color = colors.onLime.copy(alpha = 0.75f))
            AmountText(money = state.income, style = ImpeesaTheme.type.label, color = colors.onLime)
        }
    }
}
