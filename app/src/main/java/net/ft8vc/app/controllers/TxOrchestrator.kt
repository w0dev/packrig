package net.ft8vc.app.controllers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.ft8vc.app.SnackbarEvent
import net.ft8vc.audio.UsbPlaybackApi
import net.ft8vc.core.AppInfo
import net.ft8vc.core.SlotTiming
import net.ft8vc.ft8native.Ft8DecoderApi
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the FT8 transmit path: native encode → USB audio playback → PTT
 * key/release, gated by the [AppRfState] readiness state machine.
 *
 * ## Four-layer PTT defense
 *
 * Every TX path is wrapped in four overlapping safety nets so that no
 * code path can leave the radio keyed:
 *
 *  1. **Unconditional `try-finally` release** inside [doTransmit]. Even
 *     if `playback.playBlocking` throws synchronously, the `finally`
 *     block calls `session.releasePtt()`.
 *
 *  2. **`AutoCloseable` `use {}` session** ([TxSession]). Even if a
 *     suspending caller is cancelled before its `finally` runs (no
 *     `finally` semantics under coroutine cancellation in some edge
 *     cases), `use {}` guarantees `close()` runs which force-releases
 *     PTT via `runBlocking { rigSession.releasePtt() }`.
 *
 *  3. **Outer `withTimeoutOrNull(SLOT_DURATION_MS + 500)`**. If the
 *     audio thread hangs or playback exceeds the slot, the suspending
 *     transmit returns null after 15.5 s and the cleanup paths above
 *     fire.
 *
 *  4. **Independent 250 ms watchdog coroutine**. Launched on the
 *     scope (not the encode dispatcher) immediately after PTT is
 *     keyed. Sleeps for `SLOT_DURATION_MS + WATCHDOG_GRACE_MS`. If
 *     PTT is still keyed when it wakes, it force-releases PTT and
 *     surfaces the "TX safety halt — PTT forced released" snackbar
 *     plus the persistent `txSafetyHaltActive` status flag in the
 *     slice. This is the last line of defense — independent of all
 *     three try/finally / use{} / withTimeout layers above so that a
 *     bug in any one of them cannot leave PTT stuck.
 *
 * The [close] method unconditionally releases PTT one more time so
 * `OperateViewModel.onCleared()` always returns the radio to RX.
 *
 * ## AppRfState
 *
 * [AppRfState] is the single source of truth for whether the app is
 * allowed to transmit. Transitions:
 *
 *  - `READY` → `RX_ONLY`         via [notifyUsbDetached]
 *  - `RX_ONLY` → `EMERGENCY_HALT` if [emergencyHalt] called while in RX_ONLY
 *  - any  → `EMERGENCY_HALT`     via [emergencyHalt]
 *  - `EMERGENCY_HALT` → `READY`  via [acknowledgeAndResetEmergency] only
 *    after the user explicitly re-acknowledges the license.
 *  - `RX_ONLY` → `READY`         via [notifyUsbReady] after the
 *    USB device returns and the license re-check passes.
 *
 * The state intentionally does not auto-recover. Reconnect after a
 * detach routes through the same cold-init path as fresh start
 * (license re-acknowledgment required, no auto-resume of TX).
 */
