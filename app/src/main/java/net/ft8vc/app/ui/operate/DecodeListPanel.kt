package net.ft8vc.app.ui.operate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.ft8vc.app.DecodeRow
import net.ft8vc.app.ui.WithTooltip
import net.ft8vc.app.ui.theme.Ft8Amber
import net.ft8vc.app.ui.theme.Ft8Green
import net.ft8vc.core.DecodeDistance
import net.ft8vc.core.DecodePrefix
import net.ft8vc.core.DecodeViewMode
import net.ft8vc.core.MonitorDecodeFilter

@Composable
fun DecodeListPanel(
    decodes: List<DecodeRow>,
    myCall: String,
    txToneHz: Int,
    decodeViewMode: DecodeViewMode,
    onDecodeViewModeChange: (DecodeViewMode) -> Unit,
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
        MonitorDecodeFilter.visibleForDisplay(
            message = row.message,
            isCq = row.isCq,
            myCall = myCall,
            freqHz = row.freqHz,
            txToneHz = txToneHz,
            viewMode = decodeViewMode,
            cq73OnlyFilter = cq73OnlyFilter,
            qsoDx = qsoDx,
            qsoActive = qsoActive,
        )
    }
    val filterActive = decodeViewMode == DecodeViewMode.OPERATE ||
        (decodeViewMode == DecodeViewMode.ALL && cq73OnlyFilter)
    val decodeCountLabel = if (filterActive) {
        "${visibleDecodes.size}/${decodes.size}"
    } else {
        "${decodes.size}"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 0.dp, top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WithTooltip(text = "Show every decode in the receive passband") {
                    CompactFilterChip(
                        selected = decodeViewMode == DecodeViewMode.ALL,
                        onClick = { onDecodeViewModeChange(DecodeViewMode.ALL) },
                        label = "Band",
                    )
                }
                WithTooltip(text = "Hide chatter — show CQs, traffic to you, partner, and signals near TX tone") {
                    CompactFilterChip(
                        selected = decodeViewMode == DecodeViewMode.OPERATE,
                        onClick = { onDecodeViewModeChange(DecodeViewMode.OPERATE) },
                        label = "Focus",
                    )
                }
                if (decodeViewMode == DecodeViewMode.ALL) {
                    WithTooltip(text = "Show only CQ calls and sign-offs (73 / RR73)") {
                        CompactFilterChip(
                            selected = cq73OnlyFilter,
                            onClick = { onCq73OnlyFilterChange(!cq73OnlyFilter) },
                            label = "CQ·73",
                        )
                    }
                }
                Text(
                    text = decodeCountLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onClear,
                    enabled = decodes.isNotEmpty(),
                    modifier = Modifier
                        .height(36.dp)
                        .padding(end = 4.dp),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Clear decodes",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (visibleDecodes.isEmpty()) {
                Text(
                    text = emptyDecodeMessage(
                        total = decodes.size,
                        decodeViewMode = decodeViewMode,
                        cq73OnlyFilter = cq73OnlyFilter,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                ) {
                    items(visibleDecodes, key = { it.id }) { row ->
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
private fun CompactFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
            )
        },
        modifier = Modifier.height(28.dp),
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

private fun emptyDecodeMessage(
    total: Int,
    decodeViewMode: DecodeViewMode,
    cq73OnlyFilter: Boolean,
): String = when {
    total == 0 -> "Waiting for decodes…"
    decodeViewMode == DecodeViewMode.OPERATE -> "No focus traffic — try Band."
    cq73OnlyFilter -> "No CQ/73 matches."
    else -> "No decodes match filters."
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
    val prefix = DecodePrefix.prefixFor(
        message = row.message,
        isCq = row.isCq,
        isToMe = row.isToMe,
        qsoActive = qsoActive,
        qsoDx = qsoDx,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.timeUtc,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "%+3d".format(row.snr),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = DecodeDistance.label(row.distanceKm),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "%4d".format(row.freqHz),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "$prefix${row.message}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isPartner) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}
