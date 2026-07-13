package net.packset.app.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Text field for a persisted setting that is edited faster than it round-trips
 * through DataStore.
 *
 * The field owns its text while the user is editing: each keystroke updates a
 * local draft synchronously (after [transform], e.g. uppercasing) and hands the
 * transformed text to [onValueChange] for persistence. Late echoes of
 * [persistedValue] are ignored once editing has started, so the cursor never
 * jumps and typed characters are never rewound. Before the first edit the field
 * tracks [persistedValue], which arrives asynchronously when settings load.
 */
@Composable
fun SettingsTextField(
    persistedValue: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    transform: (String) -> String = { it },
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    isError: (String) -> Boolean = { false },
    supportingText: (@Composable (shown: String) -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf(false) }
    val shown = if (editing) draft else persistedValue

    OutlinedTextField(
        value = shown,
        onValueChange = {
            val transformed = transform(it)
            draft = transformed
            editing = true
            onValueChange(transformed)
        },
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        singleLine = true,
        isError = isError(shown),
        supportingText = supportingText?.let { content -> { content(shown) } },
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
    )
}
