package net.ft8vc.app.ui.spectrum

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.ft8vc.app.OperateViewModel
import net.ft8vc.app.ui.DialFrequencySelector
import net.ft8vc.app.ui.operate.TxToneIndicator
import net.ft8vc.app.ui.operate.WaterfallPanel
import net.ft8vc.app.ui.theme.Ft8vcTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpectrumScreen(vm: OperateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    Ft8vcTheme(darkTheme = true) {
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
                ) {
                    DialFrequencySelector(
                        rigFreqHz = state.rigFreqHz,
                        enabled = state.catReady && !state.catBusy,
                        onSelect = vm::setRigFrequency,
                    )
                    Text(
                        "Tap or drag to set TX tone",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
}
