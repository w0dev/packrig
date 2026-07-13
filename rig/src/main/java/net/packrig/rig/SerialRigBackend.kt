package net.packrig.rig

import android.util.Log

/**
 * [RigBackend] + [CatControl] composed from a [SerialTransport] (byte pipe)
 * and a [CatProtocol] (per-rig command builder/parser). Replaces the fused
 * DigirigRigBackend: hardware PTT is the transport's RTS line via
 * [RtsPttStrategy]; CAT PTT is the protocol's PTT command. CAT exchanges are
 * serialized on [catLock] and blocking — call off the main thread.
 *
 * @param protocol null for CAT-less presets (generic-rts): RTS PTT still
 *   works, every CAT method answers a fast null/false with no I/O.
 * @param nowMs injectable clock for the reply deadline (tests).
 */
class SerialRigBackend(
    private val transport: SerialTransport,
    private val protocol: CatProtocol?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : RigBackend, CatControl {

    private val rtsPtt = RtsPttStrategy(transport)

    /** Serializes CAT exchanges so concurrent reads/writes don't interleave. */
    private val catLock = Any()

    /** Open the transport and start de-keyed (RTS must never idle asserted). */
    fun open(): Boolean {
        if (!transport.open()) return false
        if (!rtsPtt.release()) Log.e(TAG, "Initial RTS de-assert failed")
        return true
    }

    /** Release PTT and close the transport. */
    fun close() {
        runCatching { rtsPtt.release() }
        transport.close()
    }

    override fun keyPtt() {
        if (!rtsPtt.key()) Log.e(TAG, "keyPtt: RTS assert failed")
    }

    override fun releasePtt() {
        if (!rtsPtt.release()) Log.e(TAG, "releasePtt: RTS de-assert failed")
    }

    override fun frequencyHz(): Long? {
        val p = protocol ?: return null
        return catExchange(p.readFrequencyCommand(), p.replyTerminator)?.let(p::parseFrequency)
    }

    /**
     * One-shot diagnostic: send a frequency query and classify what comes
     * back, keeping partial bytes (unlike [readReply]) so a wrong-baud rig
     * reads as [ProbeResult.Garbage] rather than a timeout.
     */
    fun probeFrequency(): ProbeResult = synchronized(catLock) {
        val p = protocol ?: return ProbeResult.NoCat
        if (!transport.write(p.readFrequencyCommand(), CAT_TIMEOUT_MS)) return ProbeResult.Silence
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var collected = ByteArray(0)
        val deadline = nowMs() + CAT_REPLY_DEADLINE_MS
        while (nowMs() < deadline) {
            val n = transport.read(buffer, CAT_TIMEOUT_MS)
            if (n < 0) break
            if (n > 0) {
                collected += buffer.copyOfRange(0, n)
                val end = collected.indexOfFirst { it == p.replyTerminator }
                if (end >= 0) {
                    val frame = collected.copyOfRange(0, end + 1)
                    return p.parseFrequency(frame)?.let { ProbeResult.Sync(it) } ?: ProbeResult.Garbage
                }
            }
        }
        return if (collected.isEmpty()) ProbeResult.Silence else ProbeResult.Garbage
    }

    override fun setFrequencyHz(hz: Long): Boolean {
        val command = protocol?.setFrequencyCommand(hz) ?: return false
        return catWrite(command)
    }

    override fun modeLabel(): String? {
        val p = protocol ?: return null
        return catExchange(p.readModeCommand(), p.replyTerminator)?.let(p::parseModeLabel)
    }

    override fun setDataMode(): Boolean {
        val command = protocol?.setDataModeCommand() ?: return false
        return catWrite(command)
    }

    override fun dataModeLabel(): String = protocol?.dataModeLabel ?: "No CAT"

    override fun catPtt(on: Boolean): Boolean {
        val command = protocol?.pttCommand(on) ?: return false
        val ok = catWrite(command)
        Log.i(TAG, "catPtt(on=$on) sent=$ok")
        return ok
    }

    /** Send a CAT command that expects no reply. */
    private fun catWrite(command: ByteArray): Boolean = synchronized(catLock) {
        val ok = transport.write(command, CAT_TIMEOUT_MS)
        Log.i(TAG, "CAT write \"${command.ascii()}\" ok=$ok")
        ok
    }

    /** Send a CAT query and read the terminated reply, or null on timeout. */
    private fun catExchange(command: ByteArray, terminator: Byte): ByteArray? = synchronized(catLock) {
        if (!transport.write(command, CAT_TIMEOUT_MS)) {
            Log.e(TAG, "CAT write \"${command.ascii()}\" failed")
            return null
        }
        val reply = readReply(terminator)
        Log.i(
            TAG,
            "CAT exchange \"${command.ascii()}\" -> " +
                (reply?.let { "\"${it.ascii()}\"" } ?: "<timeout>"),
        )
        reply
    }

    /** Accumulate reads until [terminator] or the deadline. */
    private fun readReply(terminator: Byte): ByteArray? {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var collected = ByteArray(0)
        val deadline = nowMs() + CAT_REPLY_DEADLINE_MS
        while (nowMs() < deadline) {
            val n = transport.read(buffer, CAT_TIMEOUT_MS)
            if (n < 0) {
                Log.w(TAG, "CAT read error — aborting reply wait")
                return null
            }
            if (n > 0) {
                collected += buffer.copyOfRange(0, n)
                val end = collected.indexOfFirst { it == terminator }
                if (end >= 0) return collected.copyOfRange(0, end + 1)
            }
        }
        Log.w(TAG, "CAT reply timed out (got \"${collected.ascii()}\")")
        return null
    }

    private fun ByteArray.ascii(): String = toString(Charsets.US_ASCII)

    companion object {
        private const val TAG = "SerialRigBackend"
        private const val READ_BUFFER_SIZE = 64

        /** Per-transfer timeout for CAT reads/writes. */
        private const val CAT_TIMEOUT_MS = 200

        /** Overall budget for collecting a complete terminated CAT reply. */
        private const val CAT_REPLY_DEADLINE_MS = 1000L
    }
}
