package net.ft8vc.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTabTest {

    @Test
    fun tabs_areInSpecOrderWithSpecTitles() {
        assertEquals(
            listOf("General", "Rigs", "Display", "Integrations"),
            SettingsTab.entries.map { it.title },
        )
    }
}
