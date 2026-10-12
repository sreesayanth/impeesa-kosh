package com.impeesa.finance.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.model.security.LockPolicy
import com.impeesa.finance.core.ui.component.ImpeesaChip
import com.impeesa.finance.core.ui.component.ScreenHeader
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.security.PinSetup
import com.impeesa.finance.security.SystemAuth
import com.impeesa.finance.ui.UndoEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App lock and privacy settings (IMPLEMENTATION_PLAN.md §12.4, §12.1). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val lock = container.appLock
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by lock.enabled.collectAsStateWithLifecycle()
    val biometric by lock.biometric.collectAsStateWithLifecycle()
    val timeout by lock.timeoutSeconds.collectAsStateWithLifecycle()
    val recents by lock.hideInRecents.collectAsStateWithLifecycle()
    val balances by lock.hideBalances.collectAsStateWithLifecycle()
    var settingPin by rememberSaveable { mutableStateOf(false) }
    val colors = ImpeesaTheme.colors

    Column(
        Modifier.fillMaxSize().background(colors.ink).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(title = "Security & privacy", onBack = onBack)

        SectionLabel("App lock")
        Card {
            ToggleRow(
                title = "Lock with a PIN",
                subtitle = if (enabled) "Asked when you open Kosh, and after it's been in the background" else "Keep others out if someone picks up your phone",
                checked = enabled,
                onChange = { on -> if (on) settingPin = true else lock.disable() },
            )
            if (enabled) {
                Divider()
                if (SystemAuth.biometricAvailable(context)) {
                    ToggleRow("Use fingerprint or face", "The PIN still works as a backup", biometric, lock::setBiometric)
                    Divider()
                }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Lock after leaving the app", style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LockPolicy.TIMEOUTS.forEach { seconds ->
                            ImpeesaChip(label = if (seconds == 0) "Immediately" else "${seconds / 60} min", selected = timeout == seconds, onClick = { lock.setTimeout(seconds) })
                        }
                    }
                }
                Divider()
                Text(
                    "Change PIN",
                    style = ImpeesaTheme.type.bodyStrong,
                    color = colors.lime,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { settingPin = true }.padding(16.dp),
                )
            }
        }
        Text(
            "The lock covers the whole app, including when you open it from a notification. Your data is encrypted on the phone either way; the lock doesn't stop Kosh logging new payments in the background.",
            style = ImpeesaTheme.type.caption,
            color = colors.textTertiary,
        )

        SectionLabel("Privacy")
        Card {
            ToggleRow("Hide from recent apps", "Recent-apps shows a blank card instead of your balances", recents, lock::setHideInRecents)
            Divider()
            ToggleRow("Hide balances", "Show amounts as ${MoneyFormat.symbol.trim()} •••• until you turn this off", balances, lock::setHideBalances)
        }
    }

    if (settingPin) {
        ModalBottomSheet(onDismissRequest = { settingPin = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
            PinSetup(
                title = if (enabled) "Choose a new PIN" else "Choose a PIN",
                onDone = { pin ->
                    settingPin = false
                    scope.launch {
                        val wasEnabled = enabled
                        withContext(Dispatchers.Default) { lock.setPin(pin) }
                        container.undo.post(UndoEvent(if (wasEnabled) "PIN changed" else "App lock is on"))
                    }
                },
                onCancel = { settingPin = false },
            )
        }
    }
}

@Composable
internal fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(ImpeesaTheme.colors.card)) { content() }
}

@Composable
internal fun Divider() = HorizontalDivider(color = ImpeesaTheme.colors.raised)

@Composable
internal fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = ImpeesaTheme.colors
    // The whole row is one switch for TalkBack: label, description and on/off state (§11.4).
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
            Text(subtitle, style = ImpeesaTheme.type.caption, color = colors.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = colors.lime, checkedThumbColor = colors.ink))
    }
}
