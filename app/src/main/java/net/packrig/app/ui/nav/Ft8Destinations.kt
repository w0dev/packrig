package net.packrig.app.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Ft8Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    data object Operate : Ft8Destination("operate", "Operate", Icons.Filled.Radio)
    data object Spectrum : Ft8Destination("spectrum", "Spectrum", Icons.Filled.GraphicEq)
    data object Log : Ft8Destination("log", "Log", Icons.AutoMirrored.Filled.List)
    data object Settings : Ft8Destination("settings", "Settings", Icons.Filled.Settings)

    companion object {
        val entries = listOf(Operate, Spectrum, Log, Settings)
    }
}
