package net.packset.ft8native.fakes

import net.packset.ft8native.Ft8DecodeResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Self-test of [Ft8DecoderFake]: proves the interface shape matches `Ft8Native`
 * and that every failure-injection switch behaves as specified. No MockK —
 * the fake is plain Kotlin asserted directly.
 */
class Ft8DecoderFakeSelfTest {

    @Test
    fun interfaceShape_matchesFt8Native_andDefaultStateReturnsEmptyDecodeNonEmptyEncode() {
        val fake = Ft8DecoderFake()
        assertTrue(fake.isAvailable())
        assertEquals("fake-1.0", fake.version())
        assertEquals(0, fake.decode(ShortArray(0), 12_000).size)

        val pcm = fake.encode("CQ TEST K1ABC", 1000f, 12_000)
        assertEquals(180_000, pcm.size) // 12_000 Hz * 15 s = full slot
    }

    @Test
    fun queueDecodeResults_returnsCannedResults_onNextDecode_thenEmpty() {
        val fake = Ft8DecoderFake()
        val canned = listOf(Ft8DecodeResult("CQ K1ABC FN42", -8, 0.5f, 1000f, 50))
        fake.queueDecodeResults(canned)

        val first = fake.decode(ShortArray(180_000), 12_000)
        assertEquals(1, first.size)
        assertEquals("CQ K1ABC FN42", first[0].message)

        // Queue is one-shot per decode call.
        val second = fake.decode(ShortArray(180_000), 12_000)
        assertEquals(0, second.size)
    }

    @Test
    fun queueDecodeResults_isFifo_acrossMultipleQueueings() {
        val fake = Ft8DecoderFake()
        fake.queueDecodeResults(listOf(Ft8DecodeResult("FIRST", -10, 0.0f, 1500f, 40)))
        fake.queueDecodeResults(listOf(Ft8DecodeResult("SECOND", -12, 0.1f, 1600f, 35)))

        val first = fake.decode(ShortArray(0), 12_000)
        val second = fake.decode(ShortArray(0), 12_000)
        val third = fake.decode(ShortArray(0), 12_000)

        assertEquals("FIRST", first[0].message)
        assertEquals("SECOND", second[0].message)
        assertEquals(0, third.size)
    }

    @Test
    fun decode_invocationsAreRecorded_withSampleCountSampleRateAndReturnedCount() {
        val fake = Ft8DecoderFake()
        fake.queueDecodeResults(listOf(Ft8DecodeResult("CQ", -5, 0.2f, 1200f, 60)))

        fake.decode(ShortArray(180_000), 12_000)
        fake.decode(ShortArray(90_000), 12_000)

        val recorded = fake.decodeInvocationsSnapshot()
        assertEquals(2, recorded.size)
        assertEquals(DecodeInvocation(sampleCount = 180_000, sampleRate = 12_000, returnedCount = 1), recorded[0])
        assertEquals(DecodeInvocation(sampleCount = 90_000, sampleRate = 12_000, returnedCount = 0), recorded[1])
    }

    @Test
    fun configureIsAvailable_false_collapsesDecodeAndEncode_toEmpty() {
        val fake = Ft8DecoderFake()
        fake.queueDecodeResults(listOf(Ft8DecodeResult("CQ", -5, 0.2f, 1200f, 60)))
        fake.configureIsAvailable(false)

        assertFalse(fake.isAvailable())
        assertEquals(0, fake.decode(ShortArray(180_000), 12_000).size)
        assertEquals(0, fake.encode("CQ TEST K1ABC", 1000f, 12_000).size)
    }

    @Test
    fun configureVersion_returnsConfiguredString_orNotLoadedWhenNull() {
        val fake = Ft8DecoderFake()
        fake.configureVersion("fake-2.5+abc123")
        assertEquals("fake-2.5+abc123", fake.version())

        fake.configureVersion(null)
        assertEquals("not loaded", fake.version())
    }

    @Test
    fun encode_default_returnsZeroFilledFullSlotShortArray() {
        val fake = Ft8DecoderFake()
        val pcm = fake.encode("CQ K1ABC FN42", 800f, 12_000)

        assertEquals(180_000, pcm.size)
        // Default producer fills silence — assert a sample to confirm.
        assertEquals(0.toShort(), pcm[0])
        assertEquals(0.toShort(), pcm[pcm.size / 2])
        assertEquals(0.toShort(), pcm[pcm.size - 1])

        val recorded = fake.encodeInvocationsSnapshot()
        assertEquals(1, recorded.size)
        assertEquals(EncodeInvocation("CQ K1ABC FN42", 800f, 12_000, 0, 180_000), recorded[0])
    }

    @Test
    fun configureEncodeProducer_overridesEncodeOutput_andInvocationIsRecorded() {
        val fake = Ft8DecoderFake()
        fake.configureEncodeProducer { _, _, _, _ -> shortArrayOf(1, 2, 3) }

        val pcm = fake.encode("CQ K1ABC FN42", 1000f, 12_000)
        assertArrayEquals(shortArrayOf(1, 2, 3), pcm)

        val recorded = fake.encodeInvocationsSnapshot()
        assertEquals(1, recorded.size)
        assertEquals(3, recorded[0].returnedSize)
    }

    @Test
    fun encodeRecordsOffsetSymbols() {
        val fake = Ft8DecoderFake()
        fake.encode("CQ TEST", 1500f, 12_000, offsetSymbols = 13)
        val inv = fake.encodeInvocationsSnapshot().single()
        assertEquals(13, inv.offsetSymbols)
    }

    @Test
    fun encodeDefaultsOffsetSymbolsToZero() {
        val fake = Ft8DecoderFake()
        fake.encode("CQ TEST", 1500f, 12_000)
        val inv = fake.encodeInvocationsSnapshot().single()
        assertEquals(0, inv.offsetSymbols)
    }
}
