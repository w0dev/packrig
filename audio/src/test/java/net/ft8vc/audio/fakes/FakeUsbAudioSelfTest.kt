package net.ft8vc.audio.fakes

import net.ft8vc.core.AppInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Self-test of [FakeUsbAudioCapture] + [FakeUsbAudioPlayback]: proves each
 * failure-injection switch behaves as specified. No MockK — both fakes are
 * pure Kotlin asserted directly.
 */
class FakeUsbAudioSelfTest {

    // -- FakeUsbAudioCapture ------------------------------------------------

    @Test
    fun capture_start_setsRunning_andNoFramesDelivered_untilInjected() {
        val capture = FakeUsbAudioCapture()
        val received = mutableListOf<ShortArray>()

        capture.start(preferredDeviceId = null) { received += it }
        assertTrue(capture.isCapturing)
        assertEquals(0, received.size)

        capture.stop()
        assertFalse(capture.isCapturing)
    }

    @Test
    fun capture_injectFrames_deliversToCallback_synchronously() {
        val capture = FakeUsbAudioCapture()
        val received = mutableListOf<ShortArray>()
        capture.start(preferredDeviceId = null) { received += it }

        capture.injectFrames(shortArrayOf(1, 2, 3, 4))
        capture.injectFrames(shortArrayOf(5, 6))

        assertEquals(2, received.size)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), received[0])
        assertArrayEquals(shortArrayOf(5, 6), received[1])
    }

    @Test
    fun capture_zeroSampleMode_discardsInjectedFrames_butIsCapturingRemainsTrue() {
        val capture = FakeUsbAudioCapture()
        val received = mutableListOf<ShortArray>()
        capture.start(preferredDeviceId = null) { received += it }

        capture.configureZeroSampleMode(true)
        capture.injectFrames(shortArrayOf(1, 2, 3))
        capture.injectFrames(shortArrayOf(4, 5))

        assertEquals(0, received.size)
        assertTrue(capture.isCapturing)
    }

    @Test
    fun capture_simulateDeviceRemoved_dropsFrames_andIncrementsCounter() {
        val capture = FakeUsbAudioCapture()
        val received = mutableListOf<ShortArray>()
        capture.start(preferredDeviceId = null) { received += it }

        capture.simulateDeviceRemoved()
        capture.injectFrames(shortArrayOf(1, 2))
        capture.injectFrames(shortArrayOf(3, 4))

        assertEquals(0, received.size)
        assertEquals(2, capture.deviceRemovedEventCount)
        assertTrue(capture.isCapturing)

        capture.reattachDevice()
        capture.injectFrames(shortArrayOf(7, 8))
        assertEquals(1, received.size)
        assertArrayEquals(shortArrayOf(7, 8), received[0])
    }

    @Test
    fun capture_lastStartDeviceId_recordsCallerArg() {
        val capture = FakeUsbAudioCapture()
        assertNull(capture.lastStartDeviceId)
        assertEquals(AppInfo.SAMPLE_RATE_HZ, capture.outputSampleRateHz)

        capture.start(preferredDeviceId = 42) { /* no-op */ }
        assertEquals(42, capture.lastStartDeviceId)
    }

    // -- FakeUsbAudioPlayback ----------------------------------------------

    @Test
    fun playback_playBlocking_recordsCall_andReturnsTrue() {
        val playback = FakeUsbAudioPlayback()
        val ok = playback.playBlocking(shortArrayOf(1, 2, 3), preferredDeviceId = null)

        assertTrue(ok)
        val records = playback.playbackRecordsSnapshot()
        assertEquals(1, records.size)
        assertArrayEquals(shortArrayOf(1, 2, 3), records[0].pcm)
        assertNull(records[0].preferredDeviceId)
        assertFalse(records[0].halted)
    }

    @Test
    fun playback_stop_haltsInFlightPlayBlocking() {
        val playback = FakeUsbAudioPlayback()
        playback.configureBlockingMs(200L)

        val stopper = Thread {
            Thread.sleep(20L)
            playback.stop()
        }.also { it.start() }

        val ok = playback.playBlocking(shortArrayOf(1, 2, 3), preferredDeviceId = 7)
        stopper.join(500L)

        assertFalse("playBlocking should return false when halted", ok)
        val records = playback.playbackRecordsSnapshot()
        assertEquals(1, records.size)
        assertTrue("recorded halted flag", records[0].halted)
        assertEquals(7, records[0].preferredDeviceId)
    }

    @Test
    fun playback_configureFailFastReturn_returnsImmediately_withoutSleeping() {
        val playback = FakeUsbAudioPlayback()
        playback.configureBlockingMs(10_000L) // Would block forever without fail-fast.
        playback.configureFailFastReturn(true)

        val startNs = System.nanoTime()
        val ok = playback.playBlocking(shortArrayOf(9), preferredDeviceId = null)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertFalse(ok)
        assertTrue("fail-fast must not sleep, elapsedMs=$elapsedMs", elapsedMs < 100L)

        val records = playback.playbackRecordsSnapshot()
        assertEquals(1, records.size)
        assertTrue(records[0].halted)
        assertNotNull(records[0].pcm)
    }
}
