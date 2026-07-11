package net.ft8vc.app.controllers

import net.ft8vc.app.controllers.MonitorGate.RemovalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorGateTest {

    // All conditions met — the only combination that starts monitor capture.
    private fun start(
        autoMonitorEnabled: Boolean = true,
        appInForeground: Boolean = true,
        usbAudioInputPresent: Boolean = true,
        recordAudioGranted: Boolean = true,
        isCapturing: Boolean = false,
        isTransmitting: Boolean = false,
    ) = MonitorGate.shouldStartMonitor(
        autoMonitorEnabled = autoMonitorEnabled,
        appInForeground = appInForeground,
        usbAudioInputPresent = usbAudioInputPresent,
        recordAudioGranted = recordAudioGranted,
        isCapturing = isCapturing,
        isTransmitting = isTransmitting,
    )

    @Test fun `starts when all conditions met`() {
        assertTrue(start())
    }

    @Test fun `setting off blocks start`() {
        assertFalse(start(autoMonitorEnabled = false))
    }

    @Test fun `backgrounded app blocks start`() {
        assertFalse(start(appInForeground = false))
    }

    @Test fun `no usb audio input blocks start`() {
        assertFalse(start(usbAudioInputPresent = false))
    }

    @Test fun `missing record permission blocks start`() {
        assertFalse(start(recordAudioGranted = false))
    }

    @Test fun `already capturing blocks start`() {
        assertFalse(start(isCapturing = true))
    }

    @Test fun `transmitting blocks start`() {
        assertFalse(start(isTransmitting = true))
    }

    @Test fun `background stops monitor-only capture`() {
        assertTrue(MonitorGate.shouldStopOnBackground(isCapturing = true, isOperating = false))
    }

    @Test fun `background leaves operating session alone`() {
        assertFalse(MonitorGate.shouldStopOnBackground(isCapturing = true, isOperating = true))
    }

    @Test fun `background with no capture is a no-op`() {
        assertFalse(MonitorGate.shouldStopOnBackground(isCapturing = false, isOperating = false))
    }

    @Test fun `unplug while monitoring with no usb left stops quietly`() {
        assertEquals(
            RemovalAction.STOP_QUIETLY,
            MonitorGate.onUsbAudioInputRemoved(
                isCapturing = true, isOperating = false, usbInputStillPresent = false,
            ),
        )
    }

    @Test fun `unplug while monitoring with another usb input restarts`() {
        assertEquals(
            RemovalAction.RESTART_WITH_NOTICE,
            MonitorGate.onUsbAudioInputRemoved(
                isCapturing = true, isOperating = false, usbInputStillPresent = true,
            ),
        )
    }

    @Test fun `unplug while operating keeps existing restart behavior`() {
        assertEquals(
            RemovalAction.RESTART_WITH_NOTICE,
            MonitorGate.onUsbAudioInputRemoved(
                isCapturing = true, isOperating = true, usbInputStillPresent = false,
            ),
        )
    }

    @Test fun `unplug while not capturing is ignored`() {
        assertEquals(
            RemovalAction.IGNORE,
            MonitorGate.onUsbAudioInputRemoved(
                isCapturing = false, isOperating = false, usbInputStillPresent = false,
            ),
        )
    }
}
