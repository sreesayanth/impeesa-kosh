package com.impeesa.finance.feature.placeholder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.impeesa.finance.R
import com.impeesa.finance.core.ui.component.PrimaryButton
import com.impeesa.finance.core.ui.theme.ImpeesaTheme

/** Temporary screen for destinations later phases fill in. */
@Composable
fun ComingSoonScreen(title: String, phase: String, onClose: (() -> Unit)? = null) {
    val colors = ImpeesaTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.ink)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = ImpeesaTheme.type.display, color = colors.textPrimary)
        Text(stringResource(R.string.coming_soon_body, phase), style = ImpeesaTheme.type.body, color = colors.textSecondary)
        if (onClose != null) PrimaryButton(text = "Back", onClick = onClose)
    }
}
