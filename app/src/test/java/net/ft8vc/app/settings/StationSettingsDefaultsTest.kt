package net.ft8vc.app.settings

import org.junit.Assert.assertTrue
import org.junit.Test

class StationSettingsDefaultsTest {

    @Test
    fun lateStartTxEnabledDefaultsTrue() {
        val s = StationSettings()
        assertTrue("Late-start TX must default to ON per spec R7", s.lateStartTxEnabled)
    }
}