class TxOrchestrator(
    private val decoder: Ft8DecoderApi,
    private val playback: UsbPlaybackApi,
    private val rigSession: RigSession,
    private val scope: CoroutineScope,
    private val notifyFn: (String, SnackbarEvent.Tag) -> Unit,
    private val outputDeviceIdProvider: () -> Int?,
    private val captureControl: CaptureControl,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tx-orchestrator").apply { isDaemon = true }
    },
    val encodeDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher(),
    private val slotDurationMs: Long = AppInfo.SLOT_SECONDS * 1000L,
    private val watchdogGraceMs: Long = WATCHDOG_GRACE_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val lateStartTxEnabledProvider: () -> Boolean = { true },
) : AutoCloseable {

    interface CaptureControl {
        fun pauseForTx()
        fun resumeAfterTx()
    }

    private val _slice = MutableStateFlow(
        TxSlice(
            nativeVersion = decoder.version(),
            nativeLoaded = decoder.isAvailable(),
        ),
    )
    val slice: StateFlow<TxSlice> = _slice.asStateFlow()

    private val _txLog = MutableSharedFlow<TxLogEvent>(
        replay = 0,
        extraBufferCapacity = 16,
    )
    /** Emits one event per successful transmit. UI layer collects to render synthetic decode rows. */
    val txLog: SharedFlow<TxLogEvent> = _txLog.asSharedFlow()

    /** Tracks the most recent PTT-key timestamp; the watchdog checks this. */
    @Volatile private var lastKeyEpochMs: Long = 0L
    @Volatile private var pttKeyedFlag: Boolean = false

    /** Public read of pttKeyed for tests / observers. */
    val pttKeyed: Boolean get() = pttKeyedFlag

    // ── Public API ──────────────────────────────────────────────────────

    /** Encode + transmit [message] immediately. Returns true on clean completion. */
    suspend fun transmit(message: String, txFreqHz: Int): Boolean {
        if (!preflight(message)) return false
        return doTransmit(message, txFreqHz, waitForSlotBoundary = false)
    }

    /** Wait for the next 15-s slot boundary (or fire late), then transmit [message]. */
    suspend fun transmitAfterSlotBoundary(message: String, txFreqHz: Int): Boolean {
        if (!preflight(message)) return false

        val plan = computeLateTxPlan(
            tInSlotMs = clock() - SlotTiming.slotStart(clock()),
            toggleEnabled = lateStartTxEnabledProvider(),
        )

        return when (plan) {
            is LateTxPlan.Normal,
            LateTxPlan.Deferred,
            -> {
                // v1.0 path: wait for next slot boundary then full transmit.
                val waitMs = SlotTiming.millisUntilNextSlot(clock())
                if (waitMs > 0) {
                    _slice.update { it.copy(txStatus = "TX in ${(waitMs + 999) / 1000}s…") }
                    delay(waitMs)
                }
                doTransmit(message, txFreqHz, waitForSlotBoundary = true, offsetSymbols = 0)
            }
            is LateTxPlan.Late -> doTransmitLate(message, txFreqHz, plan)
        }
    }

    /**
     * Force the app into [AppRfState.EMERGENCY_HALT]. Releases PTT idempotently,
     * stops in-progress playback, and blocks further TX until
     * [acknowledgeAndResetEmergency] is called.
     */
    fun emergencyHalt(reason: String) {
        scope.launch {
            playback.stop()
            forceReleasePtt()
            _slice.update {
                it.copy(
                    appRfState = AppRfState.EMERGENCY_HALT,
                    isTransmitting = false,
                    txStatus = reason,
                    txSafetyHaltActive = true,
                )
            }
            notifyFn(reason, SnackbarEvent.Tag.ERROR)
        }
    }

    /** Called when USB device detach is observed. Drops into RX_ONLY. */
    fun notifyUsbDetached() {
        scope.launch {
            forceReleasePtt()
            _slice.update {
                it.copy(
                    appRfState = AppRfState.RX_ONLY,
                    isTransmitting = false,
                    digirigDisconnected = true,
                    txStatus = "Digirig disconnected — RX only",
                )
            }
            notifyFn("Digirig disconnected — RX only", SnackbarEvent.Tag.ERROR)
        }
    }

    /**
     * Called after USB reconnect AND license re-acknowledgment. Transitions
     * RX_ONLY → READY. From EMERGENCY_HALT the user must call
     * [acknowledgeAndResetEmergency] first.
     */
    fun notifyUsbReady() {
        _slice.update {
            if (it.appRfState == AppRfState.RX_ONLY) {
                it.copy(
                    appRfState = AppRfState.READY,
                    digirigDisconnected = false,
                    txStatus = null,
                )
            } else {
                it
            }
        }
    }

    /** Clear the EMERGENCY_HALT latch. Requires a fresh license acknowledgment. */
    fun acknowledgeAndResetEmergency() {
        _slice.update {
            it.copy(
                appRfState = AppRfState.READY,
                txSafetyHaltActive = false,
                digirigDisconnected = false,
                txStatus = null,
            )
        }
    }

    /** Notify TxOrchestrator of native lib version handshake outcome. */
    fun refreshNativeStatus() {
        _slice.update {
            it.copy(
                nativeVersion = decoder.version(),
                nativeLoaded = decoder.isAvailable(),
            )
        }
    }

    /** Returns true iff TX is currently allowed. Useful for UI gating. */
    fun isTxAllowed(): Boolean =
        _slice.value.appRfState == AppRfState.READY && _slice.value.nativeLoaded

    // ── Core TX implementation ──────────────────────────────────────────

    private fun preflight(message: String): Boolean {
        if (!decoder.isAvailable()) {
            notifyFn("Decoder library not loaded — TX disabled", SnackbarEvent.Tag.ERROR)
            return false
        }
        when (_slice.value.appRfState) {
            AppRfState.RX_ONLY -> {
                notifyFn("Reconnect Digirig and acknowledge license to transmit", SnackbarEvent.Tag.ERROR)
                return false
            }
            AppRfState.EMERGENCY_HALT -> {
                notifyFn("TX halted — acknowledge safety event in Settings to re-enable", SnackbarEvent.Tag.ERROR)
                return false
            }
            AppRfState.READY -> Unit
        }
        if (message.isBlank()) {
            notifyFn("TX message is empty", SnackbarEvent.Tag.ERROR)
            return false
        }
        return true
    }

    private suspend fun doTransmit(
        message: String,
        txFreqHz: Int,
        waitForSlotBoundary: Boolean,
        offsetSymbols: Int = 0,
    ): Boolean {
        val pcm = withContext(encodeDispatcher) {
            decoder.encode(message, txFreqHz.toFloat(), AppInfo.SAMPLE_RATE_HZ, offsetSymbols)
        }
        if (pcm.isEmpty()) {
            notifyFn("Encoder rejected: $message", SnackbarEvent.Tag.ERROR)
            return false
        }
        return runTxBody(message, txFreqHz, pcm)
    }

    private suspend fun doTransmitLate(
        message: String,
        txFreqHz: Int,
        plan: LateTxPlan.Late,
    ): Boolean {
        // Snapshot the tap timestamp — used by the drift-abort check after encode.
        val planTsMs = clock()

        val pcm = withContext(encodeDispatcher) {
            decoder.encode(message, txFreqHz.toFloat(), AppInfo.SAMPLE_RATE_HZ, plan.offsetSymbols)
        }
        if (pcm.isEmpty()) {
            notifyFn("Encoder rejected: $message", SnackbarEvent.Tag.ERROR)
            return false
        }

        // Drift-abort check: if encode + scheduling took longer than DRIFT_ABORT_MS,
        // the planned key moment may have slipped — fall through to next-slot v1.0 path.
        val driftMs = clock() - planTsMs
        if (driftMs > LATE_TX_DRIFT_ABORT_MS) {
            val waitMs = SlotTiming.millisUntilNextSlot(clock())
            if (waitMs > 0) {
                _slice.update { it.copy(txStatus = "TX in ${(waitMs + 999) / 1000}s…") }
                delay(waitMs)
            }
            return doTransmit(message, txFreqHz, waitForSlotBoundary = true, offsetSymbols = 0)
        }

        if (plan.waitMs > 0) delay(plan.waitMs)

        return runTxBody(message, txFreqHz, pcm)
    }

    private suspend fun runTxBody(message: String, txFreqHz: Int, pcm: ShortArray): Boolean {
        captureControl.pauseForTx()
        // Fail-closed re-check: AppRfState may have flipped during the pre-TX wait
        // (slot-boundary delay in v1.0 path, or symbol-clock-alignment delay in late-TX).
        // A USB detach or emergency halt during that window must not result in keying PTT.
        when (_slice.value.appRfState) {
            AppRfState.RX_ONLY -> {
                notifyFn("Cannot transmit — Digirig disconnected during wait", SnackbarEvent.Tag.ERROR)
                captureControl.resumeAfterTx()
                return false
            }
            AppRfState.EMERGENCY_HALT -> {
                notifyFn("Cannot transmit — safety halt active", SnackbarEvent.Tag.ERROR)
                captureControl.resumeAfterTx()
                return false
            }
            AppRfState.READY -> Unit
        }
        _slice.update { it.copy(isTransmitting = true, txStatus = "TX: $message") }

        // Layers (c) + (d): outer timeout AND independent watchdog.
        val result: Boolean = try {
            withTimeoutOrNull(slotDurationMs + WATCHDOG_OUTER_GRACE_MS) {
                // Layer (b): AutoCloseable session forces PTT release on any exit.
                TxSession().use { session ->
                    // Layer (a): try-finally inside.
                    session.keyPtt()
                    try {
                        val outputId = outputDeviceIdProvider()
                        withContext(encodeDispatcher) { playback.playBlocking(pcm, outputId) }
                    } finally {
                        session.releasePtt()
                    }
                }
            } ?: run {
                // (c) tripped: force release in case (a)/(b) somehow didn't.
                forceReleasePtt()
                notifyFn("TX timeout — PTT released", SnackbarEvent.Tag.ERROR)
                _slice.update { it.copy(txSafetyHaltActive = true) }
                false
            }
        } catch (t: Throwable) {
            // Layer (a) sibling: catch encode/playback exceptions, force release
            // (TxSession.close has already done so via use{}), and report failure.
            // CancellationException is special — let it propagate to honor coroutine cancellation.
            if (t is kotlinx.coroutines.CancellationException) throw t
            forceReleasePtt()
            notifyFn(t.message ?: "Transmit failed", SnackbarEvent.Tag.ERROR)
            false
        }

        _slice.update {
            it.copy(
                isTransmitting = false,
                txStatus = if (result) "Sent: $message" else "TX halted",
            )
        }
        if (result) {
            _txLog.tryEmit(TxLogEvent(utcMillis = clock(), freqHz = txFreqHz, message = message))
        }
        captureControl.resumeAfterTx()
        return result
    }

    private suspend fun forceReleasePtt() {
        if (!pttKeyedFlag) return
        rigSession.releasePtt()
        pttKeyedFlag = false
    }

    /**
     * AutoCloseable TX-session wrapper around RigSession PTT. The watchdog
     * is launched inside [keyPtt] and cancelled inside [releasePtt]; if the
     * caller bypasses [releasePtt] (cancellation, returns, etc.) the [close]
     * fallback fires and force-releases PTT via runBlocking.
     */
    private inner class TxSession : AutoCloseable {
        private var watchdog: Job? = null
        private var keyed: Boolean = false

        suspend fun keyPtt() {
            rigSession.keyPtt()
            pttKeyedFlag = true
            keyed = true
            lastKeyEpochMs = clock()
            watchdog = scope.launch {
                delay(slotDurationMs + watchdogGraceMs)
                if (keyed && pttKeyedFlag) {
                    rigSession.releasePtt()
                    pttKeyedFlag = false
                    keyed = false
                    _slice.update { it.copy(txSafetyHaltActive = true, isTransmitting = false) }
                    notifyFn("TX safety halt — PTT forced released", SnackbarEvent.Tag.ERROR)
                }
            }
        }

        suspend fun releasePtt() {
            watchdog?.cancel()
            watchdog = null
            if (keyed) {
                rigSession.releasePtt()
                pttKeyedFlag = false
                keyed = false
            }
        }

        override fun close() {
            watchdog?.cancel()
            if (keyed || pttKeyedFlag) {
                // Cannot suspend in close(); use blocking PTT release as last-resort.
                runBlocking { rigSession.releasePtt() }
                pttKeyedFlag = false
                keyed = false
            }
        }
    }

    override fun close() {
        // Unconditional final PTT release — guarantees onCleared returns to RX.
        runBlocking {
            try { rigSession.releasePtt() } catch (_: Throwable) { }
        }
        pttKeyedFlag = false
        (encodeDispatcher as? ExecutorCoroutineDispatcher)?.close()
        executor.shutdown()
    }

    companion object {
        /** Grace period after expected TX duration before watchdog forces release. */
        const val WATCHDOG_GRACE_MS = 250L
        /** Outer-timeout grace beyond watchdog so (c) can detect (d)-stuck cases. */
        const val WATCHDOG_OUTER_GRACE_MS = 500L
    }
}

