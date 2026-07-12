package net.ft8vc.app.settings

import android.media.AudioDeviceInfo
import net.ft8vc.audio.AudioInputDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioInputLabelTest {

    private val digirig = AudioInputDevice(
        id = 7,
        name = "Digirig",
        type = AudioDeviceInfo.TYPE_USB_DEVICE,
        isUsb = true,
    )

    @Test
    fun manualSelection_showsDeviceNameAndType() {
        assertEquals(
            "Digirig (USB device)",
            audioInputDeviceLabel(selected = digirig, manuallySelected = true),
        )
    }

    @Test
    fun autoPickedDevice_showsAutomaticPrefix() {
        assertEquals(
            "Automatic — Digirig (USB device)",
            audioInputDeviceLabel(selected = digirig, manuallySelected = false),
        )
    }

    @Test
    fun noDevice_showsSystemDefault() {
        assertEquals(
            "Automatic (system default)",
            audioInputDeviceLabel(selected = null, manuallySelected = false),
        )
    }

    @Test
    fun noDevice_ignoresManualFlag() {
        // Defensive: a stale manual id that matches no device still reads as automatic.
        assertEquals(
            "Automatic (system default)",
            audioInputDeviceLabel(selected = null, manuallySelected = true),
        )
    }
}
