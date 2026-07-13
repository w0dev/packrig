package net.packrig.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbandonedPartnersTest {

    @Test fun userBlockMarksUserBlockedAndBaseCall() {
        val p = AbandonedPartners()
        p.blockUser("K1ABC/P")
        assertTrue(p.isUserBlocked("K1ABC"))
        assertTrue(p.isUserBlocked("K1ABC/P"))
        assertFalse(p.isUserBlocked("N0XYZ"))
        assertEquals(setOf("K1ABC"), p.userBlockedSnapshot())
    }

    @Test fun autoSuppressDoesNotUserBlock() {
        val p = AbandonedPartners()
        p.suppressAuto("K1ABC")
        assertFalse(p.isUserBlocked("K1ABC"))
        assertTrue(p.userBlockedSnapshot().isEmpty())
    }

    @Test fun snapshotIsUnionOfBothSets() {
        val p = AbandonedPartners()
        p.blockUser("K1ABC")
        p.suppressAuto("N0XYZ")
        assertEquals(setOf("K1ABC", "N0XYZ"), p.snapshot())
    }

    @Test fun allowResumeClearsBothSets() {
        val p = AbandonedPartners()
        p.blockUser("K1ABC")
        p.suppressAuto("K1ABC")
        p.allowResume("K1ABC")
        assertFalse(p.isUserBlocked("K1ABC"))
        assertTrue(p.snapshot().isEmpty())
    }

    @Test fun clearRemovesAllFromBothSets() {
        val p = AbandonedPartners()
        p.blockUser("K1ABC")
        p.suppressAuto("N0XYZ")
        p.clear()
        assertTrue(p.snapshot().isEmpty())
        assertTrue(p.userBlockedSnapshot().isEmpty())
    }
}
