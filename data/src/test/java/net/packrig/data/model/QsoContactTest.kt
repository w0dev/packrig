package net.packrig.data.model

import net.packrig.core.QsoRole
import net.packrig.core.QsoSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QsoContactTest {

    private val snapshot = QsoSnapshot(
        myCall = "W0DEV",
        myGrid = "EM26",
        dxCall = "K1ABC",
        dxGrid = "FN42",
        reportSent = -8,
        reportRcvd = -15,
        role = QsoRole.Initiator,
        completedAtEpochMs = 1_700_000_000_000L,
    )

    @Test
    fun fromSnapshotCarriesParkRefs() {
        val contact = QsoContact.fromSnapshot(snapshot, 14_074_000L, "20m", "US-3315,US-0891")
        assertEquals("US-3315,US-0891", contact.potaParkRefs)
    }

    @Test
    fun fromSnapshotDefaultsToNoParks() {
        val contact = QsoContact.fromSnapshot(snapshot, 14_074_000L, "20m")
        assertNull(contact.potaParkRefs)
    }
}
