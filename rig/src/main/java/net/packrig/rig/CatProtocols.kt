package net.packrig.rig

/**
 * CAT protocol families a generic preset can speak. One entry today; Phase 3
 * (Kenwood) and Phase 4 (Icom CI-V) add entries here — never new presets.
 * Ids are persisted in [RigProfile.catProtocolId]; never rename.
 */
object CatProtocols {

    const val YAESU_NEWCAT = "yaesu-newcat"
    const val ICOM_CIV = "icom-civ"

    data class Entry(
        val id: String,
        val displayName: String,
        val factory: (civAddress: Int?) -> CatProtocol,
    )

    val all: List<Entry> = listOf(
        Entry(
            id = YAESU_NEWCAT,
            displayName = "Yaesu CAT",
            factory = { _ -> YaesuCat(YaesuModels.GENERIC) },
        ),
        Entry(
            id = ICOM_CIV,
            displayName = "Icom CI-V (Icom, Xiegu)",
            factory = { addr -> IcomCiV(IcomModels.GENERIC, addr) },
        ),
    )

    fun byId(id: String): Entry? = all.firstOrNull { it.id == id }
}
