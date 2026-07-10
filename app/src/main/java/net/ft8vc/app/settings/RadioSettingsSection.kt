package net.ft8vc.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.ft8vc.app.OperateUiState
import net.ft8vc.app.ui.DialFrequencyDropdownField
import net.ft8vc.app.ui.theme.Ft8Green
import net.ft8vc.rig.RigRegistry

/**
 * Radio (rig + serial link) settings: dial frequency, mode, DATA-U, CAT baud,
 * PTT preference, and USB diagnostics. Extracted from SettingsScreen so the
 * monolithic settings view stops growing (spec 2026-07-04-radio-settings).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioSettingsSection(
    state: OperateUiState,
    usbDiagnostics: String,
    serialPortNames: List<String>,
    onSelectRadioModel: (String) -> Unit,
    onSelectCatPort: (Int?) -> Unit,
    onSelectDialFrequency: (Long) -> Unit,
    onReadRig: () -> Unit,
    onSetRigDataUsb: () -> Unit,
    onSetCatBaud: (Int) -> Unit,
    onSetPttPreference: (PttPreference) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioModelPicker(
            selectedId = state.radioModelId,
            enabled = !state.catBusy && !state.isTransmitting,
            onSelect = onSelectRadioModel,
        )
        if (serialPortNames.size > 1) {
            CatPortOverridePicker(
                override = state.catPortOverride,
                portNames = serialPortNames,
                enabled = !state.catBusy && !state.isTransmitting,
                onSelect = onSelectCatPort,
            )
        }
        if (state.radioModelId == null) {
            Text(
                "Select your radio model to enable CAT and PTT.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.catReady) {
            DialFrequencyDropdownField(
                rigFreqHz = state.rigFreqHz,
                enabled = !state.catBusy,
                onSelect = onSelectDialFrequency,
                radioModelId = state.radioModelId,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Mode: ${state.rigMode ?: "?"}", fontFamily = FontFamily.Monospace)
                TextButton(onClick = onReadRig, enabled = !state.catBusy) {
                    Text("Read rig")
                }
            }
            Button(
                onClick = onSetRigDataUsb,
                enabled = !state.catBusy && state.rigMode != "DATA-U",
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Ft8Green, contentColor = androidx.compose.ui.graphics.Color.Black),
            ) {
                Text("Set DATA-U (FT8 mode)")
            }
            state.catStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        } else if (state.radioModelId != null) {
            Text(
                "CAT unavailable — connect the radio's serial link and grant USB permission.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        CatBaudPicker(
            baud = state.catBaud,
            enabled = !state.catBusy && !state.isTransmitting,
            onSelect = onSetCatBaud,
        )
        PttPreferencePicker(
            preference = state.pttPreference,
            // TX-guarded: flipping the PTT method mid-transmit could strand a
            // CAT-keyed rig (TX1; sent, TX0; never sent on the new path).
            enabled = !state.catBusy && !state.isTransmitting,
            onSelect = onSetPttPreference,
        )
        UsbDiagnosticsExpandable(diagnostics = usbDiagnostics)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadioModelPicker(
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = selectedId
        ?.let { id -> RigRegistry.byId(id)?.displayName }
        ?: "Select your radio model"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Radio model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RigRegistry.all.forEach { d ->
                DropdownMenuItem(
                    text = { Text(d.displayName) },
                    onClick = {
                        expanded = false
                        onSelect(d.id)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatPortOverridePicker(
    override: Int?,
    portNames: List<String>,
    enabled: Boolean,
    onSelect: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = override?.let { portNames.getOrNull(it) ?: "Serial port ${it + 1}" }
        ?: "Automatic (recommended)"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("CAT port") },
            supportingText = {
                Text("Which of the radio's serial channels carries CAT control. Automatic uses your radio model's default.")
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Automatic (recommended)") },
                onClick = { expanded = false; onSelect(null) },
            )
            portNames.forEachIndexed { i, name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { expanded = false; onSelect(i) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatBaudPicker(
    baud: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = baud.toString(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("CAT baud rate") },
            supportingText = { Text("Must match FT-891 menu 05-06 (CAT RATE)") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SettingsRepository.CAT_BAUD_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.toString()) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PttPreferencePicker(
    preference: PttPreference,
    enabled: Boolean,
    onSelect: (PttPreference) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = preference.displayName,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("PTT preference") },
            supportingText = { Text(preference.description) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PttPreference.entries.forEach { pref ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(pref.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                pref.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(pref)
                    },
                )
            }
        }
    }
}

@Composable
private fun UsbDiagnosticsExpandable(diagnostics: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (expanded) "Hide USB diagnostics" else "Show USB diagnostics",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(text = if (expanded) "▴" else "▾", style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) {
            Text(
                text = diagnostics,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
