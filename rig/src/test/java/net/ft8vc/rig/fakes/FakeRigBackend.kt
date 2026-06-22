package net.ft8vc.rig.fakes

import net.ft8vc.rig.CatControl
import net.ft8vc.rig.Ft891Cat
import net.ft8vc.rig.RigBackend

/**
 * Test fake for [RigBackend] + [CatControl] — mirrors the surface
 * `DigirigRigBackend` exposes (PTT + FT-891 CAT) without speaking serial.
 *
 * Phase 0 (FOUND-03): single in-memory fake used by every Phase 1-5 controller
 * test that needs to drive the rig boundary. Failure switches simulate the four
 * realistic edge cases the field rig produces:
 *
 *  - response latency (`configureLatencyMs`)
 *  - CAT timeout / null reply (`configureTimeoutHz`, `configureTimeoutMode`)
 *  - USB detach mid-call (`simulateDetach` / `reattach`)
 *  - CAT-PTT acknowledgement flip (`configureCatPttResponse`)
 *
 * Every PTT edge is captured in [pttEdgesSnapshot] so tests can assert sequence
 * and timing without touching internal state. All mutable state is either
 * `@Volatile` (scalar flags) or guarded by [historyLock]; the fake is intended
 * to be safe under multi-threaded test exercise.
 */
class FakeRigBackend(
    initialFrequencyHz: Long = 14_074_000L,
    initialMode: Ft891Cat.Mode = Ft891Cat.Mode.DATA_USB,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RigBackend, CatControl {

    @Volatile
    private var pttKeyedFlag: Boolean = false

    @Volatile
    private var frequencyHz: Long = initialFrequencyHz

    @Volatile
    private var modeValue: Ft891Cat.Mode = initialMode

    @Volatile
    private var latencyMs: Long = 0L

    @Volatile
    private var timeoutHz: Boolean = false

    @Volatile
    private var timeoutMode: Boolean = false

    @Volatile
    private var catPttResponse: Boolean = true

    @Volatile
    private var catPttCount: Int = 0

    @Volatile
    private var detached: Boolean = false

    private val historyLock = Any()
    private val history = mutableListOf<PttEdge>()

    val pttKeyed: Boolean get() = pttKeyedFlag

    val catPttInvocations: Int get() = catPttCount

    val currentFrequencyHz: Long get() = frequencyHz

    val currentMode: Ft891Cat.Mode get() = modeValue

    override fun keyPtt() {
        if (detached) {
            appendEdge(PttEdgeKind.DETACHED)
            return
        }
        pttKeyedFlag = true
        appendEdge(PttEdgeKind.KEY)
    }

    override fun releasePtt() {
        if (detached) {
            appendEdge(PttEdgeKind.DETACHED)
            return
        }
        pttKeyedFlag = false
        appendEdge(PttEdgeKind.RELEASE)
    }

    override fun frequencyHz(): Long? {
        if (detached) return null
        if (timeoutHz) {
            timeoutHz = false
            return null
        }
        if (latencyMs > 0L) {
            Thread.sleep(latencyMs)
        }
        return frequencyHz
    }

    override fun setFrequencyHz(hz: Long): Boolean {
        if (detached) return false
        if (hz !in Ft891Cat.MIN_FREQ_HZ..Ft891Cat.MAX_FREQ_HZ) return false
        frequencyHz = hz
        return true
    }

    override fun mode(): Ft891Cat.Mode? {
        if (detached) return null
        if (timeoutMode) {
            timeoutMode = false
            return null
        }
        if (latencyMs > 0L) {
            Thread.sleep(latencyMs)
        }
        return modeValue
    }

    override fun setMode(mode: Ft891Cat.Mode): Boolean {
        if (detached) return false
        modeValue = mode
        return true
    }

    override fun catPtt(on: Boolean): Boolean {
        catPttCount += 1
        if (detached) return false
        return catPttResponse
    }

    fun configureLatencyMs(ms: Long) {
        require(ms >= 0L) { "Latency must be >= 0: $ms" }
        latencyMs = ms
    }

    fun configureTimeoutHz(enable: Boolean) {
        timeoutHz = enable
    }

    fun configureTimeoutMode(enable: Boolean) {
        timeoutMode = enable
    }

    fun configureCatPttResponse(value: Boolean) {
        catPttResponse = value
    }

    fun simulateDetach() {
        detached = true
    }

    fun reattach() {
        detached = false
    }

    fun pttEdgesSnapshot(): List<PttEdge> = synchronized(historyLock) { history.toList() }

    private fun appendEdge(kind: PttEdgeKind) {
        val edge = PttEdge(kind, clock.invoke())
        synchronized(historyLock) {
            history += edge
        }
    }
}

enum class PttEdgeKind { KEY, RELEASE, DETACHED }

data class PttEdge(val kind: PttEdgeKind, val timestampMs: Long)
