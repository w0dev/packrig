package net.ft8vc.app.controllers

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.ft8vc.app.DecodeRow
import net.ft8vc.app.OperateUiState
import net.ft8vc.audio.dsp.SpectrumProcessor
import net.ft8vc.core.AppInfo
import net.ft8vc.core.DecodeDistance
import net.ft8vc.core.DecodePassSource
import net.ft8vc.core.QsoDecode
import net.ft8vc.core.QsoMessages
import net.ft8vc.core.QsoResume
import net.ft8vc.core.QsoRx
import net.ft8vc.core.SlotCollector
import net.ft8vc.core.SnrEstimator
import net.ft8vc.core.TxSlotSelection
import net.ft8vc.core.WorkedBefore
import net.ft8vc.ft8native.Ft8DecoderApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis

/**
 * Owns the RX pipeline: PCM capture callback → level/spectrum → slot
 * collection → JNI decode → DecodeRow list. Runs JNI decode on a dedicated
 * single-thread [decodeDispatcher] so the audio capture thread never blocks
 * on the decoder, and emits each slot's decodes onto [decodesOut] for the
 * sibling QSO controller to consume.
 *
 * Decode failures (caught Throwables inside the decode block) increment
 * [DecodeSlice.decodeFailureCount]. Phase 6 promotes that counter into the
 * Operate-header "Decodes dropped: N" status chip.
 *
 * Stable DecodeRow ids (`slotStart * 1000 + indexInSlot`) and an
 * ImmutableList slice type are wired here so Compose stability work in
 * Phase 7 has the foundation it needs.
 */
