package com.impeesa.finance.feature.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.data.CategorizationRepository
import com.impeesa.finance.core.data.CategoryRepository
import com.impeesa.finance.core.data.LearnedMerchant
import com.impeesa.finance.core.data.Rule
import com.impeesa.finance.core.model.Category
import com.impeesa.finance.core.model.CategoryKind
import com.impeesa.finance.core.ui.R as UiR
import com.impeesa.finance.core.ui.component.CircleIconButton
import com.impeesa.finance.core.ui.component.EmptyState
import com.impeesa.finance.core.ui.component.ScreenHeader
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.component.SegmentedControl
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.ui.UndoBus
import com.impeesa.finance.ui.UndoEvent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class LearnedRow(val merchant: LearnedMerchant, val summary: String)

@Immutable
data class RulesUiState(
    val loading: Boolean = true,
    val rules: List<Rule> = emptyList(),
    val learned: List<LearnedRow> = emptyList(),
    val categories: List<Category> = emptyList(),
)

class RulesViewModel(
    private val categorization: CategorizationRepository,
    categories: CategoryRepository,
    private val undo: UndoBus,
) : ViewModel() {
    val state: StateFlow<RulesUiState> = combine(
        categorization.observeRules(),
        categorization.observeLearned(),
        categories.observeAll(),
    ) { rules, learned, all ->
        val names = all.associate { it.id to it.name }
        RulesUiState(
            loading = false,
            rules = rules,
            learned = learned.map { m ->
                val summary = if (m.ambiguous) "Asks each time: " + m.categoryIds.take(2).mapNotNull(names::get).joinToString(" or ")
                else "Sorted as " + (m.categoryIds.firstOrNull()?.let(names::get) ?: "a removed category")
                LearnedRow(m, summary)
            },
            categories = all,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RulesUiState())

    fun setEnabled(rule: Rule, enabled: Boolean) {
        viewModelScope.launch { categorization.setRuleEnabled(rule.id, enabled) }
    }

    fun forget(merchant: LearnedMerchant) {
        viewModelScope.launch {
            categorization.forget(merchant.merchantKey)
            undo.post(UndoEvent("Forgot what Kosh learned about ${merchant.label}"))
        }
    }
}

@Composable
fun RulesScreen(
    onBack: () -> Unit,
    onNewRule: () -> Unit,
    onEditRule: (Long) -> Unit,
    onNewCategory: () -> Unit,
    onEditCategory: (Long) -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { RulesViewModel(container.categorizationRepository, container.categoryRepository, container.undo) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val colors = ImpeesaTheme.colors

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.ink).statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header") {
            ScreenHeader(title = "Categories & rules", onBack = onBack) {
                CircleIconButton(
                    icon = UiR.drawable.ic_plus,
                    contentDescription = if (tab == 0) "New rule" else "New category",
                    onClick = if (tab == 0) onNewRule else onNewCategory,
                )
            }
        }
        item(key = "tabs") { SegmentedControl(listOf("Rules", "Categories"), tab, { tab = it }) }
        if (state.loading) return@LazyColumn

        if (tab == 0) {
            if (state.rules.isEmpty() && state.learned.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "No rules yet",
                        body = "Change a transaction's category and switch on “Always sort”, or make a rule here: when the merchant, UPI ID, message, account or amount matches, Kosh sorts it for you.",
                        actionLabel = "New rule",
                        onAction = onNewRule,
                    )
                }
            }
            if (state.rules.isNotEmpty()) {
                item(key = "rules-label") { SectionLabel("Your rules") }
                items(state.rules, key = { "rule-${it.id}" }) { rule ->
                    RuleRow(rule, onClick = { onEditRule(rule.id) }, onEnabled = { viewModel.setEnabled(rule, it) })
                }
            }
            if (state.learned.isNotEmpty()) {
                item(key = "learned-label") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionLabel("Learned from your corrections")
                        Text(
                            "After you correct a merchant twice the same way, new transactions follow. Sorted it different ways? Kosh asks.",
                            style = ImpeesaTheme.type.caption,
                            color = colors.textTertiary,
                        )
                    }
                }
                items(state.learned, key = { "learned-${it.merchant.merchantKey}" }) { row ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.card).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(row.merchant.label, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                            Text(row.summary, style = ImpeesaTheme.type.caption, color = if (row.merchant.ambiguous) colors.amber else colors.textSecondary)
                        }
                        Text(
                            "Forget",
                            style = ImpeesaTheme.type.label,
                            color = colors.coral,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { viewModel.forget(row.merchant) }.padding(8.dp),
                        )
                    }
                }
            }
        } else {
            val groups = listOf(
                CategoryKind.EXPENSE to "Spending",
                CategoryKind.INCOME to "Income",
                CategoryKind.TRANSFER to "Not counted as spending",
            )
            groups.forEach { (kind, label) ->
                val list = state.categories.filter { it.kind == kind }
                if (list.isEmpty()) return@forEach
                item(key = "cat-label-$kind") { SectionLabel(label) }
                item(key = "cat-group-$kind") {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.card)) {
                        list.forEach { category ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button) { onEditCategory(category.id) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(category.color)))
                                Text(category.name, style = ImpeesaTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                                if (!category.isSystem) Text("Yours", style = ImpeesaTheme.type.caption, color = colors.textTertiary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleRow(rule: Rule, onClick: () -> Unit, onEnabled: (Boolean) -> Unit) {
    val colors = ImpeesaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(rule.condition, style = ImpeesaTheme.type.bodyStrong, color = if (rule.enabled) colors.textPrimary else colors.textTertiary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rule.categoryColor?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(Color(it))) }
                Text("→ " + rule.outcome, style = ImpeesaTheme.type.body, color = colors.textSecondary)
            }
            val matches = when (rule.matchCount) {
                0 -> "no matches yet"
                1 -> "1 match"
                else -> "${rule.matchCount} matches"
            }
            Text((if (rule.fromEdit) "From your edit" else "Made by you") + " · " + matches, style = ImpeesaTheme.type.caption, color = colors.textTertiary)
        }
        Switch(
            checked = rule.enabled,
            onCheckedChange = onEnabled,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.lime, checkedThumbColor = colors.ink),
        )
    }
}
