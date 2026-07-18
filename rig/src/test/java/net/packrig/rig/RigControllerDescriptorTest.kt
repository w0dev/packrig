package net.packrig.rig

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

    // Whole-branch review finding (icom-civ, 2026-07-17): the live CAT path
    // must bind the *resolved* profile (protocol + CI-V address), not a raw
    // preset lookup. This is the closest feasible seam to the app-level
    // radio-model mirror in OperateViewModel — RigProfiles.resolve() honoring
    // catProtocolId/civAddress feeds straight into RigController.setDescriptor,
    // which is exactly what the mirror does off the CAT dispatcher. There is
    // no Robolectric/harness in this repo to instantiate OperateViewModel
    // itself (it needs AudioManager/USB framework classes), so the mirror's
    // collect-block logic (which profile wins, id-vs-value comparison) is not
    // exercised end-to-end here — only the resolve+bind contract it depends on.
    @Test
    fun setDescriptor_bindsResolvedProfile_protocolAndCivAddressHonored() {
        val rig = controller()
        val profile = RigProfile(
            id = "profile-uuid-1",
            name = "My Icom",
            presetId = RigRegistry.GENERIC_CAT,
            catProtocolId = CatProtocols.ICOM_CIV,
            civAddress = 0xA2,
        )
        val descriptor = RigProfiles.resolve(profile)
        rig.setDescriptor(descriptor)

        val bound = rig.descriptor
        assertEquals(0xA2, bound?.civAddress)
        val protocol = bound?.protocolFactory?.invoke(bound.civAddress)
        assertEquals(true, protocol is IcomCiV)
        assertEquals(0xA2, (protocol as IcomCiV).civAddress)
    }

    @Test
    fun setDescriptor_sameValueIsNoOp_butEditedFieldOnSameIdRebinds() {
        val rig = controller()
        val profile = RigProfile(
            id = "profile-uuid-2",
            name = "My Icom",
            presetId = RigRegistry.GENERIC_CAT,
            catProtocolId = CatProtocols.ICOM_CIV,
            civAddress = 0x58,
        )
        val original = RigProfiles.resolve(profile)!!
        rig.setDescriptor(original)
        assertEquals(original, rig.descriptor)

        // Same value, different instance (data class equality) -> no-op.
        rig.setDescriptor(original.copy())
        assertEquals(original, rig.descriptor)

        // Same UUID (profile edit), civAddress changed -> must rebind: the old
        // id-only guard (`descriptor?.id == d?.id`) would have short-circuited
        // here and left the stale address bound.
        val edited = RigProfiles.resolve(profile.copy(civAddress = 0xA2))!!
        rig.setDescriptor(edited)
        assertEquals(0xA2, rig.descriptor?.civAddress)
        assertEquals(edited, rig.descriptor)
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
            listOf("Enhanced port — CAT", "Standard port"),
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
