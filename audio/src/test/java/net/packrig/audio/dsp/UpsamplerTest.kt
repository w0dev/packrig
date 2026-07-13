package net.packrig.audio.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

class UpsamplerTest {

    @Test
    fun upsamplesByFactor() {
        val input = shortArrayOf(0, 1000, -1000)
        val out = Upsampler.linear(input, 2)
        assertEquals(6, out.size)
        assertEquals(0.toShort(), out[0])
        assertEquals(1000.toShort(), out[2])
    }

    @Test
    fun factorOneReturnsInput() {
        val input = shortArrayOf(42, 84)
        assertEquals(input, Upsampler.linear(input, 1))
    }
}
