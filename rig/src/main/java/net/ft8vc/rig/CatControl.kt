package net.ft8vc.rig

/**
 * CAT (Computer Aided Transceiver) control for reading and setting the rig's
 * VFO-A frequency and operating mode. Separate from [RigBackend] because PTT and
 * CAT are independent capabilities: a backend may key PTT without speaking CAT
 * (and vice versa). The Digirig drives both over its single CP2102 serial port.
 *
 * All calls are blocking and must be issued off the main thread. Implementations
 * return null / false when no rig is attached or the exchange times out.
 */
interface CatControl {

    /** Read the current VFO-A frequency in Hz, or null on failure. */
    fun frequencyHz(): Long?

    /** Tune VFO-A to [hz]. Returns true if the command was sent. */
    fun setFrequencyHz(hz: Long): Boolean

    /**
     * Read the current operating mode as a display label (e.g. "DATA-U"), or
     * null on failure. Labels are protocol-defined; the app treats them as
     * opaque strings.
     */
    fun modeLabel(): String?

    /** Put the rig in its FT8 data mode (e.g. DATA-U). Returns true if sent. */
    fun setDataMode(): Boolean

    /** Display label of the mode [setDataMode] selects (e.g. "DATA-U"). */
    fun dataModeLabel(): String

    /**
     * Key ([on] = true) or un-key the transmitter over CAT (`TX1;`/`TX0;`).
     * Used as the FT-891 PTT method, whose CAT jack has no hardware RTS line.
     * Returns true if the command was sent.
     */
    fun catPtt(on: Boolean): Boolean
}
