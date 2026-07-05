package net.ft8vc.app.ui.spectrum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.ft8vc.app.OperateViewModel
import net.ft8vc.app.ui.DialFrequencySelector
import net.ft8vc.app.ui.operate.TxToneIndicator
import net.ft8vc.app.ui.operate.WaterfallPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpectrumScreen(vm: OperateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val markers = remember(state.decodes) { SpectrumMarkers.forLatestSlot(state.decodes) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spectrum") },
                actions = {
                    TxToneIndicator(
                        txFreqHz = state.txFreqHz,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialFrequencySelector(
                    rigFreqHz = state.rigFreqHz,
                    enabled = state.catReady && !state.catBusy,
                    onSelect = vm::setRigFrequency,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "TX ${state.txFreqHz} Hz",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Text(
                        "Labels",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Checkbox(
                        checked = state.spectrumMarkersEnabled,
                        onCheckedChange = vm::setSpectrumMarkersEnabled,
                    )
                }
            }
            WaterfallPanel(
                vm = vm,
                version = state.waterfallVersion,
                maxFreqHz = vm.maxAudioFreqHz,
                txFreqHz = state.txFreqHz,
                onFreqChange = vm::setTxFreqHz,
                markers = markers,
                showMarkers = state.spectrumMarkersEnabled,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}
