package com.impeesa.finance.feature.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.ui.component.AmountText
import com.impeesa.finance.core.ui.component.ImpeesaChip
import com.impeesa.finance.core.ui.component.LetterTile
import com.impeesa.finance.core.ui.component.PrimaryButton
import com.impeesa.finance.core.ui.component.ScreenHeader
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.theme.ImpeesaTheme

@Composable
fun ReviewScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { ReviewViewModel(container.reviewRepository, container.categoryRepository, container.undo, container.clock) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ImpeesaTheme.colors

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.ink).statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
            ScreenHeader(title = "New transactions", onBack = onBack) {
                if (state.total > 1) {
                    Text(
                        "Confirm all",
                        style = ImpeesaTheme.type.label,
                        color = colors.textPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .border(1.dp, colors.line, RoundedCornerShape(20.dp))
                            .clickable(role = Role.Button, onClick = viewModel::confirmAll)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
        if (state.loading) return@LazyColumn
        item(key = "card") {
            AnimatedContent(
                targetState = state.current,
                contentKey = { it?.id },
                transitionSpec = { (fadeIn() + slideInVertically { it / 6 }) togetherWith fadeOut() },
                label = "reviewCard",
            ) { card ->
                if (card == null) AllCaughtUp(onBack) else CurrentCard(
                    card = card,
                    remaining = state.total,
                    onPick = { viewModel.pickCategory(card.id, it) },
                    onConfirm = { viewModel.confirm(card) },
                    onIgnore = { viewModel.ignore(card) },
                )
            }
        }
        if (state.upNext.isNotEmpty()) {
            item(key = "next-label") { SectionLabel("Up next") }
            items(state.upNext, key = { it.id }) { card ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.card).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LetterTile(letter = card.title.take(1).uppercase(), color = colors.other, size = 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(card.title, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1)
                        Text(card.whenLabel, style = ImpeesaTheme.type.caption, color = colors.textTertiary)
                    }
                    AmountText(money = signed(card), signed = true, color = if (card.isCredit) colors.lime else colors.textPrimary)
                }
            }
        }
    }
}

private fun signed(card: ReviewCard): Money = if (card.isCredit) card.amount else -card.amount

@Composable
private fun CurrentCard(
    card: ReviewCard,
    remaining: Int,
    onPick: (Long) -> Unit,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
) {
    val colors = ImpeesaTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.card)
            .border(1.dp, colors.line, RoundedCornerShape(28.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                card.sourceLabel,
                style = ImpeesaTheme.type.caption,
                color = colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(colors.raised).padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Box(Modifier.weight(1f))
            Text("$remaining left", style = ImpeesaTheme.type.caption, color = colors.textTertiary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LetterTile(letter = card.title.take(1).uppercase(), color = if (card.isCredit) colors.lime else colors.food, size = 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(card.title, style = ImpeesaTheme.type.title, color = colors.textPrimary, maxLines = 2)
                Text(card.accountLabel, style = ImpeesaTheme.type.caption, color = colors.textSecondary)
                Text(card.whenLabel, style = ImpeesaTheme.type.caption, color = colors.textTertiary)
            }
            if (card.currency == "INR") {
                AmountText(money = signed(card), signed = true, style = ImpeesaTheme.type.amountLarge, color = if (card.isCredit) colors.lime else colors.textPrimary)
            } else {
                Text("${card.currency} ${MoneyFormat.group(card.amount.paise / 100)}.${(card.amount.paise % 100).toString().padStart(2, '0')}", style = ImpeesaTheme.type.amountLarge, color = colors.textPrimary)
            }
        }
        card.message?.let { message ->
            Text(
                message,
                style = ImpeesaTheme.type.caption.copy(fontFamily = FontFamily.Monospace),
                color = colors.textSecondary,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.ink).padding(12.dp),
            )
        }
        Text(card.reasonLabel, style = ImpeesaTheme.type.caption, color = colors.amber)
        if (card.categories.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Category")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(card.categories, key = { it.id }) { category ->
                        ImpeesaChip(
                            label = category.name,
                            selected = category.id == card.selectedCategoryId,
                            onClick = { onPick(category.id) },
                            dotColor = Color(category.color),
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, colors.line, RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button, onClick = onIgnore),
                contentAlignment = Alignment.Center,
            ) {
                Text("Not spending", style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
            }
            PrimaryButton(text = "Confirm", onClick = onConfirm, modifier = Modifier.weight(1f).height(52.dp))
        }
    }
}

@Composable
private fun AllCaughtUp(onBack: () -> Unit) {
    val colors = ImpeesaTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.clip(RoundedCornerShape(48.dp)).background(colors.lime).padding(28.dp)) {
            Text("✓", style = ImpeesaTheme.type.display, color = colors.onLime)
        }
        Text("All caught up", style = ImpeesaTheme.type.headline, color = colors.textPrimary)
        Text("Nothing is waiting for you. New ones appear here when Impeesa isn't sure.", style = ImpeesaTheme.type.body, color = colors.textSecondary)
        PrimaryButton(text = "Back", onClick = onBack)
    }
}
