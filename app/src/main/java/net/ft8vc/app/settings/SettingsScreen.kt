package net.ft8vc.app.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.ft8vc.app.OperateUiState
import net.ft8vc.app.OperateViewModel
import net.ft8vc.core.ActivationProfile
import net.ft8vc.core.AnswerPolicy
import net.ft8vc.core.AppInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: OperateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    // Snackbar events surface via the app-level host in Ft8vcApp.
    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsSection("Station") {
                OutlinedTextField(
                    value = state.myCall,
                    onValueChange = vm::setMyCall,
                    label = { Text("My call") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.myGrid,
                    onValueChange = vm::setMyGrid,
                    label = { Text("Grid") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsSection("Activation (POTA)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("POTA mode", fontWeight = FontWeight.SemiBold)
                        Text(
                            "CQ POTA on-air and POTA fields in ADIF export",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.potaModeEnabled,
                        onCheckedChange = vm::setPotaModeEnabled,
                    )
                }
                if (state.potaModeEnabled) {
                    OutlinedTextField(
                        value = state.potaParkRef,
                        onValueChange = vm::setPotaParkRef,
                        label = { Text("Park reference") },
                        placeholder = { Text("US-3315, US-0891") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = state.potaParkRef.isNotBlank() &&
                            !ActivationProfile.isValidParkRefList(state.potaParkRef),
                        supportingText = {
                            Text(
                                if (state.potaParkRef.isBlank()) "Required to call CQ POTA — comma-separate for two-fers"
                                else if (!ActivationProfile.isValidParkRefList(state.potaParkRef)) "Format: prefix-number (e.g. US-3315)"
                                else "Valid park reference",
                            )
                        },
                    )
                }
            }

            SettingsSection("Display") {
                AutoToggleRow(
                    title = "Dark mode",
                    subtitle = "Use the dark color scheme across the entire app",
                    checked = state.useDarkTheme,
                    onCheckedChange = vm::setUseDarkTheme,
                    enabled = true,
                )
                DecodeColorsSection(
                    scheme = state.decodeColors,
                    onPickColor = vm::setDecodeColor,
                    onReset = vm::resetDecodeColors,
                )
            }

            SettingsSection("Audio") {
                DevicePicker(state = state, onSelect = vm::selectDevice)
                Text(
                    "Use a USB audio device (Digirig) for RX/TX. Adjust input level on Operate while monitoring.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val rigTitle = state.radioModelId
                ?.let { net.ft8vc.rig.RigRegistry.byId(it)?.displayName }
                ?.let { "Rig ($it)" }
                ?: "Radio"
            SettingsSection(rigTitle) {
                RadioSettingsSection(
                    state = state,
                    usbDiagnostics = vm.usbDiagnostics(),
                    serialPortNames = vm.serialPortDisplayNames(),
                    onSelectRadioModel = vm::setRadioModel,
                    onSelectCatPort = vm::setCatPortOverride,
                    onSelectDialFrequency = vm::setRigFrequency,
                    onReadRig = vm::readRig,
                    onSetRigDataUsb = vm::setRigDataUsb,
                    onSetCatBaud = vm::setCatBaud,
                    onSetPttPreference = vm::setPttPreference,
                )
            }

            SettingsSection("Clock alignment") {
                val residual = state.clockOffsetSeconds
                val appliedS = state.appliedClockOffsetMs / 1000f
                Text(
                    "Applied correction: %+.1f s".format(java.util.Locale.US, appliedS),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (residual != null) {
                        "Residual vs band time: %+.1f s".format(java.util.Locale.US, residual)
                    } else {
                        "Residual vs band time: not enough decodes yet"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = vm::alignClock,
                        enabled = residual != null,
                    ) { Text("Align now") }
                    OutlinedButton(
                        onClick = vm::resetClockAlignment,
                        enabled = state.appliedClockOffsetMs != 0L,
                    ) { Text("Reset") }
                }
            }

            SettingsSection("TX") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable transmit", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Allow the app to key the radio",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Switch(
                        checked = state.txEnabled,
                        onCheckedChange = vm::setTxEnabled,
                        enabled = !state.isTransmitting,
                    )
                }
                Text(
                    "Set TX tone on the Spectrum tab (tap or drag the waterfall).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Transmitting requires a valid amateur radio license for your " +
                        "jurisdiction. You are responsible for lawful operation; this " +
                        "app and its authors are not.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection("Operating (auto TX)") {
                Text(
                    "Selection and limits below apply to all auto TX modes, not only CQ hunt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnswerPolicyPicker(
                    policy = state.answerPolicy,
                    onSelect = vm::setAnswerPolicy,
                    enabled = state.txEnabled,
                )
                MaxUnansweredTxPicker(
                    cycles = state.maxUnansweredTxCycles,
                    onSelect = vm::setMaxUnansweredTxCycles,
                    enabled = state.txEnabled,
                )
                Text(
                    "Blocklist",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.userBlockedCalls.isEmpty()) {
                    Text(
                        "No blocked stations.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.userBlockedCalls.forEach { call ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(call, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { vm.unblockStation(call) }) {
                                Text("Unblock")
                            }
                        }
                    }
                    TextButton(
                        onClick = vm::clearAbandonedPartners,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Clear all")
                    }
                }
                Text(
                    "Auto behaviors",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                AutoToggleRow(
                    title = "Auto Seq",
                    subtitle = "Advance an active QSO when the expected reply is decoded",
                    checked = state.autoSeqEnabled,
                    onCheckedChange = vm::setAutoSeqEnabled,
                    enabled = state.txEnabled,
                )
                AutoToggleRow(
                    title = "Answer when called",
                    subtitle = "Start or resume when someone calls you (grid, report, etc.)",
                    checked = state.answerWhenCalledEnabled,
                    onCheckedChange = vm::setAnswerWhenCalledEnabled,
                    enabled = state.txEnabled,
                )
                AutoToggleRow(
                    title = "Auto answer CQ",
                    subtitle = "Call stations that are CQing when idle",
                    checked = state.autoAnswerCqEnabled,
                    onCheckedChange = vm::setAutoAnswerCqEnabled,
                    enabled = state.txEnabled,
                )
                AutoToggleRow(
                    title = "Late-start TX (up to 7s into slot)",
                    subtitle = "Send a truncated waveform so a late Answer/Resume still goes out this slot",
                    checked = state.lateStartTxEnabled,
                    onCheckedChange = vm::setLateStartTxEnabled,
                    // Per spec: toggle is visible and editable regardless of license; the
                    // license gate already blocks TX downstream via AppRfState.READY.
                    enabled = true,
                )
                AutoToggleRow(
                    title = "Early decode (CQs ~3s sooner)",
                    subtitle = "Runs an extra decode pass partway through each slot.",
                    checked = state.earlyDecodeEnabled,
                    onCheckedChange = vm::setEarlyDecodeEnabled,
                    enabled = true,
                )
                AutoToggleRow(
                    title = "Send RR73 (log on send)",
                    subtitle = "OFF sends RRR and logs when the partner's 73 arrives",
                    checked = state.sendRr73,
                    onCheckedChange = vm::setSendRr73,
                    enabled = state.txEnabled,
                )
                AutoToggleRow(
                    title = "Resume CQ after QSO",
                    subtitle = "After a QSO you started by calling CQ, keep calling CQ",
                    checked = state.autoCqResumeEnabled,
                    onCheckedChange = vm::setAutoCqResumeEnabled,
                    enabled = state.txEnabled,
                )
                Text(
                    "Set TX slot (Even/Odd) on Operate when TX is enabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection("Logbook") {
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> uri?.let(vm::importAdif) }
                val lastBackupLabel = state.lastAdifBackupAtMs?.let { ms ->
                    val ageMs = System.currentTimeMillis() - ms
                    when {
                        ageMs < 60_000 -> "Last backup: just now"
                        ageMs < 3_600_000 -> "Last backup: ${ageMs / 60_000} min ago"
                        ageMs < 86_400_000 -> "Last backup: ${ageMs / 3_600_000} h ago"
                        else -> "Last backup: ${ageMs / 86_400_000} d ago"
                    }
                } ?: "Last backup: never"
                Text(lastBackupLabel, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = vm::backupAdifNow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Backup now")
                }
                OutlinedButton(
                    // .adi files have no registered MIME type; filter in the reader instead.
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Import ADIF…")
                }
                Text(
                    "ADIF auto-exports after every QSO to app-private storage " +
                        "and Documents/ft8vc (survives uninstall).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection("About") {
                Text("${AppInfo.APP_NAME} ${AppInfo.VERSION_NAME}")
                if (state.nativeLoaded) {
                    Text(
                        "Decoder library: loaded v${state.nativeVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Decoder library: FAILED — reinstall app",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.txSafetyHaltActive) {
                    Button(
                        onClick = vm::acknowledgeSafetyHalt,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Acknowledge TX safety halt")
                    }
                    Text(
                        "PTT was force-released by the watchdog. TX is gated until you acknowledge.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePicker(state: OperateUiState, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.devices.firstOrNull { it.id == state.selectedDeviceId }
    val label = selected?.let { "${it.name} (${it.typeLabel})" } ?: "No input selected"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (!state.isCapturing) expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            enabled = !state.isCapturing && !state.isTransmitting,
            label = { Text("Audio input") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text("${device.name} (${device.typeLabel})") },
                    onClick = {
                        expanded = false
                        onSelect(device.id)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnswerPolicyPicker(
    policy: AnswerPolicy,
    onSelect: (AnswerPolicy) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = answerPolicyLabel(policy),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Answer selection") },
            supportingText = {
                Text("When several stations qualify in one slot (pileup, hunt, resume)")
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AnswerPolicy.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(answerPolicyLabel(entry)) },
                    onClick = {
                        expanded = false
                        onSelect(entry)
                    },
                )
            }
        }
    }
}

@Composable
private fun AutoToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

private fun answerPolicyLabel(policy: AnswerPolicy): String = when (policy) {
    AnswerPolicy.FIRST -> "First response"
    AnswerPolicy.BEST_SNR -> "Best signal (SNR)"
    AnswerPolicy.FURTHEST -> "Furthest station (grid)"
}

private val MAX_UNANSWERED_TX_OPTIONS = listOf(0, 3, 5, 8, 10, 15)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaxUnansweredTxPicker(
    cycles: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (cycles == 0) {
        "Off (no limit)"
    } else {
        "$cycles unanswered TX cycles"
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Abandon after no reply") },
            supportingText = {
                Text("Stop TX and block auto-resume when a QSO makes no decode progress")
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MAX_UNANSWERED_TX_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(if (option == 0) "Off (no limit)" else "$option TX cycles")
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

