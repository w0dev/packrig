package net.packset.rig

/**
 * CAT protocol families a generic preset can speak. One entry today; Phase 3
 * (Kenwood) and Phase 4 (Icom CI-V) add entries here — never new presets.
 * Ids are persisted in [RigProfile.catProtocolId]; never rename.
 */
object CatProtocols {

    const val YAESU_NEWCAT = "yaesu-newcat"

    data class Entry(
        val id: String,
        val displayName: String,
        val factory: () -> CatProtocol,
    )

    val all: List<Entry> = listOf(
        Entry(
            id = YAESU_NEWCAT,
            displayName = "Yaesu CAT",
            factory = { YaesuCat(YaesuModels.GENERIC) },
        ),
    )

    fun byId(id: String): Entry? = all.firstOrNull { it.id == id }
}
