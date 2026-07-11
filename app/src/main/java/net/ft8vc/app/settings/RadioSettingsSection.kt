package net.ft8vc.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import net.ft8vc.app.OperateUiState
import net.ft8vc.app.ui.DialFrequencyDropdownField
import net.ft8vc.app.ui.theme.Ft8Green
import net.ft8vc.rig.RigProfile

/**
 * Radio (rig + serial link) settings: My rigs list (add/edit/delete/select),
 * dial frequency, mode, DATA-U, and USB diagnostics. Extracted from
 * SettingsScreen so the monolithic settings view stops growing (spec
 * 2026-07-04-radio-settings; reworked to rig profiles spec 2026-07-10).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioSettingsSection(
    state: OperateUiState,
    usbDiagnostics: String,
    serialPortNames: List<String>,
    onSelectRigProfile: (String) -> Unit,
    onSaveRigProfile: (RigProfile) -> Unit,
    onDeleteRigProfile: (String) -> Unit,
    onTestCat: (RigProfile, (String) -> Unit) -> Unit,
    onSelectDialFrequency: (Long) -> Unit,
    onSetManualDialFrequency: (Long) -> Unit,
    onReadRig: () -> Unit,
    onSetRigDataUsb: () -> Unit,
) {
    var editorTarget by remember { mutableStateOf<RigProfile?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<RigProfile?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MyRigsBlock(
            profiles = state.rigProfiles,
            selectedId = state.selectedRigProfileId,
            enabled = !state.catBusy && !state.isTransmitting,
            onSelect = onSelectRigProfile,
            onAdd = { editorTarget = null; editorOpen = true },
            onEdit = { editorTarget = it; editorOpen = true },
            onDelete = { deleteTarget = it },
        )
        if (state.rigProfiles.isEmpty()) {
            Text("Add your rig to enable CAT and PTT.", style = MaterialTheme.typography.bodySmall)
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
        } else if (state.selectedRigProfileId != null && !state.rigHasCat) {
            Text(
                "No CAT (manual) — keep the radio's dial on the selected frequency.",
                style = MaterialTheme.typography.bodySmall,
            )
            DialFrequencyDropdownField(
                rigFreqHz = state.lastDialFreqHz,
                enabled = true,
                onSelect = onSetManualDialFrequency,
                radioModelId = state.radioModelId,
            )
        } else if (state.selectedRigProfileId != null) {
            Text(
                "CAT unavailable — connect the radio's serial link and grant USB permission.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        UsbDiagnosticsExpandable(diagnostics = usbDiagnostics)
    }

    if (editorOpen) {
        RigProfileEditorDialog(
            existing = editorTarget,
            allProfiles = state.rigProfiles,
            serialPortNames = serialPortNames,
            onTestCat = onTestCat,
            onSave = { editorOpen = false; onSaveRigProfile(it) },
            onDismiss = { editorOpen = false },
        )
    }
    deleteTarget?.let { doomed ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${doomed.name}?") },
            text = { Text("This removes the saved configuration. Your log is not affected.") },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; onDeleteRigProfile(doomed.id) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyRigsBlock(
    profiles: List<RigProfile>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (RigProfile) -> Unit,
    onDelete: (RigProfile) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = profiles.firstOrNull { it.id == selectedId }
    if (profiles.isNotEmpty()) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
            OutlinedTextField(
                value = selected?.name ?: "Select a rig",
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                label = { Text("My rig") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                profiles.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = { expanded = false; onSelect(p.id) },
                    )
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (profiles.size < RigProfileList.MAX) {
            TextButton(onClick = onAdd, enabled = enabled) { Text("Add rig") }
        }
        selected?.let {
            TextButton(onClick = { onEdit(it) }, enabled = enabled) { Text("Edit") }
            TextButton(onClick = { onDelete(it) }, enabled = enabled) { Text("Delete") }
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
