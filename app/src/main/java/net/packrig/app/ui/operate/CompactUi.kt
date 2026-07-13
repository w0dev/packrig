package net.packrig.app.ui.operate

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Shared spacing and chip styling for dense field-style layouts. */
object Ft8Compact {
    val screenPaddingH = 8.dp
    val screenPaddingV = 4.dp
    val sectionSpacing = 4.dp
    val chipShape = RoundedCornerShape(4.dp)

    /** Tap target for high-priority controls (Start, Halt, Start CQ, Stop QSO). */
    val tapTargetPrimary = 40.dp

    /** Tap target for compact controls (filter chips, parity chips, status-bar icons). */
    val tapTargetCompact = 28.dp

    /** Width cap for the POTA park-ref chip so a long ref truncates instead of pushing the row. */
    val potaChipMaxWidth = 96.dp

    @Deprecated("Use tapTargetPrimary.", ReplaceWith("tapTargetPrimary"))
    val buttonHeight = tapTargetPrimary
    val buttonPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    @Deprecated("Use tapTargetPrimary.", ReplaceWith("tapTargetPrimary"))
    val fieldMinHeight = tapTargetPrimary
}

@Composable
fun CompactChip(
    text: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val bg = if (emphasized) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (emphasized) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val content = @Composable {
        Text(
            text = text,
            modifier = Modifier
                .heightIn(min = 24.dp)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
            maxLines = 1,
        )
    }
    if (onClick != null) {
        Surface(
            modifier = modifier,
            shape = Ft8Compact.chipShape,
            color = bg,
            onClick = onClick,
        ) {
            content()
        }
    } else {
        Surface(
            modifier = modifier,
            shape = Ft8Compact.chipShape,
            color = bg,
        ) {
            content()
        }
    }
}
