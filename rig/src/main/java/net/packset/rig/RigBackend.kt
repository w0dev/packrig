package net.packset.rig

/**
 * Push-to-talk abstraction. The first concrete backend is [SerialRigBackend],
 * composed over a USB-serial transport (e.g. Digirig Mobile's CP2102 bridge):
 * PTT is keyed via the serial RTS line. CAT frequency/mode control rides the
 * same port and is a separate capability — see [CatControl].
 */
interface RigBackend {

    /** Assert push-to-talk (key the transmitter). */
    fun keyPtt()

    /** Release push-to-talk (return to receive). */
    fun releasePtt()
}
