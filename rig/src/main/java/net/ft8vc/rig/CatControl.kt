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

    /** Read the current operating mode, or null on failure. */
    fun mode(): Ft891Cat.Mode?

    /** Set the operating mode. Returns true if the command was sent. */
    fun setMode(mode: Ft891Cat.Mode): Boolean
}
