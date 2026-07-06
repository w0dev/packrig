package net.ft8vc.rig

/**
 * The supported radios. No `default` — an unselected model is a real state the
 * app handles (see [RigController.State.NoModel]); the operator must choose.
 * The current family is all Yaesu new-CAT; Phase 3/4 append Kenwood/Icom.
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
            transportVerified = false,
        ),
    )

    fun byId(id: String): RigDescriptor? = all.firstOrNull { it.id == id }
}
