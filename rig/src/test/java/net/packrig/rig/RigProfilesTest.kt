package net.packrig.rig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RigProfilesTest {

    private fun profile(
        presetId: String,
        catProtocolId: String? = null,
        baud: Int? = null,
        catPortIndex: Int? = null,
        pttMethod: PttMethod? = null,
    ) = RigProfile(
        id = "test-uuid",
        name = "My Rig",
        presetId = presetId,
        catProtocolId = catProtocolId,
        baud = baud,
        catPortIndex = catPortIndex,
        pttMethod = pttMethod,
    )

    @Test
    fun ft891ParityAllNullKnobsEqualsRegistryEntry() {
        // A migrated FT-891 profile must be byte-equivalent to today's registry
        // entry — only identity fields (id, displayName) may differ.
        val resolved = RigProfiles.resolve(profile("ft891"))!!
        val preset = RigRegistry.byId("ft891")!!
        assertEquals(preset.copy(id = "test-uuid", displayName = "My Rig"), resolved)
        assertSame(preset.protocolFactory, resolved.protocolFactory)
    }

    @Test
    fun nonNullKnobsOverridePresetDefaults() {
        val resolved = RigProfiles.resolve(
            profile("ft891", baud = 4_800, catPortIndex = 1, pttMethod = PttMethod.CAT),
        )!!
        assertEquals(4_800, resolved.defaultBaud)
        assertEquals(1, resolved.catPortIndex)
        assertEquals(PttMethod.CAT, resolved.defaultPtt)
    }

    @Test
    fun unknownPresetResolvesNull() {
        assertNull(RigProfiles.resolve(profile("kenwood-ts590")))
    }

    @Test
    fun protocolKnobHonoredOnlyForCatGenerics() {
        val named = RigProfiles.resolve(profile("ft891", catProtocolId = CatProtocols.YAESU_NEWCAT))!!
        assertSame(RigRegistry.byId("ft891")!!.protocolFactory, named.protocolFactory)

        val generic = RigProfiles.resolve(
            profile(RigRegistry.GENERIC_CAT, catProtocolId = CatProtocols.YAESU_NEWCAT),
        )!!
        assertSame(CatProtocols.byId(CatProtocols.YAESU_NEWCAT)!!.factory, generic.protocolFactory)
    }

    @Test
    fun genericRtsResolvesWithNoProtocolRegardlessOfKnob() {
        val resolved = RigProfiles.resolve(
            profile(RigRegistry.GENERIC_RTS, catProtocolId = CatProtocols.YAESU_NEWCAT),
        )!!
        assertNull(resolved.protocolFactory)
        assertEquals(PttMethod.RTS, resolved.defaultPtt)
    }

    @Test
    fun unknownProtocolIdFallsBackToPresetFactory() {
        val resolved = RigProfiles.resolve(
            profile(RigRegistry.GENERIC_DIGIRIG, catProtocolId = "kenwood-ts590-cat"),
        )!!
        assertSame(RigRegistry.byId(RigRegistry.GENERIC_DIGIRIG)!!.protocolFactory, resolved.protocolFactory)
    }

    @Test
    fun civAddress_overrideWinsPresetDefaultFallsThrough() {
        val preset = RigRegistry.byId("ic7300")!!
        assertEquals(0x94, preset.civAddress)
        val defaulted = RigProfiles.resolve(
            RigProfile(id = "p1", name = "My 7300", presetId = "ic7300"),
        )!!
        assertEquals(0x94, defaulted.civAddress)
        val moved = RigProfiles.resolve(
            RigProfile(id = "p2", name = "Moved 7300", presetId = "ic7300", civAddress = 0x76),
        )!!
        assertEquals(0x76, moved.civAddress)
        val protocol = moved.protocolFactory!!.invoke(moved.civAddress) as IcomCiV
        assertEquals(0x76, protocol.civAddress)
    }

    @Test
    fun catGeneric_icomCivProtocolUsesProfileAddress() {
        val resolved = RigProfiles.resolve(
            RigProfile(
                id = "p3", name = "Bench CI-V", presetId = RigRegistry.GENERIC_CAT,
                catProtocolId = CatProtocols.ICOM_CIV, civAddress = 0xA2,
            ),
        )!!
        val protocol = resolved.protocolFactory!!.invoke(resolved.civAddress) as IcomCiV
        assertEquals(0xA2, protocol.civAddress)
    }
}
