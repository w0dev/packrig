package net.ft8vc.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import net.ft8vc.app.controllers.DecodeController
import net.ft8vc.app.controllers.QsoSessionController
import net.ft8vc.app.controllers.RigSession
import net.ft8vc.app.controllers.SettingsBridge
import net.ft8vc.app.ui.Waterfall
import net.ft8vc.app.settings.PttPreference
import net.ft8vc.app.settings.SettingsRepository
import net.ft8vc.audio.AudioInputs
import net.ft8vc.audio.AudioOutputs
import net.ft8vc.audio.UsbAudioCapture
import net.ft8vc.audio.UsbAudioPlayback
import net.ft8vc.core.AnswerPolicy
import net.ft8vc.core.AppInfo
import net.ft8vc.core.DecodeViewMode
import net.ft8vc.core.QsoSnapshot
import net.ft8vc.core.QsoTxStep
import net.ft8vc.core.SlotTiming
import net.ft8vc.core.TxSlotParity
import net.ft8vc.data.Logbook
import net.ft8vc.data.RoomLogbook
import net.ft8vc.data.db.Ft8vcDatabase
import net.ft8vc.data.model.QsoContact
import net.ft8vc.app.ui.bandLabelForFreq
import net.ft8vc.ft8native.Ft8Native
import net.ft8vc.rig.Ft891Cat
import net.ft8vc.rig.RigController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Single orchestrator for the Operate / Spectrum / Log / Settings screens.
 *
 * Roadmap (v1.1): split into focused controllers, each owning a slice of state
 * and a slice of the I/O it drives. Candidates (kept here for now so the v1
 * cut stays low-risk):
 *
 * - `SettingsBridge`     — observes [SettingsRepository.settings], maps onto
 *                          [OperateUiState] (see the `init` block below).
 * - `DecodeController`   — slot collection → native decode → [DecodeRow] list,
 *                          owns [SlotCollector], decode executor, [Ft8Native].
 * - `TxOrchestrator`     — encode + [UsbAudioPlayback] + PTT, owns
 *                          `isTransmitting` and the TX thread.
 * - `QsoSessionController` — wraps [QsoMachine], abandon counter, auto-seq.
 * - `RigSession`         — CAT operations (read/write VFO + mode), dial
 *                          presets, `catBusy`.
 *
 * Once those land, this class shrinks to wiring + state composition.
 */
class OperateViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val settingsBridge = SettingsBridge(settingsRepo, viewModelScope)
    private val logbook: Logbook = RoomLogbook(Ft8vcDatabase.get(app))

    private val _state = MutableStateFlow(OperateUiState())
    val state: StateFlow<OperateUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<SnackbarEvent> = _events.asSharedFlow()

    private fun notify(text: String, tag: SnackbarEvent.Tag = SnackbarEvent.Tag.TRANSIENT) {
        _events.tryEmit(SnackbarEvent(text, tag))
    }

    private val capture = UsbAudioCapture(app)
    private val playback = UsbAudioPlayback(app)
    private val rig = RigController(app)
    private val rigSession = RigSession(
        rig = rig,
        catControl = rig,
        digirigPresenceProvider = { rig.isDigirigReady },
    )
    private val decodeController = DecodeController(
        decoder = Ft8Native,
        scope = viewModelScope,
    )
    val waterfall = Waterfall(bins = decodeController.binCount).also {
        decodeController.spectrumSink = it::addColumn
    }
    val maxAudioFreqHz: Int = decodeController.maxAudioFreqHz

    private val qsoSession = QsoSessionController(
        scope = viewModelScope,
        transmitFn = ::transmitForQsoLoop,
        onQsoComplete = ::onQsoComplete,
        notifyFn = ::notify,
        resumeCaptureIfNeeded = ::resumeCaptureIfNeededForQso,
    )

    private var txThread: Thread? = null
    private var lastDialFreqHz: Long? = null

    init {
        waterfall.floorOffsetDb = WATERFALL_FLOOR_OFFSET_DB_DEFAULT
        viewModelScope.launch {
            settingsBridge.slice.collect { s ->
                lastDialFreqHz = s.lastDialFreqHz
                _state.update { current ->
                    current.copy(
                        myCall = s.myCall,
                        myGrid = s.myGrid,
                        txFreqHz = s.txToneHz,
                        licenseAcknowledged = s.licenseAcknowledged,
                        txEnabled = s.txEnabledInSettings,
                        autoSeqEnabled = s.autoSeqEnabled,
                        answerWhenCalledEnabled = s.answerWhenCalledEnabled,
                        autoAnswerCqEnabled = s.autoAnswerCqEnabled,
                        answerPolicy = s.answerPolicy,
                        maxUnansweredTxCycles = s.maxUnansweredTxCycles,
                        selectedDeviceId = s.selectedAudioDeviceId ?: current.selectedDeviceId,
                        inputGain = s.inputGain,
                        potaModeEnabled = s.potaModeEnabled,
                        potaParkRef = s.potaParkRef,
                        cq73OnlyFilter = s.cq73OnlyFilter,
                        decodeViewMode = s.decodeViewMode,
                        txSlotParity = s.txSlotParity,
                        pttPreference = s.pttPreference,
                        lastDialFreqHz = s.lastDialFreqHz,
                        useDarkTheme = s.useDarkTheme,
                    )
                }
                decodeController.setInputGain(s.inputGain)
                decodeController.setStationContext(s.myCall, s.myGrid)
                qsoSession.updateStationProfile(s.myCall, s.myGrid, s.potaModeEnabled, s.potaParkRef)
                qsoSession.setTxEnabled(s.txEnabledInSettings)
                qsoSession.setAutoSeqEnabled(s.autoSeqEnabled)
                qsoSession.setAnswerWhenCalledEnabled(s.answerWhenCalledEnabled)
                qsoSession.setAutoAnswerCqEnabled(s.autoAnswerCqEnabled)
                qsoSession.setAnswerPolicy(s.answerPolicy)
                qsoSession.setMaxUnansweredTxCycles(s.maxUnansweredTxCycles)
                qsoSession.setDefaultTxSlotParity(s.txSlotParity)
            }
        }
        viewModelScope.launch {
            settingsBridge.stationIdentityChanged.collect {
                qsoSession.refreshOperateTxFromStation()
            }
        }
        viewModelScope.launch {
            rigSession.slice.collect { r ->
                _state.update {
                    it.copy(
                        catBusy = r.catBusy,
                        catStatus = r.catStatus ?: it.catStatus,
                        rigFreqHz = r.rigFreqHz ?: it.rigFreqHz,
                        rigMode = r.rigMode ?: it.rigMode,
                    )
                }
            }
        }
        viewModelScope.launch {
            decodeController.slice.collect { d ->
                _state.update {
                    it.copy(
                        decodes = d.decodes,
                        lastSlotDecodeCount = d.lastSlotDecodeCount,
                        levelDbfs = d.levelDbfs,
                        clip = d.clip,
                        waterfallVersion = d.waterfallVersion,
                        decodeFailureCount = d.decodeFailureCount,
                    )
                }
            }
        }
        viewModelScope.launch {
            qsoSession.slice.collect { q ->
                _state.update {
                    it.copy(
                        qsoActive = q.qsoActive,
                        qsoState = q.qsoState,
                        qsoDx = q.qsoDx,
                        operateTxText = q.operateTxText,
                        operateTxStep = q.operateTxStep,
                        operateTxEdited = q.operateTxEdited,
                        operateTxForm = q.operateTxForm,
                        nextTxMessage = q.nextTxMessage,
                        activeTxSlotParity = q.activeTxSlotParity,
                        slotIndex = q.slotIndex,
                        secondsToNextSlot = q.secondsToNextSlot,
                        isTxSlot = q.isTxSlot,
                        secondsUntilOurTxSlot = q.secondsUntilOurTxSlot,
                        utcClock = q.utcClock,
                    )
                }
            }
        }
        viewModelScope.launch {
            decodeController.decodesOut.collect { batch ->
                qsoSession.onDecodeBatch(batch.decodes, batch.slotParity)
            }
        }
        viewModelScope.launch {
            logbook.contactCount().collect { count ->
                _state.update { it.copy(contactCount = count) }
            }
        }
        refreshDevices()
    }

    fun refreshDevices() {
        val devices = AudioInputs.list(getApplication())
        _state.update { s ->
            val saved = s.selectedDeviceId
            val stillValid = devices.any { it.id == saved }
            val selected = if (stillValid) saved else devices.firstOrNull { it.isUsb }?.id
            s.copy(devices = devices, selectedDeviceId = selected)
        }
        if (_state.value.isOperating || _state.value.txEnabled) prepareRig()
    }

    fun selectDevice(id: Int) {
        val wasActive = _state.value.isCapturing
        if (wasActive) stopCapture()
        _state.update { it.copy(selectedDeviceId = id) }
        viewModelScope.launch { settingsRepo.setSelectedAudioDeviceId(id) }
        if (wasActive) beginCapture()
    }

    fun setTxEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setTxEnabledInSettings(enabled) }
        if (enabled) prepareRig()
    }

    fun setAutoSeqEnabled(enabled: Boolean) {
        _state.update { it.copy(autoSeqEnabled = enabled) }
        viewModelScope.launch { settingsRepo.setAutoSeqEnabled(enabled) }
    }

    fun setAnswerWhenCalledEnabled(enabled: Boolean) {
        _state.update { it.copy(answerWhenCalledEnabled = enabled) }
        viewModelScope.launch { settingsRepo.setAnswerWhenCalledEnabled(enabled) }
    }

    fun setAutoAnswerCqEnabled(enabled: Boolean) {
        _state.update { it.copy(autoAnswerCqEnabled = enabled) }
        viewModelScope.launch { settingsRepo.setAutoAnswerCqEnabled(enabled) }
    }

    fun setAnswerPolicy(policy: AnswerPolicy) {
        _state.update { it.copy(answerPolicy = policy) }
        viewModelScope.launch { settingsRepo.setAnswerPolicy(policy) }
    }

    fun setMaxUnansweredTxCycles(cycles: Int) {
        _state.update { it.copy(maxUnansweredTxCycles = cycles) }
        viewModelScope.launch { settingsRepo.setMaxUnansweredTxCycles(cycles) }
    }

    fun clearAbandonedPartners() = qsoSession.clearAbandonedPartners()

    fun setCq73OnlyFilter(enabled: Boolean) {
        _state.update { it.copy(cq73OnlyFilter = enabled) }
        viewModelScope.launch { settingsRepo.setCq73OnlyFilter(enabled) }
    }

    fun setDecodeViewMode(mode: DecodeViewMode) {
        _state.update { it.copy(decodeViewMode = mode) }
        viewModelScope.launch { settingsRepo.setDecodeViewMode(mode) }
    }

    fun setTxSlotParity(parity: TxSlotParity) {
        if (_state.value.qsoActive) return
        _state.update { it.copy(txSlotParity = parity) }
        viewModelScope.launch { settingsRepo.setTxSlotParity(parity) }
    }

    fun setPotaModeEnabled(enabled: Boolean) {
        if (_state.value.potaModeEnabled != enabled) {
            _state.update { it.copy(potaModeEnabled = enabled) }
            pushStationProfileToQsoSession()
            qsoSession.refreshOperateTxFromStation()
        }
        viewModelScope.launch { settingsRepo.setPotaModeEnabled(enabled) }
    }

    fun setPotaParkRef(ref: String) {
        val normalized = ref.trim().uppercase(Locale.US)
        _state.update { it.copy(potaParkRef = normalized) }
        pushStationProfileToQsoSession()
        viewModelScope.launch { settingsRepo.setPotaParkRef(ref) }
    }

    fun setPttPreference(pref: PttPreference) {
        _state.update { it.copy(pttPreference = pref) }
        viewModelScope.launch { settingsRepo.setPttPreference(pref) }
    }

    fun acknowledgeLicense() {
        viewModelScope.launch { settingsRepo.setLicenseAcknowledged(true) }
    }

    fun setMyCall(call: String) {
        val normalized = call.trim().uppercase(Locale.US)
        val changed = _state.value.myCall != normalized
        _state.update { it.copy(myCall = normalized) }
        if (changed) {
            pushStationProfileToQsoSession()
            qsoSession.refreshOperateTxFromStation()
        }
        viewModelScope.launch { settingsRepo.setMyCall(call) }
    }

    fun setMyGrid(grid: String) {
        val normalized = grid.trim().uppercase(Locale.US)
        val changed = _state.value.myGrid != normalized
        _state.update { it.copy(myGrid = normalized) }
        if (changed) {
            pushStationProfileToQsoSession()
            qsoSession.refreshOperateTxFromStation()
        }
        viewModelScope.launch { settingsRepo.setMyGrid(grid) }
    }

    private fun pushStationProfileToQsoSession() {
        val s = _state.value
        qsoSession.updateStationProfile(s.myCall, s.myGrid, s.potaModeEnabled, s.potaParkRef)
    }

    fun setTxMessage(message: String) {
        _state.update { it.copy(txMessage = message) }
    }

    fun setTxFreqHz(freqHz: Int) {
        val hz = freqHz.coerceIn(300, 3000)
        _state.update { it.copy(txFreqHz = hz) }
        viewModelScope.launch { settingsRepo.setTxToneHz(hz) }
    }

    fun setUseDarkTheme(value: Boolean) {
        _state.update { it.copy(useDarkTheme = value) }
        viewModelScope.launch { settingsRepo.setUseDarkTheme(value) }
    }

    fun setInputGain(gain: Float) {
        val g = gain.coerceIn(OperateUiState.INPUT_GAIN_MIN, 1f)
        decodeController.setInputGain(g)
        _state.update { it.copy(inputGain = g) }
        viewModelScope.launch { settingsRepo.setInputGain(g) }
    }

    /** Master operate toggle: RX + rig prep (+ TX path when enabled). */
    fun toggleOperate() {
        if (_state.value.isOperating) {
            stopOperating()
        } else {
            startOperating()
        }
    }

    fun startOperating() {
        if (_state.value.isOperating) return
        waterfall.clear()
        decodeController.reset()
        prepareRig()
        restoreLastBandIfNeeded()
        beginCapture()
        _state.update {
            it.copy(isOperating = true, isCapturing = true, operateStatus = "Operating")
        }
        qsoSession.setOperating(true)
        qsoSession.refreshOperateTxFromStation()
    }

    fun stopOperating() {
        haltTxInternal(announce = false)
        stopCapture()
        _state.update { it.copy(isOperating = false, operateStatus = null) }
        qsoSession.setOperating(false)
    }

    /** Immediately stop RF output: release PTT, stop audio, cancel pending and active QSO TX. */
    fun haltTx() {
        haltTxInternal(announce = true)
    }

    private fun haltTxInternal(announce: Boolean) {
        val wasTransmitting = _state.value.isTransmitting
        playback.stop()
        rigSession.releasePttBlocking()
        cancelTx()
        qsoSession.stopQso()
        _state.update {
            it.copy(
                isTransmitting = false,
                txStatus = if (wasTransmitting) "TX halted" else it.txStatus,
            )
        }
        if (announce) notify("Transmit halted")
    }

    private fun prepareRig() {
        when (rig.state()) {
            RigController.State.NoDevice -> {
                val usb = rig.usbDeviceSummary()
                _state.update {
                    it.copy(
                        pttReady = false,
                        txStatus = "CP2102 serial not found (PTT no-op). USB: $usb",
                    )
                }
                rigSession.refreshDigirigPresence()
            }
            RigController.State.Ready -> {
                _state.update { it.copy(pttReady = true, txStatus = "Digirig PTT ready") }
                rigSession.refreshDigirigPresence()
                viewModelScope.launch(rigSession.catDispatcher) {
                    val method = rig.configurePttFromCatProbe()
                    _state.update { it.copy(pttReady = true, txStatus = "Digirig PTT ready ($method)") }
                    onRigReady()
                }
            }
            RigController.State.NeedsPermission -> {
                _state.update { it.copy(txStatus = "Requesting USB permission…") }
                rig.ensureReady { ready ->
                    if (!ready) {
                        _state.update {
                            it.copy(pttReady = false, txStatus = "USB permission denied — PTT is no-op")
                        }
                        return@ensureReady
                    }
                    _state.update { it.copy(pttReady = true, txStatus = "Digirig PTT ready") }
                    rigSession.refreshDigirigPresence()
                    viewModelScope.launch(rigSession.catDispatcher) {
                        val method = rig.configurePttFromCatProbe()
                        _state.update { it.copy(pttReady = true, txStatus = "Digirig PTT ready ($method)") }
                        onRigReady()
                    }
                }
            }
        }
    }

    private fun onRigReady() {
        if (!rig.isCatReady) return
        _state.update { it.copy(catReady = true) }
        readRig()
    }

    fun readRig() {
        if (!rig.isCatReady) return
        viewModelScope.launch {
            val freq = rigSession.readRig()
            if (freq != null) settingsRepo.setLastDialFreqHz(freq)
        }
    }

    fun setRigFrequency(hz: Long) {
        if (!rig.isCatReady) return
        viewModelScope.launch {
            if (rigSession.setFrequency(hz)) {
                val actual = rigSession.slice.value.rigFreqHz ?: hz
                settingsRepo.setLastDialFreqHz(actual)
            }
        }
    }

    fun setRigDataUsb() {
        if (!rig.isCatReady) return
        viewModelScope.launch { rigSession.setMode(Ft891Cat.Mode.DATA_USB) }
    }

    fun usbDiagnostics(): String = rig.usbDeviceSummary()

    private fun restoreLastBandIfNeeded() {
        val hz = lastDialFreqHz ?: return
        if (!rig.isCatReady) return
        if (_state.value.rigFreqHz == hz) return
        setRigFrequency(hz)
    }

    // ── UI actions: thin delegators to QsoSessionController ─────────────

    fun startCq() {
        if (!_state.value.isOperating) startOperating()
        qsoSession.startCq()
    }

    fun answerCq(row: DecodeRow) {
        if (!_state.value.isOperating) startOperating()
        qsoSession.answerCq(row)
    }

    fun resumeFromDecode(row: DecodeRow) {
        if (!_state.value.isOperating) startOperating()
        qsoSession.resumeFromDecode(row)
    }

    fun stopQso() {
        playback.stop()
        rigSession.releasePttBlocking()
        cancelTx()
        qsoSession.stopQso()
        _state.update { it.copy(isTransmitting = false) }
    }

    fun abandonQso() {
        playback.stop()
        rigSession.releasePttBlocking()
        cancelTx()
        qsoSession.abandonQso()
        _state.update { it.copy(isTransmitting = false) }
    }

    fun setOperateTxText(text: String) = qsoSession.setOperateTxText(text)
    fun selectOperateTxStep(step: QsoTxStep) = qsoSession.selectOperateTxStep(step)
    fun resetOperateTxText() = qsoSession.resetOperateTxText()

    fun transmitOperateTxOnce() {
        if (!_state.value.txEnabled || _state.value.isTransmitting) return
        val msg = _state.value.operateTxText.trim()
        if (msg.isEmpty()) {
            notify("TX message is empty", SnackbarEvent.Tag.ERROR)
            return
        }
        if (!_state.value.isOperating) startOperating()
        cancelTx()
        txThread = Thread({
            try {
                val waitMs = SlotTiming.millisUntilNextSlot(System.currentTimeMillis())
                if (waitMs > 0) Thread.sleep(waitMs)
                if (Thread.currentThread().isInterrupted) return@Thread
                transmitMessageNow(msg)
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                notify(t.message ?: "Transmit failed", SnackbarEvent.Tag.ERROR)
            }
        }, "ft8vc-tx-operate").also { it.start() }
    }

    // ── Callbacks injected into QsoSessionController ────────────────────

    private suspend fun transmitForQsoLoop(message: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                transmitMessageNow(message)
                true
            } catch (t: Throwable) {
                notify(t.message ?: "Transmit failed", SnackbarEvent.Tag.ERROR)
                false
            }
        }
    }

    private suspend fun onQsoComplete(snapshot: QsoSnapshot) {
        val freq = _state.value.rigFreqHz
        val band = bandLabelForFreq(freq)
        val contact = QsoContact.fromSnapshot(snapshot, freq, band)
        withContext(Dispatchers.IO) { logbook.log(contact) }
    }

    private fun resumeCaptureIfNeededForQso() {
        if (_state.value.isOperating && !_state.value.isCapturing && !_state.value.isTransmitting) {
            beginCapture()
        }
    }

    private fun transmitMessageNow(message: String) {
        val pcm = Ft8Native.encode(message, _state.value.txFreqHz.toFloat(), AppInfo.SAMPLE_RATE_HZ)
        if (pcm.isEmpty()) throw IllegalStateException("Encoder rejected: $message")

        val resume = _state.value.isCapturing
        if (resume) stopCapture()
        _state.update { it.copy(isTransmitting = true, txStatus = "TX: $message") }
        val outputId = AudioOutputs.firstUsb(getApplication())?.id
        rigSession.keyPttBlocking()
        val completed = try {
            playback.playBlocking(pcm, outputId)
        } finally {
            rigSession.releasePttBlocking()
        }
        _state.update {
            it.copy(
                isTransmitting = false,
                txStatus = if (completed) "Sent: $message" else "TX halted",
            )
        }
        if (resume && (_state.value.qsoActive || _state.value.isOperating)) resumeCapture()
    }

    fun transmitNextSlot() {
        if (!_state.value.txEnabled || _state.value.isTransmitting) return
        val message = _state.value.txMessage.trim()
        if (message.isEmpty()) {
            notify("TX message is empty", SnackbarEvent.Tag.ERROR)
            return
        }
        cancelTx()
        val freqHz = _state.value.txFreqHz.toFloat()
        _state.update { it.copy(txStatus = "Encoding…") }

        txThread = Thread({
            try {
                val pcm = Ft8Native.encode(message, freqHz, AppInfo.SAMPLE_RATE_HZ)
                if (pcm.isEmpty()) throw IllegalStateException("Encoder rejected message: $message")

                val waitMs = SlotTiming.millisUntilNextSlot(System.currentTimeMillis())
                val waitSec = (waitMs + 999) / 1000
                _state.update { it.copy(txStatus = "TX in ${waitSec}s…") }
                if (waitMs > 0) Thread.sleep(waitMs)
                if (Thread.currentThread().isInterrupted) return@Thread

                val resumeCapture = _state.value.isCapturing
                if (resumeCapture) stopCapture()
                _state.update { it.copy(isTransmitting = true, txStatus = "Transmitting…") }
                val outputId = AudioOutputs.firstUsb(getApplication())?.id
                rigSession.keyPttBlocking()
                val completed = try {
                    playback.playBlocking(pcm, outputId)
                } finally {
                    rigSession.releasePttBlocking()
                }
                if (resumeCapture && !Thread.currentThread().isInterrupted) resumeCapture()
                _state.update {
                    it.copy(
                        isTransmitting = false,
                        txStatus = if (completed) "TX complete" else "TX halted",
                    )
                }
            } catch (_: InterruptedException) {
                _state.update { it.copy(isTransmitting = false, txStatus = null) }
            } catch (t: Throwable) {
                _state.update { it.copy(isTransmitting = false, txStatus = null) }
                notify(t.message ?: "Transmit failed", SnackbarEvent.Tag.ERROR)
            }
        }, "ft8vc-tx").also { it.start() }
    }

    private fun cancelTx() {
        txThread?.interrupt()
        txThread = null
    }

    private fun beginCapture() {
        try {
            capture.start(_state.value.selectedDeviceId, decodeController::onFrames)
            _state.update { it.copy(isCapturing = true) }
        } catch (t: Throwable) {
            _state.update { it.copy(isCapturing = false, isOperating = false) }
            notify(t.message ?: "Capture failed", SnackbarEvent.Tag.ERROR)
        }
    }

    private fun resumeCapture() {
        if (_state.value.isCapturing || _state.value.isTransmitting) return
        beginCapture()
    }

    private fun stopCapture() {
        capture.stop()
        decodeController.reset()
        _state.update { it.copy(isCapturing = false) }
    }

    fun clearDecodes() {
        decodeController.clearDecodes()
    }

    override fun onCleared() {
        playback.stop()
        rigSession.releasePttBlocking()
        stopOperating()
        capture.stop()
        rig.close()
        decodeController.close()
        qsoSession.close()
        rigSession.close()
        super.onCleared()
    }

    private companion object {
        /** Floor-offset dB used by the spectrum/waterfall renderer at the default brightness (0.6). */
        const val WATERFALL_FLOOR_OFFSET_DB_DEFAULT = 24f - 0.6f * 32f
    }
}
