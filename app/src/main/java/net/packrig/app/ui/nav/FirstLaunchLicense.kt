package net.packrig.app.ui.nav

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/** Pure show/hide rule for the first-launch license dialog (spec 2026-07-17). */
object FirstLaunchLicense {

    /** True only once settings are hydrated AND the license was never
     *  acknowledged — the hydration guard stops a one-frame flash at
     *  already-acknowledged users while DataStore loads. */
    fun shows(settingsLoaded: Boolean, licenseAcknowledged: Boolean): Boolean =
        settingsLoaded && !licenseAcknowledged
}

/**
 * One-time, non-dismissable license/TX acknowledgment shown at first launch
 * (spec 2026-07-17-first-launch-license-ack-design). Both buttons persist the
 * acknowledgment; they differ only in whether Enable transmit turns on.
 */
@Composable
fun FirstLaunchLicenseDialog(
    onEnableTx: () -> Unit,
    onRxOnly: () -> Unit,
) {
    AlertDialog(
        // Non-dismissable by design: one of the two buttons must be tapped.
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text("Before you get on the air") },
        text = {
            Text(
                "PackRig can key your radio and transmit. Transmitting requires " +
                    "a valid amateur radio license for your jurisdiction — you are " +
                    "responsible for lawful operation; this app and its authors " +
                    "are not. Receiving and decoding need no license.\n\n" +
                    "You can change this anytime in Settings → General → Enable transmit.",
            )
        },
        confirmButton = {
            TextButton(onClick = onEnableTx) { Text("I understand — turn on TX") }
        },
        dismissButton = {
            TextButton(onClick = onRxOnly) { Text("Just looking around (RX only)") }
        },
    )
}
