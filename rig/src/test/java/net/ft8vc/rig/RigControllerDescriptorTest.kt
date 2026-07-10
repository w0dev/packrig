package net.ft8vc.rig

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
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

    // FTX-1 bench regression (2026-07-09): the stock prober class-probes generic
    // CDC-ACM devices, so a multi-device rig yields several candidates in hash
    // order — the rig's aux CDC device must never beat its CP2105 CAT bridge.
    @Test
    fun preferVendorBridge_vendorBeatsClassProbedCdc_regardlessOfOrder() {
        val cdc = CdcAcmSerialDriver(mockk(relaxed = true))
        val vendor = object : UsbSerialDriver {
            override fun getDevice(): android.hardware.usb.UsbDevice = mockk(relaxed = true)
            override fun getPorts(): List<UsbSerialPort> = emptyList()
        }
        assertEquals(vendor, RigController.preferVendorBridge(listOf(cdc, vendor)))
        assertEquals(vendor, RigController.preferVendorBridge(listOf(vendor, cdc)))
    }

    @Test
    fun preferVendorBridge_cdcOnlyStillWins_andEmptyIsNull() {
        val cdc = CdcAcmSerialDriver(mockk(relaxed = true))
        assertEquals(cdc, RigController.preferVendorBridge(listOf(cdc)))
        assertNull(RigController.preferVendorBridge(emptyList()))
    }

    @Test
    fun portDisplayNames_cp21xxDualGetsYaesuVocabulary_othersGeneric() {
        assertEquals(
            listOf("Enhanced port — CAT (default)", "Standard port"),
            RigController.portDisplayNames(portCount = 2, isCp21xx = true),
        )
        // Generic 1-based names for anything else (0-based is programmer-speak).
        assertEquals(
            listOf("Serial port 1", "Serial port 2", "Serial port 3"),
            RigController.portDisplayNames(portCount = 3, isCp21xx = true),
        )
        assertEquals(
            listOf("Serial port 1", "Serial port 2"),
            RigController.portDisplayNames(portCount = 2, isCp21xx = false),
        )
        assertEquals(listOf("Serial port 1"), RigController.portDisplayNames(1, isCp21xx = true))
        assertEquals(emptyList<String>(), RigController.portDisplayNames(0, isCp21xx = false))
    }
}
