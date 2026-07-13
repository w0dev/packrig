package net.packrig.app.settings

import net.packrig.rig.PttMethod
import net.packrig.rig.RigProfile
import net.packrig.rig.RigRegistry

/** Pure text rules for rig card subtitles: "<CAT part>, <PTT part>". */
object RigCardSummary {

    fun subtitle(profile: RigProfile): String {
        val descriptor = RigRegistry.byId(profile.presetId) ?: return "Unknown preset"
        val cat = if (descriptor.protocolFactory != null) {
            "CAT @ ${profile.baud ?: descriptor.defaultBaud}"
        } else {
            "no CAT"
        }
        val ptt = when (profile.pttMethod ?: descriptor.defaultPtt) {
            PttMethod.AUTO -> "auto PTT"
            PttMethod.RTS -> "RTS PTT"
            PttMethod.CAT -> "CAT PTT"
        }
        return "$cat, $ptt"
    }
}
