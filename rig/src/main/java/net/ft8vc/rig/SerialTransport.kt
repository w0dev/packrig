package net.ft8vc.rig

/**
 * Byte-pipe seam between rig protocol code and the USB serial stack. The
 * production implementation wraps usb-serial-for-android ([UsbSerialTransport]);
 * tests use a scripted fake. All calls are blocking — use off the main thread.
 *
 * RTS/DTR matter beyond flow control here: on a Digirig, RTS is hardware PTT.
 * Implementations must never assert RTS except via [setRts].
 */
interface SerialTransport {

    /** Open and configure the port (baud/8N1 fixed at construction). */
    fun open(): Boolean

    /** Release the port. Safe to call when not open. */
    fun close()

    /** Write all of [bytes]. Returns true on success. */
    fun write(bytes: ByteArray, timeoutMs: Int): Boolean

    /** Read into [buffer]. Returns bytes read; 0 on timeout; -1 on error. */
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    /** Drive the RTS modem line. Returns true if the line was set. */
    fun setRts(asserted: Boolean): Boolean

    /** Drive the DTR modem line. Returns true if the line was set. */
    fun setDtr(asserted: Boolean): Boolean
}
