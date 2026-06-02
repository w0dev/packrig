package net.ft8vc.rig

/**
 * Push-to-talk abstraction. The first concrete backend is the Digirig Mobile,
 * which exposes a CP2102 USB-serial bridge: PTT is keyed via the serial RTS
 * line. FT-891 CAT frequency/mode control rides the same port and is a separate
 * capability — see [CatControl].
 */
interface RigBackend {

    /** Assert push-to-talk (key the transmitter). */
    fun keyPtt()

    /** Release push-to-talk (return to receive). */
    fun releasePtt()

    companion object {
        const val DESCRIPTION = "Digirig RTS PTT + FT-891 CAT"
    }
}
