package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QsoMessagesSenderCallTest {

    @Test fun cqSenderIsTheCaller() {
        assertEquals("K1ABC", QsoMessages.senderCall("CQ K1ABC FN42"))
    }

    @Test fun directedGridReplySenderIsSecondToken() {
        assertEquals("K1ABC", QsoMessages.senderCall("W0DEV K1ABC FN42"))
    }

    @Test fun reportSenderIsSecondToken() {
        assertEquals("K1ABC", QsoMessages.senderCall("W0DEV K1ABC -15"))
    }

    @Test fun unparseableReturnsNull() {
        assertNull(QsoMessages.senderCall("TNX 73 GL"))
    }
}
