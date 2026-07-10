package net.ft8vc.app.controllers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.ft8vc.rig.CatControl
import net.ft8vc.rig.RigBackend
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the single-threaded CAT dispatcher and serializes every rig-bound
 * operation (frequency read/write, mode read/write, PTT key/release) through
 * it. Exposes a [RigSlice] state flow so the residual ViewModel can mirror
 * rig status into [net.ft8vc.app.OperateUiState] without touching the rig.
 *
 * Per Phase 2 spec, every CAT call is wrapped in `withTimeoutOrNull(catTimeoutMs)`
 * — the outer coroutine timeout. The driver-level inner timeout, port
 * close+reopen, and consecutive-failure threshold land in Phase 6; this phase
 * only swaps the mechanism (executor → dispatcher), not the policy.
 *
 * Construct with a callable `digirigPresenceProvider` so the session can
 * refresh its slice when USB lifecycle changes without owning the USB stack.
 */
class RigSession @OptIn(ExperimentalCoroutinesApi::class) constructor(
    private val rig: RigBackend,
    private val catControl: CatControl,
    private val digirigPresenceProvider: () -> Boolean,
    private val catTimeoutMs: Long = 5_000L,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rig-session-cat").apply { isDaemon = true }
    },
    val catDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher(),
) : AutoCloseable {

    private val _slice = MutableStateFlow(
        RigSlice(digirigPresent = digirigPresenceProvider()),
    )
    val slice: StateFlow<RigSlice> = _slice.asStateFlow()

    /** Owns fire-and-forget work ([releasePttAsync]); cancelled in [close]. */
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Count of consecutive CAT operations that returned null (timeout). Phase 6 promotes this into the unreachable-state policy. */
    private val consecutiveFailures = AtomicInteger(0)
    val consecutiveFailureCount: Int get() = consecutiveFailures.get()

    /** Phase 6: number of consecutive timeouts after which CAT is declared unreachable. */
    private val unreachableThreshold = 3

    fun refreshDigirigPresence() {
        _slice.update { it.copy(digirigPresent = digirigPresenceProvider()) }
    }

    /**
     * Phase 6: clear the catUnreachable latch and reset the consecutive-failure
     * counter. UI calls this when the operator taps the "CAT unreachable — tap
     * to retry" chip.
     */
    fun retryCat() {
        consecutiveFailures.set(0)
        _slice.update { it.copy(catUnreachable = false, catStatus = "Retrying CAT…") }
    }

    suspend fun readRig(): Long? {
        if (_slice.value.catUnreachable) return null
        return readRigImpl()
    }

    private suspend fun readRigImpl(): Long? = runCat("Reading rig…") {
        val freq = catControl.frequencyHz()
        val mode = catControl.modeLabel()
        val both = freq == null && mode == null
        _slice.update {
            it.copy(
                rigFreqHz = freq ?: it.rigFreqHz,
                rigMode = mode ?: it.rigMode,
                catStatus = if (both) "No CAT reply" else "Rig in sync",
            )
        }
        if (both) recordFailure() else recordSuccess()
        freq
    }

    suspend fun setFrequency(hz: Long): Boolean {
        if (_slice.value.catUnreachable) return false
        return setFrequencyImpl(hz)
    }

    private suspend fun setFrequencyImpl(hz: Long): Boolean = runCat("Tuning…") {
        if (catControl.setFrequencyHz(hz)) {
            val actual = catControl.frequencyHz()
            _slice.update { it.copy(rigFreqHz = actual ?: hz, catStatus = "Tuned") }
            recordSuccess()
            true
        } else {
            _slice.update { it.copy(catStatus = "Tune rejected") }
            false
        }
    } ?: false

    suspend fun setDataMode(): Boolean {
        if (_slice.value.catUnreachable) return false
        return setDataModeImpl()
    }

    private suspend fun setDataModeImpl(): Boolean = runCat("Setting mode…") {
        if (catControl.setDataMode()) {
            val actual = catControl.modeLabel()
            _slice.update {
                it.copy(rigMode = actual ?: catControl.dataModeLabel(), catStatus = "Mode set")
            }
            recordSuccess()
            true
        } else {
            _slice.update { it.copy(catStatus = "Mode set rejected") }
            false
        }
    } ?: false

    suspend fun keyPtt() {
        withContext(catDispatcher) { rig.keyPtt() }
        _slice.update { it.copy(pttKeyed = true) }
    }

    suspend fun releasePtt() {
        withContext(catDispatcher) { rig.releasePtt() }
        _slice.update { it.copy(pttKeyed = false) }
    }

    /**
     * Non-blocking PTT release for main-thread callers (Stop QSO / Halt TX taps).
     * Enqueues the release on the CAT dispatcher and returns immediately — a USB
     * serial call stuck in blocking I/O must never pin the caller (field ANR,
     * 2026-07-03; coroutine timeouts cannot cancel blocking serial reads). The
     * release still serializes behind any in-flight CAT operation and stays
     * idempotent. Final-teardown paths (onCleared) keep the blocking variant so
     * the process cannot die with PTT keyed.
     */
    fun releasePttAsync() {
        sessionScope.launch { releasePtt() }
    }

    /** Synchronous PTT key for non-coroutine callers (e.g. legacy TX thread, UI cleanup). */
    fun keyPttBlocking() = runBlocking { keyPtt() }

    /** Synchronous PTT release for non-coroutine callers (e.g. legacy TX thread, UI cleanup). */
    fun releasePttBlocking() = runBlocking { releasePtt() }

    /** Run [block] on [catDispatcher] guarded by [catTimeoutMs]; returns null on timeout. */
    private suspend fun <T> runCat(busyStatus: String, block: suspend () -> T): T? {
        _slice.update { it.copy(catBusy = true, catStatus = busyStatus) }
        return try {
            val box: Box<T>? = withContext(catDispatcher) {
                withTimeoutOrNull(catTimeoutMs) { Box(block()) }
            }
            if (box == null) {
                _slice.update { it.copy(catStatus = "CAT timeout") }
                recordFailure()
                null
            } else {
                box.value
            }
        } catch (t: Throwable) {
            _slice.update { it.copy(catStatus = t.message ?: "CAT error") }
            recordFailure()
            null
        } finally {
            _slice.update { it.copy(catBusy = false) }
        }
    }

    /** Distinguishes a legit null result (block returned null) from a timeout (Box itself is null). */
    private class Box<T>(val value: T)

    private fun recordFailure() {
        val n = consecutiveFailures.incrementAndGet()
        if (n >= unreachableThreshold) {
            _slice.update { it.copy(catUnreachable = true, catStatus = "CAT unreachable — tap to retry") }
        }
    }

    private fun recordSuccess() {
        consecutiveFailures.set(0)
        if (_slice.value.catUnreachable) {
            _slice.update { it.copy(catUnreachable = false) }
        }
    }

    override fun close() {
        sessionScope.cancel()
        (catDispatcher as? ExecutorCoroutineDispatcher)?.close()
        executor.shutdown()
    }
}

data class RigSlice(
    val catBusy: Boolean = false,
    val catStatus: String? = null,
    val rigFreqHz: Long? = null,
    val rigMode: String? = null,
    val pttKeyed: Boolean = false,
    val digirigPresent: Boolean = false,
    /** Phase 6: latches true after [unreachableThreshold] consecutive CAT timeouts. Persistent chip until [retryCat]. */
    val catUnreachable: Boolean = false,
)
