package net.ft8vc.audio.fakes

import net.ft8vc.audio.UsbPlaybackApi

/** Compatibility alias kept so existing tests that reference UsbAudioPlaybackFake still compile. */
typealias UsbAudioPlaybackFake = UsbPlaybackApi

/**
 * One playBlocking invocation recorded by [FakeUsbAudioPlayback].
 *
 * `pcm` is a defensive copy of the caller's samples; mutating the original
 * after the call must not affect the recorded entry. `equals`/`hashCode` are
 * overridden because `ShortArray` is referential.
 */
class PlaybackRecord(
    val pcm: ShortArray,
    val preferredDeviceId: Int?,
    val halted: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlaybackRecord) return false
        if (!pcm.contentEquals(other.pcm)) return false
        if (preferredDeviceId != other.preferredDeviceId) return false
        if (halted != other.halted) return false
        return true
    }

    override fun hashCode(): Int {
        var result = pcm.contentHashCode()
        result = 31 * result + (preferredDeviceId ?: 0)
        result = 31 * result + halted.hashCode()
        return result
    }

    override fun toString(): String =
        "PlaybackRecord(pcm.size=${pcm.size}, preferredDeviceId=$preferredDeviceId, halted=$halted)"
}

/**
 * Test fake for `UsbAudioPlayback`. Mirrors its `playBlocking` + `stop`
 * surface, recording every invocation so tests can assert the TX path
 * produced the expected samples for the expected device.
 *
 * Phase 0 (FOUND-04, half of). Failure-injection switches:
 *
 *  - `configureBlockingMs` simulates real playback duration so `stop()` from
 *    another thread can interrupt `playBlocking` (returns false).
 *  - `configureFailFastReturn` returns false immediately without sleeping —
 *    used to simulate the Pitfall 3 "exception during playback" scenario
 *    without throwing on the test thread.
 */
class FakeUsbAudioPlayback : UsbAudioPlaybackFake {

    private val recordsLock = Any()
    private val records = mutableListOf<PlaybackRecord>()

    @Volatile
    private var blockingMs: Long = 0L

    @Volatile
    private var failFast: Boolean = false

    @Volatile
    private var halted: Boolean = false

    @Volatile
    private var nextThrowable: Throwable? = null

    private val latchLock = Any()
    private var blockLatch: java.util.concurrent.CountDownLatch? = null

    override fun playBlocking(samples12k: ShortArray, preferredDeviceId: Int?): Boolean {
        // Throw injection: consume once, then throw (simulates playback crash).
        nextThrowable?.let { t -> nextThrowable = null; throw t }

        if (failFast) {
            synchronized(recordsLock) {
                records += PlaybackRecord(samples12k.copyOf(), preferredDeviceId, halted = true)
            }
            return false
        }

        // Latch-based block: suspends until releaseBlockLatch() or stop() is called.
        val latch = synchronized(latchLock) { blockLatch }
        if (latch != null) {
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            val wasHalted = halted
            synchronized(recordsLock) {
                records += PlaybackRecord(samples12k.copyOf(), preferredDeviceId, halted = wasHalted)
            }
            return !wasHalted
        }

        halted = false
        if (blockingMs > 0L) {
            val deadline = System.nanoTime() + blockingMs * 1_000_000L
            while (!halted && System.nanoTime() < deadline) {
                Thread.sleep(10L)
            }
        }
        val wasHalted = halted
        synchronized(recordsLock) {
            records += PlaybackRecord(samples12k.copyOf(), preferredDeviceId, halted = wasHalted)
        }
        return !wasHalted
    }

    override fun stop() {
        halted = true
        synchronized(latchLock) { blockLatch }?.countDown()
    }

    fun playbackRecordsSnapshot(): List<PlaybackRecord> =
        synchronized(recordsLock) { records.toList() }

    fun configureBlockingMs(ms: Long) {
        require(ms >= 0L) { "Blocking ms must be >= 0: $ms" }
        blockingMs = ms
    }

    fun configureFailFastReturn(enable: Boolean) {
        failFast = enable
    }

    /**
     * Configure the fake to throw [t] on the next [playBlocking] call. Consumed once.
     * Simulates a mid-playback crash (Layer-a PTT defense scenario).
     */
    fun configureNextPlayThrows(t: Throwable) {
        nextThrowable = t
    }

    /**
     * Install a [java.util.concurrent.CountDownLatch] that [playBlocking] will
     * block on indefinitely. Call [releaseBlockLatch] (or [stop]) from another
     * thread to unblock. Simulates a hung playback thread for Layer-b/c/d tests.
     */
    fun configureBlockLatch(latch: java.util.concurrent.CountDownLatch) {
        synchronized(latchLock) { blockLatch = latch }
    }

    /** Release the blocking latch installed by [configureBlockLatch]. */
    fun releaseBlockLatch() {
        synchronized(latchLock) { blockLatch }?.countDown()
    }
}
