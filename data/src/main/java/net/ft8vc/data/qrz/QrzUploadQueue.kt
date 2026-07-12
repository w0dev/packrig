package net.ft8vc.data.qrz

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.ft8vc.data.adif.AdifWriter

/** Observable queue state for the Settings UI (warning icon + error text). */
data class QrzQueueStatus(
    val uploading: Boolean = false,
    val pendingCount: Int = 0,
    val lastError: String? = null,
    /** Completed flush passes; lets observers react exactly once per pass. */
    val flushCount: Int = 0,
)

/**
 * Uploads PENDING QSOs to QRZ oldest-first. One flush at a time (mutex);
 * the pass stops at the first failure so a dead network costs one request.
 * Quiet by design: failures only surface through [status].
 */
class QrzUploadQueue(
    private val store: QrzQueueStore,
    private val client: QrzClient,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(QrzQueueStatus())
    val status: StateFlow<QrzQueueStatus> = _status.asStateFlow()

    suspend fun refreshPendingCount() {
        _status.update { it.copy(pendingCount = store.pendingCount()) }
    }

    suspend fun flush(apiKey: String) {
        if (apiKey.isBlank()) return
        mutex.withLock {
            val pending = store.pending()
            _status.update { it.copy(uploading = true, pendingCount = pending.size) }
            var error: String? = null
            for (contact in pending) {
                when (val outcome = client.insert(apiKey, AdifWriter.record(contact))) {
                    is QrzOutcome.Success -> {
                        store.markUploaded(contact.id)
                        _status.update { it.copy(pendingCount = store.pendingCount()) }
                    }
                    is QrzOutcome.Failure -> {
                        error = outcome.message
                        break
                    }
                }
            }
            _status.update {
                it.copy(
                    uploading = false,
                    pendingCount = store.pendingCount(),
                    lastError = error,
                    flushCount = it.flushCount + 1,
                )
            }
        }
    }
}
