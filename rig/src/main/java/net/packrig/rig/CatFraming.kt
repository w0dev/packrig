package net.packrig.rig

/**
 * What one complete CAT frame is, relative to the last command this
 * controller sent. ASCII families see everything as [Reply] (their default);
 * CI-V distinguishes echoes, acks, and transceive broadcasts.
 */
enum class FrameClass {
    /** A frame addressed to us that answers a query. */
    Reply,

    /** Our own transmitted command reflected back (echoing wiring). */
    Echo,

    /** Set-command acknowledged (CI-V 0xFB). */
    Ack,

    /** Set-command rejected (CI-V 0xFA). */
    Nak,

    /** Unsolicited rig announcement (CI-V transceive frequency/mode). */
    Broadcast,

    /** Malformed, or traffic for another station on the bus. */
    Junk,
}

/** Result of chopping accumulated bytes into complete frames. */
data class FrameSplit(val frames: List<ByteArray>, val remainder: ByteArray)
