package net.packrig.app.ui.operate

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.packrig.app.OperateUiState
import net.packrig.app.ui.WithTooltip
import net.packrig.app.ui.theme.Ft8Amber
import net.packrig.core.ActivationProfile
import net.packrig.core.OperateTxOptions
import net.packrig.core.QsoTxStep

/** Compact TX message row — field + step picker on one line. */
@Composable
fun OperateTxSelector(
    state: OperateUiState,
    onMessageChange: (String) -> Unit,
    onSelectStep: (QsoTxStep) -> Unit,
    onResetMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isOperating) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!state.txEnabled) {
            Text(
                "RX only — enable TX in Settings.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        val customMode = state.operateTxStep == QsoTxStep.Custom
        val cqModifier = if (state.potaModeEnabled) ActivationProfile.cqModifier(true) else null
        val menuEntries = remember(
            state.qsoActive,
            state.myCall,
            state.myGrid,
            state.potaModeEnabled,
            state.operateTxForm,
        ) {
            OperateTxOptions.menuEntries(
                qsoActive = state.qsoActive,
                myCall = state.myCall,
                myGrid = state.myGrid,
                cqModifier = cqModifier,
                form = state.operateTxForm,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactTxField(
                value = state.operateTxText,
                readOnly = !customMode,
                enabled = !state.isTransmitting,
                onValueChange = onMessageChange,
                modifier = Modifier.weight(1f),
            )
            TxStepMenuButton(
                menuEntries = menuEntries,
                qsoActive = state.qsoActive,
                enabled = !state.isTransmitting,
                onSelectStep = onSelectStep,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = txStatusHint(state, customMode),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (state.isTransmitting || state.isTxSlot) {
                    Ft8Amber
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (state.operateTxEdited || customMode) {
                WithTooltip(text = "Reset TX message to the auto-sequenced value") {
                    TextButton(
                        onClick = onResetMessage,
                        enabled = !state.isTransmitting,
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text("Auto", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactTxField(
    value: String,
    readOnly: Boolean,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = MaterialTheme.colorScheme.outline
    val textColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    }
    Surface(
        modifier = modifier.heightIn(min = Ft8Compact.tapTargetPrimary),
        shape = Ft8Compact.chipShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, borderColor),
    ) {
        if (readOnly) {
            Text(
                text = value,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = textColor,
                maxLines = 1,
            )
        } else {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = textColor,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TxStepMenuButton(
    menuEntries: List<OperateTxOptions.MenuEntry>,
    qsoActive: Boolean,
    enabled: Boolean,
    onSelectStep: (QsoTxStep) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (qsoActive) "Msg ▾" else "Text…"

    Box {
        OutlinedButton(
            onClick = {
                if (qsoActive) {
                    expanded = true
                } else {
                    onSelectStep(QsoTxStep.Custom)
                }
            },
            enabled = enabled,
            modifier = Modifier.height(Ft8Compact.tapTargetPrimary),
            contentPadding = Ft8Compact.buttonPadding,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            if (qsoActive) {
                Icon(Icons.Default.ArrowDropDown, contentDescription = "TX message options")
            }
        }
        if (qsoActive) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 260.dp),
            ) {
                // DropdownMenu scrolls its content internally; wrapping items in
                // Column(verticalScroll) here crashes with infinite-height constraints.
                menuEntries.forEach { entry ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(entry.label, fontWeight = FontWeight.SemiBold)
                                entry.preview?.let { preview ->
                                    Text(
                                        preview,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelectStep(entry.step)
                        },
                    )
                }
            }
        }
    }
}

private fun txStatusHint(state: OperateUiState, customMode: Boolean): String = when {
    state.isTransmitting -> "Transmitting…"
    state.qsoActive && state.isTxSlot -> "TX this slot"
    state.qsoActive -> state.qsoState ?: "QSO active"
    customMode -> "Free text"
    else -> "Start CQ to transmit"
}
