package net.packset.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import net.packset.app.controllers.QrzSlice
import net.packset.app.controllers.QrzTestStatus

/** Renders raw key characters as XXXX-XXXX-XXXX-XXXX (dots when masked). */
private class QrzKeyVisualTransformation(private val masked: Boolean) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        return TransformedText(
            AnnotatedString(QrzKeyFormat.display(raw, masked)),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int =
                    QrzKeyFormat.originalToTransformed(offset)

                override fun transformedToOriginal(offset: Int): Int =
                    QrzKeyFormat.transformedToOriginal(offset, raw.length)
            },
        )
    }
}

/** QRZ Logbook settings: enable toggle, masked API key, test button + result. */
@Composable
fun QrzSettingsSection(
    qrz: QrzSlice,
    onSetEnabled: (Boolean) -> Unit,
    onSetApiKey: (String) -> Unit,
    onTest: () -> Unit,
) {
    var showKey by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Upload QSOs to QRZ", fontWeight = FontWeight.SemiBold)
                Text(
                    "New contacts upload automatically; retries are quiet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = qrz.enabled, onCheckedChange = onSetEnabled)
        }

        OutlinedTextField(
            // Editable text is the raw 16 characters; dashes live in the visual
            // transformation so they auto-appear per group and can't be deleted.
            value = QrzKeyFormat.sanitize(qrz.apiKey),
            onValueChange = { onSetApiKey(QrzKeyFormat.store(QrzKeyFormat.sanitize(it))) },
            label = { Text("QRZ API key") },
            placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = QrzKeyVisualTransformation(masked = !showKey),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showKey) "Hide API key" else "Show API key",
                    )
                }
            },
        )

        Button(
            onClick = onTest,
            enabled = qrz.apiKey.isNotBlank() && qrz.testStatus != QrzTestStatus.Testing,
        ) {
            Text(if (qrz.testStatus == QrzTestStatus.Testing) "Testing…" else "Test connection")
        }

        when (val status = qrz.testStatus) {
            is QrzTestStatus.Passed -> Text(
                status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            is QrzTestStatus.Failed -> Text(
                status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }

        if (qrz.warning) {
            Text(
                "Not connected — ${qrz.pendingCount} QSO(s) waiting to upload. " +
                    "They retry automatically when QRZ is reachable." +
                    (qrz.lastError?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
