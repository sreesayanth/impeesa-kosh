package com.impeesa.finance.feature.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.data.ActivityKind
import com.impeesa.finance.core.model.DateKey
import com.impeesa.finance.core.model.DateLabels
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.ui.component.ImpeesaChip
import com.impeesa.finance.core.ui.component.TxnRow
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.core.ui.R as UiR

private val kindLabels = listOf(
    ActivityKind.ALL to "All",
    ActivityKind.SPENT to "Spent",
    ActivityKind.RECEIVED to "Received",
    ActivityKind.TRANSFERS to "Transfers",
)

@Composable
fun ActivityScreen(onOpen: (Long) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { ActivityViewModel(container.transactionRepository, container.accountRepository, container.clock) }
    val items = viewModel.items.collectAsLazyPagingItems()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val totals by viewModel.dayTotals.collectAsStateWithLifecycle()
    val colors = ImpeesaTheme.colors

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.ink).statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item(key = "title", contentType = "chrome") {
            Text(
                "Activity",
                style = ImpeesaTheme.type.display,
                color = colors.textPrimary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp).semantics { heading() },
            )
        }
        item(key = "search", contentType = "chrome") {
            SearchField(query, viewModel::setQuery, Modifier.padding(horizontal = 20.dp))
        }
        item(key = "kinds", contentType = "chrome") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(kindLabels, key = { it.first }) { (kind, label) ->
                    ImpeesaChip(label = label, selected = filter.kind == kind, onClick = { viewModel.setKind(kind) })
                }
            }
        }
        if (accounts.size > 1) {
            item(key = "accounts", contentType = "chrome") {
                LazyRow(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "all") {
                        ImpeesaChip(label = "All accounts", selected = filter.accountId == null, onClick = { viewModel.setAccount(null) })
                    }
                    items(accounts, key = { it.id }) { account ->
                        ImpeesaChip(
                            label = account.displayName,
                            selected = filter.accountId == account.id,
                            onClick = { viewModel.setAccount(account.id) },
                            dotColor = Color(account.color),
                        )
                    }
                }
            }
        }

        items(
            count = items.itemCount,
            key = items.itemKey { it.key },
            contentType = items.itemContentType { if (it is ActivityItem.Header) "header" else "row" },
        ) { index ->
            when (val item = items[index]) {
                is ActivityItem.Header -> {
                    val date = DateKey.toLocalDate(item.date)
                    val spent = totals[item.date]
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp).semantics(mergeDescendants = true) { heading() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(DateLabels.header(date, viewModel.today), style = ImpeesaTheme.type.label, color = colors.textSecondary)
                        if (spent != null && spent.paise > 0) {
                            Text(MoneyFormat.MINUS + MoneyFormat.format(spent), style = ImpeesaTheme.type.label, color = colors.textSecondary)
                        }
                    }
                }
                is ActivityItem.Row -> TxnRow(
                    title = item.txn.title,
                    subtitle = item.txn.subtitle,
                    amount = item.txn.amount,
                    tileColor = item.txn.color?.let { Color(it) } ?: colors.other,
                    neutral = item.txn.isTransfer,
                    onClick = { onOpen(item.txn.id) },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                null -> Box(Modifier.fillMaxWidth().height(64.dp))
            }
        }

        if (items.loadState.refresh is LoadState.NotLoading && items.itemCount == 0) {
            item(key = "empty", contentType = "chrome") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (query.isBlank()) "Nothing here yet" else "No matches for “$query”",
                        style = ImpeesaTheme.type.title,
                        color = colors.textPrimary,
                    )
                    Text(
                        if (query.isBlank()) "Transactions you add appear here, grouped by day." else "Try a merchant, note, category or amount.",
                        style = ImpeesaTheme.type.body,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = ImpeesaTheme.colors
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = ImpeesaTheme.type.body.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.lime),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Search transactions" },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(shape)
                    .background(colors.card)
                    .border(1.dp, colors.raised, shape)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(painterResource(UiR.drawable.ic_search), contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text("Search merchant, note or amount", style = ImpeesaTheme.type.body, color = colors.textTertiary)
                    inner()
                }
            }
        },
    )
}
