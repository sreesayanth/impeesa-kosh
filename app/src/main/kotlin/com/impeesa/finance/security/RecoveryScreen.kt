package com.impeesa.finance.security

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.R
import com.impeesa.finance.core.ui.component.PrimaryButton
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import kotlinx.coroutines.launch

/**
 * Shown when the encrypted database can't be opened (IMPLEMENTATION_PLAN.md §12.3). Never wipes on its own:
 * the user chooses to start fresh, then can restore a backup from Backup & data.
 */
@Composable
fun RecoveryScreen(detail: String) {
    val container = LocalAppContainer.current
    val colors = ImpeesaTheme.colors
    var confirm by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(colors.ink).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = Modifier.size(64.dp))
            Text("Kosh can't open your data", style = ImpeesaTheme.type.headline, color = colors.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(
                "Your data is encrypted with a key kept in your phone's secure storage, and the phone no longer has that key. This can happen after some system updates or security resets. Nothing has been deleted.",
                style = ImpeesaTheme.type.body,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Text(
                "If you have a backup, start fresh and then restore it from Settings → Backup & data. Your settings are kept.",
                style = ImpeesaTheme.type.body,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            PrimaryButton(text = "Start fresh", onClick = { confirm = true })
            Text("Details: $detail", style = ImpeesaTheme.type.caption, color = colors.textTertiary, textAlign = TextAlign.Center)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Start fresh?") },
            text = { Text("The unreadable data is removed so Kosh can start again. Restore a backup afterwards if you have one.") },
            confirmButton = { TextButton(onClick = { confirm = false; container.ioScope.launch { container.resetDatabase() } }) { Text("Start fresh", color = colors.coral) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
            containerColor = colors.card,
        )
    }
}
