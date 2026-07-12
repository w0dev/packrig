package net.packset.ft8native.fakes

import net.packset.ft8native.Ft8DecodeResult
import net.packset.ft8native.Ft8DecoderApi

/**
 * One decode invocation recorded by [Ft8DecoderFake] for assertion.
 */
data class DecodeInvocation(
    val sampleCount: Int,
    val sampleRate: Int,
    val returnedCount: Int,
)

/**
 * One encode invocation recorded by [Ft8DecoderFake] for assertion.
 */
data class EncodeInvocation(
    val message: String,
    val freqHz: Float,
    val sampleRate: Int,
    val offsetSymbols: Int,
    val returnedSize: Int,
)

/**
 * Test fake for [Ft8DecoderApi] that lets JVM unit tests run without loading
 * `libft8vc.so`. Phase 0 (FOUND-05).
 *
 * Failure-injection switches mirror the four real-world scenarios the decode
 * boundary exhibits:
 *
 *  - native library missing (`configureIsAvailable(false)`) — decode/encode
 *    must collapse to empty arrays so the rest of the pipeline degrades
 *    gracefully (same behaviour as production Ft8Native when `!loaded`).
 *  - canned decode sequences (`queueDecodeResults`) — controllers can be
 *    driven through specific message sequences (CQ, GridReply, Report…).
 *  - encode override (`configureEncodeProducer`) — TX tests can substitute
 *    a deterministic byte pattern without invoking the actual encoder.
 *  - version probing (`configureVersion`) — covers the production "not
 *    loaded" string path Phase 5 logs to the user.
 */
class Ft8DecoderFake : Ft8DecoderApi {

    @Volatile
    private var available: Boolean = true

    @Volatile
    private var versionString: String? = "fake-1.0"

    private val queueLock = Any()
    private val decodeQueue = ArrayDeque<Array<Ft8DecodeResult>>()
    private val invocations = mutableListOf<DecodeInvocation>()
    private val encodeCalls = mutableListOf<EncodeInvocation>()

    @Volatile
    private var encodeProducer: ((String, Float, Int, Int) -> ShortArray)? = null

    override fun isAvailable(): Boolean = available

    override fun version(): String = versionString ?: "not loaded"

    override fun decode(samples: ShortArray, sampleRate: Int): Array<Ft8DecodeResult> {
        if (!available) {
            synchronized(queueLock) {
                invocations += DecodeInvocation(samples.size, sampleRate, returnedCount = 0)
            }
            return emptyArray()
        }
        val results = synchronized(queueLock) {
            if (decodeQueue.isEmpty()) emptyArray() else decodeQueue.removeFirst()
        }
        synchronized(queueLock) {
            invocations += DecodeInvocation(samples.size, sampleRate, returnedCount = results.size)
        }
        return results
    }

    override fun encode(
        message: String,
        freqHz: Float,
        sampleRate: Int,
        offsetSymbols: Int,
    ): ShortArray {
        if (!available) {
            synchronized(queueLock) {
                encodeCalls += EncodeInvocation(message, freqHz, sampleRate, offsetSymbols, returnedSize = 0)
            }
            return ShortArray(0)
        }
        val pcm = encodeProducer?.invoke(message, freqHz, sampleRate, offsetSymbols)
            ?: defaultEncodeOutput(sampleRate, offsetSymbols)
        synchronized(queueLock) {
            encodeCalls += EncodeInvocation(message, freqHz, sampleRate, offsetSymbols, returnedSize = pcm.size)
        }
        return pcm
    }

    private fun defaultEncodeOutput(sampleRate: Int, offsetSymbols: Int): ShortArray =
        if (offsetSymbols <= 0) {
            ShortArray(sampleRate * 15)
        } else {
            // (FT8_NN - offsetSymbols) * samplesPerSymbol, where samplesPerSymbol = round(sampleRate * 0.160)
            val nSpsym = ((sampleRate.toDouble() * 0.160) + 0.5).toInt()
            val sym = (79 - offsetSymbols).coerceAtLeast(0)
            ShortArray(sym * nSpsym)
        }

    /** Append [results] to the FIFO decode queue; the next [decode] call returns them. */
    fun queueDecodeResults(results: List<Ft8DecodeResult>) {
        synchronized(queueLock) {
            decodeQueue += results.toTypedArray()
        }
    }

    fun decodeInvocationsSnapshot(): List<DecodeInvocation> =
        synchronized(queueLock) { invocations.toList() }

    fun encodeInvocationsSnapshot(): List<EncodeInvocation> =
        synchronized(queueLock) { encodeCalls.toList() }

    fun configureIsAvailable(value: Boolean) {
        available = value
    }

    fun configureVersion(value: String?) {
        versionString = value
    }

    fun configureEncodeProducer(producer: ((String, Float, Int, Int) -> ShortArray)?) {
        encodeProducer = producer
    }

    /** Reset all state to default; useful when sharing a fake across tests (avoid if possible). */
    fun reset() {
        synchronized(queueLock) {
            decodeQueue.clear()
            invocations.clear()
            encodeCalls.clear()
        }
        available = true
        versionString = "fake-1.0"
        encodeProducer = null
    }
}
