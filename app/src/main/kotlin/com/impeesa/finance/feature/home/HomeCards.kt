package com.impeesa.finance.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.impeesa.finance.R
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.ui.R as UiR
import com.impeesa.finance.core.ui.component.AmountText
import com.impeesa.finance.core.ui.component.BarChart
import com.impeesa.finance.core.ui.component.ProgressBar
import com.impeesa.finance.core.ui.component.SurfaceCard
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.core.model.analytics.Insight

/**
 * The lime hero (design: Home). With an overall budget: spent of budget, days left, safe-to-spend
 * per day and a pace marker. Without: spent, income and saved, plus a nudge to set a budget.
 */
@Composable
internal fun BudgetHero(state: HomeUiState, onBudgets: () -> Unit) {
    val colors = ImpeesaTheme.colors
    val o = state.budget
    val overall = o?.overall
    val muted = colors.onLime.copy(alpha = 0.72f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.lime)
            .clickable(role = Role.Button, onClickLabel = "Open budgets", onClick = onBudgets)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_spent_in, o?.periodLabel ?: ""), style = ImpeesaTheme.type.label, color = colors.onLime, modifier = Modifier.weight(1f))
            if (o != null) {
                val left = o.period.daysLeft(o.today)
                Text(
                    if (left == 1) "Last day" else "$left days left",
                    style = ImpeesaTheme.type.caption,
                    color = colors.onLime,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(colors.onLime.copy(alpha = 0.1f)).padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmountText(money = o?.spent ?: Money.ZERO, style = ImpeesaTheme.type.hero, color = colors.onLime)
            if (overall != null) {
                Text("of ${MoneyFormat.format(overall.status.available)}", style = ImpeesaTheme.type.bodyStrong, color = muted, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        if (overall != null) {
            ProgressBar(
                fraction = overall.status.usedFraction,
                color = colors.onLime,
                marker = o.paceFraction,
                height = 10.dp,
            )
            Row(Modifier.fillMaxWidth()) {
                HeroStat(if (overall.status.over) "Over by" else "Left", MoneyFormat.format(overall.status.remaining.abs()), Modifier.weight(1f))
                HeroStat("Safe to spend / day", MoneyFormat.format(o.safePerDay ?: Money.ZERO), Modifier.weight(1.3f))
                HeroStat("Used", "${(overall.status.usedFraction * 100).toInt()}%", Modifier.weight(0.7f), end = true)
            }
            if (o.upcomingBills.paise > 0) {
                Text("Keeps ${MoneyFormat.format(o.upcomingBills)} aside for bills due before the period ends", style = ImpeesaTheme.type.caption, color = muted)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_income), style = ImpeesaTheme.type.label, color = muted)
                AmountText(money = state.income, style = ImpeesaTheme.type.label, color = colors.onLime)
                Text("·", style = ImpeesaTheme.type.label, color = muted)
                Text("Saved", style = ImpeesaTheme.type.label, color = muted)
                AmountText(money = state.income - (o?.spent ?: Money.ZERO), style = ImpeesaTheme.type.label, color = colors.onLime)
            }
            Text("Set a monthly budget to see what's safe to spend each day  →", style = ImpeesaTheme.type.caption, color = colors.onLime)
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String, modifier: Modifier, end: Boolean = false) {
    val colors = ImpeesaTheme.colors
    Column(modifier, horizontalAlignment = if (end) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = ImpeesaTheme.type.caption, color = colors.onLime.copy(alpha = 0.72f), maxLines = 1)
        Text(value, style = ImpeesaTheme.type.bodyStrong, color = colors.onLime, maxLines = 1)
    }
}

/** "This week" bars with the biggest day highlighted, and the top insight underneath (§10.3). */
@Composable
internal fun WeekCard(state: HomeUiState, onInsights: () -> Unit, onDismissInsight: (Insight) -> Unit) {
    val colors = ImpeesaTheme.colors
    val week = state.week
    SurfaceCard(padding = 18.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("This week", style = ImpeesaTheme.type.title, color = colors.textPrimary)
                Text("${MoneyFormat.format(week.total)} spent · ${MoneyFormat.format(week.perDay)} a day", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
            }
            Text(
                "Insights",
                style = ImpeesaTheme.type.label,
                color = colors.textPrimary,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(colors.raised).clickable(role = Role.Button, onClick = onInsights).padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        if (week.values.isNotEmpty()) {
            val highlight = week.values.indices.maxByOrNull { week.values[it] }?.takeIf { week.values[it] > 0 }
            BarChart(
                values = week.values,
                labels = week.labels,
                description = "Spending this week: ${MoneyFormat.spoken(week.total)}" +
                    (highlight?.let { ", highest ${week.labels[it]} ${MoneyFormat.spoken(Money(week.values[it]))}" } ?: ""),
                highlight = highlight,
                highlightLabel = highlight?.let { MoneyFormat.compact(Money(week.values[it])) },
                dashed = week.todayIndex.takeIf { week.values.getOrNull(it) == 0L },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        state.insight?.let { insight ->
            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.raised).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("✦", style = ImpeesaTheme.type.label, color = if (insight.positive) colors.lime else colors.amber)
                Text(insight.text, style = ImpeesaTheme.type.caption, color = colors.textPrimary, modifier = Modifier.weight(1f))
                Icon(
                    painterResource(UiR.drawable.ic_close),
                    contentDescription = "Dismiss insight",
                    tint = colors.textTertiary,
                    modifier = Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { onDismissInsight(insight) }.padding(12.dp).size(16.dp),
                )
            }
        }
    }
}
