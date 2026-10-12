package com.impeesa.finance.feature.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.data.CategoryRepository
import com.impeesa.finance.core.model.Category
import com.impeesa.finance.core.model.CategoryKind
import com.impeesa.finance.core.ui.component.ImpeesaChip
import com.impeesa.finance.core.ui.component.ImpeesaTextField
import com.impeesa.finance.core.ui.component.PrimaryButton
import com.impeesa.finance.core.ui.component.ScreenHeader
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.ui.UndoBus
import com.impeesa.finance.ui.UndoEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Colours a category can take, from the design's category palette. */
private val PALETTE = listOf(
    0xFFFF9F5A, 0xFF3FD0C9, 0xFF5CC8F5, 0xFFA493FF, 0xFFF5C94C, 0xFFF27BB5,
    0xFF7FB2FF, 0xFFC5F26B, 0xFFE98AC9, 0xFFD9896B, 0xFFC9A27A, 0xFF9AA0AA,
).map { it.toInt() }

@Immutable
data class CategoryForm(val name: String = "", val kind: CategoryKind = CategoryKind.EXPENSE, val color: Int = PALETTE.first())

@Immutable
data class CategoryEditorState(
    val loaded: Boolean = false,
    val editing: Category? = null,
    val form: CategoryForm = CategoryForm(),
    val usage: Int = 0,
    val nameTaken: Boolean = false,
    /** Where an archived category's transactions can go: same kind, still active. */
    val moveTargets: List<Category> = emptyList(),
)

class CategoryEditorViewModel(
    private val categoryId: Long,
    private val categories: CategoryRepository,
    private val undo: UndoBus,
) : ViewModel() {
    private val form = MutableStateFlow(CategoryForm())
    private val meta = MutableStateFlow(Triple<Category?, Int, Boolean>(null, 0, categoryId == 0L))
    private val taken = MutableStateFlow(false)
    private val closeChannel = Channel<Unit>(Channel.BUFFERED)
    val close: Flow<Unit> = closeChannel.receiveAsFlow()

    init {
        if (categoryId != 0L) {
            viewModelScope.launch {
                val category = categories.get(categoryId) ?: return@launch closeChannel.send(Unit)
                form.value = CategoryForm(category.name, category.kind, category.color)
                meta.value = Triple(category, categories.usage(categoryId), true)
            }
        }
    }

    val state: StateFlow<CategoryEditorState> = combine(form, meta, taken, categories.observeAll()) { f, m, t, all ->
        CategoryEditorState(
            loaded = m.third,
            editing = m.first,
            form = f,
            usage = m.second,
            nameTaken = t,
            moveTargets = all.filter { it.kind == f.kind && it.id != categoryId },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryEditorState())

    fun update(transform: (CategoryForm) -> CategoryForm) {
        form.update(transform)
        viewModelScope.launch { taken.value = form.value.name.isNotBlank() && categories.nameTaken(form.value.name, categoryId) }
    }

    fun save() {
        val f = form.value
        val editing = meta.value.first
        viewModelScope.launch {
            if (editing?.isSystem != true && categories.nameTaken(f.name, categoryId)) {
                taken.value = true
                return@launch
            }
            if (editing == null) {
                categories.create(f.name, f.kind, f.color)
                undo.post(UndoEvent("Added ${f.name.trim()}"))
            } else {
                categories.rename(editing.id, f.name, f.color)
                undo.post(UndoEvent("Saved ${if (editing.isSystem) editing.name else f.name.trim()}"))
            }
            closeChannel.send(Unit)
        }
    }

    fun archive(moveTo: Category) {
        val editing = meta.value.first ?: return
        viewModelScope.launch {
            categories.archive(editing.id, moveTo.id)
            undo.post(UndoEvent("Archived ${editing.name} · moved to ${moveTo.name}"))
            closeChannel.send(Unit)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryEditorScreen(categoryId: Long, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { CategoryEditorViewModel(categoryId, container.categoryRepository, container.undo) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.close.collect { onBack() } }
    var archiveTo by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    val colors = ImpeesaTheme.colors
    val editing = state.editing
    val system = editing?.isSystem == true

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader(title = if (categoryId == 0L) "New category" else "Edit category", onBack = onBack)
        if (!state.loaded) return@Column

        if (system) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Name")
                Text(editing!!.name, style = ImpeesaTheme.type.title, color = colors.textPrimary)
                Text(
                    "Built-in categories keep their name so automatic sorting keeps working. You can change the colour.",
                    style = ImpeesaTheme.type.caption,
                    color = colors.textTertiary,
                )
            }
        } else {
            ImpeesaTextField(
                value = state.form.name,
                onValueChange = { name -> if (name.length <= 30) viewModel.update { it.copy(name = name) } },
                label = "Name",
                placeholder = "e.g. Pets",
                capitalization = KeyboardCapitalization.Words,
            )
            if (state.nameTaken) Text("You already have a category with this name.", style = ImpeesaTheme.type.caption, color = colors.coral)
        }

        if (editing == null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Type")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(CategoryKind.EXPENSE to "Spending", CategoryKind.INCOME to "Income", CategoryKind.TRANSFER to "Not spending").forEach { (kind, label) ->
                        ImpeesaChip(label = label, selected = state.form.kind == kind, onClick = { viewModel.update { it.copy(kind = kind) } })
                    }
                }
                if (state.form.kind == CategoryKind.TRANSFER) {
                    Text("Money moved here is never counted as spending or income, like investments or money lent.", style = ImpeesaTheme.type.caption, color = colors.textTertiary)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Colour")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PALETTE.forEach { color ->
                    val selected = state.form.color == color
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .semantics { this.selected = selected }
                            .clickable(role = Role.RadioButton) { viewModel.update { it.copy(color = color) } }
                            .border(2.dp, if (selected) colors.textPrimary else Color.Transparent, CircleShape)
                            .padding(6.dp)
                            .clip(CircleShape)
                            .background(Color(color)),
                    )
                }
            }
        }

        PrimaryButton(
            text = if (editing == null) "Add category" else "Save",
            enabled = system || (state.form.name.isNotBlank() && !state.nameTaken),
            onClick = viewModel::save,
        )

        if (editing != null && !system) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Archive category", style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                Text(
                    when (state.usage) {
                        0 -> "No transactions use it. Pick where any rules for it should point instead."
                        1 -> "1 transaction uses it. Move it, and any rules, to:"
                        else -> "${state.usage} transactions use it. Move them, and any rules, to:"
                    },
                    style = ImpeesaTheme.type.caption,
                    color = colors.textSecondary,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.moveTargets.forEach { target ->
                        ImpeesaChip(label = target.name, selected = archiveTo == target.id, onClick = { archiveTo = target.id }, dotColor = Color(target.color))
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, if (archiveTo != null) colors.coral else colors.line, RoundedCornerShape(14.dp))
                        .clickable(enabled = archiveTo != null, role = Role.Button) { confirmArchive = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Archive", style = ImpeesaTheme.type.bodyStrong, color = if (archiveTo != null) colors.coral else colors.textTertiary)
                }
            }
        }
    }

    val target = state.moveTargets.firstOrNull { it.id == archiveTo }
    if (confirmArchive && editing != null && target != null) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("Archive ${editing.name}?") },
            text = { Text("Its transactions and rules move to ${target.name}. It disappears from pickers.") },
            confirmButton = { TextButton(onClick = { confirmArchive = false; viewModel.archive(target) }) { Text("Archive", color = colors.coral) } },
            dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("Cancel") } },
            containerColor = colors.card,
        )
    }
}
