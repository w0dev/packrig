package net.packset.app.settings

import net.packset.rig.RigRegistry

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
}
