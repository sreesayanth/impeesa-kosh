package com.impeesa.finance.feature.profile

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.model.Currencies
import com.impeesa.finance.core.ui.component.ImpeesaTextField
import com.impeesa.finance.core.ui.component.PrimaryButton
import com.impeesa.finance.core.ui.component.ScreenHeader
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.feature.onboarding.OnboardingScaffold
import com.impeesa.finance.feature.settings.CurrencyRow
import kotlinx.coroutines.launch
import com.impeesa.finance.core.ui.R as UiR

/** Avatar colours, in picker order. The stored index points into this list. */
@Composable
private fun avatarColors(): List<Color> {
    val c = ImpeesaTheme.colors
    return listOf(c.lime, c.food, c.shopping, c.travel, c.groceries, c.fun_, c.amber, c.health)
}

@Composable
private fun avatarColor(index: Int): Color = avatarColors().let { it[index.coerceIn(it.indices)] }

/** Round avatar with the user's initials, or a person glyph before a name is set. */
@Composable
fun ProfileAvatar(profile: Profile, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val tint by animateColorAsState(avatarColor(profile.colorIndex), label = "avatar")
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        if (profile.hasName) {
            val base = if (size >= 72.dp) ImpeesaTheme.type.display else ImpeesaTheme.type.label
            Text(profile.initials, style = base, color = tint)
        } else {
            Icon(painterResource(UiR.drawable.ic_person), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.48f))
        }
    }
}

@Composable
private fun ColorPicker(selected: Int, onSelect: (Int) -> Unit) {
    val names = listOf("Lime", "Orange", "Violet", "Sky", "Teal", "Pink", "Amber", "Blue")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Colour")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            avatarColors().forEachIndexed { index, color ->
                val isSelected = index == selected
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .semantics { this.selected = isSelected }
                        .clickable(role = Role.RadioButton, onClickLabel = names[index]) { onSelect(index) }
                        .border(2.dp, if (isSelected) ImpeesaTheme.colors.textPrimary else Color.Transparent, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
    }
}

/** Name field, colour picker and live avatar preview, shared by setup and edit. */
@Composable
private fun ProfileForm(name: String, onName: (String) -> Unit, colorIndex: Int, onColor: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        ProfileAvatar(
            Profile(Profile.cleanName(name), colorIndex),
            size = 96.dp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        ImpeesaTextField(
            value = name,
            onValueChange = { if (it.length <= Profile.MAX_NAME) onName(it) },
            label = "Your name",
            placeholder = "e.g. Aarav",
            capitalization = KeyboardCapitalization.Words,
        )
        ColorPicker(colorIndex, onColor)
    }
}

/** Onboarding step: "What should we call you?" Skippable; the name only personalises the app. */
@Composable
fun ProfileSetupScreen(onContinue: () -> Unit) {
    val container = LocalAppContainer.current
    val prefs = container.prefs
    val colors = ImpeesaTheme.colors
    var name by rememberSaveable { mutableStateOf(prefs.profile.value.name) }
    var colorIndex by rememberSaveable { mutableIntStateOf(prefs.profile.value.colorIndex) }
    var currencyCode by rememberSaveable { mutableStateOf(prefs.setupCurrency ?: prefs.money.value.currency.code) }
    var choosingCurrency by rememberSaveable { mutableStateOf(false) }
    val currency = Currencies.byCode(currencyCode)

    fun next() {
        prefs.setupCurrency = currencyCode
        // Capture needs it before the inbox import; the display follows at the end of setup.
        container.ioScope.launch { container.captureRepository.homeCurrency = currencyCode }
        onContinue()
    }

    OnboardingScaffold(step = 1) {
        Text(
            "What should we call you?",
            style = ImpeesaTheme.type.display,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Your name and colour only personalise the app. They stay on this phone.",
            style = ImpeesaTheme.type.body,
            color = colors.textSecondary,
        )
        ProfileForm(name, { name = it }, colorIndex, { colorIndex = it })
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Currency")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.card)
                    .border(1.dp, colors.line, RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button, onClickLabel = "Change currency") { choosingCurrency = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.widthIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(colors.raised).padding(8.dp), contentAlignment = Alignment.Center) {
                    Text(currency.symbol.trim(), style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(currency.name, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                    Text(currency.code, style = ImpeesaTheme.type.caption, color = colors.textTertiary)
                }
                Text("Change", style = ImpeesaTheme.type.label, color = colors.lime)
            }
            Text(
                "Kosh reads Indian bank SMS in rupees. You can change this any time in Settings.",
                style = ImpeesaTheme.type.caption,
                color = colors.textTertiary,
            )
        }
        Spacer(Modifier.height(4.dp))
        PrimaryButton(
            text = "Continue",
            enabled = Profile.cleanName(name).isNotEmpty(),
            onClick = {
                prefs.saveProfile(name, colorIndex)
                next()
            },
        )
        Text(
            "Skip for now",
            style = ImpeesaTheme.type.bodyStrong,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { next() }
                .padding(14.dp),
        )
    }

    if (choosingCurrency) {
        AlertDialog(
            onDismissRequest = { choosingCurrency = false },
            title = { Text("Currency") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Currencies.ALL.forEach { option ->
                        CurrencyRow(option, selected = option.code == currencyCode) {
                            currencyCode = option.code
                            choosingCurrency = false
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingCurrency = false }) { Text("Cancel") } },
            containerColor = colors.card,
        )
    }
}

/** Edit profile, reached from the avatar on Home. */
@Composable
fun EditProfileScreen(onBack: () -> Unit) {
    val prefs = LocalAppContainer.current.prefs
    val colors = ImpeesaTheme.colors
    val saved by prefs.profile.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(saved.name) }
    var colorIndex by rememberSaveable { mutableIntStateOf(saved.colorIndex) }
    val changed = Profile.cleanName(name) != saved.name || colorIndex != saved.colorIndex

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        ScreenHeader(title = "Edit profile", onBack = onBack)
        ProfileForm(name, { name = it }, colorIndex, { colorIndex = it })
        Text(
            "Leave the name empty to hide it. Your profile is stored only on this phone and is never uploaded.",
            style = ImpeesaTheme.type.caption,
            color = colors.textSecondary,
        )
        PrimaryButton(
            text = "Save",
            enabled = changed,
            onClick = {
                prefs.saveProfile(name, colorIndex)
                onBack()
            },
        )
    }
}
