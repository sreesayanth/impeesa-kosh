package com.impeesa.finance.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.impeesa.finance.core.model.Category
import com.impeesa.finance.core.ui.component.ImpeesaChip
import com.impeesa.finance.core.ui.theme.ImpeesaTheme

/**
 * After a correction: "Always sort Swiggy as Groceries?" and "Apply to 13 earlier Swiggy transactions"
 * (IMPLEMENTATION_PLAN.md §7.4). Without either, two consistent corrections teach the app anyway.
 */
@Composable
internal fun LearnCard(
    prompt: LearnPrompt,
    onAlways: (Boolean) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ImpeesaTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(colors.lime.copy(alpha = 0.07f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Moved to ${prompt.categoryName}", style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text(
                "Done",
                style = ImpeesaTheme.type.label,
                color = colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onDismiss).padding(6.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Always sort ${prompt.merchant} as ${prompt.categoryName}", style = ImpeesaTheme.type.body, color = colors.textPrimary)
                Text(
                    if (prompt.alwaysSort) "Saved as a rule. Edit it in Settings → Categories & rules."
                    else "Otherwise Kosh learns after you correct it twice.",
                    style = ImpeesaTheme.type.caption,
                    color = colors.textTertiary,
                )
            }
            Switch(
                checked = prompt.alwaysSort,
                onCheckedChange = onAlways,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.lime, checkedThumbColor = colors.ink),
            )
        }
        when {
            prompt.appliedCount != null -> Text(
                if (prompt.appliedCount == 0) "No earlier transactions needed changing" else "Updated ${prompt.appliedCount} earlier transactions",
                style = ImpeesaTheme.type.caption,
                color = colors.lime,
            )
            prompt.earlierCount > 0 -> Text(
                "Apply to ${prompt.earlierCount} earlier ${prompt.merchant} " + if (prompt.earlierCount == 1) "transaction" else "transactions",
                style = ImpeesaTheme.type.label,
                color = colors.lime,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onApply).padding(vertical = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CategorySheet(
    categories: List<Category>,
    selectedId: Long?,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ImpeesaTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.card,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Category", style = ImpeesaTheme.type.title, color = colors.textPrimary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { category ->
                    ImpeesaChip(
                        label = category.name,
                        selected = category.id == selectedId,
                        onClick = { onPick(category.id) },
                        dotColor = Color(category.color),
                    )
                }
            }
        }
    }
}
