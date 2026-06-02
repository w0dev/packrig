package net.ft8vc.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import net.ft8vc.app.ui.MonitorScreen
import net.ft8vc.app.ui.theme.Ft8vcTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Ft8vcTheme {
                MonitorScreen()
            }
        }
    }
}
