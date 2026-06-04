package net.ft8vc.app.ui.operate

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.ft8vc.app.OperateViewModel
import net.ft8vc.app.ui.Ft8DialBands
import net.ft8vc.app.ui.theme.Ft8vcTheme
import net.ft8vc.core.AppInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperateScreen(vm: OperateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val snackbarHostState = remember { SnackbarHostState() }
    var showBandSheet by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

    LaunchedEffect(state.snackbarMessage, state.error) {
        val msg = state.snackbarMessage ?: state.error
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            vm.clearSnackbar()
        }
    }

    LaunchedEffect(state.qsoCompleteBanner) {
        state.qsoCompleteBanner?.let {
            snackbarHostState.showSnackbar(it)
            kotlinx.coroutines.delay(5000)
            vm.clearQsoBanner()
        }
    }

    Ft8vcTheme(darkTheme = true) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OperateStatusBar(
                    state = state,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.catReady) {
                    TextButton(onClick = { showBandSheet = true }) {
                        Text("Change band")
                    }
                }
                WaterfallPanel(
                    vm = vm,
                    version = state.waterfallVersion,
                    maxFreqHz = vm.maxAudioFreqHz,
                    txFreqHz = state.txFreqHz,
                    onFreqChange = vm::setTxFreqHz,
                    modifier = Modifier.fillMaxWidth().weight(1.2f),
                )
                DecodeListPanel(
                    decodes = state.decodes,
                    cq73OnlyFilter = state.cq73OnlyFilter,
                    onCq73OnlyFilterChange = vm::setCq73OnlyFilter,
                    qsoDx = state.qsoDx,
                    qsoActive = state.qsoActive,
                    canAnswer = state.txEnabled && !state.qsoActive,
                    canResume = state.txEnabled && !state.qsoActive,
                    onClear = vm::clearDecodes,
                    onAnswerCq = vm::answerCq,
                    onResume = vm::resumeFromDecode,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                InputLevelControl(
                    inputGain = state.inputGain,
                    levelDbfs = state.levelDbfs,
                    clip = state.clip,
                    onInputGainChange = vm::setInputGain,
                )
                OperateControls(
                    state = state,
                    onAutoSeqChange = vm::setAutoSeqEnabled,
                    onAnswerWhenCalledChange = vm::setAnswerWhenCalledEnabled,
                    onToggleOperate = {
                        if (state.isOperating) {
                            vm.stopOperating()
                        } else if (!state.licenseAcknowledged) {
                            showLicenseDialog = true
                        } else if (!hasPermission) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            vm.startOperating()
                        }
                    },
                    onStartCq = vm::startCq,
                    onStopQso = vm::stopQso,
                )
                Text(
                    "${AppInfo.APP_NAME} ${AppInfo.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
    }

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text("Amateur radio license required") },
            text = {
                Text(
                    "Transmitting requires a valid amateur radio license. Test TX into a " +
                        "dummy load first. You are responsible for lawful operation.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.acknowledgeLicense()
                    showLicenseDialog = false
                    if (!hasPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        vm.startOperating()
                    }
                }) { Text("I understand") }
            },
            dismissButton = {
                TextButton(onClick = { showLicenseDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showBandSheet && state.catReady) {
        ModalBottomSheet(onDismissRequest = { showBandSheet = false }, sheetState = sheetState) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("FT8 dial frequency", style = MaterialTheme.typography.titleMedium)
                Ft8DialBands.forEach { band ->
                    TextButton(
                        onClick = {
                            vm.setRigFrequency(band.hz)
                            showBandSheet = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(band.menuText)
                    }
                }
            }
        }
    }
}
