package net.packrig.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoded WAV contents (downmixed to mono 16-bit PCM). */
data class WavData(
    val samples: ShortArray,
    val sampleRate: Int,
) {
    // Arrays need custom equals/hashCode; not used for comparison in practice.
    override fun equals(other: Any?): Boolean =
        this === other || (other is WavData && sampleRate == other.sampleRate && samples.contentEquals(other.samples))

    override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRate
}

/**
 * Minimal reader for uncompressed 16-bit PCM WAV files at 12 kHz mono. Stereo is
 * downmixed to mono. Used to load golden test clips for decoder regression tests.
 */
object WavIo {

    fun readPcm16(bytes: ByteArray): WavData {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12) { "file too small" }
        require(readTag(buf, 0) == "RIFF") { "not a RIFF file" }
        require(readTag(buf, 8) == "WAVE") { "not a WAVE file" }

        var channels = 1
        var sampleRate = 0
        var bitsPerSample = 16
        var dataOffset = -1
        var dataLen = 0

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkId = readTag(buf, pos)
            val chunkSize = buf.getInt(pos + 4)
            val body = pos + 8
            when (chunkId) {
                "fmt " -> {
                    channels = buf.getShort(body + 2).toInt()
                    sampleRate = buf.getInt(body + 4)
                    bitsPerSample = buf.getShort(body + 14).toInt()
                }
                "data" -> {
                    dataOffset = body
                    dataLen = minOf(chunkSize, bytes.size - body)
                }
            }
            // Chunks are word-aligned (even sizes).
            pos = body + chunkSize + (chunkSize and 1)
        }

        require(bitsPerSample == 16) { "only 16-bit PCM supported, got $bitsPerSample" }
        require(dataOffset >= 0 && sampleRate > 0) { "missing fmt/data chunk" }

        val totalShorts = dataLen / 2
        val frames = if (channels > 0) totalShorts / channels else totalShorts
        val mono = ShortArray(frames)
        for (i in 0 until frames) {
            if (channels <= 1) {
                mono[i] = buf.getShort(dataOffset + i * 2)
            } else {
                var acc = 0
                for (c in 0 until channels) {
                    acc += buf.getShort(dataOffset + (i * channels + c) * 2).toInt()
                }
                mono[i] = (acc / channels).toShort()
            }
        }
        return WavData(mono, sampleRate)
    }

    private fun readTag(buf: ByteBuffer, offset: Int): String {
        val chars = CharArray(4) { (buf.get(offset + it).toInt() and 0xFF).toChar() }
        return String(chars)
    }
}
