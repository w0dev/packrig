package net.ft8vc.rig

import android.content.Context
import android.hardware.usb.UsbManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RigControllerDescriptorTest {

    private fun controller(): RigController {
        val context = mockk<Context>(relaxed = true)
        val usbManager = mockk<UsbManager>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.USB_SERVICE) } returns usbManager
        return RigController(context)
    }

    @Test
    fun stateIsNoModelUntilDescriptorSet() {
        val rig = controller()
        assertEquals(RigController.State.NoModel, rig.state())
    }

    @Test
    fun setDescriptorClearsNoModel() {
        val rig = controller()
        rig.setDescriptor(RigRegistry.byId("ft891"))
        // No USB device in a unit test, so state falls through to NoDevice — the
        // point is it is no longer NoModel once a model is chosen.
        assertEquals(RigController.State.NoDevice, rig.state())
    }

    @Test
    fun resolvePortIndex_prefersOverride_thenDescriptor_boundsChecked() {
        // override wins when in range
        assertEquals(1, RigController.resolveCatPortIndex(portCount = 2, override = 1, descriptorIndex = 0))
        // null override falls back to descriptor index
        assertEquals(0, RigController.resolveCatPortIndex(portCount = 2, override = null, descriptorIndex = 0))
        // descriptor index in range on a single-port device
        assertEquals(0, RigController.resolveCatPortIndex(portCount = 1, override = null, descriptorIndex = 0))
        // out-of-range override -> null (bind fails cleanly)
        assertNull(RigController.resolveCatPortIndex(portCount = 1, override = 3, descriptorIndex = 0))
        // out-of-range descriptor index -> null
        assertNull(RigController.resolveCatPortIndex(portCount = 1, override = null, descriptorIndex = 2))
        // no ports -> null
        assertNull(RigController.resolveCatPortIndex(portCount = 0, override = null, descriptorIndex = 0))
    }

    @Test
    fun catPortOverrideDefaultsToNull() {
        assertEquals(null, controller().catPortOverride)
    }
}
