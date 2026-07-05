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
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * Discovers a supported USB-serial rig interface (Digirig CP210x today; FTDI /
 * CH340 / PL2303 / CDC-ACM come with the library), manages USB permission, and
 * routes PTT/CAT to a [SerialRigBackend] when available, falling back to a
 * no-op. Phase 1 hardcodes the FT-891 protocol table; phase 2's RigDescriptor
 * registry makes the model selectable.
 *
 * Implements [RigBackend] so callers can key/unkey PTT without caring whether a
 * radio is actually attached (e.g. on the emulator).
 */
class RigController(private val context: Context) : RigBackend, CatControl {

    enum class State {
        /** No supported USB-serial device present. */
        NoDevice,

        /** Device present but USB permission not yet granted. */
        NeedsPermission,

        /** Device present, permission granted, PTT wired. */
        Ready,
    }

    private val appContext = context.applicationContext
    private val usbManager get() = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile
    private var backend: SerialRigBackend? = null
    private val fallback = NoOpRigBackend()

    /**
     * CAT baud for the next bind/rebind — must match FT-891 menu 05-06 (CAT RATE).
     * Owner (OperateViewModel) mirrors the persisted setting here; changing it does
     * NOT reconfigure a live connection — call [rebind] to apply.
     */
    @Volatile
    var catBaud: Int = DEFAULT_CAT_BAUD

    /** True once a real serial PTT backend is open. (Name kept for app parity.) */
    val isDigirigReady: Boolean get() = backend != null

    /** Silicon Labs PIDs the stock probe table may lack (CP2102 dual variant). */
    private val customProber = UsbSerialProber(
        ProbeTable().addProduct(0x10C4, 0xEA61, Cp21xxSerialDriver::class.java),
    )

    private fun findDriver(): UsbSerialDriver? =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
            ?: customProber.findAllDrivers(usbManager).firstOrNull()

    fun findDevice(): UsbDevice? = findDriver()?.device

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
        return if (backend != null) State.Ready else State.NeedsPermission
    }

    /**
     * Bind to a connected, permitted serial device. Returns true if PTT is now
     * wired. No-op (returns current readiness) if no device or no permission.
     */
    @Synchronized
    fun bindIfPermitted(): Boolean {
        if (backend != null) return true
        val driver = findDriver() ?: return false
        if (!usbManager.hasPermission(driver.device)) return false
        val port = driver.ports.firstOrNull() ?: return false
        val candidate = SerialRigBackend(
            transport = UsbSerialTransport(usbManager, port, catBaud),
            protocol = YaesuCat(YaesuCat.FT891),
        )
        return if (candidate.open()) {
            backend = candidate
            Log.i(TAG, "Serial rig backend bound for PTT/CAT (${driver.device.deviceName})")
            true
        } else {
            Log.e(TAG, "Backend open() failed after USB permission granted")
            false
        }
    }

    /**
     * Drop any backend left over from a previous USB enumeration and bind the
     * currently attached device. A detach/reattach cycle re-enumerates the
     * device, so a held backend points at dead file descriptors while [state]
     * still reports Ready — call this on USB_DEVICE_ATTACHED before re-probing.
     * Returns true if PTT is wired to the fresh device.
     */
    @Synchronized
    fun rebind(): Boolean {
        backend?.close()
        backend = null
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
                onResult(backend != null)
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

    private fun active(): RigBackend = backend ?: fallback

    /**
     * When true, PTT uses CAT `TX1;`/`TX0;`. When false (Digirig default), PTT
     * uses the serial **RTS** line — the hardware PTT path on Digirig Mobile.
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
        val rig = backend ?: return "no-op"
        useCatPtt = rig.frequencyHz() != null
        val method = if (useCatPtt) "CAT" else "RTS"
        Log.i(TAG, "PTT method: $method (CAT probe reply=${useCatPtt})")
        return method
    }

    override fun keyPtt() {
        val rig = backend
        if (useCatPtt && rig != null) {
            // CAT only — do not assert RTS (Menu 05-08 CAT RTS can latch TX).
            rig.catPtt(true)
        } else {
            active().keyPtt()
        }
    }

    override fun releasePtt() {
        val rig = backend
        if (useCatPtt && rig != null) {
            rig.catPtt(false)
        } else {
            active().releasePtt()
        }
        // Always de-assert RTS after any TX path so hardware PTT cannot stick.
        rig?.releasePtt()
    }

    /** True once CAT can talk to a real rig (serial backend open). */
    val isCatReady: Boolean get() = backend != null

    override fun frequencyHz(): Long? = backend?.frequencyHz()

    override fun setFrequencyHz(hz: Long): Boolean = backend?.setFrequencyHz(hz) ?: false

    override fun modeLabel(): String? = backend?.modeLabel()

    override fun setDataMode(): Boolean = backend?.setDataMode() ?: false

    override fun dataModeLabel(): String =
        backend?.dataModeLabel() ?: YaesuCat(YaesuCat.FT891).dataModeLabel

    override fun catPtt(on: Boolean): Boolean = backend?.catPtt(on) ?: false

    /** Release the USB connection (call from owner's teardown). */
    @Synchronized
    fun close() {
        backend?.close()
        backend = null
    }

    companion object {
        private const val TAG = "RigController"
        private const val ACTION_USB_PERMISSION = "net.ft8vc.rig.USB_PERMISSION"

        /**
         * Default CAT baud. The FT-891 ships at 4800 (menu 05-06); FT8 setups
         * commonly raise it to 38400 for snappier polling. Must match the rig.
         */
        const val DEFAULT_CAT_BAUD = 38_400
    }
}
