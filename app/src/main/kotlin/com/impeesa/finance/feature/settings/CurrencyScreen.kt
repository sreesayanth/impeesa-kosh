package com.impeesa.finance.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.model.Currencies
import com.impeesa.finance.core.model.Currency
import com.impeesa.finance.core.model.DigitGrouping
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.model.MoneySettings
import com.impeesa.finance.core.ui.component.ScreenHeader
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.component.SegmentedControl
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import kotlinx.coroutines.launch

/**
 * Display currency and digit grouping. Changes how amounts look everywhere; it never converts them.
 * Bank SMS in any other currency go to Review as foreign.
 */
@Composable
fun CurrencyScreen(onBack: () -> Unit, onChanged: () -> Unit) {
    val container = LocalAppContainer.current
    val settings by container.prefs.money.collectAsStateWithLifecycle()
    val colors = ImpeesaTheme.colors

    fun apply(next: MoneySettings) {
        if (next == settings) return
        container.prefs.setMoney(next)
        container.ioScope.launch { container.captureRepository.homeCurrency = next.currency.code }
        // The app redraws from scratch with the new format; this brings the user back here.
        onChanged()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.ink).statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") { ScreenHeader(title = "Currency & format", onBack = onBack) }
        item(key = "preview") {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(colors.card).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Preview", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
                Text(MoneyFormat.format(Money(12_34_567_89), showPaise = true), style = ImpeesaTheme.type.headline, color = colors.textPrimary, textAlign = TextAlign.Center)
                Text(
                    "Short: ${MoneyFormat.compact(Money(12_34_567_89))} · Spending: ${MoneyFormat.format(Money(-486_00))}",
                    style = ImpeesaTheme.type.caption,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item(key = "grouping-label") { SectionLabel("Digit grouping") }
        item(key = "grouping") {
            SegmentedControl(
                options = listOf("1,00,000", "100,000"),
                selectedIndex = if (settings.grouping == DigitGrouping.INDIAN) 0 else 1,
                onSelect = { apply(settings.copy(grouping = if (it == 0) DigitGrouping.INDIAN else DigitGrouping.INTERNATIONAL)) },
            )
        }
        item(key = "grouping-note") {
            Text(
                if (settings.grouping == DigitGrouping.INDIAN) "Lakh and crore (1.2 L, 3.4 Cr in short form)." else "Thousand, million, billion (1.2M, 3.4B in short form).",
                style = ImpeesaTheme.type.caption,
                color = colors.textTertiary,
            )
        }
        item(key = "currency-label") { SectionLabel("Currency") }
        item(key = "currencies") {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(colors.card)) {
                Currencies.ALL.forEach { currency ->
                    CurrencyRow(currency, selected = currency == settings.currency) {
                        // A new currency brings its usual grouping; the user can still switch it above.
                        apply(MoneySettings(currency, currency.defaultGrouping))
                    }
                }
            }
        }
        item(key = "note") {
            Text(
                "This changes how amounts are shown; it doesn't convert them. Automatic capture reads Indian bank SMS in rupees, so with another currency those messages wait in Review marked as a different currency.",
                style = ImpeesaTheme.type.caption,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
internal fun CurrencyRow(currency: Currency, selected: Boolean, onClick: () -> Unit) {
    val colors = ImpeesaTheme.colors
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.widthIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(colors.raised).padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(currency.symbol.trim(), style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text(currency.name, style = ImpeesaTheme.type.body, color = colors.textPrimary)
            Text(currency.code, style = ImpeesaTheme.type.caption, color = colors.textTertiary)
        }
        Box(
            Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) colors.lime else colors.line, CircleShape).padding(5.dp)
                .clip(CircleShape).background(if (selected) colors.lime else Color.Transparent),
        )
    }
}
