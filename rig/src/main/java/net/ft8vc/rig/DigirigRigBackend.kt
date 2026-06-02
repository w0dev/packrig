package net.ft8vc.rig

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * [RigBackend] + [CatControl] over a Digirig's CP2102 USB-serial bridge.
 *
 * PTT is keyed on the RTS modem line via `SET_MHS` control transfers; FT-891 CAT
 * frequency/mode rides the same port as bulk UART data. Caller must already hold
 * USB permission for [device]. [open] claims interface 0, enables the UART, and
 * configures the line for CAT at [catBaud] (8N1).
 *
 * @param catBaud must match the rig's CAT RATE menu (FT-891 menu 05-06).
 */
class DigirigRigBackend(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val catBaud: Int = DEFAULT_CAT_BAUD,
) : RigBackend, CatControl {

    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null

    /** Serializes CAT exchanges so concurrent reads/writes don't interleave. */
    private val catLock = Any()

    /** Open and configure the bridge. Returns false if the device can't be claimed. */
    @Synchronized
    fun open(): Boolean {
        if (connection != null) return true
        if (device.interfaceCount <= 0) {
            Log.e(TAG, "Device has no interfaces")
            return false
        }
        val intf = device.getInterface(0)
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            Log.e(TAG, "openDevice returned null (permission revoked?)")
            return false
        }
        if (!conn.claimInterface(intf, true)) {
            Log.e(TAG, "claimInterface failed")
            conn.close()
            return false
        }
        connection = conn
        iface = intf
        findBulkEndpoints(intf)
        controlTransfer(Cp210x.REQUEST_IFC_ENABLE, Cp210x.UART_ENABLE)
        configureUart()
        // Start de-keyed.
        controlTransfer(Cp210x.REQUEST_SET_MHS, Cp210x.mhsValue(rts = false))
        Log.i(TAG, "Digirig CP2102 opened: ${device.deviceName} (CAT @ $catBaud baud)")
        return true
    }

    private fun findBulkEndpoints(intf: UsbInterface) {
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep else bulkOut = ep
        }
        if (bulkIn == null || bulkOut == null) {
            Log.w(TAG, "CP2102 bulk endpoints missing (in=$bulkIn out=$bulkOut); CAT disabled")
        }
    }

    /** Set baud rate and 8N1 line control for CAT. */
    private fun configureUart() {
        controlTransfer(Cp210x.REQUEST_SET_LINE_CTL, Cp210x.LINE_CTL_8N1)
        val sent = controlTransferData(Cp210x.REQUEST_SET_BAUDRATE, Cp210x.baudRateBytes(catBaud))
        if (sent < 0) Log.e(TAG, "SET_BAUDRATE failed")
    }

    override fun keyPtt() {
        if (controlTransfer(Cp210x.REQUEST_SET_MHS, Cp210x.mhsValue(rts = true)) < 0) {
            Log.e(TAG, "keyPtt control transfer failed")
        }
    }

    override fun releasePtt() {
        if (controlTransfer(Cp210x.REQUEST_SET_MHS, Cp210x.mhsValue(rts = false)) < 0) {
            Log.e(TAG, "releasePtt control transfer failed")
        }
    }

    override fun frequencyHz(): Long? {
        val reply = catExchange(Ft891Cat.readFrequencyCommand()) ?: return null
        return Ft891Cat.parseFrequencyResponse(reply)
    }

    override fun setFrequencyHz(hz: Long): Boolean {
        val command = runCatching { Ft891Cat.setFrequencyCommand(hz) }.getOrNull() ?: return false
        return catWrite(command)
    }

    override fun mode(): Ft891Cat.Mode? {
        val reply = catExchange(Ft891Cat.readModeCommand()) ?: return null
        return Ft891Cat.parseModeResponse(reply)
    }

    override fun setMode(mode: Ft891Cat.Mode): Boolean = catWrite(Ft891Cat.setModeCommand(mode))

    /** Send a CAT command that expects no reply. */
    private fun catWrite(command: String): Boolean = synchronized(catLock) {
        writeSerial(command.toByteArray(Charsets.US_ASCII))
    }

    /** Send a CAT query and read the `;`-terminated reply, or null on timeout. */
    private fun catExchange(command: String): String? = synchronized(catLock) {
        if (!writeSerial(command.toByteArray(Charsets.US_ASCII))) return null
        readReply()
    }

    private fun writeSerial(bytes: ByteArray): Boolean {
        val conn = connection ?: return false
        val out = bulkOut ?: return false
        var offset = 0
        while (offset < bytes.size) {
            val chunk = bytes.copyOfRange(offset, bytes.size)
            val n = conn.bulkTransfer(out, chunk, chunk.size, CAT_TIMEOUT_MS)
            if (n <= 0) {
                Log.e(TAG, "Serial write failed at offset $offset")
                return false
            }
            offset += n
        }
        return true
    }

    /** Accumulate bulk-IN bytes until the CAT terminator or [CAT_REPLY_DEADLINE_MS]. */
    private fun readReply(): String? {
        val conn = connection ?: return null
        val ep = bulkIn ?: return null
        val buffer = ByteArray(ep.maxPacketSize.coerceAtLeast(64))
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + CAT_REPLY_DEADLINE_MS
        while (System.currentTimeMillis() < deadline) {
            val n = conn.bulkTransfer(ep, buffer, buffer.size, CAT_TIMEOUT_MS)
            if (n > 0) {
                sb.append(String(buffer, 0, n, Charsets.US_ASCII))
                if (sb.contains(Ft891Cat.TERMINATOR)) {
                    return sb.substring(0, sb.indexOf(Ft891Cat.TERMINATOR.toString()) + 1)
                }
            }
        }
        Log.w(TAG, "CAT reply timed out (got \"$sb\")")
        return null
    }

    /** Release PTT, release the interface, and close the connection. */
    @Synchronized
    fun close() {
        val conn = connection ?: return
        runCatching { releasePtt() }
        iface?.let { conn.releaseInterface(it) }
        conn.close()
        connection = null
        iface = null
        bulkIn = null
        bulkOut = null
    }

    private fun controlTransfer(request: Int, value: Int): Int = controlTransferData(request, null, value)

    private fun controlTransferData(request: Int, data: ByteArray?, value: Int = 0): Int {
        val conn = connection ?: return -1
        return conn.controlTransfer(
            Cp210x.REQTYPE_HOST_TO_DEVICE,
            request,
            value,
            /* index = interface */ 0,
            data,
            data?.size ?: 0,
            TIMEOUT_MS,
        )
    }

    companion object {
        private const val TAG = "DigirigRigBackend"
        private const val TIMEOUT_MS = 1000

        /** Per-transfer USB timeout for CAT bulk reads/writes. */
        private const val CAT_TIMEOUT_MS = 200

        /** Overall budget for collecting a complete `;`-terminated CAT reply. */
        private const val CAT_REPLY_DEADLINE_MS = 1000L

        /**
         * Default CAT baud. The FT-891 ships at 4800 (menu 05-06); FT8 setups
         * commonly raise it to 38400 for snappier polling. Must match the rig.
         */
        const val DEFAULT_CAT_BAUD = 38_400
    }
}
