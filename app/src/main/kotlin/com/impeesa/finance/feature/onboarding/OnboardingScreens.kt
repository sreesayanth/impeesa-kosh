package com.impeesa.finance.feature.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.R
import com.impeesa.finance.capture.InboxReconcileWorker
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.ui.component.AmountText
import com.impeesa.finance.core.ui.component.LetterTile
import com.impeesa.finance.core.ui.component.PrimaryButton
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import kotlinx.coroutines.delay

@Composable
internal fun OnboardingScaffold(step: Int, content: @Composable () -> Unit) {
    val colors = ImpeesaTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { index ->
                Box(
                    Modifier
                        .height(8.dp)
                        .size(width = if (index == step) 24.dp else 8.dp, height = 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (index == step) colors.lime else colors.line),
                )
            }
        }
        content()
    }
}

@Composable
private fun Bullet(title: String, body: String) {
    val colors = ImpeesaTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(colors.card), contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.lime))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
            Text(body, style = ImpeesaTheme.type.body, color = colors.textSecondary)
        }
    }
}

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val colors = ImpeesaTheme.colors
    OnboardingScaffold(step = 0) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = Modifier.size(44.dp))
            Text("Kosh", style = ImpeesaTheme.type.headline, color = colors.textPrimary)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Know where every rupee goes.",
            style = ImpeesaTheme.type.display.copy(fontSize = ImpeesaTheme.type.display.fontSize * 1.15f),
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Kosh turns bank SMS into a clear picture of your money — without you typing a thing.",
            style = ImpeesaTheme.type.body,
            color = colors.textSecondary,
        )
        Bullet("Automatic", "Logs spends as they happen")
        Bullet("Private", "No bank login, no internet access — everything stays on this phone")
        Bullet("Clear", "Budgets, bills and habits at a glance")
        Spacer(Modifier.height(12.dp))
        PrimaryButton(text = "Get started", onClick = onStart)
    }
}

/**
 * Prominent disclosure shown before the system permission prompt (Google Play SMS policy,
 * IMPLEMENTATION_PLAN.md §12.6). Says exactly what is read and why.
 */
@Composable
fun SmsPermissionScreen(onGranted: () -> Unit, onSkip: () -> Unit) {
    val colors = ImpeesaTheme.colors
    val context = LocalContext.current
    val container = LocalAppContainer.current
    var denied by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onGranted() }
    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            InboxReconcileWorker.scheduleDaily(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onGranted()
            }
        } else {
            denied = true
        }
    }

    OnboardingScaffold(step = 2) {
        SmsToTransactionDemo()
        Text(
            "Every rupee, tracked for you.",
            style = ImpeesaTheme.type.display,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Kosh reads your bank and card SMS and logs each transaction automatically.",
            style = ImpeesaTheme.type.body,
            color = colors.textSecondary,
        )
        Bullet("Stays on your phone", "Messages are read on this device and never uploaded. Kosh has no internet access.")
        Bullet("Only money messages", "Personal chats and OTPs are ignored and never saved.")
        Bullet("Your choice", "You can turn this off any time in your phone's settings.")
        if (denied) {
            Text(
                "SMS access was not allowed. You can still add transactions yourself, or allow it from settings.",
                style = ImpeesaTheme.type.caption,
                color = colors.amber,
            )
        }
        PrimaryButton(
            text = if (denied) "Open settings" else "Allow SMS access",
            onClick = {
                if (denied) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else {
                    smsLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
                }
            },
        )
        Text(
            "I'll add transactions myself",
            style = ImpeesaTheme.type.bodyStrong,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) {
                    container.prefs.finishOnboarding()
                    onSkip()
                }
                .padding(14.dp),
        )
    }
}

/** A bank SMS turning into a logged transaction — the whole idea in one picture. */
@Composable
private fun SmsToTransactionDemo() {
    val colors = ImpeesaTheme.colors
    var showCard by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(700)
        showCard = true
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.card)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(18.dp)).background(colors.raised).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("VM-HDFCBK", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
            Text(
                "Rs.486.00 debited from A/c XX4821 to VPA swiggy@icici on 11-10-26. UPI Ref 628491037215.",
                style = ImpeesaTheme.type.caption.copy(fontFamily = FontFamily.Monospace),
                color = colors.textPrimary,
            )
        }
        AnimatedVisibility(visible = showCard, enter = fadeIn() + slideInVertically { it / 2 }) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.textPrimary).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LetterTile(letter = "S", color = colors.food)
                Column(Modifier.weight(1f)) {
                    Text("Swiggy", style = ImpeesaTheme.type.bodyStrong, color = colors.ink)
                    Text("Food · HDFC ••4821 · Logged", style = ImpeesaTheme.type.caption, color = colors.ink.copy(alpha = 0.7f))
                }
                AmountText(money = Money(-48_600), signed = true, color = colors.ink)
            }
        }
    }
}
