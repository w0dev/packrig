package net.packrig.audio

/**
 * Production-side seam taken by Phase 5's `TxOrchestrator`. Mirrors the
 * minimum surface `UsbAudioPlayback` exposes (blocking play + stop) so
 * tests can substitute `FakeUsbAudioPlayback` without touching the
 * `AudioTrack` lifecycle.
 */
interface UsbPlaybackApi {
    /** Block until [samples12k] finishes playing on the USB output. Returns true on clean completion. */
    fun playBlocking(samples12k: ShortArray, preferredDeviceId: Int?): Boolean

    /** Stop any in-progress playback. Safe to call when nothing is playing. */
    fun stop()
}
