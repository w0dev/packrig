package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallBaseNameTest {

    @Test fun stripsPortableSuffix() {
        assertEquals("K1ABC", CallBaseName.of("K1ABC/P"))
    }

    @Test fun stripsDashSuffix() {
        assertEquals("K1ABC", CallBaseName.of("K1ABC-9"))
    }

    @Test fun uppercasesAndTrims() {
        assertEquals("N0XYZ", CallBaseName.of("  n0xyz  "))
    }

    @Test fun blankReturnsNull() {
        assertNull(CallBaseName.of("   "))
    }
}
