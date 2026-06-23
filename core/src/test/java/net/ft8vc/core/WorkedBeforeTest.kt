package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkedBeforeTest {
    @Test fun never_when_no_bands_worked() {
        assertEquals(WorkedBefore.Never, WorkedBefore.classify("20m", emptySet()))
    }

    @Test fun this_band_when_current_band_in_set() {
        assertEquals(WorkedBefore.ThisBand, WorkedBefore.classify("20m", setOf("20m", "40m")))
    }

    @Test fun other_band_when_set_nonempty_but_current_missing() {
        assertEquals(WorkedBefore.OtherBand, WorkedBefore.classify("20m", setOf("40m", "15m")))
    }

    @Test fun never_when_current_band_null() {
        // No dial preset matched — treat as no information.
        assertEquals(WorkedBefore.Never, WorkedBefore.classify(null, setOf("20m")))
    }
}
