package com.impeesa.finance.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.ui.theme.ImpeesaTheme

/** Money with the right font, sign and a spoken label for TalkBack. */
@Composable
fun AmountText(
    money: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = ImpeesaTheme.type.amount,
    color: Color = ImpeesaTheme.colors.textPrimary,
    signed: Boolean = false,
    showPaise: Boolean = false,
) {
    Text(
        text = MoneyFormat.format(money, signed = signed, showPaise = showPaise),
        modifier = modifier.semantics { contentDescription = MoneyFormat.spoken(money) },
        style = style,
        color = color,
        maxLines = 1,
    )
}

/** The standard rounded card on the dark ground. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(ImpeesaTheme.colors.card)
            .padding(padding),
        content = content,
    )
}

/** Square tile with a tinted background, used for merchants and categories. */
@Composable
fun LetterTile(
    letter: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = ImpeesaTheme.type.title, color = color)
    }
}

/** One transaction line: tile, title, subtitle and amount. */
@Composable
fun TxnRow(
    title: String,
    subtitle: String,
    amount: Money,
    tileColor: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = ImpeesaTheme.colors
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable)
            .heightIn(min = 64.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LetterTile(letter = title.take(1).uppercase(), color = tileColor)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = ImpeesaTheme.type.caption, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AmountText(
            money = amount,
            signed = true,
            color = if (amount.paise > 0) colors.lime else colors.textPrimary,
        )
    }
}

/** Pill chip; selected chips invert to a light fill. */
@Composable
fun ImpeesaChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
) {
    val colors = ImpeesaTheme.colors
    val background by animateColorAsState(if (selected) colors.textPrimary else Color.Transparent, label = "chipBg")
    val content by animateColorAsState(if (selected) colors.ink else colors.textSecondary, label = "chipFg")
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, if (selected) colors.textPrimary else colors.line, CircleShape)
            .semantics { this.selected = selected }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (dotColor != null) Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
        Text(label, style = ImpeesaTheme.type.label, color = content)
    }
}

/** Segmented control like Week / Month / Year. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ImpeesaTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.card)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val background by animateColorAsState(if (selected) colors.textPrimary else Color.Transparent, label = "segBg")
            val content by animateColorAsState(if (selected) colors.ink else colors.textSecondary, label = "segFg")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(background)
                    .semantics { this.selected = selected }
                    .clickable(role = Role.Tab) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(option, style = ImpeesaTheme.type.label.copy(fontSize = ImpeesaTheme.type.body.fontSize), color = content)
            }
        }
    }
}

/** Full-width lime call to action. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = ImpeesaTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) colors.lime else colors.raised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = ImpeesaTheme.type.bodyStrong, color = if (enabled) colors.onLime else colors.textSecondary)
    }
}

/** Card shown when a screen has nothing yet, with one clear next step. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ImpeesaTheme.colors
    SurfaceCard(modifier = modifier.fillMaxWidth(), padding = 24.dp) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EmptyBars()
            Text(title, style = ImpeesaTheme.type.headline.copy(fontSize = ImpeesaTheme.type.title.fontSize), color = colors.textPrimary)
            Text(body, style = ImpeesaTheme.type.body, color = colors.textSecondary)
            if (actionLabel != null) {
                Spacer(Modifier.height(4.dp))
                PrimaryButton(text = actionLabel, onClick = onAction)
            }
        }
    }
}

@Composable
private fun EmptyBars() {
    val colors = ImpeesaTheme.colors
    Row(
        modifier = Modifier.height(64.dp).clearAndSetSemantics { },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(22, 34, 16, 48).forEachIndexed { index, h ->
            val last = index == 3
            Box(
                Modifier
                    .width(14.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (last) colors.lime else colors.raised),
            )
        }
    }
}
