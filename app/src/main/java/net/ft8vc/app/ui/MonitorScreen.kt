package net.ft8vc.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.ft8vc.app.DecodeRow
import net.ft8vc.app.MonitorViewModel
import net.ft8vc.app.ui.theme.Ft8Amber
import net.ft8vc.app.ui.theme.Ft8Green
import net.ft8vc.app.ui.theme.Ft8Red
import net.ft8vc.core.AppInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(vm: MonitorViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
            vm.start()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            AppInfo.APP_NAME,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Monitor - ${state.sampleRateHz} Hz",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { vm.refreshDevices() },
                        enabled = !state.isCapturing && !state.isTransmitting,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh devices")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DevicePicker(
                state = state,
                onSelect = vm::selectDevice,
            )

            LevelMeter(levelDbfs = state.levelDbfs, clip = state.clip)

            BrightnessSlider(onOffsetChange = { vm.waterfall.floorOffsetDb = it })

            WaterfallView(
                vm = vm,
                version = state.waterfallVersion,
                maxFreqHz = vm.maxAudioFreqHz,
                txFreqHz = state.txFreqHz,
                onFreqChange = vm::setTxFreqHz,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            DecodeList(
                decodes = state.decodes,
                onClear = vm::clearDecodes,
                onAnswerCq = vm::answerCq,
                canAnswer = state.txEnabled && !state.qsoActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            TxPanel(
                state = state,
                onTxEnabledChange = vm::setTxEnabled,
                onMessageChange = vm::setTxMessage,
                onFreqChange = vm::setTxFreqHz,
                onTransmit = vm::transmitNextSlot,
            )

            if (state.catReady) {
                RigPanel(
                    state = state,
                    onReadRig = vm::readRig,
                    onSetFrequency = vm::setRigFrequency,
                    onSetDataUsb = vm::setRigDataUsb,
                )
            }

            if (state.txEnabled) {
                QsoPanel(
                    state = state,
                    onMyCallChange = vm::setMyCall,
                    onMyGridChange = vm::setMyGrid,
                    onStartCq = vm::startCq,
                    onStopQso = vm::stopQso,
                )
            }

            CaptureButton(
                isCapturing = state.isCapturing,
                isTransmitting = state.isTransmitting,
                onClick = {
                    when {
                        state.isCapturing || state.isTransmitting -> vm.stop()
                        hasPermission -> vm.start()
                        else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun TxPanel(
    state: net.ft8vc.app.MonitorUiState,
    onTxEnabledChange: (Boolean) -> Unit,
    onMessageChange: (String) -> Unit,
    onFreqChange: (Int) -> Unit,
    onTransmit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Transmit",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        !state.txEnabled -> "Disabled by default"
                        state.pttReady -> "Digirig PTT ready — dummy load first"
                        else -> "Licensed use only — dummy load first"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.txEnabled && state.pttReady) Ft8Green else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.txEnabled,
                onCheckedChange = onTxEnabledChange,
                enabled = !state.isTransmitting,
            )
        }
        if (state.txEnabled) {
            OutlinedTextField(
                value = state.txMessage,
                onValueChange = onMessageChange,
                label = { Text("Message") },
                enabled = !state.isTransmitting,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${state.txFreqHz} Hz",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Slider(
                    value = state.txFreqHz.toFloat(),
                    onValueChange = { onFreqChange(it.toInt()) },
                    valueRange = 300f..3000f,
                    enabled = !state.isTransmitting,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = onTransmit,
                enabled = !state.isCapturing && !state.isTransmitting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ft8Amber,
                    contentColor = Color.Black,
                ),
            ) {
                Text("Transmit next slot", fontWeight = FontWeight.SemiBold)
            }
            state.txStatus?.let { status ->
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** A common FT8/data dial frequency (VFO-A) for a band. */
private data class DataBand(val label: String, val hz: Long) {
    val freqMhz: String get() = "%.3f MHz".format(hz / 1_000_000.0)
    val menuText: String get() = "$label  ·  $freqMhz"
}

/** Common FT8 dial frequencies (VFO-A) by band. */
private val Ft8DialBands = listOf(
    DataBand("160m", 1_840_000L),
    DataBand("80m", 3_573_000L),
    DataBand("60m", 5_357_000L),
    DataBand("40m", 7_074_000L),
    DataBand("30m", 10_136_000L),
    DataBand("20m", 14_074_000L),
    DataBand("17m", 18_100_000L),
    DataBand("15m", 21_074_000L),
    DataBand("12m", 24_915_000L),
    DataBand("10m", 28_074_000L),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RigPanel(
    state: net.ft8vc.app.MonitorUiState,
    onReadRig: () -> Unit,
    onSetFrequency: (Long) -> Unit,
    onSetDataUsb: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val matchedBand = Ft8DialBands.firstOrNull { it.hz == state.rigFreqHz }
    val fieldText = matchedBand?.menuText
        ?: state.rigFreqHz?.let { "%.6f MHz".format(it / 1_000_000.0) }
        ?: "Select band"

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "FT-891 CAT",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.rigMode ?: "?",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (state.rigMode == "DATA-U") Ft8Green else MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = onReadRig, enabled = !state.catBusy) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Read rig")
                }
            }
        }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (!state.catBusy) expanded = it },
        ) {
            OutlinedTextField(
                value = fieldText,
                onValueChange = {},
                readOnly = true,
                enabled = !state.catBusy,
                label = { Text("Band / dial frequency") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Ft8DialBands.forEach { band ->
                    DropdownMenuItem(
                        text = { Text(band.menuText) },
                        onClick = {
                            expanded = false
                            onSetFrequency(band.hz)
                        },
                    )
                }
            }
        }
        Button(
            onClick = onSetDataUsb,
            enabled = !state.catBusy && state.rigMode != "DATA-U",
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Ft8Green,
                contentColor = Color.Black,
            ),
        ) {
            Text("Set DATA-U (FT8 mode)", fontWeight = FontWeight.SemiBold)
        }
        state.catStatus?.let { status ->
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun QsoPanel(
    state: net.ft8vc.app.MonitorUiState,
    onMyCallChange: (String) -> Unit,
    onMyGridChange: (String) -> Unit,
    onStartCq: () -> Unit,
    onStopQso: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Auto QSO",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.myCall,
                onValueChange = onMyCallChange,
                label = { Text("My call") },
                enabled = !state.qsoActive,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.myGrid,
                onValueChange = onMyGridChange,
                label = { Text("Grid") },
                enabled = !state.qsoActive,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        if (state.qsoActive) {
            Button(
                onClick = onStopQso,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ft8Red,
                    contentColor = Color.Black,
                ),
            ) {
                Text("Stop QSO", fontWeight = FontWeight.SemiBold)
            }
        } else {
            Button(
                onClick = onStartCq,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ft8Green,
                    contentColor = Color.Black,
                ),
            ) {
                Text("Start CQ", fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Or tap a CQ decode to answer it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.qsoState?.let { label ->
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePicker(
    state: net.ft8vc.app.MonitorUiState,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.devices.firstOrNull { it.id == state.selectedDeviceId }
    val label = selected?.let { "${it.name} (${it.typeLabel})" } ?: "No input selected"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (!state.isCapturing && !state.isTransmitting) expanded = it },
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            enabled = !state.isCapturing && !state.isTransmitting,
            label = { Text("Audio input") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (state.devices.isEmpty()) {
                DropdownMenuItem(text = { Text("No inputs found") }, onClick = { expanded = false })
            }
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

@Composable
private fun LevelMeter(levelDbfs: Float, clip: Boolean) {
    val fraction = ((levelDbfs + 80f) / 80f).coerceIn(0f, 1f)
    val barColor = when {
        clip || fraction > 0.95f -> Ft8Red
        fraction > 0.8f -> Ft8Amber
        else -> Ft8Green
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Input level",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (clip) "CLIP  ${"%.0f".format(levelDbfs)} dBFS" else "${"%.0f".format(levelDbfs)} dBFS",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (clip) Ft8Red else MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(barColor),
            )
        }
    }
}

@Composable
private fun BrightnessSlider(onOffsetChange: (Float) -> Unit) {
    // Slider right = brighter (lower floor offset). Maps 0..1 -> +24..-8 dB.
    var pos by remember { mutableStateOf(0.6f) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Brightness",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = pos,
            onValueChange = {
                pos = it
                onOffsetChange(24f - it * 32f)
            },
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
    }
}

@Composable
private fun WaterfallView(
    vm: MonitorViewModel,
    version: Long,
    maxFreqHz: Int,
    txFreqHz: Int,
    onFreqChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Map a horizontal pixel position to an audio frequency in Hz.
    fun freqForX(x: Float, widthPx: Int): Int =
        if (widthPx <= 0) txFreqHz else (x / widthPx * maxFreqHz).toInt()

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .pointerInput(maxFreqHz) {
                    detectTapGestures { offset -> onFreqChange(freqForX(offset.x, size.width)) }
                }
                .pointerInput(maxFreqHz) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onFreqChange(freqForX(change.position.x, size.width))
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Reading `version` above forces recomposition; snapshot pulls latest pixels.
                @Suppress("UNUSED_EXPRESSION") version
                val image = vm.waterfall.snapshot()
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                )

                // TX frequency marker.
                if (maxFreqHz > 0) {
                    val markerX = (txFreqHz.toFloat() / maxFreqHz * size.width)
                        .coerceIn(0f, size.width)
                    drawLine(
                        color = Ft8Amber,
                        start = Offset(markerX, 0f),
                        end = Offset(markerX, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
        }
        FrequencyAxis(maxFreqHz = maxFreqHz)
        Text(
            "Tap the waterfall to set TX · $txFreqHz Hz",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FrequencyAxis(maxFreqHz: Int) {
    val ticks = listOf(0, maxFreqHz / 4, maxFreqHz / 2, maxFreqHz * 3 / 4, maxFreqHz)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ticks.forEach {
            Text(
                "$it",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DecodeList(
    decodes: List<DecodeRow>,
    onClear: () -> Unit,
    onAnswerCq: (DecodeRow) -> Unit,
    canAnswer: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Decodes (${decodes.size})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onClear, enabled = decodes.isNotEmpty()) {
                Icon(Icons.Filled.Delete, contentDescription = "Clear decodes")
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (decodes.isEmpty()) {
                Text(
                    "No decodes yet. Decoding runs once per 15-second UTC slot.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    items(decodes) { row ->
                        DecodeRowView(
                            row = row,
                            onClick = if (canAnswer && row.isCq) {
                                { onAnswerCq(row) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DecodeRowView(row: DecodeRow, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(row.timeUtc, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("%+3d".format(row.snr), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("%4d".format(row.freqHz), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = row.message,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = if (row.isCq) Ft8Green else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CaptureButton(
    isCapturing: Boolean,
    isTransmitting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = isCapturing || isTransmitting
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Ft8Red else Ft8Green,
            contentColor = Color.Black,
        ),
    ) {
        Icon(
            imageVector = if (active) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = null,
        )
        Text(
            text = when {
                isTransmitting -> "  Stop TX"
                isCapturing -> "  Stop monitoring"
                else -> "  Start monitoring"
            },
            fontWeight = FontWeight.SemiBold,
        )
    }
}
