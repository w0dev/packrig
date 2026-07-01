package net.ft8vc.app.ui.log

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ft8vc.app.LogViewModel
import net.ft8vc.data.Activation
import net.ft8vc.data.Activations
import net.ft8vc.data.adif.AdifExportException
import net.ft8vc.data.model.QsoContact
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LogScreen(vm: LogViewModel = viewModel()) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val activations by vm.activations.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showActivations by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showSetParksDialog by remember { mutableStateOf(false) }
    var parksDraft by remember { mutableStateOf("") }
    val selectionActive = selectedIds.isNotEmpty()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    if (selectionActive) {
                        Text("${selectedIds.size} selected")
                    } else {
                        Column {
                            Text("Log (${contacts.size})")
                            Text(
                                "Share exports validated ADIF 3.1",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (selectionActive) {
                        TextButton(onClick = {
                            val days = contacts.filter { it.id in selectedIds }
                                .map { Activations.utcDateOf(it.utcMillis) }
                                .toSet()
                            selectedIds = contacts
                                .filter { Activations.utcDateOf(it.utcMillis) in days }
                                .map { it.id }
                                .toSet()
                        }) { Text("Day") }
                        IconButton(onClick = {
                            parksDraft = ""
                            showSetParksDialog = true
                        }) {
                            Icon(Icons.Filled.EditNote, contentDescription = "Set parks")
                        }
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                        }
                    } else {
                        IconButton(
                            onClick = { showActivations = true },
                            enabled = activations.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.Park, contentDescription = "POTA activation export")
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        shareAdif(context, "ft8vc_export.adi", vm.exportAdif())
                                    } catch (e: AdifExportException) {
                                        snackbarHostState.showSnackbar(e.message ?: "ADIF export failed")
                                    }
                                }
                            },
                            enabled = contacts.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = "Export ADIF")
                        }
                        IconButton(onClick = { showClearConfirm = true }, enabled = contacts.isNotEmpty()) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear log")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (contacts.isEmpty()) {
                Text(
                    "Complete a QSO on Operate to populate your log.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(contacts, key = { it.id }) { contact ->
                        LogRow(
                            contact = contact,
                            selected = contact.id in selectedIds,
                            onTap = {
                                if (selectionActive) {
                                    selectedIds =
                                        if (contact.id in selectedIds) selectedIds - contact.id
                                        else selectedIds + contact.id
                                }
                            },
                            onLongPress = { selectedIds = selectedIds + contact.id },
                        )
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear log?") },
            text = { Text("Remove all ${contacts.size} logged QSOs from the device.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAll()
                    showClearConfirm = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showSetParksDialog) {
        AlertDialog(
            onDismissRequest = { showSetParksDialog = false },
            title = { Text("Set parks on ${selectedIds.size} QSOs") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = parksDraft,
                        onValueChange = { parksDraft = it.uppercase() },
                        label = { Text("Park reference(s)") },
                        placeholder = { Text("US-3315, US-0891") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Replaces parks on all selected QSOs. Leave empty to clear (home QSOs).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setParksOnContacts(selectedIds.toList(), parksDraft) { ok ->
                        scope.launch {
                            if (ok) {
                                showSetParksDialog = false
                                selectedIds = emptySet()
                            } else {
                                snackbarHostState.showSnackbar(
                                    "Invalid park list — use refs like US-3315, comma-separated",
                                )
                            }
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSetParksDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showActivations) {
        ModalBottomSheet(onDismissRequest = { showActivations = false }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("POTA activations", style = MaterialTheme.typography.titleMedium)
                Text(
                    "One file per park per UTC day — upload each to pota.app",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn {
                    items(activations, key = { "${it.parkRef}:${it.utcDate}" }) { activation ->
                        val dateLabel = activation.utcDate.let {
                            "${it.take(4)}-${it.substring(4, 6)}-${it.takeLast(2)}"
                        }
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "${activation.parkRef} · $dateLabel · ${activation.qsoCount} QSOs",
                                fontFamily = FontFamily.Monospace,
                            )
                            IconButton(onClick = {
                                scope.launch {
                                    try {
                                        val (name, adif) = vm.exportActivation(activation)
                                        shareAdif(context, name, adif)
                                    } catch (e: AdifExportException) {
                                        snackbarHostState.showSnackbar(e.message ?: "Export failed")
                                    }
                                }
                            }) {
                                Icon(Icons.Filled.Share, contentDescription = "Share ${activation.parkRef}")
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun shareAdif(context: android.content.Context, fileName: String, adif: String) {
    val uri = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, fileName)
        file.writeText(adif)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    context.startActivity(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(
    contact: QsoContact,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val fmt = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RowBetween(contact.dxCall, fmt.format(Date(contact.utcMillis)) + " UTC")
        Text(
            buildString {
                contact.dxGrid?.let { append("$it · ") }
                contact.band?.let { append("$it · ") }
                contact.potaParkRefs?.let { append("$it · ") }
                append("RST ${contact.rstRcvd ?: "?"} / ${contact.rstSent ?: "?"}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RowBetween(left: String, right: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(left, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(
            right,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
