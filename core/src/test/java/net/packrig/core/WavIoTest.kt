package net.packrig.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavIoTest {

    private fun buildWav(samples: ShortArray, sampleRate: Int, channels: Int): ByteArray {
        val dataLen = samples.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataLen)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1) // PCM
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * channels * 2) // byte rate
        bb.putShort((channels * 2).toShort()) // block align
        bb.putShort(16) // bits
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataLen)
        for (s in samples) bb.putShort(s)
        return bb.array()
    }

    @Test
    fun readsMono16Bit() {
        val samples = shortArrayOf(0, 100, -100, 32767, -32768, 42)
        val wav = buildWav(samples, 12_000, 1)

        val result = WavIo.readPcm16(wav)

        assertEquals(12_000, result.sampleRate)
        assertEquals(samples.size, result.samples.size)
        assertEquals(32767.toShort(), result.samples[3])
        assertEquals((-32768).toShort(), result.samples[4])
    }

    @Test
    fun downmixesStereoToMono() {
        // Interleaved L/R: (100,200) and (-100,-200) -> averages 150, -150.
        val stereo = shortArrayOf(100, 200, -100, -200)
        val wav = buildWav(stereo, 48_000, 2)

        val result = WavIo.readPcm16(wav)

        assertEquals(48_000, result.sampleRate)
        assertEquals(2, result.samples.size)
        assertEquals(150.toShort(), result.samples[0])
        assertEquals((-150).toShort(), result.samples[1])
    }
}
