package net.packrig.app.ui.spectrum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.packrig.app.OperateViewModel
import net.packrig.app.ui.DialFrequencySelector
import net.packrig.app.ui.operate.TxToneIndicator
import net.packrig.app.ui.operate.WaterfallPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpectrumScreen(vm: OperateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

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
                    rigFreqHz = state.effectiveDialFreqHz,
                    enabled = (state.catReady && !state.catBusy) ||
                        (state.rigHasCat.not() && state.selectedRigProfileId != null),
                    onSelect = if (state.catReady) vm::setRigFrequency else vm::setManualDialFrequency,
                    radioModelId = state.radioModelId,
                )
                Text(
                    "TX ${state.txFreqHz} Hz",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            WaterfallPanel(
                vm = vm,
                version = state.waterfallVersion,
                maxFreqHz = vm.maxAudioFreqHz,
                txFreqHz = state.txFreqHz,
                onFreqChange = vm::setTxFreqHz,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}
