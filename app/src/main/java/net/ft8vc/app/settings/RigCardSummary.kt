package net.ft8vc.app.settings

import net.ft8vc.rig.PttMethod
import net.ft8vc.rig.RigProfile
import net.ft8vc.rig.RigRegistry

/** Pure text rules for rig card subtitles: "<model> — <CAT part>, <PTT part>". */
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
        return "${descriptor.displayName} — $cat, $ptt"
    }
}
