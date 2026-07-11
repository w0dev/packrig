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
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
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
        /** No radio model selected yet. */
        NoModel,

        /** Model selected, but no supported USB-serial device present. */
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

    /**
     * Selected radio. Null until the operator picks a model — [bindIfPermitted]
     * is a no-op and [state] reports [State.NoModel]. Owner (OperateViewModel)
     * mirrors the persisted RADIO_MODEL setting via [setDescriptor]; call
     * [rebind] to apply.
     *
     * The setter is private so callers cannot bypass [setDescriptor], which
     * additionally drops any backend bound to the previous model. The
     * synthesized setter is also renamed at the JVM level (`@set:JvmName`) so
     * it does not clash with [setDescriptor]'s signature.
     */
    @Volatile
    @get:JvmName("getDescriptor")
    @set:JvmName("assignDescriptor")
    var descriptor: RigDescriptor? = null
        private set

    /** Operator override for which serial port carries CAT; null = descriptor default. */
    @Volatile
    var catPortOverride: Int? = null

    /**
     * Set the active model and drop any backend bound to the previous one.
     * Closing a live backend is blocking USB I/O, and this method contends the
     * same monitor as [bindIfPermitted]/[rebind] — call off the main thread
     * whenever a backend may be bound (field ANR class, 2026-07-03).
     */
    @Synchronized
    fun setDescriptor(d: RigDescriptor?) {
        if (descriptor?.id == d?.id) return
        descriptor = d
        backend?.close()
        backend = null
    }

    private fun findDriver(): UsbSerialDriver? {
        // The stock prober class-probes generic CDC-ACM devices in addition to
        // its VID/PID table, so a multi-device rig can yield several candidates
        // in hash order. Prefer deterministically (FTX-1 bench, 2026-07-09: the
        // rig's aux "CDC USB Demonstration" device won the coin toss over its
        // CP2105 CAT bridge and every CAT write failed).
        val stock = preferVendorBridge(UsbSerialProber.getDefaultProber().findAllDrivers(usbManager))
        if (stock != null) return stock
        val custom = customProber().findAllDrivers(usbManager).firstOrNull()
        if (custom != null) return custom
        // Some built-in-USB rigs enumerate only as CDC-ACM; try it directly
        // against a present device as the last resort.
        val device = usbManager.deviceList.values.firstOrNull() ?: return null
        return runCatching { CdcAcmSerialDriver(device) }.getOrNull()
    }

    /** Prober seeded with the current descriptor's custom PIDs plus the CP2102 dual variant. */
    private fun customProber(): UsbSerialProber {
        val table = ProbeTable().addProduct(0x10C4, 0xEA61, Cp21xxSerialDriver::class.java)
        descriptor?.customProbePids?.forEach { id ->
            table.addProduct(id.vendorId, id.productId, Cp21xxSerialDriver::class.java)
        }
        return UsbSerialProber(table)
    }

    fun findDevice(): UsbDevice? = findDriver()?.device

    /** Serial ports the currently attached, matched driver exposes (0 if none). */
    fun availablePortCount(): Int = findDriver()?.ports?.size ?: 0

    /** Operator-facing names for the attached driver's serial ports (see [portDisplayNames]). */
    fun catPortDisplayNames(): List<String> {
        val driver = findDriver() ?: return emptyList()
        return portDisplayNames(driver.ports.size, driver is Cp21xxSerialDriver)
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
        if (descriptor == null) return State.NoModel
        findDevice() ?: return State.NoDevice
        return if (backend != null) State.Ready else State.NeedsPermission
    }

    /**
     * Bind to a connected, permitted serial device. Returns true if PTT is now
     * wired. No-op (returns current readiness) if no model selected, no device,
     * no permission, or no usable CAT port index.
     */
    @Synchronized
    fun bindIfPermitted(): Boolean {
        if (backend != null) return true
        val d = descriptor ?: return false
        val driver = findDriver() ?: return false
        if (!usbManager.hasPermission(driver.device)) return false
        val index = resolveCatPortIndex(driver.ports.size, catPortOverride, d.catPortIndex)
        if (index == null) {
            Log.e(TAG, "No serial port at CAT index (ports=${driver.ports.size}, override=$catPortOverride, descriptor=${d.catPortIndex})")
            return false
        }
        val port = driver.ports[index]
        val candidate = SerialRigBackend(
            transport = UsbSerialTransport(usbManager, port, catBaud),
            protocol = d.protocolFactory?.invoke(),
        )
        return if (candidate.open()) {
            backend = candidate
            Log.i(TAG, "Serial rig backend bound: ${d.displayName} @ port $index (${driver.device.deviceName})")
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
     * Test CAT for a draft profile's descriptor without adopting it: close any
     * live backend, open the candidate at [baud], query once, close, and
     * re-bind the selected configuration. Blocking serial I/O — call on the
     * CAT dispatcher, and only when not transmitting (caller-guarded).
     */
    @Synchronized
    fun probe(d: RigDescriptor, baud: Int): ProbeResult {
        val factory = d.protocolFactory ?: return ProbeResult.NoCat
        val driver = findDriver() ?: return ProbeResult.NoDevice
        if (!usbManager.hasPermission(driver.device)) return ProbeResult.NoPermission
        val index = resolveCatPortIndex(driver.ports.size, null, d.catPortIndex)
            ?: return ProbeResult.NoDevice
        val hadBackend = backend != null
        backend?.close()
        backend = null
        val candidate = SerialRigBackend(
            transport = UsbSerialTransport(usbManager, driver.ports[index], baud),
            protocol = factory(),
        )
        val result = if (!candidate.open()) {
            ProbeResult.Silence
        } else {
            try {
                candidate.probeFrequency()
            } finally {
                candidate.close()
            }
        }
        if (hadBackend) bindIfPermitted()
        return result
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
        backend?.dataModeLabel()
            ?: descriptor?.protocolFactory?.invoke()?.dataModeLabel
            ?: "DATA-U"

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

        /**
         * Pick the serial port index for CAT: operator [override] if set, else the
         * [descriptorIndex], validated against [portCount]. Null = no usable port.
         */
        fun resolveCatPortIndex(portCount: Int, override: Int?, descriptorIndex: Int): Int? {
            val wanted = override ?: descriptorIndex
            return if (wanted in 0 until portCount) wanted else null
        }

        /**
         * Among prober-matched drivers, prefer a vendor-specific bridge (matched
         * by VID/PID: CP210x, FTDI, CH34x, Prolific) over a class-probed CDC-ACM
         * driver. Composite rigs (FTX-1) expose auxiliary CDC control devices
         * that are not the CAT port; the vendor bridge is the CAT bridge.
         */
        fun preferVendorBridge(drivers: List<UsbSerialDriver>): UsbSerialDriver? =
            drivers.firstOrNull { it !is CdcAcmSerialDriver } ?: drivers.firstOrNull()

        /**
         * Operator-facing serial-port names. Every dual-port bridge in the
         * current registry is a CP210x-family dual UART whose two channels
         * Yaesu (and the Silicon Labs Windows driver hams know from WSJT-X
         * setup) call the Enhanced and Standard COM ports — CAT rides
         * Enhanced. Anything else gets generic 1-based names.
         */
        fun portDisplayNames(portCount: Int, isCp21xx: Boolean): List<String> =
            if (isCp21xx && portCount == 2) {
                listOf("Enhanced port — CAT (default)", "Standard port")
            } else {
                (1..portCount).map { "Serial port $it" }
            }
    }
}
