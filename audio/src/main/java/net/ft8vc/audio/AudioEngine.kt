package net.ft8vc.audio

import net.ft8vc.core.AppInfo

/**
 * Capture of mono PCM at [AppInfo.SAMPLE_RATE_HZ] from a selected input device
 * (the Digirig USB sound card in normal use).
 *
 * Phase 1 implements the capture path. The playback/TX path is added in Phase 3.
 * The contract is transport-agnostic so the FT8 engine never cares whether audio
 * comes from USB, the internal mic (VOX), or a test WAV.
 */
interface AudioEngine {

    /** Sample rate of frames delivered to [start]'s callback. */
    val outputSampleRateHz: Int

    /**
     * Begin capturing. [onFrames] is invoked on a capture thread with mono
     * 16-bit PCM decimated to [outputSampleRateHz].
     *
     * @param preferredDeviceId an [android.media.AudioDeviceInfo] id, or null for default routing.
     */
    fun start(preferredDeviceId: Int?, onFrames: (ShortArray) -> Unit)

    /** Stop capture and release resources. Safe to call when already stopped. */
    fun stop()

    companion object {
        const val DESCRIPTION = "USB audio capture @ ${AppInfo.SAMPLE_RATE_HZ} Hz (Phase 1)"
    }
}
