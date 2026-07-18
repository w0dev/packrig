package net.packrig.rig

/** Profile → descriptor resolution: preset defaults with profile knobs on top. */
object RigProfiles {

    /**
     * Synthesize the [RigDescriptor] a profile describes, or null when the
     * preset is unknown (e.g. profile written by a newer app version). The
     * result carries the profile's id/name; all other fields come from the
     * preset unless the profile overrides them. [RigProfile.catProtocolId]
     * is honored only for CAT generics ([RigRegistry.isCatGeneric]).
     */
    fun resolve(profile: RigProfile): RigDescriptor? {
        val preset = RigRegistry.byId(profile.presetId) ?: return null
        val protocolOverride =
            if (RigRegistry.isCatGeneric(preset.id)) {
                profile.catProtocolId?.let { CatProtocols.byId(it)?.factory }
            } else {
                null
            }
        return preset.copy(
            id = profile.id,
            displayName = profile.name,
            protocolFactory = protocolOverride ?: preset.protocolFactory,
            defaultBaud = profile.baud ?: preset.defaultBaud,
            catPortIndex = profile.catPortIndex ?: preset.catPortIndex,
            defaultPtt = profile.pttMethod ?: preset.defaultPtt,
            civAddress = profile.civAddress ?: preset.civAddress,
        )
    }
}
