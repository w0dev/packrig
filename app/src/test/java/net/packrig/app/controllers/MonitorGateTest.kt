package net.packrig.app.controllers

import net.packrig.app.controllers.MonitorGate.RemovalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorGateTest {

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
