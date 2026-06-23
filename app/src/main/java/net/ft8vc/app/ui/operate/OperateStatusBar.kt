package net.ft8vc.app.ui.operate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.ft8vc.app.OperateUiState
import net.ft8vc.app.ui.WithTooltip
import net.ft8vc.app.ui.theme.Ft8Amber
import net.ft8vc.app.ui.theme.Ft8Green
import net.ft8vc.app.ui.theme.Ft8Red
import net.ft8vc.core.ActivationProfile
import net.ft8vc.core.TxSlotParity

@Composable
fun OperateStatusBar(
    state: OperateUiState,
    inputGain: Float,
    onInputGainChange: (Float) -> Unit,
    onPotaChipClick: () -> Unit = {},
    onBandClick: (() -> Unit)? = null,
    onHaltTx: () -> Unit = {},
    onTxSlotParityChange: (TxSlotParity) -> Unit = {},
    onRetryCat: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var inputGainExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Phase 6: persistent reliability chips at the top of the header.
        if (state.catUnreachable || state.decodeFailureRecent || state.digirigDisconnected || state.txSafetyHaltActive) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.catUnreachable) {
                    CompactChip(
                        text = "CAT unreachable — tap to retry",
                        modifier = Modifier.clickable(onClick = onRetryCat),
                    )
                }
                if (state.digirigDisconnected) {
                    CompactChip(text = "Digirig disconnected — RX only")
                }
                if (state.txSafetyHaltActive) {
                    CompactChip(text = "TX safety halt — see Settings")
                }
                if (state.decodeFailureRecent && state.decodeFailureCount > 0) {
                    CompactChip(text = "Decodes dropped: ${state.decodeFailureCount}")
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val freqLabel = state.rigFreqHz?.let { "%.3f".format(it / 1_000_000.0) } ?: "—"
            Text(
                text = "$freqLabel MHz",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = if (onBandClick != null) {
                    Modifier.clickable(onClick = onBandClick)
                } else {
                    Modifier
                },
            )
            Text(
                text = state.rigMode ?: "—",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (state.rigMode == "DATA-U") Ft8Green else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            CompactChip(text = "TX ${state.txFreqHz}")
            if (state.potaModeEnabled) {
                val parkLabel = state.potaParkRef.ifBlank { "POTA?" }
                val validPark = state.potaParkRef.isNotBlank() &&
                    ActivationProfile.isValidParkRef(state.potaParkRef)
                WithTooltip(text = "Tap to edit POTA park reference") {
                    Surface(
                        modifier = Modifier
                            .widthIn(max = Ft8Compact.potaChipMaxWidth)
                            .clickable(onClick = onPotaChipClick),
                        shape = Ft8Compact.chipShape,
                        color = if (validPark) Ft8Green.copy(alpha = 0.2f) else Ft8Amber.copy(alpha = 0.2f),
                    ) {
                        Text(
                            text = parkLabel,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = if (validPark) Ft8Green else Ft8Amber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (state.isTransmitting) {
                WithTooltip(text = "Cancel the in-progress transmission") {
                    Button(
                        onClick = onHaltTx,
                        modifier = Modifier.height(Ft8Compact.tapTargetPrimary),
                        contentPadding = Ft8Compact.buttonPadding,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Ft8Red,
                            contentColor = Color.Black,
                        ),
                    ) {
                        Text(
                            text = "HALT",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val slotProgress = 1f - (state.secondsToNextSlot.coerceIn(0, 15) / 15f)
            LinearProgressIndicator(
                progress = { slotProgress },
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp),
                color = if (state.isTransmitting || state.isTxSlot) Ft8Amber else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                text = slotClockLabel(state),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            when {
                state.isTransmitting -> {
                    Text(
                        text = "TX",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Ft8Amber,
                    )
                }
                state.qsoActive && state.activeTxSlotParity != null -> {
                    TxSlotParityLabel(
                        parity = state.activeTxSlotParity,
                        isOurSlot = state.isTxSlot,
                    )
                }
                state.txEnabled -> {
                    TxSlotParityToggle(
                        selected = state.txSlotParity,
                        onSelect = onTxSlotParityChange,
                        enabled = !state.qsoActive,
                    )
                }
                else -> {
                    Text(
                        text = rxTxLabel(state),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    state.isTransmitting -> state.txStatus ?: "Transmitting…"
                    else -> state.qsoState ?: "${state.myCall} ${state.myGrid}"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = if (state.isTransmitting) Ft8Red else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { inputGainExpanded = !inputGainExpanded },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Input gain",
                    tint = when {
                        inputGainExpanded -> MaterialTheme.colorScheme.primary
                        state.clip -> Ft8Red
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            if (state.isOperating) {
                CompactLevelBar(
                    levelDbfs = state.levelDbfs,
                    clip = state.clip,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        AnimatedVisibility(
            visible = inputGainExpanded,
            enter = expandVertically(expandFrom = Alignment.Top),
            exit = shrinkVertically(shrinkTowards = Alignment.Top),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "In",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = inputGain,
                    onValueChange = onInputGainChange,
                    onValueChangeFinished = {
                        scope.launch {
                            delay(400)
                            inputGainExpanded = false
                        }
                    },
                    valueRange = OperateUiState.INPUT_GAIN_MIN..1f,
                    modifier = Modifier
                        .weight(1f)
                        .height(24.dp),
                )
                Text(
                    text = "${(inputGain * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (state.clip) Ft8Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun slotClockLabel(state: OperateUiState): String {
    val slotHint = if (state.txEnabled && !state.qsoActive && !state.isTxSlot) {
        " · TX ${state.secondsUntilOurTxSlot}s"
    } else {
        " · ${state.secondsToNextSlot}s"
    }
    return "${state.utcClock}$slotHint"
}

private fun rxTxLabel(state: OperateUiState): String = when {
    state.isTransmitting -> "TX"
    state.isTxSlot && state.qsoActive -> "TX▸"
    state.qsoActive -> "RX▸"
    state.isOperating -> "RX"
    else -> "—"
}

@Composable
fun SlotClock(state: OperateUiState) {
    Text(
        "${state.utcClock} UTC · ${state.secondsToNextSlot}s to slot",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
    )
}
