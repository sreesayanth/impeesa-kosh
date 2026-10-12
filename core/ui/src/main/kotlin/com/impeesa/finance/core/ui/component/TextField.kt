package com.impeesa.finance.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.impeesa.finance.core.ui.theme.ImpeesaTheme

/** Labelled single-line text field in the app's style. */
@Composable
fun ImpeesaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    prefix: String? = null,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    /** Hides what is typed (passphrases) and keeps the keyboard from learning it. */
    password: Boolean = false,
) {
    val colors = ImpeesaTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = ImpeesaTheme.type.bodyStrong.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.lime),
            keyboardOptions = KeyboardOptions(
                capitalization = if (password) KeyboardCapitalization.None else capitalization,
                autoCorrectEnabled = !password,
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = ImeAction.Done,
            ),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(shape)
                        .background(colors.card)
                        // The outline keeps the field visible on card-coloured sheets too.
                        .border(1.dp, colors.line, shape)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        if (prefix != null) Text(prefix, style = ImpeesaTheme.type.bodyStrong, color = colors.textTertiary, modifier = Modifier.padding(end = 6.dp))
                        Box {
                            if (value.isEmpty() && placeholder.isNotEmpty()) {
                                Text(placeholder, style = ImpeesaTheme.type.body, color = colors.textTertiary)
                            }
                            inner()
                        }
                    }
                }
            },
        )
    }
}
