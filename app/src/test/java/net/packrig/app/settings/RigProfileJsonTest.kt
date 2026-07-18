package net.packrig.app.settings

import net.packrig.rig.PttMethod
import net.packrig.rig.RigProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RigProfileJsonTest {

    private val full = RigProfile(
        id = "a1b2",
        name = "Home FT-891",
        presetId = "ft891",
        catProtocolId = "yaesu-newcat",
        baud = 4_800,
        catPortIndex = 1,
        pttMethod = PttMethod.CAT,
    )
    private val sparse = RigProfile(id = "c3d4", name = "SOTA — no CAT", presetId = "generic-rts")

    @Test
    fun roundTripPreservesEveryFieldAndOrder() {
        assertEquals(listOf(full, sparse), RigProfileJson.decode(RigProfileJson.encode(listOf(full, sparse))))
    }

    @Test
    fun nullFieldsSurviveRoundTrip() {
        val decoded = RigProfileJson.decode(RigProfileJson.encode(listOf(sparse))).single()
        assertEquals(null, decoded.catProtocolId)
        assertEquals(null, decoded.baud)
        assertEquals(null, decoded.catPortIndex)
        assertEquals(null, decoded.pttMethod)
    }

    @Test
    fun corruptInputDecodesToEmpty() {
        assertTrue(RigProfileJson.decode(null).isEmpty())
        assertTrue(RigProfileJson.decode("").isEmpty())
        assertTrue(RigProfileJson.decode("not json").isEmpty())
        assertTrue(RigProfileJson.decode("""{"v":1,"profiles":[{"name":"missing id"}]}""").isEmpty())
        assertTrue(RigProfileJson.decode("""{"v":99,"profiles":[]}""").isEmpty())
        assertTrue(
            RigProfileJson.decode(
                """{"v":1,"profiles":[{"id":"x","name":"n","preset":"ft891","baud":"fast"}]}""",
            ).isEmpty(),
        )
        assertTrue(RigProfileJson.decode("""{"v":1,"profiles":"nope"}""").isEmpty())
        assertTrue(RigProfileJson.decode("""{"v":1,"profiles":[42]}""").isEmpty())
    }

    @Test
    fun unknownPttMethodStringDecodesAsNullNotCrash() {
        val raw = """{"v":1,"profiles":[{"id":"x","name":"n","preset":"ft891","ptt":"LASER"}]}"""
        assertEquals(null, RigProfileJson.decode(raw).single().pttMethod)
    }

    @Test
    fun namesWithQuotesAndUnicodeRoundTrip() {
        val tricky = sparse.copy(name = """Bob's "portable" — 891 ✓""")
        assertEquals(tricky, RigProfileJson.decode(RigProfileJson.encode(listOf(tricky))).single())
    }

    @Test
    fun civAddress_roundTripsAndOmitsWhenNull() {
        val with = RigProfile(id = "a", name = "n", presetId = "ic7300", civAddress = 0x76)
        val without = RigProfile(id = "b", name = "m", presetId = "ft891")
        val decoded = RigProfileJson.decode(RigProfileJson.encode(listOf(with, without)))
        assertEquals(0x76, decoded[0].civAddress)
        assertNull(decoded[1].civAddress)
    }
}
