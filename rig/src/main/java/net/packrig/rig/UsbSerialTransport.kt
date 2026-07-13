package net.packrig.rig

import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort

/**
 * [SerialTransport] over a usb-serial-for-android [UsbSerialPort]. Configures
 * 8N1 at [baud] with flow control off on open, and never asserts RTS during
 * open/close — on a Digirig RTS is hardware PTT, and an RTS blip on connect
 * would key the transmitter (docs/USB_SERIAL_LIB_UPGRADE.md, coupling pt. 4).
 *
 * @param baud must match the rig's CAT RATE menu (FT-891 menu 05-06).
 */
class UsbSerialTransport(
    private val usbManager: UsbManager,
    private val port: UsbSerialPort,
    private val baud: Int,
) : SerialTransport {

    override fun open(): Boolean {
        val device = port.driver.device
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "openDevice returned null (permission revoked?)")
            return false
        }
        return runCatching {
            port.open(connection)
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // Not every driver supports these; failures are non-fatal.
            runCatching { port.setFlowControl(UsbSerialPort.FlowControl.NONE) }
            runCatching { port.setRTS(false) }
            Log.i(TAG, "Serial port opened: ${device.deviceName} 8N1 @ $baud baud")
            true
        }.getOrElse { t ->
            Log.e(TAG, "Port open/config failed: ${t.message}")
            runCatching { port.close() }
            connection.close()
            false
        }
    }

    override fun close() {
        runCatching { port.setRTS(false) }
        runCatching { port.close() } // also closes the UsbDeviceConnection
    }

    override fun write(bytes: ByteArray, timeoutMs: Int): Boolean =
        runCatching {
            port.write(bytes, timeoutMs)
            true
        }.getOrElse { t ->
            Log.e(TAG, "Serial write failed: ${t.message}")
            false
        }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int =
        runCatching { port.read(buffer, timeoutMs) }.getOrElse { t ->
            Log.e(TAG, "Serial read failed: ${t.message}")
            -1
        }

    override fun setRts(asserted: Boolean): Boolean =
        runCatching {
            port.setRTS(asserted)
            true
        }.getOrElse { t ->
            Log.e(TAG, "setRTS($asserted) failed: ${t.message}")
            false
        }

    override fun setDtr(asserted: Boolean): Boolean =
        runCatching {
            port.setDTR(asserted)
            true
        }.getOrElse { t ->
            Log.e(TAG, "setDTR($asserted) failed: ${t.message}")
            false
        }

    companion object {
        private const val TAG = "UsbSerialTransport"
    }
}
