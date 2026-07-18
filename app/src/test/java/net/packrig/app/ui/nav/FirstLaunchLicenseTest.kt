package net.packrig.app.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLaunchLicenseTest {

    @Test
    fun shows_onlyWhenHydratedAndUnacknowledged() {
        assertTrue(FirstLaunchLicense.shows(settingsLoaded = true, licenseAcknowledged = false))
        // The load-bearing case: defaults while DataStore hydrates must NOT
        // flash the dialog at already-acknowledged users.
        assertFalse(FirstLaunchLicense.shows(settingsLoaded = false, licenseAcknowledged = false))
        assertFalse(FirstLaunchLicense.shows(settingsLoaded = true, licenseAcknowledged = true))
        assertFalse(FirstLaunchLicense.shows(settingsLoaded = false, licenseAcknowledged = true))
    }

    @Test
    fun applyChoice_bothChoicesAcknowledge_txSetExplicitlyBothDirections() {
        var acks = 0
        var tx: Boolean? = null
        FirstLaunchLicense.applyChoice(true, { acks++ }, { tx = it })
        assertEquals(1, acks)
        assertEquals(true, tx)
        // RX only must actively disarm — a legacy install may have TX on already.
        FirstLaunchLicense.applyChoice(false, { acks++ }, { tx = it })
        assertEquals(2, acks)
        assertEquals(false, tx)
    }
}
