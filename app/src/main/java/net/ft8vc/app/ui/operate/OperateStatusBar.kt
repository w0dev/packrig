package net.ft8vc.app.ui.operate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.ft8vc.app.OperateUiState
import net.ft8vc.app.ui.theme.Ft8Amber
import net.ft8vc.app.ui.theme.Ft8Green

@Composable
fun OperateStatusBar(state: OperateUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                state.rigFreqHz?.let { "%.3f MHz".format(it / 1_000_000.0) } ?: "— MHz",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                state.rigMode ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = if (state.rigMode == "DATA-U") Ft8Green else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${state.utcClock} UTC · slot ${state.slotIndex + 1}/4 · ${state.secondsToNextSlot}s",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when {
                    state.isTransmitting -> "TX"
                    state.isTxSlot && state.qsoActive -> "TX slot"
                    state.qsoActive -> "RX slot"
                    state.isOperating -> "RX"
                    else -> "Idle"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (state.isTransmitting || state.isTxSlot) Ft8Amber else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            state.qsoState ?: "${state.myCall} ${state.myGrid}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
fun SlotClock(state: OperateUiState) {
    Text(
        "${state.utcClock} UTC · ${state.secondsToNextSlot}s to slot",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
    )
}
