package net.packset.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class QrzKeyFormatTest {

    @Test
    fun sanitize_stripsDashesUppercasesAndCaps16() {
        assertEquals("ABCD1234EFGH5678", QrzKeyFormat.sanitize("abcd-1234-efgh-5678"))
        assertEquals("ABC", QrzKeyFormat.sanitize(" ab c "))
        assertEquals("ABCD1234EFGH5678", QrzKeyFormat.sanitize("ABCD1234EFGH5678XXXX"))
        assertEquals("", QrzKeyFormat.sanitize("---"))
    }

    @Test
    fun store_groupsWithDashesNoTrailing() {
        assertEquals("", QrzKeyFormat.store(""))
        assertEquals("ABC", QrzKeyFormat.store("ABC"))
        assertEquals("ABCD", QrzKeyFormat.store("ABCD"))
        assertEquals("ABCD-E", QrzKeyFormat.store("ABCDE"))
        assertEquals("ABCD-1234-EFGH-5678", QrzKeyFormat.store("ABCD1234EFGH5678"))
    }

    @Test
    fun display_appendsEnforcedDashAtCompleteGroups() {
        assertEquals("ABCD-", QrzKeyFormat.display("ABCD", masked = false))
        assertEquals("ABCD-1234-", QrzKeyFormat.display("ABCD1234", masked = false))
        assertEquals("ABCD-1234-EFGH-", QrzKeyFormat.display("ABCD1234EFGH", masked = false))
        // Complete key: no trailing dash.
        assertEquals("ABCD-1234-EFGH-5678", QrzKeyFormat.display("ABCD1234EFGH5678", masked = false))
        assertEquals("ABC", QrzKeyFormat.display("ABC", masked = false))
    }

    @Test
    fun display_maskedKeepsDashesVisible() {
        assertEquals("••••-", QrzKeyFormat.display("ABCD", masked = true))
        assertEquals("••••-••••-••••-••••", QrzKeyFormat.display("ABCD1234EFGH5678", masked = true))
    }

    @Test
    fun originalToTransformed_cursorHopsPastDash() {
        assertEquals(0, QrzKeyFormat.originalToTransformed(0))
        assertEquals(3, QrzKeyFormat.originalToTransformed(3))
        // After typing the 4th char the cursor sits after the enforced dash.
        assertEquals(5, QrzKeyFormat.originalToTransformed(4))
        assertEquals(10, QrzKeyFormat.originalToTransformed(8))
        assertEquals(15, QrzKeyFormat.originalToTransformed(12))
        assertEquals(19, QrzKeyFormat.originalToTransformed(16))
    }

    @Test
    fun transformedToOriginal_mapsDashPositionsBack() {
        assertEquals(0, QrzKeyFormat.transformedToOriginal(0, rawLength = 16))
        assertEquals(4, QrzKeyFormat.transformedToOriginal(4, rawLength = 16))
        assertEquals(4, QrzKeyFormat.transformedToOriginal(5, rawLength = 16))
        assertEquals(8, QrzKeyFormat.transformedToOriginal(10, rawLength = 16))
        assertEquals(16, QrzKeyFormat.transformedToOriginal(19, rawLength = 16))
        // Clamped to raw length for positions past the end.
        assertEquals(4, QrzKeyFormat.transformedToOriginal(5, rawLength = 4))
    }

    @Test
    fun roundTrip_offsetsConsistentForEveryCursorPosition() {
        for (rawLen in 0..16) {
            for (offset in 0..rawLen) {
                val t = QrzKeyFormat.originalToTransformed(offset)
                assertEquals(
                    "raw=$rawLen offset=$offset",
                    offset,
                    QrzKeyFormat.transformedToOriginal(t, rawLen),
                )
            }
        }
    }
}
