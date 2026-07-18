package net.packrig.app.settings

import net.packrig.rig.CatProtocols
import net.packrig.rig.RigRegistry

/** Pure visibility rules for the rig profile editor form. */
object RigProfileForm {

    /**
     * The CAT port override applies to every CAT-capable preset — named models
     * included, so multi-port bridges (e.g. FTX-1's CP2105) stay adjustable
     * without switching to a generic preset (owner decision 2026-07-11).
     * Hidden on single-port bridges, where there is nothing to choose.
     */
    fun showsCatPortPicker(presetId: String, portCount: Int): Boolean =
        RigRegistry.byId(presetId)?.protocolFactory != null && portCount > 1

    /** The CI-V address field: always on CI-V presets (prefilled, editable —
     *  operators can move the rig's address, and a mismatch is otherwise
     *  undebuggable); on CAT generics only once Icom CI-V is the chosen
     *  protocol, where it is required. */
    fun showsCivAddressField(presetId: String, catProtocolId: String?): Boolean {
        val preset = RigRegistry.byId(presetId) ?: return false
        if (preset.civAddress != null) return true
        return RigRegistry.isCatGeneric(presetId) && catProtocolId == CatProtocols.ICOM_CIV
    }

    /** Two hex digits, 01–DF (E0 is this app's own address on the bus). */
    fun parseCivAddress(text: String): Int? =
        text.trim().takeIf { it.length in 1..2 }?.toIntOrNull(16)?.takeIf { it in 0x01..0xDF }

    fun civAddressError(text: String, presetId: String, catProtocolId: String?): String? {
        if (!showsCivAddressField(presetId, catProtocolId)) return null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return if (RigRegistry.isCatGeneric(presetId)) {
                "Enter the radio's CI-V address — two hex digits from its menu, e.g. 94"
            } else {
                null // blank keeps the preset's factory default
            }
        }
        return if (parseCivAddress(trimmed) == null) {
            "CI-V address is two hex digits between 01 and DF"
        } else {
            null
        }
    }

    /** Fixed protocol family of a named CAT preset, for the read-only info row.
     *  Null for generics (they pick their own) and CAT-less presets. */
    fun protocolLabel(presetId: String): String? {
        val preset = RigRegistry.byId(presetId) ?: return null
        if (RigRegistry.isGeneric(presetId) || preset.protocolFactory == null) return null
        return if (preset.civAddress != null) "Icom CI-V" else "Yaesu CAT"
    }
}
