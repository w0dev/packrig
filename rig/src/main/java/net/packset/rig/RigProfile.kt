package net.packset.rig

/**
 * One user-saved rig configuration (spec 2026-07-10-rig-profiles-design):
 * a preset choice plus nullable knob overrides. Null = use the preset default.
 * Persisted as JSON in app settings; field names there mirror these.
 *
 * @param id stable UUID string (selection + editing key; never reused).
 * @param presetId a [RigRegistry] id — named model or generic.
 * @param catProtocolId a [CatProtocols] id; honored only for CAT generics.
 */
data class RigProfile(
    val id: String,
    val name: String,
    val presetId: String,
    val catProtocolId: String? = null,
    val baud: Int? = null,
    val catPortIndex: Int? = null,
    val pttMethod: PttMethod? = null,
)
