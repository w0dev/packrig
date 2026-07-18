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
        val outcome = catExchange(p, p.readFrequencyCommand())
        // A transceive broadcast heard while waiting is a valid answer when the
        // direct reply never came (echo-heavy or busy CI-V buses).
        return outcome.reply?.let(p::parseFrequency)
            ?: outcome.broadcasts.firstNotNullOfOrNull(p::parseFrequency)
    }

    fun probeFrequency(): ProbeResult = synchronized(catLock) {
        val p = protocol ?: return ProbeResult.NoCat
        if (p.wantsInputFlush) drainInput()
        if (!transport.write(p.readFrequencyCommand(), CAT_TIMEOUT_MS)) return ProbeResult.Silence
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var pending = ByteArray(0)
        var sawBytes = false
        var sawEcho = false
        var sawOther = false
        val deadline = nowMs() + CAT_REPLY_DEADLINE_MS
        while (nowMs() < deadline) {
            val n = transport.read(buffer, CAT_TIMEOUT_MS)
            if (n < 0) break
            if (n == 0) continue
            sawBytes = true
            pending += buffer.copyOfRange(0, n)
            val split = p.splitFrames(pending)
            pending = split.remainder
            for (frame in split.frames) {
                when (p.classifyFrame(frame)) {
                    FrameClass.Reply ->
                        return p.parseFrequency(frame)?.let { ProbeResult.Sync(it) } ?: ProbeResult.Garbage
                    FrameClass.Echo -> sawEcho = true
                    FrameClass.Broadcast -> {
                        p.parseFrequency(frame)?.let { return ProbeResult.Sync(it) }
                        sawOther = true
                    }
                    else -> sawOther = true
                }
            }
        }
        return when {
            sawEcho && !sawOther -> ProbeResult.EchoOnly
            sawBytes -> ProbeResult.Garbage
            else -> ProbeResult.Silence
        }
    }

    override fun setFrequencyHz(hz: Long): Boolean {
        val p = protocol ?: return false
        val command = p.setFrequencyCommand(hz) ?: return false
        return catWrite(p, command)
    }

    override fun modeLabel(): String? {
        val p = protocol ?: return null
        return catExchange(p, p.readModeCommand()).reply?.let(p::parseModeLabel)
    }

    override fun setDataMode(): Boolean {
        val p = protocol ?: return false
        return catWrite(p, p.setDataModeCommand())
    }

    override fun dataModeLabel(): String = protocol?.dataModeLabel ?: "No CAT"

    override fun catPtt(on: Boolean): Boolean {
        val p = protocol ?: return false
        val command = p.pttCommand(on) ?: return false
        val ok = catWrite(p, command)
        Log.i(TAG, "catPtt(on=$on) sent=$ok")
        return ok
    }

    /** Reply (or null) plus any transceive broadcasts heard while waiting. */
    private class Exchange(val reply: ByteArray?, val broadcasts: List<ByteArray>)

    /** Send a set command. Fire-and-forget unless the protocol acks sets. */
    private fun catWrite(p: CatProtocol, command: ByteArray): Boolean = synchronized(catLock) {
        if (p.wantsInputFlush) drainInput()
        if (!transport.write(command, CAT_TIMEOUT_MS)) {
            Log.e(TAG, "CAT write \"${command.ascii()}\" failed")
            return false
        }
        if (!p.setCommandsAcked) {
            Log.i(TAG, "CAT write \"${command.ascii()}\" ok=true")
            return true
        }
        val outcome = consumeFrames(p) { c -> c == FrameClass.Ack || c == FrameClass.Nak }
        val acked = outcome.reply?.let { p.classifyFrame(it) == FrameClass.Ack } ?: false
        Log.i(TAG, "CAT set \"${command.ascii()}\" acked=$acked")
        return acked
    }

    /** Send a query and collect frames until a Reply or the deadline. */
    private fun catExchange(p: CatProtocol, command: ByteArray): Exchange = synchronized(catLock) {
        if (p.wantsInputFlush) drainInput()
        if (!transport.write(command, CAT_TIMEOUT_MS)) {
            Log.e(TAG, "CAT write \"${command.ascii()}\" failed")
            return Exchange(null, emptyList())
        }
        val outcome = consumeFrames(p) { c -> c == FrameClass.Reply }
        Log.i(
            TAG,
            "CAT exchange \"${command.ascii()}\" -> " +
                (outcome.reply?.let { "\"${it.ascii()}\"" } ?: "<timeout>"),
        )
        return outcome
    }

    /** Accumulate reads, split into frames, and return the first frame matching
     *  [wanted]; echoes, junk, and unclaimed acks are dropped, broadcasts kept. */
    private fun consumeFrames(p: CatProtocol, wanted: (FrameClass) -> Boolean): Exchange {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var pending = ByteArray(0)
        val broadcasts = mutableListOf<ByteArray>()
        val deadline = nowMs() + CAT_REPLY_DEADLINE_MS
        while (nowMs() < deadline) {
            val n = transport.read(buffer, CAT_TIMEOUT_MS)
            if (n < 0) {
                Log.w(TAG, "CAT read error — aborting frame wait")
                return Exchange(null, broadcasts)
            }
            if (n == 0) continue
            pending += buffer.copyOfRange(0, n)
            val split = p.splitFrames(pending)
            pending = split.remainder
            for (frame in split.frames) {
                val klass = p.classifyFrame(frame)
                if (wanted(klass)) return Exchange(frame, broadcasts)
                if (klass == FrameClass.Broadcast) broadcasts += frame
            }
        }
        Log.w(TAG, "CAT frame wait timed out")
        return Exchange(null, broadcasts)
    }

    /** Discard stale bytes (unclaimed acks, echoes) queued before an exchange. */
    private fun drainInput() {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        repeat(MAX_FLUSH_READS) {
            if (transport.read(buffer, FLUSH_READ_TIMEOUT_MS) <= 0) return
        }
    }

    private fun ByteArray.ascii(): String = toString(Charsets.US_ASCII)

    companion object {
        private const val TAG = "SerialRigBackend"
        private const val READ_BUFFER_SIZE = 64

        /** Per-transfer timeout for CAT reads/writes. */
        private const val CAT_TIMEOUT_MS = 200

        /** Overall budget for collecting a complete terminated CAT reply. */
        private const val CAT_REPLY_DEADLINE_MS = 1000L

        /** Per-read timeout while draining stale input before an exchange. */
        private const val FLUSH_READ_TIMEOUT_MS = 10

        /** Cap on drain reads so a chatty bus can't stall an exchange forever. */
        private const val MAX_FLUSH_READS = 16
    }
}
