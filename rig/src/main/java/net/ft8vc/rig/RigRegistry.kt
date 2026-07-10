package net.ft8vc.rig

/**
 * The preset table for rig profiles. No `default` — an unselected model is a
 * real state the app handles (see [RigController.State.NoModel]); the operator
 * must choose. The current family is all Yaesu new-CAT; Phase 3/4 append
 * Kenwood/Icom.
 */
object RigRegistry {

    val all: List<RigDescriptor> = listOf(
        RigDescriptor(
            id = "ft891",
            displayName = "Yaesu FT-891",
            protocolFactory = { YaesuCat(YaesuCat.FT891) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.AUTO,
            transportVerified = true,
        ),
        RigDescriptor(
            id = "ft991a",
            displayName = "Yaesu FT-991A",
            protocolFactory = { YaesuCat(YaesuModels.FT991A) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "ftdx10",
            displayName = "Yaesu FTDX10",
            protocolFactory = { YaesuCat(YaesuModels.FTDX10) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "ft710",
            displayName = "Yaesu FT-710",
            protocolFactory = { YaesuCat(YaesuModels.FT710) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "ftdx101",
            displayName = "Yaesu FTDX101",
            protocolFactory = { YaesuCat(YaesuModels.FTDX101) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "ftx1",
            displayName = "Yaesu FTX-1",
            protocolFactory = { YaesuCat(YaesuModels.FTX1) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            // Bench 2026-07-09, owner hardware: internal hub → stock CP2105
            // (10c4:ea70, CAT on port 0 = Enhanced) + Yaesu aux CDC device +
            // C-Media UAC codec. CAT read/write confirmed at 38400.
            transportVerified = true,
        ),
        RigDescriptor(
            id = GENERIC_DIGIRIG,
            displayName = "Digirig with CAT (generic)",
            protocolFactory = CatProtocols.byId(CatProtocols.YAESU_NEWCAT)!!.factory,
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.RTS,
            transportVerified = false,
        ),
        RigDescriptor(
            id = GENERIC_CAT,
            displayName = "USB CAT cable / built-in USB (generic)",
            protocolFactory = CatProtocols.byId(CatProtocols.YAESU_NEWCAT)!!.factory,
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            transportVerified = false,
        ),
        RigDescriptor(
            id = GENERIC_RTS,
            displayName = "Audio only — no CAT (generic)",
            protocolFactory = null,
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.RTS,
            transportVerified = false,
        ),
    )

    /** Preset ids for the user-configured generics. Persisted — never rename. */
    const val GENERIC_DIGIRIG = "generic-digirig"
    const val GENERIC_CAT = "generic-cat"
    const val GENERIC_RTS = "generic-rts"

    val generics: List<RigDescriptor> get() = all.filter { isGeneric(it.id) }

    fun isGeneric(id: String): Boolean =
        id == GENERIC_DIGIRIG || id == GENERIC_CAT || id == GENERIC_RTS

    /** Generics whose CAT protocol is a user-facing choice. */
    fun isCatGeneric(id: String): Boolean = id == GENERIC_DIGIRIG || id == GENERIC_CAT

    fun byId(id: String): RigDescriptor? = all.firstOrNull { it.id == id }
}
