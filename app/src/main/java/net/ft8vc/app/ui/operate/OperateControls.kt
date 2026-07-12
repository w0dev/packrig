package net.ft8vc.app.ui.operate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.ft8vc.app.OperateUiState
import net.ft8vc.app.ui.theme.Ft8Amber
import net.ft8vc.app.ui.theme.Ft8Green
import net.ft8vc.app.ui.theme.Ft8Red
import net.ft8vc.core.StationProfileValidator

@Composable
fun CompactLevelBar(levelDbfs: Float, clip: Boolean, modifier: Modifier = Modifier) {
    val fraction = ((levelDbfs + 80f) / 80f).coerceIn(0f, 1f)
    val barColor = when {
        clip || fraction > 0.95f -> Ft8Red
        fraction > 0.8f -> Ft8Amber
        else -> Ft8Green
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor),
            )
        }
        Text(
            text = if (clip) "CLIP" else "${"%.0f".format(levelDbfs)}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun OperateControls(
    state: OperateUiState,
    onToggleOperate: () -> Unit,
    onStartCq: () -> Unit,
    onEndQso: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Button(
            onClick = onToggleOperate,
            enabled = !state.isTransmitting,
            modifier = Modifier
                .weight(1f)
                .height(Ft8Compact.tapTargetPrimary),
            contentPadding = Ft8Compact.buttonPadding,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.isOperating) Ft8Red else Ft8Green,
                contentColor = Color.Black,
            ),
        ) {
            Text(
                if (state.isOperating) "Stop decoding" else "Start decoding",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        if (state.isOperating && state.txEnabled) {
            if (state.qsoActive) {
                Button(
                    onClick = onEndQso,
                    modifier = Modifier
                        .weight(1f)
                        .height(Ft8Compact.tapTargetPrimary),
                    contentPadding = Ft8Compact.buttonPadding,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Ft8Red,
                        contentColor = Color.Black,
                    ),
                ) {
                    Text("End QSO", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
            } else {
                val stationComplete = StationProfileValidator.isComplete(state.myCall, state.myGrid)
                Button(
                    onClick = onStartCq,
                    enabled = !state.isTransmitting && stationComplete,
                    modifier = Modifier
                        .weight(1f)
                        .height(Ft8Compact.tapTargetPrimary),
                    contentPadding = Ft8Compact.buttonPadding,
                    colors = ButtonDefaults.buttonColors(containerColor = Ft8Green, contentColor = Color.Black),
                ) {
                    Text(
                        if (state.potaModeEnabled) "CQ POTA" else "Start CQ",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
