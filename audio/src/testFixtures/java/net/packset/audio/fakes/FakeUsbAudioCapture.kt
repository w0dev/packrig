package net.packset.audio.fakes

import net.packset.audio.AudioEngine
import net.packset.core.AppInfo

/**
 * Test fake for [AudioEngine] — mirrors the surface `UsbAudioCapture` exposes
 * without touching `AudioRecord`. Frame delivery is driven by the test thread
 * via [injectFrames]; the production type uses a background capture thread,
 * but the fake collapses that for determinism.
 *
 * Phase 0 (FOUND-04, half of) — controller tests in Phase 1-5 use this fake to
 * simulate the realistic capture edge cases described in PITFALLS Pitfall 8:
 *
 *  - capture started but no frames yet (slot boundary not crossed)
 *  - frames arriving in arbitrary chunk shapes (injectFrames)
 *  - USB device returns zero samples with no error (configureZeroSampleMode)
 *  - device physically removed mid-session (simulateDeviceRemoved)
 */
class FakeUsbAudioCapture : AudioEngine {

    override val outputSampleRateHz: Int = AppInfo.SAMPLE_RATE_HZ

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var deviceRemoved: Boolean = false

    @Volatile
    private var zeroSampleMode: Boolean = false

    @Volatile
    private var deviceRemovedEvents: Int = 0

    @Volatile
    var lastStartDeviceId: Int? = null
        private set

    private var pendingOnFrames: ((ShortArray) -> Unit)? = null

    val isCapturing: Boolean get() = running

    val deviceRemovedEventCount: Int get() = deviceRemovedEvents

    override fun start(preferredDeviceId: Int?, onFrames: (ShortArray) -> Unit) {
        if (running) return
        lastStartDeviceId = preferredDeviceId
        pendingOnFrames = onFrames
        running = true
    }

    override fun stop() {
        running = false
        pendingOnFrames = null
    }

    /**
     * Synchronously deliver [frames] to the registered onFrames callback.
     * No-op when not running, in zero-sample mode, or after [simulateDeviceRemoved].
     * Device-removed calls increment [deviceRemovedEventCount] for assertion.
     */
    fun injectFrames(frames: ShortArray) {
        if (!running) return
        if (deviceRemoved) {
            deviceRemovedEvents += 1
            return
        }
        if (zeroSampleMode) return
        pendingOnFrames?.invoke(frames)
    }

    fun configureZeroSampleMode(enable: Boolean) {
        zeroSampleMode = enable
    }

    fun simulateDeviceRemoved() {
        deviceRemoved = true
    }

    fun reattachDevice() {
        deviceRemoved = false
    }
}
