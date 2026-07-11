package net.ft8vc.rig

/** Outcome of a one-shot Test CAT probe (spec 2026-07-10, Diagnostics). */
sealed interface ProbeResult {
    /** The rig answered a frequency query — CAT works at these settings. */
    data class Sync(val freqHz: Long) : ProbeResult

    /** Bytes arrived but didn't parse — classic wrong-baud symptom. */
    data object Garbage : ProbeResult

    /** Nothing arrived — wrong port, cable, or rig CAT menu off. */
    data object Silence : ProbeResult

    data object NoDevice : ProbeResult
    data object NoPermission : ProbeResult

    /** The profile has no CAT protocol (generic-rts). */
    data object NoCat : ProbeResult
}
