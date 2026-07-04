package net.ft8vc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression guard for the "TX Halted" sticky-status bug: haltTx() latches
 * "TX halted" into the VM-residual status and nothing nulls it, so the merge
 * must let the TxOrchestrator slice win whenever it has a live status —
 * otherwise every subsequent transmit displays the stale halt notice until
 * app restart.
 */
class TxStatusMergeTest {

    @Test
    fun `slice status wins over stale halt residual during next TX`() {
        assertEquals(
            "TX: CQ W0DEV EN35",
            mergedTxStatus(sliceTxStatus = "TX: CQ W0DEV EN35", viewTxStatus = "TX halted"),
        )
    }

    @Test
    fun `slice completion status wins over USB-probe residual`() {
        assertEquals(
            "Sent: CQ W0DEV EN35",
            mergedTxStatus(sliceTxStatus = "Sent: CQ W0DEV EN35", viewTxStatus = "Digirig PTT ready"),
        )
    }

    @Test
    fun `view residual shows when slice has no status`() {
        assertEquals(
            "Digirig PTT ready",
            mergedTxStatus(sliceTxStatus = null, viewTxStatus = "Digirig PTT ready"),
        )
    }

    @Test
    fun `null when neither side has a status`() {
        assertNull(mergedTxStatus(sliceTxStatus = null, viewTxStatus = null))
    }
}
