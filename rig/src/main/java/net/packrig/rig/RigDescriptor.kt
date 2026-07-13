package net.packrig.rig

/**
 * Everything [RigController] needs to compose the Phase 1 seams for one radio:
 * how to talk to it ([protocolFactory]), how it connects ([defaultBaud],
 * [catPortIndex], [customProbePids]), and how to key it ([defaultPtt]).
 *
 * @param id stable key persisted in settings (never localize/rename).
 * @param catPortIndex which serial port carries CAT; 0 for single-port bridges,
 *   0 = Enhanced port on dual-UART rigs (CP2105).
 * @param customProbePids extra VID/PID entries when the stock CP210x prober
 *   table misses the rig's bridge.
 * @param transportVerified false when the transport fields are best-guess pending
 *   hardware (docs tracking only — no UI marker).
 */
data class RigDescriptor(
    val id: String,
    val displayName: String,
    /** Builds the CAT protocol, or null for CAT-less presets (generic-rts):
     *  PTT keys via RTS, every CAT read/write is a fast no-op. */
    val protocolFactory: (() -> CatProtocol)?,
    val defaultBaud: Int,
    val catPortIndex: Int,
    val defaultPtt: PttMethod,
    val customProbePids: List<UsbId> = emptyList(),
    val transportVerified: Boolean,
)
