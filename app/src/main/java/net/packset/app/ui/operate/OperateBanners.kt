package net.packset.app.ui.operate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
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
import net.packset.app.ui.theme.Ft8Amber

/** Amber warning surface shared by Operate-screen advisories. */
@Composable
private fun WarningBanner(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Ft8Amber.copy(alpha = 0.2f),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = Ft8Amber,
        )
    }
}

/** Shown when the user has not yet entered a valid call + grid. */
@Composable
fun StationProfileBanner(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WarningBanner(
        text = "Set your callsign and grid in Settings →",
        onClick = onOpenSettings,
        modifier = modifier,
    )
}

/** Shown when CAT reports a non-DATA-U mode. Tap to switch to DATA-U. */
@Composable
fun RigModeWarningBanner(
    rigMode: String,
    onSetDataUsb: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WarningBanner(
        text = "Rig mode: $rigMode — tap to set DATA-U",
        onClick = onSetDataUsb,
        modifier = modifier,
    )
}
