package net.ft8vc.app

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import net.ft8vc.app.ui.nav.Ft8vcApp

class MainActivity : ComponentActivity() {

    // Same instance Ft8vcApp resolves via viewModel() — both use this activity's
    // ViewModelStore and the default factory/key.
    private val operateVm: OperateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Ft8vcApp()
        }
    }

    /**
     * launchMode=singleTask delivers USB_DEVICE_ATTACHED here instead of
     * launching a second activity instance (which previously stacked a second
     * ViewModel — two concurrent QSO machines/rig controllers). Re-probe
     * devices so a plugged/re-enumerated Digirig is picked up, matching what
     * the fresh instance's init used to do.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            operateVm.onUsbAttached()
        }
    }
}
