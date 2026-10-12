package com.impeesa.finance.core.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.impeesa.finance.core.ui.R
import com.impeesa.finance.core.ui.theme.ImpeesaTheme

/** 44dp round button with an icon, used for back, close and toolbar actions. */
@Composable
fun CircleIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = ImpeesaTheme.colors.textPrimary,
    background: Color = ImpeesaTheme.colors.card,
    // 48 dp: the minimum touch target (IMPLEMENTATION_PLAN.md §11.4).
    size: Dp = 48.dp,
) {
    val colors = ImpeesaTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, colors.raised, CircleShape)
            .semantics { this.contentDescription = contentDescription }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Title bar for pushed screens: back button, title, optional trailing content. */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes backIcon: Int = R.drawable.ic_back,
    backLabel: String = "Back",
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircleIconButton(icon = backIcon, contentDescription = backLabel, onClick = onBack)
        Text(
            title,
            style = ImpeesaTheme.type.headline,
            color = ImpeesaTheme.colors.textPrimary,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 2,
        )
        trailing()
    }
}

/** Small uppercase label above a group of controls. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = ImpeesaTheme.type.caption.copy(letterSpacing = ImpeesaTheme.type.caption.fontSize * 0.05f),
        color = ImpeesaTheme.colors.textTertiary,
        modifier = modifier,
    )
}

/** Number pad for entering amounts. Keys: digits, "." and backspace. */
@Composable
fun AmountKeypad(
    onKey: (Char) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val rows = listOf("123", "456", "789", ".0<")
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    val isDelete = key == '<'
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .semantics { contentDescription = if (isDelete) "Delete" else key.toString() }
                            .clickable(role = Role.Button) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (isDelete) onDelete() else onKey(key)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isDelete) {
                            Icon(painterResource(R.drawable.ic_backspace), contentDescription = null, tint = ImpeesaTheme.colors.textPrimary)
                        } else {
                            Text(key.toString(), style = ImpeesaTheme.type.amountLarge.copy(fontSize = ImpeesaTheme.type.amountLarge.fontSize * 1.1f), color = ImpeesaTheme.colors.textPrimary)
                        }
                    }
                }
            }
        }
    }
}

/** Chip for picking an account: coloured letter tile plus the account name. */
@Composable
fun AccountChip(
    name: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ImpeesaTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .height(46.dp)
            .clip(shape)
            .background(if (selected) colors.textPrimary else Color.Transparent)
            .border(1.dp, if (selected) colors.textPrimary else colors.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), style = ImpeesaTheme.type.label, color = Color.White)
        }
        Text(name, style = ImpeesaTheme.type.label, color = if (selected) colors.ink else colors.textSecondary, maxLines = 1)
    }
}
