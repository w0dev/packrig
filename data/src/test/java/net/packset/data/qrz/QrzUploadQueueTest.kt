package net.packset.data.qrz

import kotlinx.coroutines.test.runTest
import net.packset.data.model.QsoContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun contact(id: Long, utc: Long) = QsoContact(
    id = id, utcMillis = utc, myCall = "W0DEV", myGrid = "EM26",
    dxCall = "K1ABC", dxGrid = "FN42", rstSent = -8, rstRcvd = -15,
    freqHz = 14_074_000, mode = "FT8", band = "20m",
)

private class FakeStore(contacts: List<QsoContact>) : QrzQueueStore {
    val rows = contacts.toMutableList()
    val uploaded = mutableListOf<Long>()
    override suspend fun pending() = rows.sortedBy { it.utcMillis }
    override suspend fun pendingCount() = rows.size
    override suspend fun markUploaded(id: Long) {
        uploaded += id
        rows.removeAll { it.id == id }
    }
}

private class ScriptedClient(private val outcomes: MutableList<QrzOutcome>) : QrzClient {
    val inserted = mutableListOf<String>()
    override suspend fun status(apiKey: String): QrzOutcome = outcomes.removeAt(0)
    override suspend fun insert(apiKey: String, adifRecord: String): QrzOutcome {
        inserted += adifRecord
        return outcomes.removeAt(0)
    }
}

class QrzUploadQueueTest {

    @Test
    fun flush_uploadsOldestFirstAndMarksUploaded() = runTest {
        val store = FakeStore(listOf(contact(2, 2000), contact(1, 1000)))
        val client = ScriptedClient(
            mutableListOf(QrzOutcome.Success(null, null), QrzOutcome.Success(null, null)),
        )
        val queue = QrzUploadQueue(store, client)
        queue.flush("KEY")
        assertEquals(listOf(1L, 2L), store.uploaded)
        assertEquals(0, queue.status.value.pendingCount)
        assertNull(queue.status.value.lastError)
    }

    @Test
    fun flush_stopsAtFirstFailureAndRecordsError() = runTest {
        val store = FakeStore(listOf(contact(1, 1000), contact(2, 2000)))
        val client = ScriptedClient(mutableListOf(QrzOutcome.Failure("invalid api key")))
        val queue = QrzUploadQueue(store, client)
        queue.flush("KEY")
        assertEquals(emptyList<Long>(), store.uploaded)
        assertEquals(1, client.inserted.size)
        assertEquals("invalid api key", queue.status.value.lastError)
        assertEquals(2, queue.status.value.pendingCount)
    }

    @Test
    fun flush_successAfterFailureClearsError() = runTest {
        val store = FakeStore(listOf(contact(1, 1000)))
        val client = ScriptedClient(
            mutableListOf(QrzOutcome.Failure("boom"), QrzOutcome.Success(null, null)),
        )
        val queue = QrzUploadQueue(store, client)
        queue.flush("KEY")
        assertEquals("boom", queue.status.value.lastError)
        queue.flush("KEY")
        assertNull(queue.status.value.lastError)
        assertEquals(0, queue.status.value.pendingCount)
    }

    @Test
    fun flush_blankKeyIsNoOp() = runTest {
        val store = FakeStore(listOf(contact(1, 1000)))
        val client = ScriptedClient(mutableListOf())
        val queue = QrzUploadQueue(store, client)
        queue.flush("  ")
        assertEquals(0, client.inserted.size)
        // pendingCount untouched (still default 0 until a flush/refresh runs)
        assertEquals(0, queue.status.value.flushCount)
    }

    @Test
    fun flush_emptyQueueTouchesNothing() = runTest {
        val store = FakeStore(emptyList())
        val client = ScriptedClient(mutableListOf())
        val queue = QrzUploadQueue(store, client)
        queue.flush("KEY")
        assertEquals(0, client.inserted.size)
        assertNull(queue.status.value.lastError)
    }

    @Test
    fun refreshPendingCount_reflectsStore() = runTest {
        val store = FakeStore(listOf(contact(1, 1000), contact(2, 2000)))
        val queue = QrzUploadQueue(store, ScriptedClient(mutableListOf()))
        queue.refreshPendingCount()
        assertEquals(2, queue.status.value.pendingCount)
    }

    @Test
    fun flush_incrementsFlushCountPerPass() = runTest {
        val store = FakeStore(emptyList())
        val queue = QrzUploadQueue(store, ScriptedClient(mutableListOf()))
        queue.flush("KEY")
        queue.flush("KEY")
        assertEquals(2, queue.status.value.flushCount)
    }

    @Test
    fun flush_uploadsAdifRecordPayload() = runTest {
        val store = FakeStore(listOf(contact(1, 1000)))
        val client = ScriptedClient(mutableListOf(QrzOutcome.Success(null, null)))
        QrzUploadQueue(store, client).flush("KEY")
        val payload = client.inserted.single()
        // AdifWriter.record output: field tags + <EOR>
        assertTrue(payload.contains("<EOR>", ignoreCase = true))
        assertTrue(payload.contains("K1ABC"))
    }
}
