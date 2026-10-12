package com.impeesa.finance.feature.alerts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.data.Alert
import com.impeesa.finance.core.model.DateLabels
import com.impeesa.finance.core.model.alerts.AlertKind
import com.impeesa.finance.core.ui.component.EmptyState
import com.impeesa.finance.core.ui.component.ScreenHeader
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import java.time.Instant
import java.time.LocalDate

/** The in-app alerts list, 60 days (IMPLEMENTATION_PLAN.md §5.10). Opening it marks everything read. */
@Composable
fun AlertsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val container = LocalAppContainer.current
    val alerts by container.ledgerRepository.observeAlerts().collectAsStateWithLifecycle(initialValue = null)
    val colors = ImpeesaTheme.colors
    val zone = container.clock.zone
    val today = LocalDate.now(container.clock)
    LaunchedEffect(Unit) {
        // Show what was unread first, then mark it read.
        kotlinx.coroutines.delay(1_500)
        container.ledgerRepository.markAllAlertsRead()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.ink).statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            ScreenHeader(title = "Alerts", onBack = onBack) {
                Text(
                    "Settings",
                    style = ImpeesaTheme.type.label,
                    color = colors.lime,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onSettings).padding(8.dp),
                )
            }
        }
        val list = alerts ?: return@LazyColumn
        if (list.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    title = "No alerts",
                    body = "Bill reminders, budget warnings, large payments and renewals show up here. Alerts are kept for 60 days.",
                    actionLabel = "Alert settings",
                    onAction = onSettings,
                )
            }
            return@LazyColumn
        }
        items(list, key = { it.id }) { alert ->
            AlertRow(alert, today, zone) { onOpen(alert.link) }
        }
    }
}

@Composable
private fun AlertRow(alert: Alert, today: LocalDate, zone: java.time.ZoneId, onClick: () -> Unit) {
    val colors = ImpeesaTheme.colors
    val time = Instant.ofEpochMilli(alert.createdAt).atZone(zone)
    val tint = when (AlertKind.entries.firstOrNull { it.name == alert.kind }) {
        AlertKind.CARD_DUE, AlertKind.LARGE_TRANSACTION -> colors.coral
        AlertKind.BUDGET, AlertKind.UNUSUAL_DAY, AlertKind.BALANCE_SYNC, AlertKind.LOW_BALANCE -> colors.amber
        AlertKind.SUBSCRIPTION -> colors.fun_
        AlertKind.WEEKLY_SUMMARY, AlertKind.NEW_ACCOUNT -> colors.lime
        else -> colors.travel
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.card).clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(if (alert.read) Color.Transparent else tint))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(alert.title, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
            Text(alert.body, style = ImpeesaTheme.type.caption, color = colors.textSecondary)
            Text(DateLabels.whenShort(time.toLocalDate(), time.toLocalTime(), today), style = ImpeesaTheme.type.caption, color = colors.textTertiary)
        }
    }
}

/** One switch per alert type; system channel settings still apply on top. */
@Composable
fun AlertSettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val enabled by container.prefs.enabledAlerts.collectAsStateWithLifecycle()
    val colors = ImpeesaTheme.colors
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.ink).statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") { ScreenHeader(title = "Alert settings", onBack = onBack) }
        item(key = "note") {
            Text(
                "All alerts are created on this phone; nothing is sent anywhere. You can also mute each kind in your phone's notification settings.",
                style = ImpeesaTheme.type.caption,
                color = colors.textSecondary,
            )
        }
        item(key = "label") { SectionLabel("Notify me about") }
        items(AlertKind.entries, key = { it.name }) { kind ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.card).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(kind.title, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                    Text(kind.description, style = ImpeesaTheme.type.caption, color = colors.textSecondary)
                }
                Switch(
                    checked = kind in enabled,
                    onCheckedChange = { container.prefs.setAlertEnabled(kind, it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.lime, checkedThumbColor = colors.ink),
                )
            }
        }
    }
}
