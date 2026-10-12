package com.impeesa.finance.security

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.R
import com.impeesa.finance.core.model.security.PinHasher
import com.impeesa.finance.core.ui.R as UiR
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen lock over the whole app (IMPLEMENTATION_PLAN.md §12.4). Back leaves the app instead of skipping it. */
@Composable
fun LockScreen() {
    val container = LocalAppContainer.current
    val lock = container.appLock
    val activity = LocalContext.current as FragmentActivity
    val profile by container.prefs.profile.collectAsStateWithLifecycle()
    val biometricOn by lock.biometric.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val colors = ImpeesaTheme.colors
    var entry by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var lockedUntil by remember { mutableLongStateOf(lock.lockoutUntil()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var resetting by rememberSaveable { mutableStateOf(false) }
    val canUseBiometric = biometricOn && SystemAuth.biometricAvailable(activity)

    BackHandler { activity.moveTaskToBack(true) }

    fun promptBiometric() {
        SystemAuth.authenticate(activity, "Unlock Kosh", "Use your fingerprint or face", allowDeviceCredential = false, onSuccess = lock::unlock)
    }
    LaunchedEffect(Unit) { if (canUseBiometric && lockedUntil <= System.currentTimeMillis()) promptBiometric() }
    LaunchedEffect(lockedUntil) {
        while (lockedUntil > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.ink)
            // Swallow touches so nothing underneath can be used.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        if (resetting) {
            PinSetup(
                title = "Set a new PIN",
                onDone = { pin ->
                    scope.launch {
                        withContext(Dispatchers.Default) { lock.setPin(pin) }
                        lock.unlock()
                        resetting = false
                    }
                },
                onCancel = { resetting = false },
            )
            return@Box
        }
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = Modifier.size(64.dp))
            Text(if (profile.hasName) "Welcome back, ${profile.firstName}" else "Kosh is locked", style = ImpeesaTheme.type.title, color = colors.textPrimary)
            val waiting = lockedUntil > now
            Text(
                when {
                    waiting -> "Too many tries. Wait ${((lockedUntil - now) / 1000) + 1} s"
                    message != null -> message!!
                    else -> "Enter your PIN"
                },
                style = ImpeesaTheme.type.body,
                color = if (waiting || message != null) colors.coral else colors.textSecondary,
            )
            PinDots(filled = entry.length, total = lock.pinLength())
            PinPad(
                enabled = !waiting,
                onDigit = { d ->
                    if (entry.length >= lock.pinLength()) return@PinPad
                    entry += d
                    message = null
                    if (entry.length == lock.pinLength()) {
                        val attempt = entry
                        scope.launch {
                            val result = withContext(Dispatchers.Default) { lock.verify(attempt) }
                            entry = ""
                            when (result) {
                                PinResult.Ok -> Unit
                                is PinResult.Wrong -> message = if (result.attemptsBeforeWait <= 2) "Wrong PIN · ${result.attemptsBeforeWait} more before a wait" else "Wrong PIN"
                                is PinResult.LockedOut -> lockedUntil = result.untilMillis
                            }
                        }
                    }
                },
                onBackspace = { entry = entry.dropLast(1) },
                biometric = if (canUseBiometric) ::promptBiometric else null,
            )
            Text(
                "Forgot PIN?",
                style = ImpeesaTheme.type.label,
                color = colors.lime,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) {
                    if (SystemAuth.deviceCredentialAvailable(activity)) {
                        SystemAuth.authenticate(
                            activity, "Reset your Kosh PIN", "Confirm it's you with your phone's screen lock",
                            allowDeviceCredential = true,
                            onSuccess = { resetting = true },
                        )
                    } else {
                        message = "Your phone has no screen lock to confirm with. Uninstalling removes the PIN and all data."
                    }
                }.padding(10.dp),
            )
        }
    }
}

@Composable
fun PinDots(filled: Int, total: Int) {
    val colors = ImpeesaTheme.colors
    Row(
        Modifier.clearAndSetSemantics { contentDescription = "$filled of $total digits entered" },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(total) { i ->
            Box(
                Modifier.size(14.dp).clip(CircleShape)
                    .background(if (i < filled) colors.lime else colors.ink)
                    .border(1.5.dp, if (i < filled) colors.lime else colors.line, CircleShape),
            )
        }
    }
}

@Composable
fun PinPad(enabled: Boolean, onDigit: (Char) -> Unit, onBackspace: () -> Unit, biometric: (() -> Unit)? = null) {
    val colors = ImpeesaTheme.colors
    val rows = listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'), listOf('B', '0', '<'))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { key ->
                    Box(Modifier.weight(1f).aspectRatio(1.6f), contentAlignment = Alignment.Center) {
                        when (key) {
                            'B' -> if (biometric != null) {
                                KeyButton(enabled = enabled, label = "Use fingerprint", onClick = biometric) {
                                    Text("☝", style = ImpeesaTheme.type.headline, color = colors.lime)
                                }
                            }
                            '<' -> KeyButton(enabled = enabled, label = "Delete digit", onClick = onBackspace) {
                                Icon(painterResource(UiR.drawable.ic_backspace), contentDescription = null, tint = colors.textPrimary)
                            }
                            else -> KeyButton(enabled = enabled, label = key.toString(), onClick = { onDigit(key) }) {
                                Text(key.toString(), style = ImpeesaTheme.type.headline, color = if (enabled) colors.textPrimary else colors.textTertiary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(enabled: Boolean, label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(ImpeesaTheme.colors.card)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Choose a PIN, then type it again. 4–6 digits; "Next" accepts 4 or 5. */
@Composable
fun PinSetup(title: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    val colors = ImpeesaTheme.colors
    var first by rememberSaveable { mutableStateOf<String?>(null) }
    var entry by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val confirming = first != null
    val target = first?.length ?: 6

    fun submit(pin: String) {
        if (!confirming) {
            first = pin
            entry = ""
        } else if (pin == first) {
            onDone(pin)
        } else {
            error = "PINs didn't match. Start again."
            first = null
            entry = ""
        }
    }

    Column(
        Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(if (confirming) "Type it again" else title, style = ImpeesaTheme.type.title, color = colors.textPrimary)
        Text(
            error ?: if (confirming) "To make sure it's right" else "4 to 6 digits. Don't reuse your phone's PIN.",
            style = ImpeesaTheme.type.caption,
            color = if (error != null) colors.coral else colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        PinDots(filled = entry.length, total = target)
        PinPad(
            enabled = true,
            onDigit = { d ->
                if (entry.length >= target) return@PinPad
                entry += d
                error = null
                if (entry.length == target) submit(entry)
            },
            onBackspace = { entry = entry.dropLast(1) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Cancel", style = ImpeesaTheme.type.label, color = colors.textSecondary, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onCancel).padding(10.dp))
            if (!confirming && PinHasher.isValid(entry) && entry.length < 6) {
                Text("Next", style = ImpeesaTheme.type.label, color = colors.lime, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { submit(entry) }.padding(10.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
