package net.ft8vc.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.ft8vc.app.ui.theme.Ft8Amber
import net.ft8vc.app.ui.theme.Ft8Green
import net.ft8vc.app.ui.theme.Ft8vcTheme
import net.ft8vc.audio.AudioEngine
import net.ft8vc.core.AppInfo
import net.ft8vc.core.BuildPhase
import net.ft8vc.data.Logbook
import net.ft8vc.ft8native.Ft8Native
import net.ft8vc.rig.RigBackend

private data class ModuleStatus(
    val name: String,
    val summary: String,
    val ready: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val nativeReady = Ft8Native.isAvailable()
    val nativeVersion = if (nativeReady) Ft8Native.version() else "not loaded"

    val modules = listOf(
        ModuleStatus(":core", "Slot scheduler + message models (${AppInfo.currentPhase})", true),
        ModuleStatus(":audio", AudioEngine.DESCRIPTION, false),
        ModuleStatus(":rig", RigBackend.DESCRIPTION, false),
        ModuleStatus(":data", Logbook.DESCRIPTION, false),
        ModuleStatus(":ft8-native", "JNI bridge -> ft8_lib | native: $nativeVersion", nativeReady),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = AppInfo.APP_NAME,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = AppInfo.TAGLINE,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.size(4.dp))
            Text(
                text = "Phase 0 skeleton",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "Module wiring, NDK toolchain, and CI are in place. " +
                    "Phase 1 (USB audio capture + live waterfall) builds on top of these.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            modules.forEach { ModuleCard(it) }

            Spacer(Modifier.size(8.dp))
            Text(
                text = "v${AppInfo.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModuleCard(status: ModuleStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusDot(ready = status.ready)
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = status.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = status.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(ready: Boolean) {
    val color: Color = if (ready) Ft8Green else Ft8Amber
    Surface(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape),
        color = color,
        content = {},
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0F14)
@Composable
private fun HomeScreenPreview() {
    Ft8vcTheme { HomeScreen() }
}
