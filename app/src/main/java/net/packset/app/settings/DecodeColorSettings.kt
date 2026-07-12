package net.packset.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.packset.core.DecodeCategory

/**
 * Curated palette for decode-row colors. Chosen for legibility on the dark
 * theme; a fixed set (no free picker) so the operator can't select an
 * unreadable color in the field.
 */
val DECODE_COLOR_PALETTE: List<Int> = listOf(
    0xFFE63946.toInt(), // red (Ft8Red)
    0xFFFF6B6B.toInt(), // coral
    0xFFFFB347.toInt(), // amber (Ft8Amber)
    0xFFFFD166.toInt(), // yellow
    0xFF3DDC97.toInt(), // green (Ft8Green)
    0xFF2EC4B6.toInt(), // teal
    0xFF4CC9F0.toInt(), // cyan
    0xFF6C9DFF.toInt(), // blue
    0xFF9B8CFF.toInt(), // violet
    0xFFE07BE0.toInt(), // magenta
    0xFFFF8FA3.toInt(), // pink
    0xFF9AA0A6.toInt(), // gray
)

private data class CategoryRow(
    val category: DecodeCategory,
    val label: String,
    val description: String,
)

private val CATEGORY_ROWS = listOf(
    CategoryRow(DecodeCategory.MY_CALL, "My call", "Messages directed at your callsign"),
    CategoryRow(DecodeCategory.PARTNER, "QSO partner", "Messages involving your current QSO partner"),
    CategoryRow(DecodeCategory.OWN_TX, "My TX", "Messages you transmitted"),
    CategoryRow(DecodeCategory.CQ_NEW, "CQ — new", "CQ from a station not yet worked"),
    CategoryRow(DecodeCategory.CQ_WORKED_OTHER_BAND, "CQ — worked (other band)", "CQ from a call worked, but not on this band"),
    CategoryRow(DecodeCategory.CQ_WORKED_THIS_BAND, "CQ — worked (this band)", "CQ from a call already in the log on this band"),
)

/**
 * Decode-color editor. Always expanded — it owns most of the Display tab
 * (was a collapsible row when Settings was one long scroll).
 */
@Composable
fun DecodeColorsSection(
    scheme: DecodeColorScheme,
    onPickColor: (DecodeCategory, Int) -> Unit,
    onReset: () -> Unit,
) {
    var editing by remember { mutableStateOf<DecodeCategory?>(null) }

    Column {
        Text(
            "Decode colors",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        CATEGORY_ROWS.forEach { rowSpec ->
            val argb = scheme.colorFor(rowSpec.category) ?: return@forEach
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editing = rowSpec.category }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(rowSpec.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        rowSpec.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Swatch(color = Color(argb))
            }
        }
        TextButton(onClick = onReset) {
            Text("Reset to defaults", style = MaterialTheme.typography.labelMedium)
        }
    }

    editing?.let { category ->
        val label = CATEGORY_ROWS.first { it.category == category }.label
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Color for $label") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DECODE_COLOR_PALETTE.chunked(4).forEach { paletteRow ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            paletteRow.forEach { argb ->
                                Swatch(
                                    color = Color(argb),
                                    size = 36.dp,
                                    onClick = {
                                        onPickColor(category, argb)
                                        editing = null
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Swatch(
    color: Color,
    size: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}
