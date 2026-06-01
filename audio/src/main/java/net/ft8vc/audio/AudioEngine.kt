package net.ft8vc.audio

import net.ft8vc.core.AppInfo

/**
 * Capture and playback of 12 kHz mono PCM through the Digirig USB sound card.
 *
 * Phase 1 implements the capture path (USB device selection, AAudio/Oboe input,
 * ring buffering, level/clip metering). Phase 3 adds the playback/TX path.
 * This interface is intentionally transport-agnostic so the FT8 engine never
 * cares whether audio comes from USB, the internal mic (VOX), or a test WAV.
 */
interface AudioEngine {

    /** Begin capturing mono PCM frames at [AppInfo.SAMPLE_RATE_HZ]. */
    fun startCapture(onFrames: (ShortArray) -> Unit)

    /** Stop any active capture or playback and release resources. */
    fun stop()

    companion object {
        const val DESCRIPTION = "USB audio I/O @ ${AppInfo.SAMPLE_RATE_HZ} Hz (Phase 1)"
    }
}
