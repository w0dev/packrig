package net.ft8vc.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import net.ft8vc.app.ui.Waterfall
import net.ft8vc.audio.AudioInputDevice
import net.ft8vc.audio.AudioInputs
import net.ft8vc.audio.AudioOutputs
import net.ft8vc.audio.UsbAudioCapture
import net.ft8vc.audio.UsbAudioPlayback
import net.ft8vc.audio.dsp.SpectrumProcessor
import net.ft8vc.core.AppInfo
import net.ft8vc.core.QsoDecode
import net.ft8vc.core.QsoMachine
import net.ft8vc.core.QsoMessages
import net.ft8vc.core.QsoRx
import net.ft8vc.core.QsoState
import net.ft8vc.core.SlotCollector
import net.ft8vc.core.SlotTiming
import net.ft8vc.ft8native.Ft8Native
import net.ft8vc.rig.Ft891Cat
import net.ft8vc.rig.RigController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

data class DecodeRow(
    val timeUtc: String,
    val snr: Int,
    val dtSeconds: Float,
    val freqHz: Int,
    val message: String,
    val isCq: Boolean,
)

data class MonitorUiState(
    val devices: List<AudioInputDevice> = emptyList(),
    val selectedDeviceId: Int? = null,
    val isCapturing: Boolean = false,
    val levelDbfs: Float = SILENCE_DBFS,
    val clip: Boolean = false,
    val sampleRateHz: Int = AppInfo.SAMPLE_RATE_HZ,
    val error: String? = null,
    val waterfallVersion: Long = 0L,
    val decodes: List<DecodeRow> = emptyList(),
    val lastSlotDecodeCount: Int = -1,
    val txEnabled: Boolean = false,
    val txMessage: String = DEFAULT_TX_MESSAGE,
    val txFreqHz: Int = DEFAULT_TX_FREQ_HZ,
    val txStatus: String? = null,
    val isTransmitting: Boolean = false,
    val pttReady: Boolean = false,
    val myCall: String = DEFAULT_MY_CALL,
    val myGrid: String = DEFAULT_MY_GRID,
    val qsoActive: Boolean = false,
    val qsoState: String? = null,
    val qsoDx: String? = null,
    val catReady: Boolean = false,
    val rigFreqHz: Long? = null,
    val rigMode: String? = null,
    val catStatus: String? = null,
    val catBusy: Boolean = false,
) {
    companion object {
        const val SILENCE_DBFS = -100f
        const val MAX_DECODE_ROWS = 300
        const val DEFAULT_TX_MESSAGE = "CQ TEST FN31"
        const val DEFAULT_TX_FREQ_HZ = 1000
        const val DEFAULT_MY_CALL = "TEST"
        const val DEFAULT_MY_GRID = "FN31"
        /** Delay after a TX slot boundary so the prior RX slot can decode first. */
        const val QSO_TX_GRACE_MS = 300L
    }
}

