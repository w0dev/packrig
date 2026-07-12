package net.ft8vc.app.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.ft8vc.app.settings.QrzKeyCipher
import net.ft8vc.app.settings.StationSettings
import net.ft8vc.data.model.QsoContact
import net.ft8vc.data.qrz.QrzClient
import net.ft8vc.data.qrz.QrzOutcome
import net.ft8vc.data.qrz.QrzQueueStore
import net.ft8vc.data.qrz.QrzUploadQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeCipher : QrzKeyCipher {
    override fun encrypt(plaintext: String) = "enc:$plaintext"
    override fun decrypt(encoded: String) = encoded.removePrefix("enc:").takeIf { encoded.startsWith("enc:") }
}

private class MemoryStore(contacts: List<QsoContact> = emptyList()) : QrzQueueStore {
    val rows = contacts.toMutableList()
    override suspend fun pending() = rows.sortedBy { it.utcMillis }
    override suspend fun pendingCount() = rows.size
    override suspend fun markUploaded(id: Long) { rows.removeAll { it.id == id } }
}

private class StubClient(
    var insertOutcome: QrzOutcome = QrzOutcome.Success(null, null),
    var statusOutcome: QrzOutcome = QrzOutcome.Success("W0DEV", 42),
) : QrzClient {
    var statusCalls = 0
    var insertCalls = 0
    override suspend fun status(apiKey: String): QrzOutcome { statusCalls++; return statusOutcome }
    override suspend fun insert(apiKey: String, adifRecord: String): QrzOutcome { insertCalls++; return insertOutcome }
}

private fun pendingContact(id: Long) = QsoContact(
    id = id, utcMillis = id * 1000, myCall = "W0DEV", myGrid = "EM26",
    dxCall = "K1ABC", dxGrid = null, rstSent = -8, rstRcvd = -15,
    freqHz = 14_074_000, mode = "FT8", band = "20m",
)

@OptIn(ExperimentalCoroutinesApi::class)
class QrzUploadControllerTest {

    private fun TestScope.buildController(
        settings: MutableStateFlow<StationSettings>,
        store: MemoryStore,
        client: StubClient,
        persisted: MutableList<String?> = mutableListOf(),
        onConnectivity: MutableList<() -> Unit> = mutableListOf(),
    ): QrzUploadController = QrzUploadController(
        settings = settings,
        queue = QrzUploadQueue(store, client),
        client = client,
        cipher = FakeCipher(),
        setEnabledPref = { settings.value = settings.value.copy(qrzUploadEnabled = it) },
        setApiKeyPref = { settings.value = settings.value.copy(qrzApiKeyEncrypted = "enc:$it") },
        persistLastError = { persisted += it },
        registerConnectivityListener = { onConnectivity += it },
        // Explicit test dispatcher: launches must land on the TestCoroutineScheduler
        // (backgroundScope alone proved racy on this toolchain).
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
    )

    @Test
    fun slice_decryptsApiKeyFromSettings() = runTest {
        val settings = MutableStateFlow(StationSettings(qrzApiKeyEncrypted = "enc:ABCD-1234"))
        val controller = buildController(settings, MemoryStore(), StubClient())
        advanceUntilIdle()
        assertEquals("ABCD-1234", controller.slice.value.apiKey)
    }

    @Test
    fun startupWithPendingAndEnabled_flushes() = runTest {
        val settings = MutableStateFlow(
            StationSettings(qrzUploadEnabled = true, qrzApiKeyEncrypted = "enc:K"),
        )
        val store = MemoryStore(listOf(pendingContact(1)))
        val client = StubClient()
        buildController(settings, store, client)
        advanceUntilIdle()
        assertEquals(1, client.insertCalls)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun onQsoLogged_flushesWhenEnabled() = runTest {
        val settings = MutableStateFlow(
            StationSettings(qrzUploadEnabled = true, qrzApiKeyEncrypted = "enc:K"),
        )
        val store = MemoryStore()
        val client = StubClient()
        val controller = buildController(settings, store, client)
        advanceUntilIdle()
        store.rows += pendingContact(7)
        controller.onQsoLogged()
        advanceUntilIdle()
        assertEquals(1, client.insertCalls)
    }

    @Test
    fun failedUpload_setsWarningAndPersistsError() = runTest {
        val settings = MutableStateFlow(
            StationSettings(qrzUploadEnabled = true, qrzApiKeyEncrypted = "enc:K"),
        )
        val store = MemoryStore(listOf(pendingContact(1)))
        val client = StubClient(insertOutcome = QrzOutcome.Failure("invalid api key"))
        val persisted = mutableListOf<String?>()
        val controller = buildController(settings, store, client, persisted)
        advanceUntilIdle()
        assertTrue(controller.slice.value.warning)
        assertEquals("invalid api key", controller.slice.value.lastError)
        assertEquals(listOf<String?>("invalid api key"), persisted)
    }

    @Test
    fun successfulFlush_clearsWarning() = runTest {
        val settings = MutableStateFlow(
            StationSettings(qrzUploadEnabled = true, qrzApiKeyEncrypted = "enc:K"),
        )
        val store = MemoryStore(listOf(pendingContact(1)))
        val client = StubClient(insertOutcome = QrzOutcome.Failure("boom"))
        val controller = buildController(settings, store, client)
        advanceUntilIdle()
        assertTrue(controller.slice.value.warning)
        client.insertOutcome = QrzOutcome.Success(null, null)
        controller.onQsoLogged()
        advanceUntilIdle()
        assertFalse(controller.slice.value.warning)
        assertEquals(null, controller.slice.value.lastError)
    }

    @Test
    fun testConnection_success_showsCallsignAndCount() = runTest {
        val settings = MutableStateFlow(StationSettings(qrzApiKeyEncrypted = "enc:K"))
        val controller = buildController(settings, MemoryStore(), StubClient())
        advanceUntilIdle()
        controller.testConnection()
        advanceUntilIdle()
        assertEquals(
            QrzTestStatus.Passed("Connected — W0DEV, 42 QSOs"),
            controller.slice.value.testStatus,
        )
    }

    @Test
    fun testConnection_failure_showsReason() = runTest {
        val settings = MutableStateFlow(StationSettings(qrzApiKeyEncrypted = "enc:K"))
        val client = StubClient(statusOutcome = QrzOutcome.Failure("invalid api key"))
        val controller = buildController(settings, MemoryStore(), client)
        advanceUntilIdle()
        controller.testConnection()
        advanceUntilIdle()
        assertEquals(QrzTestStatus.Failed("invalid api key"), controller.slice.value.testStatus)
    }

    @Test
    fun connectivityReturn_flushesWhenEnabled() = runTest {
        val settings = MutableStateFlow(
            StationSettings(qrzUploadEnabled = true, qrzApiKeyEncrypted = "enc:K"),
        )
        val store = MemoryStore()
        val client = StubClient()
        val callbacks = mutableListOf<() -> Unit>()
        buildController(settings, store, client, onConnectivity = callbacks)
        advanceUntilIdle()
        store.rows += pendingContact(3)
        callbacks.single().invoke()
        advanceUntilIdle()
        assertEquals(1, client.insertCalls)
    }

    @Test
    fun disabled_neverFlushes() = runTest {
        val settings = MutableStateFlow(StationSettings(qrzApiKeyEncrypted = "enc:K"))
        val store = MemoryStore(listOf(pendingContact(1)))
        val client = StubClient()
        val controller = buildController(settings, store, client)
        advanceUntilIdle()
        controller.onQsoLogged()
        advanceUntilIdle()
        assertEquals(0, client.insertCalls)
        assertFalse(controller.slice.value.warning)
    }
}