enum class AppRfState { READY, RX_ONLY, EMERGENCY_HALT }

data class TxSlice(
    val isTransmitting: Boolean = false,
    val txStatus: String? = null,
    val appRfState: AppRfState = AppRfState.READY,
    val nativeVersion: String = "not loaded",
    val nativeLoaded: Boolean = false,
    /** True after the watchdog or outer timeout forced PTT release. Latches until acknowledge. */
    val txSafetyHaltActive: Boolean = false,
    /** True between USB detach and reconnect. */
    val digirigDisconnected: Boolean = false,
)

data class TxLogEvent(
    val utcMillis: Long,
    val freqHz: Int,
    val message: String,
)

/**
 * Late-start TX decision derived from how far into the slot the operator tapped.
 *
 * - [Normal] — route through the unchanged v1.0 TX path (no truncation).
 * - [Deferred] — defer to the next slot via existing `transmitAfterSlotBoundary` queueing.
 * - [Late] — emit a truncated waveform starting at `offsetSymbols`, after waiting `waitMs`
 *   to land the first emitted sample on a symbol boundary.
 *
 * Computed by [computeLateTxPlan] from `t_in_slot` and the Settings toggle.
 */
sealed interface LateTxPlan {
    object Normal : LateTxPlan
    object Deferred : LateTxPlan
    data class Late(val offsetSymbols: Int, val waitMs: Long) : LateTxPlan
}