class DecodeController(
    private val decoder: Ft8DecoderApi,
    private val scope: CoroutineScope,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "decode-controller").apply { isDaemon = true }
    },
    val decodeDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    sampleRateHz: Int = AppInfo.SAMPLE_RATE_HZ,
    /**
     * Synchronous worked-before classifier. Receives the parsed sender callsign
     * (uppercase, no modifiers) and returns the row's [WorkedBefore] tag. The
     * default returns [WorkedBefore.Never] so unit tests / fixtures don't need
     * to thread a logbook through.
     *
     * The VM wraps a suspending in-memory cache via `runBlocking` here; first
     * lookup per call hits a suspending Room query, subsequent lookups read an
     * in-memory map. Decode batches arrive at 15-second intervals, so the
     * runBlocking on the decode-dispatcher thread does not affect RX cadence.
     */
    private val workedBeforeLookup: (String) -> WorkedBefore = { WorkedBefore.Never },
) : AutoCloseable {

    /** UI-side sink for spectrum FFT columns. VM points this at `waterfall::addColumn`; tests leave it as a no-op. */
    @Volatile var spectrumSink: (FloatArray) -> Unit = {}

    private val spectrum = SpectrumProcessor(sampleRate = sampleRateHz)
    val binCount: Int = spectrum.binCount
    val maxAudioFreqHz: Int = spectrum.freqForBin(spectrum.binCount).toInt()

    private val slotCollector = SlotCollector(sampleRateHz)

    private val earlyDecodeEnabled = MutableStateFlow(true)
    private val currentSlotStart = MutableStateFlow<Long?>(null)
    /** 80% of 12-second buffer at 12 kHz = 115_200 samples minimum to attempt an early pass. */
    private val earlyMinSamples = AppInfo.SAMPLE_RATE_HZ * 12 * 8 / 10  // 115_200

    fun setEarlyDecodeEnabled(enabled: Boolean) {
        earlyDecodeEnabled.value = enabled
    }

    init {
        scope.launch(decodeDispatcher) {
            currentSlotStart.collectLatest { slotStart ->
                if (slotStart == null) return@collectLatest
                if (!earlyDecodeEnabled.value) return@collectLatest
                val targetMs = slotStart + EARLY_OFFSET_MS
                val waitMs = targetMs - clock()
                if (waitMs > 0) delay(waitMs)
                // Re-check the toggle on wake — it may have flipped during the delay.
                if (!earlyDecodeEnabled.value) return@collectLatest
                val snap = slotCollector.snapshot() ?: return@collectLatest
                if (snap.size < earlyMinSamples) return@collectLatest
                decodeSlot(snap, slotStart, source = DecodePassSource.Early)
            }
        }
    }

    private val _slice = MutableStateFlow(DecodeSlice())
    val slice: StateFlow<DecodeSlice> = _slice.asStateFlow()

    private val _decodesOut = MutableSharedFlow<DecodeBatch>(extraBufferCapacity = 4)
    val decodesOut: SharedFlow<DecodeBatch> = _decodesOut.asSharedFlow()

    private val utcTimeFormat = SimpleDateFormat("HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @Volatile private var inputGain: Float = 1f
    @Volatile private var stationContext: StationContext = StationContext("", "")
    private val failureCount = AtomicLong(0L)
    private var gainScratch: ShortArray? = null
    private var levelEma = OperateUiState.SILENCE_DBFS
    private var lastUiUpdateNs = 0L
    /** Phase 6 (RELY-04): count of slots since the last decode failure, used to auto-clear the "Decodes dropped" chip. */
    private var consecutiveSuccessfulSlots: Int = 0
    /** Phase 6 (RELY-02b): consecutive slots with zero PCM samples. UI / VM cross-checks against AudioManager devices. */
    private var zeroSampleSlots: Int = 0

    /**
     * Per-slot dedup set: slotStart → set of [DecodeRowKey.stableId] values
     * already inserted into the list and emitted on [_decodesOut] for that
     * slot. Capped at 4 most-recent slots — oldest evicted when a 5th slot
     * arrives (bounded memory; aged slots are off the screen anyway).
     */
    private val seenKeys: LinkedHashMap<Long, HashSet<Long>> =
        object : LinkedHashMap<Long, HashSet<Long>>(/* initialCapacity = */ 8) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, HashSet<Long>>): Boolean =
                size > 4
        }

    fun setInputGain(gain: Float) {
        inputGain = gain.coerceIn(OperateUiState.INPUT_GAIN_MIN, 1f)
    }

    fun setStationContext(myCall: String, myGrid: String) {
        stationContext = StationContext(myCall, myGrid)
    }

    fun reset() {
        slotCollector.reset()
        levelEma = OperateUiState.SILENCE_DBFS
        lastUiUpdateNs = 0L
        _slice.update {
            it.copy(levelDbfs = OperateUiState.SILENCE_DBFS, clip = false)
        }
    }

    fun clearDecodes() {
        _slice.update { it.copy(decodes = persistentListOf(), lastSlotDecodeCount = -1) }
    }

    /** Called from the audio-capture thread on every PCM chunk. Hands JNI decode work off to [decodeDispatcher]. */
    fun onFrames(frames: ShortArray) {
        val pcm = scaledFrames(frames, inputGain)
        var sumSq = 0.0
        var peak = 0
        for (s in pcm) {
            val a = if (s.toInt() == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else abs(s.toInt())
            if (a > peak) peak = a
            sumSq += s.toDouble() * s.toDouble()
        }
        val rms = sqrt(sumSq / frames.size)
        val instDb = (20.0 * log10(rms / 32768.0 + 1e-9)).toFloat()
        levelEma += 0.3f * (instDb - levelEma)
        val clipped = peak >= 32000

        spectrum.process(pcm) { column -> spectrumSink(column) }
        val slotStartNow = net.ft8vc.core.SlotTiming.slotStart(clock())
        if (currentSlotStart.value != slotStartNow) {
            currentSlotStart.value = slotStartNow
        }
        slotCollector.add(pcm, clock()) { samples, slotStart ->
            scope.launch(decodeDispatcher) { decodeSlot(samples, slotStart, source = DecodePassSource.Full) }
        }

        val now = System.nanoTime()
        if (now - lastUiUpdateNs >= 30_000_000L) {
            lastUiUpdateNs = now
            _slice.update {
                it.copy(
                    levelDbfs = levelEma.coerceIn(OperateUiState.SILENCE_DBFS, 0f),
                    clip = clipped,
                    waterfallVersion = it.waterfallVersion + 1,
                )
            }
        }
    }

    private fun scaledFrames(frames: ShortArray, gain: Float): ShortArray {
        if (gain >= 0.999f) return frames
        val scratch = gainScratch?.takeIf { it.size >= frames.size }
            ?: ShortArray(frames.size).also { gainScratch = it }
        for (i in frames.indices) {
            val v = (frames[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            scratch[i] = v.toShort()
        }
        return scratch
    }

    internal suspend fun decodeSlot(
        samples: ShortArray,
        slotStartEpochMs: Long,
        source: DecodePassSource = DecodePassSource.Full,
    ) {
        // Phase 6 (RELY-02b): per-slot zero-sample tracking. A slot with no non-
        // zero samples means the audio path is silent — surface to the VM so it
        // can cross-check AudioManager.getDevices() and recreate capture.
        var hasNonZero = false
        for (s in samples) { if (s.toInt() != 0) { hasNonZero = true; break } }
        if (!hasNonZero) {
            zeroSampleSlots += 1
            _slice.update { it.copy(zeroSampleSlots = zeroSampleSlots) }
        } else if (zeroSampleSlots > 0) {
            zeroSampleSlots = 0
            _slice.update { it.copy(zeroSampleSlots = 0) }
        }

        val passDurationMs: Long
        val results: List<net.ft8vc.ft8native.Ft8DecodeResult> = try {
            var out: Array<net.ft8vc.ft8native.Ft8DecodeResult> = emptyArray()
            passDurationMs = measureTimeMillis {
                out = decoder.decode(samples, AppInfo.SAMPLE_RATE_HZ)
            }
            withRecomputedSnr(out, samples, AppInfo.SAMPLE_RATE_HZ)
        } catch (t: Throwable) {
            failureCount.incrementAndGet()
            consecutiveSuccessfulSlots = 0
            _slice.update {
                it.copy(
                    decodeFailureCount = failureCount.get(),
                    decodeFailureRecent = true,
                    lastDecodePassSource = source,
                )
            }
            return
        }

        // success → duration + source → slice
        _slice.update {
            it.copy(
                lastDecodePassDurationMs = passDurationMs,
                lastDecodePassSource = source,
            )
        }

        // Phase 6 (RELY-04): clear the "Decodes dropped" chip after 5 consecutive
        // successful slots so the UI auto-recovers from a transient blip.
        consecutiveSuccessfulSlots += 1
        if (consecutiveSuccessfulSlots >= DECODE_FAILURE_DECAY_SLOTS && _slice.value.decodeFailureRecent) {
            _slice.update { it.copy(decodeFailureRecent = false) }
        }

        val ctx = stationContext
        val time = utcTimeFormat.format(Date(slotStartEpochMs))
        val slotParity = TxSlotSelection.slotParity(slotStartEpochMs)
        val sorted = results.sortedByDescending { it.score }

        val keysThisSlot = seenKeys.getOrPut(slotStartEpochMs) { HashSet() }
        val newlyEmitted = mutableListOf<QsoDecode>()
        val newRows = mutableListOf<DecodeRow>()
        val updates = mutableMapOf<Long, net.ft8vc.ft8native.Ft8DecodeResult>()  // stableId → fresher result

        for (r in sorted) {
            val message = r.message.trim()
            val id = DecodeRowKey.stableId(slotStartEpochMs, r.freqHz.toDouble(), message)
            if (keysThisSlot.add(id)) {
                // First time we see this key in this slot — insert new row + emit
                val sender = senderCallFromMessage(message)
                val worked = if (sender != null) workedBeforeLookup(sender) else WorkedBefore.Never
                newRows += DecodeRow(
                    id = id,
                    timeUtc = time,
                    snr = r.snr,
                    dtSeconds = r.dtSeconds,
                    freqHz = Math.round(r.freqHz),
                    message = message,
                    isCq = message.startsWith("CQ"),
                    isToMe = QsoResume.isDirectedToMe(ctx.myCall, message),
                    distanceKm = DecodeDistance.kmFromMessage(ctx.myGrid, message),
                    slotParity = slotParity,
                    workedBefore = worked,
                    passSource = source,
                )
                newlyEmitted += QsoDecode(message, r.snr)
            } else if (source == DecodePassSource.Full) {
                // FULL pass: update the already-inserted EARLY row in place
                updates[id] = r
            }
            // EARLY pass duplicate within itself: no-op (shouldn't happen given the single
            // early trigger per slot, but cheap defense-in-depth).
        }

        _slice.update { s ->
            val withUpdates: List<DecodeRow> = if (updates.isEmpty()) s.decodes else s.decodes.map { row ->
                val r = updates[row.id]
                if (r == null) row else row.copy(
                    snr = r.snr,
                    dtSeconds = r.dtSeconds,
                    freqHz = Math.round(r.freqHz),
                )
            }
            val combined = (newRows + withUpdates).take(OperateUiState.MAX_DECODE_ROWS)
            s.copy(
                decodes = combined.toPersistentList(),
                lastSlotDecodeCount = newRows.size,
            )
        }

        if (newlyEmitted.isNotEmpty()) {
            _decodesOut.emit(
                DecodeBatch(
                    slotStartEpochMs = slotStartEpochMs,
                    slotParity = slotParity,
                    decodes = newlyEmitted,
                ),
            )
        }
    }

    /**
     * Append a row that did not come from the native decoder (e.g. a
     * synthesized TX row produced from [net.ft8vc.app.controllers.TxOrchestrator.txLog]).
     * Uses the same prepend + [OperateUiState.MAX_DECODE_ROWS] eviction
     * pattern as the decoder batch path so chronological ordering and the
     * row cap are preserved.
     */
    fun appendSyntheticRow(row: DecodeRow) {
        _slice.update { s ->
            val combined = (listOf(row) + s.decodes).take(OperateUiState.MAX_DECODE_ROWS)
            s.copy(decodes = combined.toPersistentList())
        }
    }

    /** Extract the sender callsign from a parsed FT8 message; null when message has no sender. */
    private fun senderCallFromMessage(message: String): String? =
        when (val rx = QsoMessages.parse(message)) {
            is QsoRx.Cq -> rx.call
            is QsoRx.GridReply -> rx.sender
            is QsoRx.Report -> rx.sender
            is QsoRx.RReport -> rx.sender
            is QsoRx.Roger -> rx.sender
            is QsoRx.RogerBye -> rx.sender
            is QsoRx.Bye -> rx.sender
            QsoRx.Other -> null
        }

    override fun close() {
        (decodeDispatcher as? ExecutorCoroutineDispatcher)?.close()
        executor.shutdown()
    }

    companion object {
        /** Phase 6 (RELY-04): consecutive clean slots before the "Decodes dropped" chip auto-clears. */
        const val DECODE_FAILURE_DECAY_SLOTS = 5
        /** Slot-relative offset (ms) at which the early decode pass fires. */
        const val EARLY_OFFSET_MS = 12_000L

        /**
         * Replace each decoder result's snr with the [SnrEstimator] value computed
         * from the slot [samples]. ft8_lib's own snr field is a sync metric, not dB
         * (see SnrEstimator); this is where the real, WSJT-X-tracking SNR is set.
         * Pure; all other fields preserved.
         */
        fun withRecomputedSnr(
            results: Array<net.ft8vc.ft8native.Ft8DecodeResult>,
            samples: ShortArray,
            sampleRate: Int,
        ): List<net.ft8vc.ft8native.Ft8DecodeResult> =
            results.map { r ->
                r.copy(snr = SnrEstimator.estimate(samples, sampleRate, r.freqHz, r.dtSeconds))
            }
    }
}

data class DecodeSlice(
    val decodes: ImmutableList<DecodeRow> = persistentListOf(),
    val lastSlotDecodeCount: Int = -1,
    val levelDbfs: Float = OperateUiState.SILENCE_DBFS,
    val clip: Boolean = false,
    val waterfallVersion: Long = 0L,
    val decodeFailureCount: Long = 0L,
    /** Phase 6: true when there has been a decode failure in the last [DECODE_FAILURE_DECAY_SLOTS] slots. */
    val decodeFailureRecent: Boolean = false,
    /** Phase 6: count of consecutive slots with all-zero PCM samples. VM cross-checks against AudioManager. */
    val zeroSampleSlots: Int = 0,
    /** Wall-clock duration of the most recent decode pass, ms. */
    val lastDecodePassDurationMs: Long = 0L,
    /** Which pass produced [lastDecodePassDurationMs]. Null before any pass runs. */
    val lastDecodePassSource: net.ft8vc.core.DecodePassSource? = null,
)

data class DecodeBatch(
    val slotStartEpochMs: Long,
    val slotParity: net.ft8vc.core.TxSlotParity,
    val decodes: List<QsoDecode>,
)

private data class StationContext(val myCall: String, val myGrid: String)
