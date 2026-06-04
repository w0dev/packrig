package net.ft8vc.app.ui.operate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.ft8vc.app.DecodeRow
import net.ft8vc.app.ui.theme.Ft8Amber
import net.ft8vc.app.ui.theme.Ft8Green
import net.ft8vc.core.MonitorDecodeFilter

@Composable
fun DecodeListPanel(
    decodes: List<DecodeRow>,
    cq73OnlyFilter: Boolean,
    onCq73OnlyFilterChange: (Boolean) -> Unit,
    qsoDx: String?,
    qsoActive: Boolean,
    canAnswer: Boolean,
    canResume: Boolean,
    onClear: () -> Unit,
    onAnswerCq: (DecodeRow) -> Unit,
    onResume: (DecodeRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleDecodes = decodes.filter { row ->
        MonitorDecodeFilter.visible(row.message, cq73OnlyFilter, qsoDx, qsoActive)
    }
    val decodeCountLabel = if (cq73OnlyFilter) {
        "Decodes (${visibleDecodes.size}/${decodes.size})"
    } else {
        "Decodes (${decodes.size})"
    }

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                decodeCountLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = cq73OnlyFilter,
                    onClick = { onCq73OnlyFilterChange(!cq73OnlyFilter) },
                    label = { Text("CQ/73") },
                )
                IconButton(onClick = onClear, enabled = decodes.isNotEmpty()) {
                    Icon(Icons.Filled.Delete, contentDescription = "Clear decodes")
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (visibleDecodes.isEmpty()) {
                Text(
                    if (decodes.isEmpty()) {
                        "No decodes yet. Decoding runs once per 15-second UTC slot."
                    } else {
                        "No CQ or 73 decodes match the filter."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    items(visibleDecodes) { row ->
                        DecodeRowItem(
                            row = row,
                            qsoDx = qsoDx,
                            qsoActive = qsoActive,
                            onClick = when {
                                canAnswer && row.isCq -> ({ onAnswerCq(row) })
                                canResume && row.isToMe -> ({ onResume(row) })
                                else -> null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DecodeRowItem(
    row: DecodeRow,
    qsoDx: String?,
    qsoActive: Boolean,
    onClick: (() -> Unit)?,
) {
    val isPartner = qsoDx != null && row.message.contains(qsoDx)
    val dimmed = qsoActive && !row.isCq && !isPartner
    val textColor = when {
        row.isCq -> Ft8Green
        row.isToMe && !qsoActive -> Ft8Amber
        isPartner -> MaterialTheme.colorScheme.primary
        dimmed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.onSurface
    }
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
            fontWeight = if (isPartner) FontWeight.Bold else FontWeight.SemiBold,
            color = textColor,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}