class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    private val capture = UsbAudioCapture(app)
    private val playback = UsbAudioPlayback(app)
    private val rig = RigController(app)
    private val spectrum = SpectrumProcessor(sampleRate = AppInfo.SAMPLE_RATE_HZ)
    private val slotCollector = SlotCollector(AppInfo.SAMPLE_RATE_HZ)
    private val decodeExecutor = Executors.newSingleThreadExecutor()
    private val catExecutor = Executors.newSingleThreadExecutor()
    private val utcTimeFormat = SimpleDateFormat("HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Read on the UI thread via [Waterfall.snapshot]; written on the capture thread. */
    val waterfall = Waterfall(bins = spectrum.binCount)
    val maxAudioFreqHz: Int = spectrum.freqForBin(spectrum.binCount).toInt()

    private var levelEma = MonitorUiState.SILENCE_DBFS
    private var lastUiUpdateNs = 0L
    private var txThread: Thread? = null

    /** Guards [qso] against concurrent access by the decode and QSO-loop threads. */
    private val qsoLock = Any()
    private var qso: QsoMachine? = null
    @Volatile private var qsoRunning = false
    private var qsoThread: Thread? = null

    init {
        refreshDevices()
        _state.update { it.copy(selectedDeviceId = it.devices.firstOrNull { d -> d.isUsb }?.id) }
    }

    fun refreshDevices() {
        val devices = AudioInputs.list(getApplication())
        _state.update { s ->
            val stillValid = devices.any { it.id == s.selectedDeviceId }
            s.copy(
                devices = devices,
                selectedDeviceId = if (stillValid) s.selectedDeviceId else devices.firstOrNull { it.isUsb }?.id,
            )
        }
    }

    fun selectDevice(id: Int) {
        val wasCapturing = _state.value.isCapturing
        if (wasCapturing) stopCapture()
        _state.update { it.copy(selectedDeviceId = id) }
        if (wasCapturing) start()
    }

    fun setTxEnabled(enabled: Boolean) {
        _state.update { it.copy(txEnabled = enabled, txStatus = null) }
        if (enabled) prepareRig()
    }

    /** Discover the Digirig and request USB permission if needed, then wire PTT. */
    private fun prepareRig() {
        when (rig.state()) {
            RigController.State.NoDevice -> {
                _state.update { it.copy(pttReady = false, txStatus = "No Digirig — PTT is no-op") }
            }
            RigController.State.Ready -> {
                _state.update { it.copy(pttReady = true, txStatus = "Digirig PTT ready") }
                onRigReady()
            }
            RigController.State.NeedsPermission -> {
                _state.update { it.copy(txStatus = "Requesting USB permission…") }
                rig.ensureReady { ready ->
                    _state.update {
                        it.copy(
                            pttReady = ready,
                            txStatus = if (ready) "Digirig PTT ready" else "USB permission denied — PTT is no-op",
                        )
                    }
                    if (ready) onRigReady()
                }
            }
        }
    }

    /** Reflect CAT availability and pull the rig's current frequency/mode once. */
    private fun onRigReady() {
        if (!rig.isCatReady) return
        _state.update { it.copy(catReady = true) }
        readRig()
    }

    /** Poll the FT-891 for its current VFO-A frequency and mode. */
    fun readRig() {
        if (!rig.isCatReady) return
        runCat("Reading rig…") {
            val freq = rig.frequencyHz()
            val mode = rig.mode()
            _state.update {
                it.copy(
                    rigFreqHz = freq ?: it.rigFreqHz,
                    rigMode = mode?.label ?: it.rigMode,
                    catStatus = if (freq == null && mode == null) "No CAT reply" else "Rig in sync",
                )
            }
        }
    }

    /** Tune the rig's VFO-A to [hz] (whole Hz), then read back to confirm. */
    fun setRigFrequency(hz: Long) {
        if (!rig.isCatReady) return
        runCat("Tuning…") {
            if (rig.setFrequencyHz(hz)) {
                val freq = rig.frequencyHz()
                _state.update { it.copy(rigFreqHz = freq ?: hz, catStatus = "Tuned") }
            } else {
                _state.update { it.copy(catStatus = "Tune rejected") }
            }
        }
    }

    /** Switch the rig to DATA-USB, the mode FT8 expects. */
    fun setRigDataUsb() {
        if (!rig.isCatReady) return
        runCat("Setting DATA-U…") {
            if (rig.setMode(Ft891Cat.Mode.DATA_USB)) {
                val mode = rig.mode()
                _state.update {
                    it.copy(rigMode = mode?.label ?: Ft891Cat.Mode.DATA_USB.label, catStatus = "Mode set")
                }
            } else {
                _state.update { it.copy(catStatus = "Mode set rejected") }
            }
        }
    }

    private fun runCat(busyStatus: String, block: () -> Unit) {
        _state.update { it.copy(catBusy = true, catStatus = busyStatus) }
        catExecutor.execute {
            try {
                block()
            } catch (t: Throwable) {
                _state.update { it.copy(catStatus = t.message ?: "CAT error") }
            } finally {
                _state.update { it.copy(catBusy = false) }
            }
        }
    }

    fun setTxMessage(message: String) {
        _state.update { it.copy(txMessage = message) }
    }

    fun setTxFreqHz(freqHz: Int) {
        _state.update { it.copy(txFreqHz = freqHz.coerceIn(300, 3000)) }
    }

    fun setMyCall(call: String) {
        _state.update { it.copy(myCall = call.trim().uppercase(Locale.US)) }
    }

    fun setMyGrid(grid: String) {
        _state.update { it.copy(myGrid = grid.trim().uppercase(Locale.US)) }
    }

    /** Begin an automated QSO by calling CQ and auto-sequencing replies. */
    fun startCq() {
        val s = _state.value
        if (s.myCall.isBlank()) {
            _state.update { it.copy(error = "Set your callsign first") }
            return
        }
        if (!s.txEnabled) {
            _state.update { it.copy(error = "Enable TX first") }
            return
        }
        val machine = QsoMachine(s.myCall, s.myGrid)
        machine.startCq()
        startQsoLoop(machine)
    }

    /** Answer a decoded CQ row, auto-sequencing through to 73. */
    fun answerCq(row: DecodeRow) {
        val s = _state.value
        if (s.myCall.isBlank()) {
            _state.update { it.copy(error = "Set your callsign first") }
            return
        }
        if (!s.txEnabled) {
            _state.update { it.copy(error = "Enable TX first") }
            return
        }
        val cq = QsoMessages.parse(row.message) as? QsoRx.Cq ?: run {
            _state.update { it.copy(error = "Not a CQ: ${row.message}") }
            return
        }
        val machine = QsoMachine(s.myCall, s.myGrid)
        machine.answerCq(cq.call, cq.grid, row.snr)
        startQsoLoop(machine)
    }

    fun stopQso() {
        qsoRunning = false
        qsoThread?.interrupt()
        qsoThread = null
        synchronized(qsoLock) { qso = null }
        _state.update { it.copy(qsoActive = false, qsoState = null, qsoDx = null) }
    }

    private fun startQsoLoop(machine: QsoMachine) {
        stopQso()
        if (!_state.value.isCapturing) start()
        synchronized(qsoLock) { qso = machine }
        qsoRunning = true
        publishQsoState()

        qsoThread = Thread({
            try {
                // We transmit on the parity of the upcoming slot, RX on the other.
                val firstBoundary = SlotTiming.nextSlotStart(System.currentTimeMillis())
                val txParity = SlotTiming.slotIndexInMinute(firstBoundary) % 2
                while (qsoRunning) {
                    val wait = SlotTiming.millisUntilNextSlot(System.currentTimeMillis())
                    if (wait > 0) Thread.sleep(wait)
                    if (!qsoRunning) break

                    val slotStart = SlotTiming.slotStart(System.currentTimeMillis())
                    val ourTx = SlotTiming.slotIndexInMinute(slotStart) % 2 == txParity
                    if (!ourTx) continue

                    // Brief grace so the just-finished RX slot has decoded and advanced
                    // the machine before we choose this slot's message.
                    Thread.sleep(MonitorUiState.QSO_TX_GRACE_MS)
                    if (!qsoRunning) break

                    val message = synchronized(qsoLock) { qso?.txMessage() }
                    if (message == null) break

                    transmitMessageNow(message)
                    synchronized(qsoLock) { qso?.markTransmitted() }
                    publishQsoState()

                    val complete = synchronized(qsoLock) { qso?.state == QsoState.Complete }
                    if (complete) break
                }
            } catch (_: InterruptedException) {
                // stopQso requested.
            } catch (t: Throwable) {
                _state.update { it.copy(error = t.message ?: "QSO failed") }
            } finally {
                qsoRunning = false
                if (!Thread.currentThread().isInterrupted) {
                    if (!_state.value.isCapturing && !_state.value.isTransmitting) beginCapture()
                    publishQsoState()
                }
            }
        }, "ft8vc-qso").also { it.start() }
    }

    /** Encode and transmit [message] immediately (the loop has aligned the slot). */
    private fun transmitMessageNow(message: String) {
        val pcm = Ft8Native.encode(message, _state.value.txFreqHz.toFloat(), AppInfo.SAMPLE_RATE_HZ)
        if (pcm.isEmpty()) throw IllegalStateException("Encoder rejected: $message")

        val resume = _state.value.isCapturing
        if (resume) stopCapture()
        _state.update { it.copy(isTransmitting = true, txStatus = "TX: $message") }
        val outputId = AudioOutputs.firstUsb(getApplication())?.id
        rig.keyPtt()
        try {
            playback.playBlocking(pcm, outputId)
        } finally {
            rig.releasePtt()
        }
        _state.update { it.copy(isTransmitting = false, txStatus = "Sent: $message") }
        if (resume && qsoRunning) resumeCapture()
    }

    private fun publishQsoState() {
        val snapshot = synchronized(qsoLock) {
            qso?.let { Triple(it.state, it.dxCall, it.isActive) }
        }
        if (snapshot == null) {
            _state.update { it.copy(qsoActive = false, qsoState = null, qsoDx = null) }
            return
        }
        val (st, dx, active) = snapshot
        _state.update {
            it.copy(qsoActive = active, qsoState = qsoStateLabel(st, dx), qsoDx = dx)
        }
    }

    private fun qsoStateLabel(state: QsoState, dx: String?): String = when (state) {
        QsoState.Idle -> "Idle"
        QsoState.CallingCq -> "Calling CQ…"
        QsoState.Answering -> "Answering ${dx ?: "?"}…"
        QsoState.SendingReport -> "Report → ${dx ?: "?"}"
        QsoState.SendingRReport -> "R-report → ${dx ?: "?"}"
        QsoState.SendingRoger -> "RRR → ${dx ?: "?"}"
        QsoState.SendingSeventyThree -> "73 → ${dx ?: "?"}"
        QsoState.Complete -> "QSO complete${dx?.let { " with $it" } ?: ""}"
    }

    fun start() {
        if (_state.value.isCapturing || _state.value.isTransmitting) return
        waterfall.clear()
        slotCollector.reset()
        beginCapture()
    }

    private fun beginCapture() {
        try {
            capture.start(_state.value.selectedDeviceId, ::onFrames)
            _state.update { it.copy(isCapturing = true, error = null) }
        } catch (t: Throwable) {
            _state.update { it.copy(isCapturing = false, error = t.message ?: "Capture failed") }
        }
    }

    private fun resumeCapture() {
        if (_state.value.isCapturing || _state.value.isTransmitting) return
        beginCapture()
    }

    fun stop() {
        stopQso()
        cancelTx()
        stopCapture()
    }

    /**
     * Encode [txMessage] and transmit at the next UTC slot boundary. Requires
     * [MonitorUiState.txEnabled]; pauses RX during the slot if monitoring.
     */
    fun transmitNextSlot() {
        if (!_state.value.txEnabled || _state.value.isTransmitting) return
        val message = _state.value.txMessage.trim()
        if (message.isEmpty()) {
            _state.update { it.copy(error = "TX message is empty") }
            return
        }

        cancelTx()
        val freqHz = _state.value.txFreqHz.toFloat()
        _state.update { it.copy(txStatus = "Encoding…", error = null) }

        txThread = Thread({
            try {
                val pcm = Ft8Native.encode(message, freqHz, AppInfo.SAMPLE_RATE_HZ)
                if (pcm.isEmpty()) {
                    throw IllegalStateException("Encoder rejected message: $message")
                }

                val waitMs = SlotTiming.millisUntilNextSlot(System.currentTimeMillis())
                val waitSec = (waitMs + 999) / 1000
                _state.update { it.copy(txStatus = "TX in ${waitSec}s…") }
                if (waitMs > 0) Thread.sleep(waitMs)
                if (Thread.currentThread().isInterrupted) return@Thread

                val resumeCapture = _state.value.isCapturing
                if (resumeCapture) stopCapture()

                _state.update { it.copy(isTransmitting = true, txStatus = "Transmitting…") }
                val outputId = AudioOutputs.firstUsb(getApplication())?.id
                rig.keyPtt()
                try {
                    playback.playBlocking(pcm, outputId)
                } finally {
                    rig.releasePtt()
                }

                if (resumeCapture && !Thread.currentThread().isInterrupted) resumeCapture()
                _state.update {
                    it.copy(
                        isTransmitting = false,
                        txStatus = "TX complete",
                    )
                }
            } catch (_: InterruptedException) {
                _state.update { it.copy(isTransmitting = false, txStatus = null) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        isTransmitting = false,
                        txStatus = null,
                        error = t.message ?: "Transmit failed",
                    )
                }
            }
        }, "ft8vc-tx").also { it.start() }
    }

    private fun cancelTx() {
        txThread?.interrupt()
        txThread = null
    }

    private fun stopCapture() {
        capture.stop()
        slotCollector.reset()
        _state.update {
            it.copy(
                isCapturing = false,
                levelDbfs = MonitorUiState.SILENCE_DBFS,
                clip = false,
            )
        }
    }

    private fun onFrames(frames: ShortArray) {
        // Level metering.
        var sumSq = 0.0
        var peak = 0
        for (s in frames) {
            val a = if (s.toInt() == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else Math.abs(s.toInt())
            if (a > peak) peak = a
            sumSq += s.toDouble() * s.toDouble()
        }
        val rms = Math.sqrt(sumSq / frames.size)
        val instDb = (20.0 * Math.log10(rms / 32768.0 + 1e-9)).toFloat()
        levelEma += 0.3f * (instDb - levelEma)
        val clip = peak >= 32000

        // Spectrum -> waterfall.
        spectrum.process(frames) { column -> waterfall.addColumn(column) }

        // Accumulate into UTC slots; decode each completed slot off the capture thread.
        slotCollector.add(frames, System.currentTimeMillis()) { samples, slotStart ->
            decodeExecutor.execute { decodeSlot(samples, slotStart) }
        }

        // Throttle UI updates to ~33 fps regardless of audio callback rate.
        val now = System.nanoTime()
        if (now - lastUiUpdateNs >= 30_000_000L) {
            lastUiUpdateNs = now
            _state.update {
                it.copy(
                    levelDbfs = levelEma.coerceIn(MonitorUiState.SILENCE_DBFS, 0f),
                    clip = clip,
                    waterfallVersion = it.waterfallVersion + 1,
                )
            }
        }
    }

    private fun decodeSlot(samples: ShortArray, slotStartEpochMs: Long) {
        val results = Ft8Native.decode(samples, AppInfo.SAMPLE_RATE_HZ)
        val time = utcTimeFormat.format(Date(slotStartEpochMs))
        val rows = results
            .sortedByDescending { it.score }
            .map { r ->
                DecodeRow(
                    timeUtc = time,
                    snr = r.snr,
                    dtSeconds = r.dtSeconds,
                    freqHz = Math.round(r.freqHz),
                    message = r.message.trim(),
                    isCq = r.message.trimStart().startsWith("CQ"),
                )
            }
        _state.update { s ->
            val combined = rows + s.decodes
            s.copy(
                decodes = combined.take(MonitorUiState.MAX_DECODE_ROWS),
                lastSlotDecodeCount = rows.size,
            )
        }

        if (qsoRunning) {
            val decodes = rows.map { QsoDecode(it.message, it.snr) }
            val advanced = synchronized(qsoLock) { qso?.onDecodes(decodes) ?: false }
            if (advanced) publishQsoState()
        }
    }

    fun clearDecodes() {
        _state.update { it.copy(decodes = emptyList(), lastSlotDecodeCount = -1) }
    }

    override fun onCleared() {
        stopQso()
        cancelTx()
        capture.stop()
        rig.close()
        decodeExecutor.shutdownNow()
        catExecutor.shutdownNow()
        super.onCleared()
    }
}
