package net.packrig.app.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable

/**
 * Wraps any composable with a long-press / hover tooltip. Tap still fires the
 * wrapped content's own click handler.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        focusable = false,
        enableUserInput = true,
        content = { content() },
    )
}
