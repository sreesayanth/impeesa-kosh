package com.impeesa.finance.feature.add

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.model.DateLabels
import com.impeesa.finance.core.model.EntryKind
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.ui.R as UiR
import com.impeesa.finance.core.ui.component.AccountChip
import com.impeesa.finance.core.ui.component.AmountKeypad
import com.impeesa.finance.core.ui.component.CircleIconButton
import com.impeesa.finance.core.ui.component.ImpeesaChip
import com.impeesa.finance.core.ui.component.PrimaryButton
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.component.SegmentedControl
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun AddTransactionScreen(editId: Long?, onClose: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel {
        AddTransactionViewModel(
            editId = editId,
            transactions = container.transactionRepository,
            accounts = container.accountRepository,
            categories = container.categoryRepository,
            clock = container.clock,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.saved.collect { onClose() } }
    val today = remember { LocalDate.now(container.clock) }

    AddTransactionContent(
        state = state,
        today = today,
        onClose = onClose,
        onKind = viewModel::setKind,
        onKey = viewModel::press,
        onDelete = viewModel::delete,
        onCategory = viewModel::pickCategory,
        onAccount = viewModel::pickAccount,
        onToAccount = viewModel::pickToAccount,
        onDate = viewModel::setDate,
        onNote = viewModel::setNote,
        onPreset = viewModel::applyPreset,
        onSave = viewModel::save,
    )
}

private val kinds = listOf(EntryKind.EXPENSE, EntryKind.INCOME, EntryKind.TRANSFER)

@Composable
private fun AddTransactionContent(
    state: AddUiState,
    today: LocalDate,
    onClose: () -> Unit,
    onKind: (EntryKind) -> Unit,
    onKey: (Char) -> Unit,
    onDelete: () -> Unit,
    onCategory: (Long) -> Unit,
    onAccount: (Long) -> Unit,
    onToAccount: (Long) -> Unit,
    onDate: (LocalDate) -> Unit,
    onNote: (String) -> Unit,
    onPreset: (QuickPreset) -> Unit,
    onSave: () -> Unit,
) {
    val colors = ImpeesaTheme.colors
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(icon = UiR.drawable.ic_close, contentDescription = "Close", onClick = onClose)
            Text(
                if (state.isEditing) "Edit transaction" else "Add transaction",
                style = ImpeesaTheme.type.title,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(44.dp))
        }

        SegmentedControl(
            options = listOf("Expense", "Income", "Transfer"),
            selectedIndex = kinds.indexOf(state.kind),
            onSelect = { onKind(kinds[it]) },
        )

        val amountColor by animateColorAsState(if (state.kind == EntryKind.INCOME) colors.lime else colors.textPrimary, label = "amount")
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.semantics {
                    contentDescription = MoneyFormat.spoken(state.input.money)
                    liveRegion = LiveRegionMode.Polite
                },
            ) {
                Text(MoneyFormat.symbol.trim(), style = ImpeesaTheme.type.headline, color = colors.textTertiary, modifier = Modifier.padding(top = 10.dp, end = 4.dp))
                Text(state.input.display(), style = ImpeesaTheme.type.hero.copy(fontSize = ImpeesaTheme.type.hero.fontSize * 1.15f), color = amountColor, maxLines = 1)
            }
            Text(
                state.error ?: state.hint,
                style = ImpeesaTheme.type.caption,
                color = if (state.error != null) colors.coral else colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.presets.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.presets, key = { it.label }) { preset ->
                        Box(
                            Modifier
                                .heightIn(min = 36.dp)
                                .clip(CircleShape)
                                .border(1.dp, colors.line, CircleShape)
                                .clickable(role = Role.Button) { onPreset(preset) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(preset.label, style = ImpeesaTheme.type.caption, color = colors.textSecondary)
                        }
                    }
                }
            }

            if (state.kind != EntryKind.TRANSFER) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Category")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.categories, key = { it.id }) { category ->
                            ImpeesaChip(
                                label = category.name,
                                selected = category.id == state.categoryId,
                                onClick = { onCategory(category.id) },
                                dotColor = Color(category.color),
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(
                    when (state.kind) {
                        EntryKind.INCOME -> "Received in"
                        EntryKind.TRANSFER -> "From"
                        EntryKind.EXPENSE -> "Paid with"
                    },
                )
                AccountRow(state, selectedId = state.accountId, exclude = null, onPick = onAccount)
            }

            if (state.kind == EntryKind.TRANSFER) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("To")
                    AccountRow(state, selectedId = state.toAccountId, exclude = state.accountId, onPick = onToAccount)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.card)
                        .clickable(role = Role.Button) { showDatePicker = true }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(painterResource(UiR.drawable.ic_calendar), contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(16.dp))
                    Text(DateLabels.chip(state.date, today), style = ImpeesaTheme.type.label, color = colors.textPrimary)
                }
                NoteField(value = state.note, onValueChange = onNote, modifier = Modifier.weight(1f))
            }
        }

        AmountKeypad(onKey = onKey, onDelete = onDelete, modifier = Modifier.padding(vertical = 8.dp))
        PrimaryButton(text = state.saveLabel, onClick = onSave, enabled = !state.saving, modifier = Modifier.padding(bottom = 12.dp))
    }

    if (showDatePicker) {
        TransactionDatePicker(
            initial = state.date,
            today = today,
            onPick = { onDate(it); showDatePicker = false },
            onDismiss = { showDatePicker = false },
        )
    }
}

@Composable
private fun AccountRow(state: AddUiState, selectedId: Long?, exclude: Long?, onPick: (Long) -> Unit) {
    val accounts = state.accounts.filter { it.id != exclude }
    if (accounts.isEmpty()) {
        Text("Add an account in the Accounts tab", style = ImpeesaTheme.type.caption, color = ImpeesaTheme.colors.textSecondary)
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
        items(accounts, key = { it.id }) { account ->
            AccountChip(
                name = account.displayName,
                color = Color(account.color),
                selected = account.id == selectedId,
                onClick = { onPick(account.id) },
            )
        }
    }
}

@Composable
private fun NoteField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = ImpeesaTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = ImpeesaTheme.type.label.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.lime),
        modifier = modifier.semantics { contentDescription = "Note" },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.card)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text("Add a note", style = ImpeesaTheme.type.label, color = colors.textTertiary)
                inner()
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionDatePicker(initial: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val todayEnd = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis < todayEnd
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState)
    }
}
