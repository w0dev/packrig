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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@Composable
fun InputLevelControl(
    inputGain: Float,
    levelDbfs: Float,
    clip: Boolean,
    onInputGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("Input level", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${(inputGain * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (clip) Ft8Amber else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = inputGain,
            onValueChange = onInputGainChange,
            valueRange = OperateUiState.INPUT_GAIN_MIN..1f,
        )
        CompactLevelBar(levelDbfs = levelDbfs, clip = clip)
    }
}

@Composable
fun CompactLevelBar(levelDbfs: Float, clip: Boolean, modifier: Modifier = Modifier) {
    val fraction = ((levelDbfs + 80f) / 80f).coerceIn(0f, 1f)
    val barColor = when {
        clip || fraction > 0.95f -> Ft8Red
        fraction > 0.8f -> Ft8Amber
        else -> Ft8Green
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor),
            )
        }
        Text(
            if (clip) "CLIP" else "${"%.0f".format(levelDbfs)} dB",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun OperateSeqToggles(
    state: OperateUiState,
    onAutoSeqChange: (Boolean) -> Unit,
    onAnswerWhenCalledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto Seq", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Advance active QSO on decode",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.autoSeqEnabled,
                onCheckedChange = onAutoSeqChange,
                enabled = state.txEnabled,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Answer when called", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Resume QSO when someone calls you",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.answerWhenCalledEnabled,
                onCheckedChange = onAnswerWhenCalledChange,
                enabled = state.txEnabled,
            )
        }
    }
}

@Composable
fun OperateControls(
    state: OperateUiState,
    onToggleOperate: () -> Unit,
    onStartCq: () -> Unit,
    onStopQso: () -> Unit,
    onAutoSeqChange: (Boolean) -> Unit,
    onAnswerWhenCalledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.isOperating) {
            OperateSeqToggles(
                state = state,
                onAutoSeqChange = onAutoSeqChange,
                onAnswerWhenCalledChange = onAnswerWhenCalledChange,
            )
        }
        Button(
            onClick = onToggleOperate,
            enabled = !state.isTransmitting,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.isOperating) Ft8Red else Ft8Green,
                contentColor = Color.Black,
            ),
        ) {
            Text(
                if (state.isOperating) "Stop operating" else "Start operating",
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (state.isOperating && state.txEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.qsoActive) {
                    Button(
                        onClick = onStopQso,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Ft8Red, contentColor = Color.Black),
                    ) {
                        Text("Stop QSO", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(
                        onClick = onStartCq,
                        enabled = !state.isTransmitting,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Ft8Green, contentColor = Color.Black),
                    ) {
                        Text("Start CQ", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
