package net.ft8vc.rig

/**
 * Radio control abstraction. The first concrete backend is the Digirig Mobile,
 * which exposes a CP2102 USB-serial bridge: PTT is keyed via the serial RTS
 * line, and (optionally) the Yaesu FT-891 is controlled over CAT on the same
 * port. Phase 3 wires PTT; CAT frequency/mode sync follows in v1.x.
 */
interface RigBackend {

    /** Assert push-to-talk (key the transmitter). */
    fun keyPtt()

    /** Release push-to-talk (return to receive). */
    fun releasePtt()

    companion object {
        const val DESCRIPTION = "Digirig RTS PTT + FT-891 CAT (Phase 3 / v1.x)"
    }
}