/**
 * Decide whether a transmit request at slot-relative time [tInSlotMs] should
 * run through the v1.0 path, defer to the next slot, or fire late with a
 * truncated waveform.
 *
 * Constants are derived in the spec — see
 * `docs/superpowers/specs/2026-06-22-late-start-ft8-tx-design.md` §Symbol-clock math.
 *
 * - `t < 1340` (one symbol period past waveform start): [Normal]
 * - `1340 ≤ t ≤ 7000`: [Late] with `offsetSymbols ∈ [1, 37]`
 * - `t > 7000`: [Deferred]
 * - `toggleEnabled == false`: always [Normal] (PARITY-01 escape hatch)
 */
internal fun computeLateTxPlan(tInSlotMs: Long, toggleEnabled: Boolean): LateTxPlan {
    if (!toggleEnabled) return LateTxPlan.Normal
    if (tInSlotMs < LATE_TX_FLOOR_MS) return LateTxPlan.Normal
    if (tInSlotMs > LATE_TX_CUTOFF_MS) return LateTxPlan.Deferred

    // (tInSlot - waveformStart) / symbolPeriod, rounded UP so the first emitted
    // sample is never inside a partial symbol.
    val rawOffset = (tInSlotMs - WAVEFORM_START_MS).toDouble() / SYMBOL_PERIOD_MS
    val offsetSymbols = kotlin.math.ceil(rawOffset).toInt().coerceAtLeast(1)
    val keyMomentInSlot = WAVEFORM_START_MS + offsetSymbols * SYMBOL_PERIOD_MS
    val waitMs = (keyMomentInSlot - tInSlotMs).coerceAtLeast(0L)
    return LateTxPlan.Late(offsetSymbols = offsetSymbols, waitMs = waitMs)
}

// Constants — see spec §Symbol-clock math.
internal const val WAVEFORM_START_MS = 1180L
internal const val SYMBOL_PERIOD_MS = 160L
internal const val LATE_TX_FLOOR_MS = WAVEFORM_START_MS + SYMBOL_PERIOD_MS  // 1340
internal const val LATE_TX_CUTOFF_MS = 7000L
internal const val LATE_TX_DRIFT_ABORT_MS = 80L
internal const val FT8_NN = 79
internal const val SAMPLES_PER_SYMBOL = 1920
