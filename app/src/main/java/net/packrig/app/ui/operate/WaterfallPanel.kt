package net.packrig.app.ui.operate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import net.packrig.app.OperateViewModel
import net.packrig.app.ui.theme.Ft8Red
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** FT8 occupied bandwidth: 8-FSK x 6.25 Hz tone spacing. */
private const val FT8_SIGNAL_WIDTH_HZ = 50

/** Slot marks are labeled with the slot's UTC start time, WSJT-X style. */
private val SLOT_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)

@Composable
fun WaterfallPanel(
    vm: OperateViewModel,
    version: Long,
    maxFreqHz: Int,
    txFreqHz: Int,
    onFreqChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun freqForX(x: Float, widthPx: Int): Int =
        if (widthPx <= 0) txFreqHz else (x / widthPx * maxFreqHz).toInt()

    val textMeasurer = rememberTextMeasurer()
    val markColor = Color.White.copy(alpha = 0.55f)
    val markLabelStyle = MaterialTheme.typography.labelSmall.copy(
        fontFamily = FontFamily.Monospace,
        color = markColor,
    )

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .pointerInput(maxFreqHz) {
                    detectTapGestures { offset -> onFreqChange(freqForX(offset.x, size.width)) }
                }
                .pointerInput(maxFreqHz) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onFreqChange(freqForX(change.position.x, size.width))
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                @Suppress("UNUSED_EXPRESSION") version
                val image = vm.waterfall.snapshot()
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                )
                // 15-second slot boundaries, WSJT-X style: hairline across the
                // panel with the slot's UTC start time at the left edge.
                val history = vm.waterfall.history
                for (mark in vm.waterfall.slotMarkers()) {
                    val y = (mark.row + 0.5f) / history * size.height
                    drawLine(
                        color = markColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                    val label = SLOT_TIME_FORMAT.format(Instant.ofEpochMilli(mark.slotStartEpochMillis))
                    val layout = textMeasurer.measure(AnnotatedString(label), markLabelStyle)
                    val pad = 4.dp.toPx()
                    // Label sits below the line; flip above near the bottom edge.
                    val below = y + pad
                    val textY = if (below + layout.size.height > size.height) {
                        y - pad - layout.size.height
                    } else {
                        below
                    }
                    drawText(layout, topLeft = Offset(pad, textY))
                }
                if (maxFreqHz <= 0) return@Canvas
                val hzToX = { hz: Int -> (hz.toFloat() / maxFreqHz * size.width).coerceIn(0f, size.width) }

                // TX marker, WSJT-X style: light red fill over the 50 Hz FT8
                // footprint, goalpost caps at top/bottom, and a solid line at
                // the exact TX tone so the operator can read their footprint
                // directly against the band traces.
                val bandStart = hzToX(txFreqHz)
                val bandEnd = hzToX(txFreqHz + FT8_SIGNAL_WIDTH_HZ)
                val bandWidth = (bandEnd - bandStart).coerceAtLeast(1f)
                drawRect(
                    color = Ft8Red.copy(alpha = 0.18f),
                    topLeft = Offset(bandStart, 0f),
                    size = Size(bandWidth, size.height),
                )
                val capStroke = 3.dp.toPx()
                drawLine(
                    color = Ft8Red,
                    start = Offset(bandStart, capStroke / 2f),
                    end = Offset(bandEnd, capStroke / 2f),
                    strokeWidth = capStroke,
                )
                drawLine(
                    color = Ft8Red,
                    start = Offset(bandStart, size.height - capStroke / 2f),
                    end = Offset(bandEnd, size.height - capStroke / 2f),
                    strokeWidth = capStroke,
                )
                // Solid leading edge at the exact TX tone.
                drawLine(
                    color = Ft8Red,
                    start = Offset(bandStart, 0f),
                    end = Offset(bandStart, size.height),
                    strokeWidth = 2.5.dp.toPx(),
                )
            }
        }
        FrequencyAxis(maxFreqHz = maxFreqHz)
    }
}

@Composable
private fun FrequencyAxis(maxFreqHz: Int) {
    val ticks = listOf(0, maxFreqHz / 4, maxFreqHz / 2, maxFreqHz * 3 / 4, maxFreqHz)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        ticks.forEachIndexed { index, hz ->
            Text(
                text = if (index == ticks.lastIndex) "$hz Hz" else "$hz",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
