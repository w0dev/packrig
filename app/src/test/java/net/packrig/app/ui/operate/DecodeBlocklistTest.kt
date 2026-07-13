package net.packrig.app.ui.operate

import net.packrig.core.DecodeRowSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeBlocklistTest {

    @Test fun blockedSenderIsHidden() {
        assertTrue(
            DecodeBlocklist.isSenderBlocked("W0DEV K1ABC FN42", DecodeRowSource.Rx, listOf("K1ABC")),
        )
    }

    @Test fun portableSenderMatchesBaseCallBlock() {
        assertTrue(
            DecodeBlocklist.isSenderBlocked("W0DEV K1ABC/P FN42", DecodeRowSource.Rx, listOf("K1ABC")),
        )
    }

    @Test fun unblockedSenderIsVisible() {
        assertFalse(
            DecodeBlocklist.isSenderBlocked("W0DEV N0XYZ FN42", DecodeRowSource.Rx, listOf("K1ABC")),
        )
    }

    @Test fun ownTxRowIsNeverBlocked() {
        assertFalse(
            DecodeBlocklist.isSenderBlocked("CQ K1ABC FN42", DecodeRowSource.Tx, listOf("K1ABC")),
        )
    }

    @Test fun senderToBlockReturnsBaseCall() {
        assertEquals("K1ABC", DecodeBlocklist.senderToBlock("CQ K1ABC/P FN42", DecodeRowSource.Rx))
    }

    @Test fun senderToBlockNullForTxOrUnparseable() {
        assertNull(DecodeBlocklist.senderToBlock("CQ K1ABC FN42", DecodeRowSource.Tx))
        assertNull(DecodeBlocklist.senderToBlock("TNX 73 GL", DecodeRowSource.Rx))
    }
}
