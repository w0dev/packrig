package net.packset.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun Ft8DialPresetMenuItems(radioModelId: String? = null, onSelect: (Long) -> Unit) {
    presetsForModel(radioModelId).forEach { preset ->
        DropdownMenuItem(
            text = { Text(preset.menuText) },
            onClick = { onSelect(preset.hz) },
        )
    }
}

@Composable
fun DialFrequencyDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    radioModelId: String? = null,
    onSelect: (Long) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        Ft8DialPresetMenuItems(radioModelId) { hz ->
            onSelect(hz)
            onDismissRequest()
        }
    }
}

/**
 * Compact monospace dial label with anchored preset menu (Spectrum, Operate status bar).
 */
@Composable
fun DialFrequencySelector(
    rigFreqHz: Long?,
    enabled: Boolean,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    radioModelId: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = dialLabelText(rigFreqHz)
    val suffix = if (enabled) " ▾" else ""

    Box(modifier = modifier) {
        Text(
            text = label + suffix,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = if (enabled) {
                Modifier.clickable { expanded = true }
            } else {
                Modifier
            },
        )
        if (enabled) {
            DialFrequencyDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                radioModelId = radioModelId,
                onSelect = onSelect,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialFrequencyBottomSheet(
    onDismissRequest: () -> Unit,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    radioModelId: String? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("FT8 dial frequency", style = MaterialTheme.typography.titleMedium)
            presetsForModel(radioModelId).forEach { preset ->
                TextButton(
                    onClick = {
                        onSelect(preset.hz)
                        onDismissRequest()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(preset.menuText)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialFrequencyDropdownField(
    rigFreqHz: Long?,
    enabled: Boolean,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    radioModelId: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val matched = dialPresetForFreq(rigFreqHz)
    val fieldText = matched?.menuText
        ?: rigFreqHz?.let { "%.6f MHz".format(it / 1_000_000.0) }
        ?: "Select band"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = fieldText,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Band / dial frequency") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Ft8DialPresetMenuItems(radioModelId) { hz ->
                expanded = false
                onSelect(hz)
            }
        }
    }
}
