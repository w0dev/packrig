package net.packrig.app.ui.operate

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.packrig.app.OperateViewModel
import net.packrig.app.ui.DialFrequencyBottomSheet
import net.packrig.core.StationProfileValidator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperateScreen(
    vm: OperateViewModel,
    onNavigateToSettings: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    var showBandSheet by remember { mutableStateOf(false) }
    var showPotaSheet by remember { mutableStateOf(false) }
    var pendingBlockCall by remember { mutableStateOf<String?>(null) }
    val potaSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var parkRefDraft by remember(state.potaParkRef) { mutableStateOf(state.potaParkRef) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (granted) {
            vm.refreshDevices()
            vm.startOperating()
        }
    }

    DisposableEffect(state.isOperating) {
        if (state.isOperating) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Snackbar events surface via the app-level host in PackRigApp.
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(
                    horizontal = Ft8Compact.screenPaddingH,
                    vertical = Ft8Compact.screenPaddingV,
                ),
            verticalArrangement = Arrangement.spacedBy(Ft8Compact.sectionSpacing),
        ) {
            OperateStatusBar(
                state = state,
                inputGain = state.inputGain,
                onInputGainChange = vm::setInputGain,
                onPotaChipClick = { showPotaSheet = true },
                onBandClick = if (state.catReady) {{ showBandSheet = true }} else null,
                onHaltTx = vm::haltTx,
                onTxSlotParityChange = vm::setTxSlotParity,
                onRetryCat = vm::retryCat,
                onRetryCapture = vm::retryCapture,
                onAlignClock = vm::alignClock,
                modifier = Modifier.fillMaxWidth(),
            )
            val stationComplete = StationProfileValidator.isComplete(state.myCall, state.myGrid)
            if (!stationComplete) {
                StationProfileBanner(onOpenSettings = onNavigateToSettings)
            }
            if (state.catReady && state.rigMode != null && state.rigMode != "DATA-U") {
                RigModeWarningBanner(
                    rigMode = state.rigMode ?: "?",
                    onSetDataUsb = vm::setRigDataUsb,
                )
            }
            DecodeListPanel(
                decodes = state.decodes,
                myCall = state.myCall,
                txToneHz = state.txFreqHz,
                decodeViewMode = state.decodeViewMode,
                onDecodeViewModeChange = vm::setDecodeViewMode,
                cq73OnlyFilter = state.cq73OnlyFilter,
                onCq73OnlyFilterChange = vm::setCq73OnlyFilter,
                qsoDx = state.qsoDx,
                qsoActive = state.qsoActive,
                decodeColors = state.decodeColors,
                canAnswer = state.txEnabled && !state.qsoActive,
                canResume = state.txEnabled && !state.qsoActive,
                onClear = vm::clearDecodes,
                onAnswerCq = { row -> vm.answerCq(row) },
                onResume = { row -> vm.resumeFromDecode(row) },
                userBlockedCalls = state.userBlockedCalls,
                onBlockSender = { call ->
                    if (state.blockConfirmEnabled) pendingBlockCall = call else vm.blockStation(call)
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            OperateTxSelector(
                state = state,
                onMessageChange = vm::setOperateTxText,
                onSelectStep = vm::selectOperateTxStep,
                onResetMessage = vm::resetOperateTxText,
                modifier = Modifier.fillMaxWidth(),
            )
            OperateControls(
                state = state,
                onToggleOperate = {
                    if (state.isOperating) {
                        vm.stopOperating()
                    } else if (!hasPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        vm.startOperating()
                    }
                },
                onStartCq = { vm.startCq() },
                onEndQso = vm::stopQso,
            )
        }
    }

    pendingBlockCall?.let { call ->
        BlockConfirmDialog(
            call = call,
            onConfirm = { dontAskAgain ->
                vm.blockStation(call)
                if (dontAskAgain) vm.setBlockConfirmEnabled(false)
                pendingBlockCall = null
            },
            onDismiss = { pendingBlockCall = null },
        )
    }

    if (showBandSheet && state.catReady) {
        DialFrequencyBottomSheet(
            onDismissRequest = { showBandSheet = false },
            onSelect = vm::setRigFrequency,
            radioModelId = state.radioModelId,
        )
    }

    if (showPotaSheet) {
        ModalBottomSheet(onDismissRequest = { showPotaSheet = false }, sheetState = potaSheetState) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("POTA park reference", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = parkRefDraft,
                    onValueChange = { parkRefDraft = it.uppercase() },
                    label = { Text("Park reference") },
                    placeholder = { Text("US-3315, US-0891") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        vm.setPotaParkRef(parkRefDraft)
                        showPotaSheet = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun BlockConfirmDialog(
    call: String,
    onConfirm: (dontAskAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var dontAskAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block $call?") },
        text = {
            Column {
                Text("Their decodes will be hidden. You can unblock in Settings.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = dontAskAgain, onCheckedChange = { dontAskAgain = it })
                    Text("Don't ask me again")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dontAskAgain) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
