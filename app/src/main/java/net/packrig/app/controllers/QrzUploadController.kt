package net.packrig.app.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.packrig.app.settings.QrzKeyCipher
import net.packrig.app.settings.StationSettings
import net.packrig.data.qrz.QrzClient
import net.packrig.data.qrz.QrzOutcome
import net.packrig.data.qrz.QrzUploadQueue

/** Progress/result of the user-initiated "Test connection" button. */
sealed interface QrzTestStatus {
    data object Idle : QrzTestStatus
    data object Testing : QrzTestStatus
    data class Passed(val message: String) : QrzTestStatus
    data class Failed(val message: String) : QrzTestStatus
}

/** Everything the QRZ settings section renders. */
data class QrzSlice(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val warning: Boolean = false,
    val lastError: String? = null,
    val pendingCount: Int = 0,
    val testStatus: QrzTestStatus = QrzTestStatus.Idle,
)

/**
 * Owns QRZ upload triggers and settings-facing state
 * (spec 2026-07-11-qrz-logbook-upload). Quiet by design: failures surface
 * only through [slice] — never snackbars, never the Operate screen.
 */
class QrzUploadController(
    settings: Flow<StationSettings>,
    private val queue: QrzUploadQueue,
    private val client: QrzClient,
    private val cipher: QrzKeyCipher,
    private val setEnabledPref: suspend (Boolean) -> Unit,
    private val setApiKeyPref: suspend (String) -> Unit,
    private val persistLastError: suspend (String?) -> Unit,
    registerConnectivityListener: (onAvailable: () -> Unit) -> Unit,
    private val scope: CoroutineScope,
) {

    private val _slice = MutableStateFlow(QrzSlice())
    val slice: StateFlow<QrzSlice> = _slice.asStateFlow()

    val isEnabled: Boolean get() = _slice.value.enabled

    // Decryption cache: encrypted blob → plaintext (re-decrypt only on change).
    private var lastEncrypted: String? = null
    private var startupFlushDone = false
    private var bootError: String? = null
    private var queueTouched = false
    private var lastSeenFlushCount = 0

    init {
        scope.launch {
            settings.collect { s ->
                if (s.qrzApiKeyEncrypted != lastEncrypted) {
                    lastEncrypted = s.qrzApiKeyEncrypted
                    val key = s.qrzApiKeyEncrypted?.let(cipher::decrypt) ?: ""
                    _slice.update { it.copy(apiKey = key) }
                }
                bootError = s.qrzLastError
                _slice.update { it.copy(enabled = s.qrzUploadEnabled) }
                recomputeWarning()
                if (!startupFlushDone) {
                    startupFlushDone = true
                    queue.refreshPendingCount()
                    if (s.qrzUploadEnabled) flush()
                }
            }
        }
        scope.launch {
            queue.status.collect { q ->
                if (q.flushCount > lastSeenFlushCount) {
                    lastSeenFlushCount = q.flushCount
                    queueTouched = true
                    persistLastError(q.lastError)
                }
                _slice.update {
                    it.copy(pendingCount = q.pendingCount, lastError = liveError(q.lastError))
                }
                recomputeWarning()
            }
        }
        registerConnectivityListener {
            if (isEnabled) flush()
        }
    }

    /** Trigger 1: a QSO was just inserted as PENDING. */
    fun onQsoLogged() {
        if (isEnabled) flush()
    }

    fun setEnabled(enabled: Boolean) {
        scope.launch {
            setEnabledPref(enabled)
            if (enabled) flush() else _slice.update { it.copy(testStatus = QrzTestStatus.Idle) }
        }
    }

    fun setApiKey(plaintext: String) {
        _slice.update { it.copy(apiKey = plaintext) }
        scope.launch { setApiKeyPref(plaintext) }
    }

    fun testConnection() {
        val key = _slice.value.apiKey.trim()
        if (key.isEmpty()) {
            _slice.update { it.copy(testStatus = QrzTestStatus.Failed("Enter an API key first")) }
            return
        }
        _slice.update { it.copy(testStatus = QrzTestStatus.Testing) }
        scope.launch {
            when (val outcome = client.status(key)) {
                is QrzOutcome.Success -> {
                    val message = outcome.callsign?.let { "Connected — $it" } ?: "Connected"
                    _slice.update { it.copy(testStatus = QrzTestStatus.Passed(message)) }
                    flush()
                }
                is QrzOutcome.Failure -> {
                    _slice.update { it.copy(testStatus = QrzTestStatus.Failed(outcome.message)) }
                    queueTouched = true
                    persistLastError(outcome.message)
                    _slice.update { it.copy(lastError = outcome.message) }
                    recomputeWarning()
                }
            }
        }
    }

    private fun flush() {
        scope.launch {
            queue.flush(_slice.value.apiKey.trim())
            queue.refreshPendingCount()
        }
    }

    /** Persisted boot error until the queue has run once; live queue error after. */
    private fun liveError(queueError: String?): String? =
        if (queueTouched) queueError else queueError ?: bootError

    private fun recomputeWarning() {
        _slice.update {
            it.copy(warning = it.enabled && it.lastError != null && it.pendingCount > 0)
        }
    }
}
