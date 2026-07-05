package net.ft8vc.rig

/**
 * Pure per-rig-family CAT protocol: builds command bytes and parses reply
 * bytes. No I/O, no Android — the transport lives behind [SerialTransport].
 * ByteArray (not String) so binary protocols (Icom CI-V) fit the same seam.
 */
interface CatProtocol {

    /** Byte that ends a reply frame (';' for ASCII CAT, 0xFD for CI-V). */
    val replyTerminator: Byte

    /** Display label of the mode [setDataModeCommand] selects (e.g. "DATA-U"). */
    val dataModeLabel: String

    /** Query the current VFO frequency. */
    fun readFrequencyCommand(): ByteArray

    /** Tune to [hz], or null if out of the model's range. */
    fun setFrequencyCommand(hz: Long): ByteArray?

    /** Parse a frequency reply into Hz, or null if malformed. */
    fun parseFrequency(reply: ByteArray): Long?

    /** Query the operating mode. */
    fun readModeCommand(): ByteArray

    /** Parse a mode reply into a display label, or null if unrecognized. */
    fun parseModeLabel(reply: ByteArray): String?

    /** Select the rig's FT8 data mode. */
    fun setDataModeCommand(): ByteArray

    /** Key/unkey via CAT, or null if this family has no CAT PTT. */
    fun pttCommand(on: Boolean): ByteArray?
}
