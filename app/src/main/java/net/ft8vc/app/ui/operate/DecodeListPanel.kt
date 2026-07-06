package net.ft8vc.app.ui.operate

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.ft8vc.app.DecodeRow
import net.ft8vc.app.settings.DecodeColorScheme
import net.ft8vc.app.ui.WithTooltip
import net.ft8vc.core.DecodeCategory
import net.ft8vc.core.DecodeCategoryResolver
import net.ft8vc.core.DecodeDistance
import net.ft8vc.core.DecodePrefix
import net.ft8vc.core.DecodeRowSource
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
    decodeColors: DecodeColorScheme = DecodeColorScheme.DEFAULT,
    canAnswer: Boolean,
    canResume: Boolean,
    onClear: () -> Unit,
    onAnswerCq: (DecodeRow) -> Unit,
    onResume: (DecodeRow) -> Unit,
    userBlockedCalls: List<String>,
    onBlockSender: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleDecodes = decodes.filter { row ->
        !DecodeBlocklist.isSenderBlocked(row.message, row.source, userBlockedCalls) &&
        (row.source is DecodeRowSource.Tx ||
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
            ))
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
            if (decodes.size >= net.ft8vc.app.OperateUiState.MAX_DECODE_ROWS) {
                Text(
                    text = "showing last ${net.ft8vc.app.OperateUiState.MAX_DECODE_ROWS} — older cleared",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            if (visibleDecodes.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Match the data rows' effective inset: LazyColumn's 6.dp
                        // plus each DecodeRowItem's own 2.dp horizontal padding.
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Labels are space-padded to the monospace width of the data
                    // cell below them (time HHmmss=6, snr %+3d=3, dist=4, cc=2,
                    // freq %4d=4) so each header sits over its own column.
                    DecodeHeaderCell("UTC   ")
                    DecodeHeaderCell("SNR")
                    DecodeHeaderCell("DIST")
                    DecodeHeaderCell("CC")
                    DecodeHeaderCell("  Hz")
                    Text(
                        text = "MSG",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
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
                val listState = rememberLazyListState()
                val newestId = visibleDecodes.first().id
                // Keyed items anchor the viewport by key on prepend — that holds
                // position while the operator reads history, but it also un-pins
                // the bottom. Runs at composition time, before the new rows are
                // measured, so canScrollBackward still answers "was the operator
                // at the bottom?"; requestScrollToItem re-pins on the next layout.
                remember(newestId) {
                    if (!listState.canScrollBackward) listState.requestScrollToItem(0)
                    newestId
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    // Newest-first slice + reverseLayout = newest at the BOTTOM
                    // (field request 2026-07-03).
                    reverseLayout = true,
                ) {
                    items(visibleDecodes, key = { it.id }) { row ->
                        val blockTarget = DecodeBlocklist.senderToBlock(row.message, row.source)
                        DecodeRowItem(
                            row = row,
                            qsoDx = qsoDx,
                            qsoActive = qsoActive,
                            decodeColors = decodeColors,
                            onClick = when {
                                canAnswer && row.isCq -> ({ onAnswerCq(row) })
                                canResume && row.isToMe -> ({ onResume(row) })
                                else -> null
                            },
                            onLongClick = blockTarget?.let { call -> { onBlockSender(call) } },
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

@Composable
private fun DecodeHeaderCell(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DecodeRowItem(
    row: DecodeRow,
    qsoDx: String?,
    qsoActive: Boolean,
    decodeColors: DecodeColorScheme,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    val isTx = row.source is DecodeRowSource.Tx
    val category = DecodeCategoryResolver.resolve(
        isTx = isTx,
        isCq = row.isCq,
        isToMe = row.isToMe,
        workedBefore = row.workedBefore,
        qsoActive = qsoActive,
        qsoDx = qsoDx,
        message = row.message,
    )
    // Fill categories carry the color as a row background; the rest as text color.
    val filled = when (category) {
        DecodeCategory.OWN_TX, DecodeCategory.PARTNER, DecodeCategory.MY_CALL -> true
        else -> false
    }
    val categoryColor = decodeColors.colorFor(category)?.let { Color(it) }
    val rowBackground = if (filled && categoryColor != null) {
        categoryColor.copy(alpha = FILL_ALPHA)
    } else {
        Color.Transparent
    }
    val dimmed = category == DecodeCategory.OTHER && qsoActive
    val textColor = when {
        filled -> MaterialTheme.colorScheme.onSurface
        categoryColor != null -> categoryColor
        dimmed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val prefix = DecodePrefix.glyphFor(category)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background(rowBackground)
            .testTag("decodeRow_${category.name}")
            .then(
                if (!isTx && (onClick != null || onLongClick != null)) {
                    Modifier.combinedClickable(
                        onClick = onClick ?: {},
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.timeUtc,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(
                    MaterialTheme.colorScheme.onSurface
                        .copy(alpha = slotTintAlpha(row.slotParity)),
                ),
        )
        Text(
            text = if (isTx) "   " else "%+3d".format(row.snr),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (isTx) "    " else DecodeDistance.label(row.distanceKm),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (isTx) "  " else (row.countryCode ?: " —"),
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
            fontWeight = if (filled) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Fixed background alpha for filled categories (OWN_TX / PARTNER / MY_CALL). */
private const val FILL_ALPHA = 0.16f
