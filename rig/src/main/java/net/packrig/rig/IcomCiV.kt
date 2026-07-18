package net.packrig.rig

/**
 * Icom CI-V binary protocol (also spoken by Xiegu), parameterized by
 * [IcomModelSpec] and an optional bus-address override (operators can move a
 * rig off its factory address). Frames are `FE FE <to> <from> <cmd> [data] FD`;
 * frequencies are 5 BCD bytes, 1-Hz digit pair first. 0xFD never occurs inside
 * a frame body, so splitting on it is sound.
 */
class IcomCiV(
    val model: IcomModelSpec,
    civAddressOverride: Int? = null,
) : CatProtocol {

    val civAddress: Int = civAddressOverride ?: model.civAddress

    override val dataModeLabel: String =
        if (model.dataModeStrategy == DataModeStrategy.CMD_06_ONLY) "USB" else "USB-D"

    override val wantsInputFlush: Boolean = true

    override val setCommandsAcked: Boolean = true

    override fun readFrequencyCommand(): ByteArray = frame(CMD_READ_FREQ)

    override fun setFrequencyCommand(hz: Long): ByteArray? {
        if (hz !in model.minFreqHz..model.maxFreqHz) return null
        return frame(CMD_SET_FREQ, *bcdFrequency(hz))
    }

    override fun parseFrequency(reply: ByteArray): Long? {
        val body = commandBody(reply) ?: return null
        val cmd = body[0].toInt() and 0xFF
        // 0x03 = poll reply; 0x00 = transceive broadcast. Same payload layout.
        if (cmd != CMD_READ_FREQ && cmd != CMD_TRANSCEIVE_FREQ) return null
        val data = body.copyOfRange(1, body.size)
        if (data.size < 5) return null
        return bcdToHz(data)
    }

    override fun readModeCommand(): ByteArray = frame(CMD_READ_MODE)

    override fun parseModeLabel(reply: ByteArray): String? {
        val body = commandBody(reply) ?: return null
        val cmd = body[0].toInt() and 0xFF
        if (cmd != CMD_READ_MODE && cmd != CMD_TRANSCEIVE_MODE) return null
        if (body.size < 2) return null
        // Mode codes are matched as raw byte values: 0x00–0x08 read the same
        // raw or BCD, and DV arrives as byte 0x17 (BCD "17") — the label map
        // is keyed accordingly.
        return model.modeLabels[body[1].toInt() and 0xFF]
    }

    override fun setDataModeCommand(): ByteArray = when (model.dataModeStrategy) {
        // 0x26 0x00 = selected VFO: mode USB, data on, FIL1.
        DataModeStrategy.CMD_26 -> frame(0x26, 0x00, MODE_USB, 0x01, 0x01)
        // Two frames in one write: USB via 0x06, then data mode via 0x1A 0x06.
        // The backend awaits the first ack; the flush drains the second.
        DataModeStrategy.CMD_06_PLUS_1A ->
            frame(0x06, MODE_USB, 0x01) + frame(0x1A, 0x06, 0x01, 0x01)
        DataModeStrategy.CMD_06_ONLY -> frame(0x06, MODE_USB, 0x01)
    }

    override fun pttCommand(on: Boolean): ByteArray =
        frame(0x1C, 0x00, if (on) 0x01 else 0x00)

    override fun splitFrames(bytes: ByteArray): FrameSplit {
        val frames = mutableListOf<ByteArray>()
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] == END.toByte()) {
                // Discard pre-preamble line noise inside the chunk.
                var head = start
                while (head < i - 1 &&
                    !(bytes[head] == PREAMBLE.toByte() && bytes[head + 1] == PREAMBLE.toByte())
                ) {
                    head++
                }
                frames += bytes.copyOfRange(head, i + 1)
                start = i + 1
            }
        }
        return FrameSplit(frames, bytes.copyOfRange(start, bytes.size))
    }

    override fun classifyFrame(frame: ByteArray): FrameClass {
        // Shortest legal frame is 6 bytes (FE FE to from cmd FD).
        if (frame.size < 6) return FrameClass.Junk
        if (frame[0] != PREAMBLE.toByte() || frame[1] != PREAMBLE.toByte()) return FrameClass.Junk
        if (frame.last() != END.toByte()) return FrameClass.Junk
        val to = frame[2].toInt() and 0xFF
        val from = frame[3].toInt() and 0xFF
        val cmd = frame[4].toInt() and 0xFF
        return when {
            from == CONTROLLER -> FrameClass.Echo
            from != civAddress -> FrameClass.Junk
            to != CONTROLLER && to != BROADCAST -> FrameClass.Junk
            cmd == RESULT_OK -> FrameClass.Ack
            cmd == RESULT_NG -> FrameClass.Nak
            cmd == CMD_TRANSCEIVE_FREQ || cmd == CMD_TRANSCEIVE_MODE -> FrameClass.Broadcast
            else -> FrameClass.Reply
        }
    }

    /** `[cmd, data…]` of a well-formed frame addressed to this controller. */
    private fun commandBody(frame: ByteArray): ByteArray? {
        if (frame.size < 6) return null
        if (frame[0] != PREAMBLE.toByte() || frame[1] != PREAMBLE.toByte()) return null
        if (frame.last() != END.toByte()) return null
        val to = frame[2].toInt() and 0xFF
        val from = frame[3].toInt() and 0xFF
        if (from != civAddress || (to != CONTROLLER && to != BROADCAST)) return null
        return frame.copyOfRange(4, frame.size - 1)
    }

    private fun frame(vararg body: Int): ByteArray {
        val out = ByteArray(body.size + 5)
        out[0] = PREAMBLE.toByte()
        out[1] = PREAMBLE.toByte()
        out[2] = civAddress.toByte()
        out[3] = CONTROLLER.toByte()
        body.forEachIndexed { i, b -> out[4 + i] = b.toByte() }
        out[out.size - 1] = END.toByte()
        return out
    }

    /** 5 BCD bytes, little-endian digit pairs: byte k = digit(2k+1)<<4 | digit(2k). */
    private fun bcdFrequency(hz: Long): IntArray {
        val out = IntArray(5)
        var rest = hz
        for (k in 0 until 5) {
            val lo = (rest % 10).toInt()
            val hi = ((rest / 10) % 10).toInt()
            out[k] = (hi shl 4) or lo
            rest /= 100
        }
        return out
    }

    private fun bcdToHz(data: ByteArray): Long? {
        var hz = 0L
        var scale = 1L
        for (k in 0 until 5) {
            val lo = data[k].toInt() and 0x0F
            val hi = (data[k].toInt() shr 4) and 0x0F
            if (lo > 9 || hi > 9) return null
            hz += lo * scale + hi * scale * 10
            scale *= 100
        }
        return hz
    }

    companion object {
        const val CONTROLLER = 0xE0
        const val BROADCAST = 0x00
        private const val PREAMBLE = 0xFE
        private const val END = 0xFD
        private const val RESULT_OK = 0xFB
        private const val RESULT_NG = 0xFA
        private const val CMD_TRANSCEIVE_FREQ = 0x00
        private const val CMD_TRANSCEIVE_MODE = 0x01
        private const val CMD_READ_FREQ = 0x03
        private const val CMD_READ_MODE = 0x04
        private const val CMD_SET_FREQ = 0x05
        private const val MODE_USB = 0x01
    }
}
