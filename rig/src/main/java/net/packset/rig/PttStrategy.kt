package net.packset.rig

/**
 * How the transmitter gets keyed. Selected per rig configuration: Digirig-style
 * interfaces key a modem line ([RtsPttStrategy]); rigs whose CAT jack has no
 * hardware PTT line key over CAT ([CatPttStrategy]). Phase 2's rig descriptors
 * pick the strategy; until then [SerialRigBackend] uses RTS and RigController
 * keeps its CAT-vs-RTS probe.
 */
interface PttStrategy {

    /** Assert push-to-talk. Returns true if the underlying action succeeded. */
    fun key(): Boolean

    /** Release push-to-talk. Returns true if the underlying action succeeded. */
    fun release(): Boolean
}

/** Hardware PTT on the serial RTS line (Digirig Mobile). */
class RtsPttStrategy(private val transport: SerialTransport) : PttStrategy {
    override fun key(): Boolean = transport.setRts(true)
    override fun release(): Boolean = transport.setRts(false)
}

/** Software PTT over CAT (e.g. Yaesu `TX1;`/`TX0;`). */
class CatPttStrategy(private val cat: CatControl) : PttStrategy {
    override fun key(): Boolean = cat.catPtt(true)
    override fun release(): Boolean = cat.catPtt(false)
}
