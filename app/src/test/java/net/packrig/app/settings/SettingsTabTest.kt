package net.packrig.app.settings

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

    @Test
    fun tabWeights_followLabelLengthWithFloor() {
        // "Integrations" wraps if every tab gets 1/4 of the row — weights hand
        // longer labels proportionally more width (owner request 2026-07-12).
        assertEquals(12f, SettingsTab.INTEGRATIONS.weight, 0f)
        assertEquals(7f, SettingsTab.GENERAL.weight, 0f)
        // Short labels are floored so the tab stays a reasonable touch target.
        assertEquals(5f, SettingsTab.RIGS.weight, 0f)
    }
}
