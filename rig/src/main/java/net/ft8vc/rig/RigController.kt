package net.ft8vc.rig

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * Discovers a connected Digirig (CP2102), manages USB permission, and routes PTT
 * to the real [DigirigRigBackend] when available, falling back to a no-op.
 *
 * Implements [RigBackend] so callers can key/unkey PTT without caring whether a
 * radio is actually attached (e.g. on the emulator).
 */
class RigController(private val context: Context) : RigBackend, CatControl {

    enum class State {
        /** No CP2102 device present. */
        NoDevice,

        /** Device present but USB permission not yet granted. */
        NeedsPermission,

        /** Device present, permission granted, PTT wired. */
        Ready,
    }

    private val appContext = context.applicationContext
    private val usbManager get() = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile
    private var digirig: DigirigRigBackend? = null
    private val fallback = NoOpRigBackend()

    /** True once a real Digirig PTT backend is open. */
    val isDigirigReady: Boolean get() = digirig != null

    fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            Cp210x.matches(it.vendorId, it.productId)
        }

    /** Short summary of USB devices Android reports (for UI diagnostics). */
    fun usbDeviceSummary(): String {
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) return "Android sees no USB devices"
        return devices.joinToString("; ") { dev ->
            val vid = dev.vendorId.toString(16).padStart(4, '0')
            val pid = dev.productId.toString(16).padStart(4, '0')
            val name = dev.productName?.toString()?.ifBlank { null } ?: dev.deviceName
            "$vid:$pid $name"
        }
    }

    /** Current discovery/permission state (for UI status). */
    fun state(): State {
        findDevice() ?: return State.NoDevice
        return if (digirig != null) State.Ready else State.NeedsPermission
    }

    /**
     * Bind to a connected, permitted Digirig. Returns true if PTT is now wired.
     * No-op (returns current readiness) if there is no device or no permission.
     */
    @Synchronized
    fun bindIfPermitted(): Boolean {
        if (digirig != null) return true
        val device = findDevice() ?: return false
        if (!usbManager.hasPermission(device)) return false
        val backend = DigirigRigBackend(usbManager, device)
        return if (backend.open()) {
            digirig = backend
            Log.i(TAG, "Digirig bound for PTT/CAT")
            true
        } else {
            Log.e(TAG, "Digirig open() failed after USB permission granted")
            false
        }
    }

    /**
     * Drop any backend left over from a previous USB enumeration and bind the
     * currently attached device. A detach/reattach cycle re-enumerates the
     * Digirig, so a held [DigirigRigBackend] points at dead file descriptors
     * while [state] still reports Ready — call this on USB_DEVICE_ATTACHED
     * before re-probing. Returns true if PTT is wired to the fresh device.
     */
    @Synchronized
    fun rebind(): Boolean {
        digirig?.close()
        digirig = null
        return bindIfPermitted()
    }

    /**
     * Ensure PTT is wired. If a device is present but unpermitted, prompts the
     * user for USB permission and binds on grant. [onResult] reports readiness.
     */
    fun ensureReady(onResult: (Boolean) -> Unit) {
        if (bindIfPermitted()) {
            onResult(true)
            return
        }
        val device = findDevice()
        if (device == null) {
            onResult(false)
            return
        }
        requestPermission(device, onResult)
    }

    private fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                runCatching { appContext.unregisterReceiver(this) }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.i(TAG, "USB permission result: granted=$granted")
                if (granted) bindIfPermitted()
                onResult(digirig != null)
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        val flags = PendingIntent.FLAG_MUTABLE
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val pi = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    private fun active(): RigBackend = digirig ?: fallback

    /**
     * When true, PTT uses CAT `TX1;`/`TX0;`. When false (Digirig default), PTT
     * uses the CP2102 **RTS** line — the hardware PTT path on Digirig Mobile.
     *
     * [configurePttFromCatProbe] sets this from a live `FA;` query. Until then
     * we default to RTS so TX works even when CAT readback is broken on the phone.
     */
    @Volatile
    var useCatPtt: Boolean = false

    /**
     * Pick CAT vs RTS PTT from whether the rig answers a frequency query.
     * Call off the main thread (can block ~1 s on timeout).
     */
    fun configurePttFromCatProbe(): String {
        val rig = digirig ?: return "no-op"
        useCatPtt = rig.frequencyHz() != null
        val method = if (useCatPtt) "CAT" else "RTS"
        Log.i(TAG, "PTT method: $method (CAT probe reply=${useCatPtt})")
        return method
    }

    override fun keyPtt() {
        val rig = digirig
        if (useCatPtt && rig != null) {
            // CAT only — do not assert RTS (Menu 05-08 CAT RTS can latch TX).
            rig.catPtt(true)
        } else {
            active().keyPtt()
        }
    }

    override fun releasePtt() {
        val rig = digirig
        if (useCatPtt && rig != null) {
            rig.catPtt(false)
        } else {
            active().releasePtt()
        }
        // Always de-assert RTS after any TX path so Digirig hardware PTT cannot stick.
        rig?.releasePtt()
    }

    /** True once CAT can talk to a real rig (Digirig open). */
    val isCatReady: Boolean get() = digirig != null

    override fun frequencyHz(): Long? = digirig?.frequencyHz()

    override fun setFrequencyHz(hz: Long): Boolean = digirig?.setFrequencyHz(hz) ?: false

    override fun mode(): Ft891Cat.Mode? = digirig?.mode()

    override fun setMode(mode: Ft891Cat.Mode): Boolean = digirig?.setMode(mode) ?: false

    override fun catPtt(on: Boolean): Boolean = digirig?.catPtt(on) ?: false

    /** Release the USB connection (call from owner's teardown). */
    @Synchronized
    fun close() {
        digirig?.close()
        digirig = null
    }

    companion object {
        private const val TAG = "RigController"
        private const val ACTION_USB_PERMISSION = "net.ft8vc.rig.USB_PERMISSION"
    }
}
