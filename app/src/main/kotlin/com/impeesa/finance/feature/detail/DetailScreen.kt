package com.impeesa.finance.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.model.EntryKind
import com.impeesa.finance.core.ui.R as UiR
import com.impeesa.finance.core.ui.component.AmountText
import com.impeesa.finance.core.ui.component.CircleIconButton
import com.impeesa.finance.core.ui.component.LetterTile
import com.impeesa.finance.core.ui.component.ScreenHeader
import com.impeesa.finance.core.ui.component.SurfaceCard
import com.impeesa.finance.core.ui.theme.ImpeesaTheme

@Composable
fun DetailScreen(id: Long, onBack: () -> Unit, onEdit: (Long) -> Unit, onReview: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel {
        DetailViewModel(id, container.transactionRepository, container.accountRepository, container.categoryRepository, container.undo, container.clock)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.close.collect { onBack() } }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val colors = ImpeesaTheme.colors

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ScreenHeader(title = "Transaction", onBack = onBack) {
            if (!state.missing && !state.loading) {
                CircleIconButton(icon = UiR.drawable.ic_edit, contentDescription = "Edit", onClick = { onEdit(id) })
            }
        }
        if (state.missing) {
            Text("This transaction was deleted.", style = ImpeesaTheme.type.body, color = colors.textSecondary)
            return@Column
        }
        if (state.loading) return@Column

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LetterTile(letter = state.title.take(1).uppercase(), color = state.tileColor?.let { Color(it) } ?: colors.other, size = 72.dp)
            Text(state.title, style = ImpeesaTheme.type.title, color = colors.textPrimary)
            AmountText(
                money = if (state.kind == EntryKind.TRANSFER) state.amount.abs() else state.amount,
                signed = state.kind != EntryKind.TRANSFER,
                showPaise = true,
                style = ImpeesaTheme.type.hero,
                color = if (state.kind == EntryKind.INCOME) colors.lime else colors.textPrimary,
            )
            Text(state.whenLabel, style = ImpeesaTheme.type.caption, color = colors.textSecondary)
            Box(Modifier.clip(CircleShape).background(colors.lime.copy(alpha = 0.1f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(state.sourceLabel, style = ImpeesaTheme.type.caption, color = colors.lime)
            }
        }

        if (state.pendingReview) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.amber.copy(alpha = 0.1f))
                    .clickable(role = Role.Button, onClick = onReview)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Waiting for your review — not counted yet", style = ImpeesaTheme.type.label, color = colors.amber, modifier = Modifier.weight(1f))
                Text("Review", style = ImpeesaTheme.type.label, color = colors.lime)
            }
        }

        SurfaceCard(padding = 16.dp) {
            state.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = colors.raised)
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.label, style = ImpeesaTheme.type.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
                    if (row.dotColor != null) {
                        Box(Modifier.padding(end = 8.dp).size(8.dp).clip(CircleShape).background(Color(row.dotColor)))
                    }
                    Text(row.value, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                }
            }
        }

        state.originalMessage?.let { message ->
            SurfaceCard(padding = 16.dp) {
                Text("Original message", style = ImpeesaTheme.type.label, color = colors.textPrimary)
                Text(
                    message,
                    style = ImpeesaTheme.type.caption.copy(fontFamily = FontFamily.Monospace),
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.ink).padding(12.dp),
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.card)
                .clickable(role = Role.Button) { confirmDelete = true },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(UiR.drawable.ic_trash), contentDescription = null, tint = colors.coral, modifier = Modifier.size(18.dp))
            Text("  Delete", style = ImpeesaTheme.type.bodyStrong, color = colors.coral)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this transaction?") },
            text = { Text(if (state.kind == EntryKind.TRANSFER) "Both sides of the transfer will be removed. You can undo right after." else "You can undo right after.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Delete", color = colors.coral) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            containerColor = colors.card,
        )
    }
}
