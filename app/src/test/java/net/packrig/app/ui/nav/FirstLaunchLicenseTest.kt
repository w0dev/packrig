package net.packrig.app.ui.nav

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
}
