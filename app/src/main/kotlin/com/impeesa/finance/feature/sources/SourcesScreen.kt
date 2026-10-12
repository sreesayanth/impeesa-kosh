package com.impeesa.finance.feature.sources

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.capture.CaptureHealth
import com.impeesa.finance.capture.SmsPermission
import com.impeesa.finance.core.data.capture.CaptureStats
import com.impeesa.finance.core.ui.component.ScreenHeader
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.component.SurfaceCard
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import java.time.LocalDate

/** "Auto-capture": where Impeesa looks for transactions and how to keep it working (design board "Sources"). */
@Composable
fun SourcesScreen(onBack: () -> Unit, onEnableSms: () -> Unit, onRescan: () -> Unit) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val colors = ImpeesaTheme.colors
    var smsAllowed by remember { mutableStateOf(SmsPermission.granted(context)) }
    var notificationsAllowed by remember { mutableStateOf(CaptureHealth.notificationAccessGranted(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        smsAllowed = SmsPermission.granted(context)
        notificationsAllowed = CaptureHealth.notificationAccessGranted(context)
        onPauseOrDispose { }
    }
    val today = remember { LocalDate.now(container.clock) }
    val stats by remember { container.captureRepository.observeStats(today.year * 100 + today.monthValue) }
        .collectAsStateWithLifecycle(CaptureStats(0, 0, emptyMap()))
    val apps = remember(refresh) { container.captureRepository.watchedApps() }

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
        ScreenHeader(title = "Auto-capture", onBack = onBack)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusTile("SMS", smsAllowed, Modifier.weight(1f)) { onEnableSms() }
            StatusTile("Notifications", notificationsAllowed, Modifier.weight(1f)) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        if (!notificationsAllowed) {
            Text(
                "Turn on notification access to also capture GPay, PhonePe and Paytm payments. Find “Impeesa payment alerts” in the list. " +
                    "On Android 13+, sideloaded apps may first need “Allow restricted settings” from the app's info page.",
                style = ImpeesaTheme.type.caption,
                color = colors.textSecondary,
            )
        }

        backgroundHelp()?.let { help ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(colors.amber.copy(alpha = 0.08f))
                    .border(1.dp, colors.amber.copy(alpha = 0.3f), RoundedCornerShape(22.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Your phone may pause Impeesa in the background", style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                Text(
                    "$help Anything missed is picked up next time you open the app.",
                    style = ImpeesaTheme.type.caption,
                    color = colors.textSecondary,
                )
                Text(
                    "Open phone settings",
                    style = ImpeesaTheme.type.label,
                    color = colors.onLime,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.amber)
                        .clickable(role = Role.Button) { openBackgroundSettings(context) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Payment apps")
            SurfaceCard(padding = 4.dp) {
                apps.forEach { watched ->
                    val installed = isInstalled(context, watched.app.packageName)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(watched.app.app, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                            Text(
                                when {
                                    watched.app.kind.name == "MESSAGING" -> "Bank alerts sent as chat messages (RCS)"
                                    installed -> "Installed"
                                    else -> "Not installed"
                                },
                                style = ImpeesaTheme.type.caption,
                                color = colors.textTertiary,
                            )
                        }
                        Switch(
                            checked = watched.enabled,
                            onCheckedChange = {
                                container.captureRepository.setAppEnabled(watched.app.packageName, it)
                                refresh++
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.lime, checkedThumbColor = colors.ink),
                        )
                    }
                }
            }
        }

        SurfaceCard(padding = 16.dp) {
            Text("This month", style = ImpeesaTheme.type.title, color = colors.textPrimary)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Count(stats.logged + stats.review, "captured")
                Count(stats.skipped["OTP"] ?: 0, "OTPs")
                Count(stats.skipped["PROMOTIONAL"] ?: 0, "offers")
                Count(stats.skipped.filterKeys { it != "OTP" && it != "PROMOTIONAL" }.values.sum(), "other")
            }
            Text(
                "Skipped messages are never stored. Personal chats are never read.",
                style = ImpeesaTheme.type.caption,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.card)
                .clickable(role = Role.Button, enabled = smsAllowed, onClick = onRescan)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Rescan messages", style = ImpeesaTheme.type.bodyStrong, color = if (smsAllowed) colors.textPrimary else colors.textTertiary)
        }
    }
}

@Composable
private fun StatusTile(label: String, allowed: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = ImpeesaTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
        Text(if (allowed) "Allowed" else "Off · tap to turn on", style = ImpeesaTheme.type.caption, color = if (allowed) colors.lime else colors.coral)
    }
}

@Composable
private fun Count(value: Int, label: String) {
    Column {
        Text(value.toString(), style = ImpeesaTheme.type.amountLarge, color = ImpeesaTheme.colors.textPrimary)
        Text(label, style = ImpeesaTheme.type.caption, color = ImpeesaTheme.colors.textSecondary)
    }
}

private fun isInstalled(context: Context, packageName: String): Boolean = try {
    context.packageManager.getPackageInfo(packageName, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}

private val manufacturer = Build.MANUFACTURER.lowercase()

/** Advice for phones known to stop background apps. Null for phones that behave. */
private fun backgroundHelp(): String? = when {
    manufacturer in setOf("xiaomi", "redmi", "poco") -> "Turn on Autostart for Impeesa and set Battery saver to “No restrictions”."
    manufacturer in setOf("oppo", "realme", "oneplus") -> "Allow auto-launch and background activity for Impeesa."
    manufacturer == "vivo" -> "Allow background start-up and high background power use for Impeesa."
    manufacturer in setOf("huawei", "honor") -> "In App launch, set Impeesa to “Manage manually” with everything on."
    manufacturer == "samsung" -> "Set Impeesa's battery use to “Unrestricted” so it isn't put to sleep."
    else -> null
}

/** OEM screens move between versions; try the known ones and fall back to app info. */
private fun openBackgroundSettings(context: Context) {
    val candidates = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
    ).map { Intent().setComponent(it) }
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    for (intent in candidates + fallback) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: Exception) {
            // Not on this phone; try the next one.
        }
    }
}
