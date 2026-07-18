package net.packrig.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.packrig.rig.CatProtocols
import net.packrig.rig.PttMethod
import net.packrig.rig.RigProfile
import net.packrig.rig.RigRegistry
import java.util.UUID

/**
 * Add/Edit dialog for one rig profile. Exposes the spec's form-field table:
 * name always; CAT protocol + baud for CAT setups; CAT port for any CAT setup
 * on a multi-port bridge (widened from generic-cat-only — owner decision
 * 2026-07-11); PTT for CAT setups (generic-rts is fixed RTS). Everything else
 * keeps preset defaults and never appears (spec 2026-07-10, Settings UX).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RigProfileEditorDialog(
    existing: RigProfile?,                 // null = Add rig
    allProfiles: List<RigProfile>,
    serialPortNames: List<String>,
    onTestCat: (RigProfile, (String) -> Unit) -> Unit,
    onSave: (RigProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var presetId by remember { mutableStateOf(existing?.presetId ?: "") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var baud by remember { mutableStateOf(existing?.baud) }
    var catPortIndex by remember { mutableStateOf(existing?.catPortIndex) }
    var pttMethod by remember { mutableStateOf(existing?.pttMethod) }
    var catProtocolId by remember {
        mutableStateOf(existing?.catProtocolId ?: CatProtocols.YAESU_NEWCAT)
    }
    var civAddressText by remember {
        mutableStateOf(existing?.civAddress?.let { "%02X".format(it) } ?: "")
    }
    var testResult by remember { mutableStateOf<String?>(null) }

    val preset = RigRegistry.byId(presetId)
    val isCatSetup = preset != null && preset.protocolFactory != null
    val nameError = RigProfileList.nameError(name, allProfiles, existing?.id)
    val shownProtocolId = if (RigRegistry.isCatGeneric(presetId)) catProtocolId else null
    val civError = RigProfileForm.civAddressError(civAddressText, presetId, shownProtocolId)
    val canSave = preset != null && nameError == null && civError == null

    fun draft() = RigProfile(
        id = existing?.id ?: UUID.randomUUID().toString(),
        name = name.trim(),
        presetId = presetId,
        catProtocolId = if (RigRegistry.isCatGeneric(presetId)) catProtocolId else null,
        baud = baud,
        catPortIndex = catPortIndex,
        pttMethod = pttMethod,
        civAddress = RigProfileForm.parseCivAddress(civAddressText),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add rig" else "Edit rig") },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { onSave(draft()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                PresetPicker(
                    selectedId = presetId.ifEmpty { null },
                    onSelect = { id ->
                        presetId = id
                        val chosen = RigRegistry.byId(id)
                        if (name.isBlank() && chosen != null && !RigRegistry.isGeneric(id)) {
                            name = chosen.displayName
                        }
                        // Re-prefill knobs from the new preset (null = default).
                        baud = null
                        catPortIndex = null
                        pttMethod = null
                        catProtocolId = CatProtocols.YAESU_NEWCAT
                        civAddressText = chosen?.civAddress?.let { "%02X".format(it) } ?: ""
                        testResult = null
                    },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    // Empty name: hint in normal color; only style as an error once typed.
                    isError = name.isNotEmpty() && nameError != null,
                    supportingText = { nameError?.let { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isCatSetup) {
                    if (RigRegistry.isCatGeneric(presetId)) {
                        CatProtocolPicker(
                            selectedId = catProtocolId,
                            onSelect = { id ->
                                catProtocolId = id
                                civAddressText = ""
                                testResult = null
                            },
                        )
                    }
                    RigProfileForm.protocolLabel(presetId)?.let {
                        Text("Protocol: $it", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (RigProfileForm.showsCivAddressField(presetId, shownProtocolId)) {
                        OutlinedTextField(
                            value = civAddressText,
                            onValueChange = { civAddressText = it.uppercase().take(2) },
                            label = { Text("CI-V address") },
                            isError = civAddressText.isNotEmpty() && civError != null,
                            supportingText = {
                                Text(civError ?: "Two hex digits from the radio's CI-V menu, e.g. 94")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    CatBaudPicker(
                        baud = baud ?: preset.defaultBaud,
                        defaultBaud = preset.defaultBaud,
                        enabled = true,
                        onSelect = { baud = it },
                    )
                    if (RigProfileForm.showsCatPortPicker(presetId, serialPortNames.size)) {
                        CatPortOverridePicker(
                            override = catPortIndex,
                            portNames = serialPortNames,
                            enabled = true,
                            onSelect = { catPortIndex = it },
                        )
                    }
                    PttPreferencePicker(
                        preference = (pttMethod ?: preset.defaultPtt).toPreference(),
                        enabled = true,
                        onSelect = { pttMethod = it.toPttMethod() },
                    )
                    TextButton(onClick = { onTestCat(draft()) { testResult = it } }) {
                        Text("Test CAT")
                    }
                    testResult?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                } else if (preset != null) {
                    Text(
                        "No CAT for this setup — PTT keys through the serial RTS line, " +
                            "and you set your dial frequency in the Radio section of Settings.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetPicker(selectedId: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = selectedId?.let { RigRegistry.byId(it)?.displayName } ?: "Select your radio"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Radio model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RigRegistry.all.forEach { d ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(d.displayName)
                            if (d.transportVerified) {
                                Text(
                                    "Field-verified",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = { expanded = false; onSelect(d.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatProtocolPicker(
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = CatProtocols.byId(selectedId)?.displayName ?: selectedId
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("CAT protocol") },
            supportingText = { Text("The command language your radio speaks — check its manual if unsure") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CatProtocols.all.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.displayName) },
                    onClick = { expanded = false; onSelect(entry.id) },
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
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
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
    defaultBaud: Int,
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
            supportingText = { Text("Must match your rig's CAT rate menu setting") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SettingsRepository.CAT_BAUD_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option == defaultBaud) "$option (default)" else option.toString()) },
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
            label = { Text("PTT method") },
            supportingText = { Text(preference.description) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
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
