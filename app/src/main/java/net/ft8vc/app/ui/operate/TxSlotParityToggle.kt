package net.ft8vc.app.ui.operate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.ft8vc.core.TxSlotParity

/** Even/Odd TX period picker (:00/:30 vs :15/:45 UTC). */
@Composable
fun TxSlotParityToggle(
    selected: TxSlotParity,
    onSelect: (TxSlotParity) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TxParityChip(
            label = "Even",
            selected = selected == TxSlotParity.EVEN,
            onClick = { onSelect(TxSlotParity.EVEN) },
            enabled = enabled,
        )
        TxParityChip(
            label = "Odd",
            selected = selected == TxSlotParity.ODD,
            onClick = { onSelect(TxSlotParity.ODD) },
            enabled = enabled,
        )
    }
}

@Composable
private fun TxParityChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        modifier = Modifier.height(26.dp),
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

@Composable
fun TxSlotParityLabel(
    parity: TxSlotParity,
    isOurSlot: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = if (isOurSlot) "TX ${parity.label.lowercase()}▸" else "TX ${parity.label.lowercase()}",
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        color = if (isOurSlot) {
            net.ft8vc.app.ui.theme.Ft8Amber
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
