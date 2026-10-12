package com.impeesa.finance.feature.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.ui.R as UiR
import com.impeesa.finance.core.ui.component.ScreenHeader
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.feature.profile.ProfileAvatar

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onProfile: () -> Unit,
    onAutoCapture: () -> Unit,
    onRules: () -> Unit,
    onBudgets: () -> Unit,
    onAlerts: () -> Unit,
    onAccounts: () -> Unit,
    onSecurity: () -> Unit,
    onData: () -> Unit,
    onPrivacy: () -> Unit,
    onCurrency: () -> Unit,
) {
    val container = LocalAppContainer.current
    val money by container.prefs.money.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val profile by container.prefs.profile.collectAsStateWithLifecycle()
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
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
        ScreenHeader(title = "Settings", onBack = onBack)

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(colors.card)
                .clickable(role = Role.Button, onClickLabel = "Edit profile", onClick = onProfile)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProfileAvatar(profile, size = 52.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (profile.hasName) profile.name else "Add your name", style = ImpeesaTheme.type.title, color = colors.textPrimary)
                Text("Edit profile", style = ImpeesaTheme.type.caption, color = colors.lime)
            }
            Chevron()
        }

        SectionLabel("Tracking")
        Group {
            SettingsRow(UiR.drawable.ic_tune, "Auto-capture", "SMS, payment apps and background tips", onAutoCapture)
            HorizontalDivider(color = colors.raised)
            SettingsRow(UiR.drawable.ic_insights, "Categories & rules", "How transactions get sorted", onRules)
            HorizontalDivider(color = colors.raised)
            SettingsRow(UiR.drawable.ic_calendar, "Budgets", "Monthly limits and when your month starts", onBudgets)
            HorizontalDivider(color = colors.raised)
            SettingsRow(UiR.drawable.ic_accounts, "Accounts", "Banks, cards, wallets and cash", onAccounts)
            HorizontalDivider(color = colors.raised)
            SettingsRow(UiR.drawable.ic_bell, "Alerts", "Bill reminders, budgets, renewals and more", onAlerts)
            HorizontalDivider(color = colors.raised)
            SettingsRow(UiR.drawable.ic_accounts, "Currency & format", "${money.currency.name} · ${MoneyFormat.format(Money(1_00_000_00))}", onCurrency)
        }

        SectionLabel("Security & your data")
        Group {
            SettingsRow(UiR.drawable.ic_person, "Security & privacy", "App lock, fingerprint, hide balances", onSecurity)
            HorizontalDivider(color = colors.raised)
            SettingsRow(UiR.drawable.ic_accounts, "Backup & data", "Encrypted backup, restore, export, delete all", onData)
            HorizontalDivider(color = colors.raised)
            SettingsRow(UiR.drawable.ic_insights, "Privacy & about", "What Kosh reads and keeps", onPrivacy)
        }

        Text(
            "Kosh $version · No internet access · Your data stays on this phone",
            style = ImpeesaTheme.type.caption,
            color = colors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(ImpeesaTheme.colors.card)) { content() }
}

@Composable
private fun SettingsRow(@DrawableRes icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = ImpeesaTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.raised), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = colors.lime, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
            Text(subtitle, style = ImpeesaTheme.type.caption, color = colors.textSecondary)
        }
        Chevron()
    }
}

@Composable
private fun Chevron() {
    Icon(
        painterResource(UiR.drawable.ic_back),
        contentDescription = null,
        tint = ImpeesaTheme.colors.textTertiary,
        modifier = Modifier.size(18.dp).rotate(180f),
    )
}
