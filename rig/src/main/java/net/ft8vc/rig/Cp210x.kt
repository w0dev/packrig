package net.ft8vc.rig

/**
 * Silicon Labs CP210x (CP2102) USB-UART bridge constants and helpers.
 *
 * The Digirig Mobile uses a CP2102 for its serial port; PTT is keyed on the
 * RTS modem line. RTS is set with the vendor `SET_MHS` control request.
 */
object Cp210x {

    /** Silicon Labs vendor id. */
    const val VENDOR_ID = 0x10C4

    /** CP2102/CP2109 product id (the Digirig's bridge). */
    const val PRODUCT_ID = 0xEA60

    /** CP210x product IDs seen on Digirig and generic Silicon Labs bridges. */
    val PRODUCT_IDS = setOf(
        0xEA60, // CP2102, CP2104, CP2102N (default)
        0xEA61, // CP2102 dual / some CP2101–4 variants
        0xEA70, // CP2105
        0xEA71, // CP2108
    )

    fun matches(deviceVendorId: Int, deviceProductId: Int): Boolean =
        deviceVendorId == VENDOR_ID && deviceProductId in PRODUCT_IDS

    /** Vendor request, host-to-device (USB_DIR_OUT | USB_TYPE_VENDOR | USB_RECIP_DEVICE). */
    const val REQTYPE_HOST_TO_DEVICE = 0x41

    /** Enable/disable the UART interface. */
    const val REQUEST_IFC_ENABLE = 0x00

    /** Set modem handshaking lines (DTR/RTS). */
    const val REQUEST_SET_MHS = 0x07

    /** Set the UART line control (data bits / parity / stop bits) via wValue. */
    const val REQUEST_SET_LINE_CTL = 0x03

    /** Set hardware flow control; must be off for manual RTS PTT. */
    const val REQUEST_SET_FLOW = 0x13

    /** Set the baud rate; the 32-bit rate rides in the data stage (little-endian). */
    const val REQUEST_SET_BAUDRATE = 0x1E

    /**
     * 16-byte SET_FLOW payload that disables RTS/CTS and DTR/DSR handshaking.
     * Matches usb-serial-for-android CP2102 [FLOW_CONTROL_OFF].
     */
    val FLOW_CONTROL_OFF: ByteArray = byteArrayOf(
        0x01, 0x00, 0x00, 0x00,
        0x40, 0x00, 0x00, 0x00,
        0x00, 0x80.toByte(), 0x00, 0x00,
        0x00, 0x20, 0x00, 0x00,
    )

    const val UART_ENABLE = 0x0001
    const val UART_DISABLE = 0x0000

    // SET_MHS wValue layout: low byte = line states, high byte = write mask.
    private const val DTR_STATE = 0x0001
    private const val RTS_STATE = 0x0002
    private const val DTR_MASK = 0x0100
    private const val RTS_MASK = 0x0200

    /**
     * wValue for SET_MHS that drives RTS to [rts] while leaving DTR low. Only the
     * RTS line is masked for write, so DTR is not disturbed by the radio cable.
     */
    fun mhsValue(rts: Boolean): Int = RTS_MASK or (if (rts) RTS_STATE else 0)

    /**
     * wValue for SET_LINE_CTL. Layout: bits 8-15 = data bits, bits 4-7 = parity
     * (0 = none, 1 = odd, 2 = even), bits 0-3 = stop bits (0 = 1, 1 = 1.5, 2 = 2).
     * The FT-891 CAT port uses 8 data bits, no parity, 1 stop bit (8N1).
     */
    fun lineCtlValue(dataBits: Int = 8, parity: Int = 0, stopBits: Int = 0): Int =
        ((dataBits and 0xFF) shl 8) or ((parity and 0x0F) shl 4) or (stopBits and 0x0F)

    /** 8N1 line control, the FT-891 CAT default. */
    const val LINE_CTL_8N1 = 0x0800

    /** Encode [baud] as the 4-byte little-endian payload for SET_BAUDRATE. */
    fun baudRateBytes(baud: Int): ByteArray = byteArrayOf(
        (baud and 0xFF).toByte(),
        ((baud ushr 8) and 0xFF).toByte(),
        ((baud ushr 16) and 0xFF).toByte(),
        ((baud ushr 24) and 0xFF).toByte(),
    )
}
