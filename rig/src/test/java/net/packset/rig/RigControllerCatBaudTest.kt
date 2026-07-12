package net.packset.rig

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * catBaud must default to RigController.DEFAULT_CAT_BAUD so an untouched
 * install binds at 38400 exactly as v1.0 did (behavior parity), and must be
 * assignable so a persisted setting applies to the next bind/rebind.
 */
class RigControllerCatBaudTest {

    private fun controller(): RigController {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        return RigController(context)
    }

    @Test
    fun catBaudDefaultsToBackendDefault() {
        assertEquals(RigController.DEFAULT_CAT_BAUD, controller().catBaud)
    }

    @Test
    fun catBaudIsAssignable() {
        val rig = controller()
        rig.catBaud = 4800
        assertEquals(4800, rig.catBaud)
    }
}
