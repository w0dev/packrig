package net.packrig.rig

/**
 * The preset table for rig profiles (spec 2026-07-10-rig-profiles-design). No `default` — an unselected model is a
 * real state the app handles (see [RigController.State.NoModel]); the operator
 * must choose. Yaesu new-CAT and Icom CI-V families are present; Phase 3 still
 * appends Kenwood and Elecraft.
 */
object RigRegistry {

    val all: List<RigDescriptor> = listOf(
        RigDescriptor(
            id = "ft891",
            displayName = "Yaesu FT-891",
            protocolFactory = { _ -> YaesuCat(YaesuCat.FT891) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.AUTO,
            transportVerified = true,
        ),
        RigDescriptor(
            id = "ft991a",
            displayName = "Yaesu FT-991A",
            protocolFactory = { _ -> YaesuCat(YaesuModels.FT991A) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "ftdx10",
            displayName = "Yaesu FTDX10",
            protocolFactory = { _ -> YaesuCat(YaesuModels.FTDX10) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "ft710",
            displayName = "Yaesu FT-710",
            protocolFactory = { _ -> YaesuCat(YaesuModels.FT710) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "ftdx101",
            displayName = "Yaesu FTDX101D / MP",
            protocolFactory = { _ -> YaesuCat(YaesuModels.FTDX101) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "ftx1",
            displayName = "Yaesu FTX-1",
            protocolFactory = { _ -> YaesuCat(YaesuModels.FTX1) },
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            // Bench 2026-07-09, owner hardware: internal hub → stock CP2105
            // (10c4:ea70, CAT on port 0 = Enhanced) + Yaesu aux CDC device +
            // C-Media UAC codec. CAT read/write confirmed at 38400.
            transportVerified = true,
        ),
        RigDescriptor(
            id = "ic7300",
            displayName = "Icom IC-7300",
            protocolFactory = { addr -> IcomCiV(IcomModels.IC7300, addr) },
            defaultBaud = 115_200,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            civAddress = IcomModels.IC7300.civAddress,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "ic705",
            displayName = "Icom IC-705",
            protocolFactory = { addr -> IcomCiV(IcomModels.IC705, addr) },
            defaultBaud = 19_200,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            civAddress = IcomModels.IC705.civAddress,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "ic7100",
            displayName = "Icom IC-7100",
            protocolFactory = { addr -> IcomCiV(IcomModels.IC7100, addr) },
            defaultBaud = 19_200,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            civAddress = IcomModels.IC7100.civAddress,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "xiegu-g90",
            displayName = "Xiegu G90 (via Digirig)",
            protocolFactory = { addr -> IcomCiV(IcomModels.XIEGU_G90, addr) },
            defaultBaud = 19_200,
            catPortIndex = 0,
            defaultPtt = PttMethod.RTS,
            civAddress = IcomModels.XIEGU_G90.civAddress,
            transportVerified = false,
        ),
        RigDescriptor(
            id = "xiegu-x6100",
            displayName = "Xiegu X6100",
            protocolFactory = { addr -> IcomCiV(IcomModels.XIEGU_X6100, addr) },
            defaultBaud = 19_200,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            civAddress = IcomModels.XIEGU_X6100.civAddress,
            transportVerified = false,
        ),
        RigDescriptor(
            id = GENERIC_DIGIRIG,
            displayName = "Digirig — CAT + RTS PTT (generic)",
            protocolFactory = CatProtocols.byId(CatProtocols.YAESU_NEWCAT)!!.factory,
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.RTS,
            transportVerified = false,
        ),
        RigDescriptor(
            id = GENERIC_CAT,
            displayName = "USB CAT cable / built-in USB — CAT PTT (generic)",
            protocolFactory = CatProtocols.byId(CatProtocols.YAESU_NEWCAT)!!.factory,
            defaultBaud = 38_400,
            catPortIndex = 0,
            defaultPtt = PttMethod.CAT,
            transportVerified = false,
        ),
        RigDescriptor(
            id = GENERIC_RTS,
            displayName = "Serial PTT only (RTS), no CAT (generic)",
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
