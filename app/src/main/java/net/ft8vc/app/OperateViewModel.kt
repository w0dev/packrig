package net.ft8vc.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import net.ft8vc.app.controllers.AppRfState
import net.ft8vc.app.controllers.DecodeController
import net.ft8vc.app.controllers.QsoSessionController
import net.ft8vc.app.controllers.RigSession
import net.ft8vc.app.controllers.SettingsBridge
import net.ft8vc.app.controllers.TxOrchestrator
import net.ft8vc.app.ui.Waterfall
import net.ft8vc.app.settings.PttPreference
import net.ft8vc.app.settings.SettingsRepository
import net.ft8vc.audio.AudioInputs
import net.ft8vc.audio.AudioOutputs
import net.ft8vc.audio.UsbAudioCapture
import net.ft8vc.audio.UsbAudioPlayback
import net.ft8vc.core.AnswerPolicy
import net.ft8vc.core.DecodeViewMode
import net.ft8vc.core.QsoSnapshot
import net.ft8vc.core.QsoTxStep
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

    private val txOrchestrator = TxOrchestrator(
        decoder = Ft8Native,
        playback = playback,
        rigSession = rigSession,
        scope = viewModelScope,
        notifyFn = ::notify,
        outputDeviceIdProvider = { AudioOutputs.firstUsb(getApplication())?.id },
        captureControl = object : TxOrchestrator.CaptureControl {
            override fun pauseForTx() {
                if (_state.value.isCapturing) stopCapture()
            }
            override fun resumeAfterTx() {
                if (_state.value.isOperating && !_state.value.isCapturing) beginCapture()
            }
        },
    )

    private var txThread: Thread? = null
    private var lastDialFreqHz: Long? = null

    // ── USB detach receiver: triggers EMERGENCY-HALT-like routing per SAFETY-02 ────
    private val usbDetachReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action != android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED) return
            txOrchestrator.notifyUsbDetached()
            qsoSession.stopQso()
            rigSession.refreshDigirigPresence()
        }
    }

    // ── Phase 6 (RELY-02a): AudioDeviceCallback — first of two hot-swap signals ────
    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
            if (removedDevices == null || removedDevices.isEmpty()) return
            val anyInput = removedDevices.any { it.isSource }
            if (anyInput && _state.value.isCapturing) {
                notify("Audio device removed — restarting capture", SnackbarEvent.Tag.ERROR)
                restartCapture()
            }
        }
    }

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
                        lastAdifBackupAtMs = s.lastAdifBackupAtMs,
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
                        catUnreachable = r.catUnreachable,
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
                        decodeFailureRecent = d.decodeFailureRecent,
                        zeroSampleSlots = d.zeroSampleSlots,
                    )
                }
                // Phase 6 (RELY-02b): cross-check zero-sample slots against AudioManager.
                // After >2 consecutive zero-sample slots AND USB output gone, force capture restart.
                if (d.zeroSampleSlots > 2 && _state.value.isCapturing) {
                    val devices = AudioInputs.list(getApplication())
                    val usbPresent = devices.any { it.isUsb }
                    if (!usbPresent) {
                        notify("Audio device removed — restarting capture", SnackbarEvent.Tag.ERROR)
                        restartCapture()
                    }
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
        viewModelScope.launch {
            txOrchestrator.slice.collect { tx ->
                _state.update {
                    it.copy(
                        isTransmitting = tx.isTransmitting,
                        txStatus = tx.txStatus ?: it.txStatus,
                        appRfState = tx.appRfState,
                        nativeVersion = tx.nativeVersion,
                        nativeLoaded = tx.nativeLoaded,
                        txSafetyHaltActive = tx.txSafetyHaltActive,
                        digirigDisconnected = tx.digirigDisconnected,
                    )
                }
            }
        }
        // Register USB-detach receiver for runtime safety routing (cold-launch attach
        // is handled by the manifest intent-filter — see SAFETY-02 for the symmetry).
        val filter = android.content.IntentFilter(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(usbDetachReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(usbDetachReceiver, filter)
        }
        // License acknowledgment changes after detach require re-checking via Settings;
        // we re-publish the native handshake whenever the bridge fires (cheap).
        txOrchestrator.refreshNativeStatus()

        // Phase 6 (RELY-02a): AudioDeviceCallback — fires alongside zero-sample watchdog.
        val am = app.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        am.registerAudioDeviceCallback(audioDeviceCallback, null)
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
        // Per SAFETY-02: license re-acknowledgment after a USB reconnect is the
        // explicit user action that transitions RX_ONLY → READY.
        if (rig.isDigirigReady) txOrchestrator.notifyUsbReady()
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
        qsoSession.stopQso()
        if (wasTransmitting) {
            _state.update { it.copy(isTransmitting = false, txStatus = "TX halted") }
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
        qsoSession.stopQso()
    }

    fun abandonQso() {
        playback.stop()
        rigSession.releasePttBlocking()
        qsoSession.abandonQso()
    }

    fun setOperateTxText(text: String) = qsoSession.setOperateTxText(text)
    fun selectOperateTxStep(step: QsoTxStep) = qsoSession.selectOperateTxStep(step)
    fun resetOperateTxText() = qsoSession.resetOperateTxText()

    fun transmitOperateTxOnce() {
        if (_state.value.isTransmitting) return
        val msg = _state.value.operateTxText.trim()
        if (!_state.value.isOperating) startOperating()
        viewModelScope.launch {
            txOrchestrator.transmitAfterSlotBoundary(msg, _state.value.txFreqHz)
        }
    }

    fun transmitNextSlot() {
        if (_state.value.isTransmitting) return
        val message = _state.value.txMessage.trim()
        viewModelScope.launch {
            txOrchestrator.transmitAfterSlotBoundary(message, _state.value.txFreqHz)
        }
    }

    /** Clear the latched RF-safety halt so TX can resume after operator review. */
    fun acknowledgeSafetyHalt() {
        txOrchestrator.acknowledgeAndResetEmergency()
    }

    // ── Callbacks injected into QsoSessionController ────────────────────

    private suspend fun transmitForQsoLoop(message: String): Boolean {
        return txOrchestrator.transmit(message, _state.value.txFreqHz)
    }

    private suspend fun onQsoComplete(snapshot: QsoSnapshot) {
        val freq = _state.value.rigFreqHz
        val band = bandLabelForFreq(freq)
        val contact = QsoContact.fromSnapshot(snapshot, freq, band)
        withContext(Dispatchers.IO) { logbook.log(contact) }
        // Phase 7 (HYG-04): atomic ADIF auto-export on ApplicationScope so the
        // backup outlives this ViewModel if the user pauses the app mid-write.
        AdifAutoBackup.scheduleBackupAfterQso(getApplication(), logbook, settingsRepo)
    }

    /** Phase 7 (UX-06): user-triggered backup from the Settings → Logbook row. */
    fun backupAdifNow() {
        viewModelScope.launch {
            val result = AdifAutoBackup.backupNow(getApplication(), logbook, settingsRepo)
            notify(
                if (result != null) "ADIF backup written" else "ADIF backup failed",
                if (result != null) SnackbarEvent.Tag.TRANSIENT else SnackbarEvent.Tag.ERROR,
            )
        }
    }

    private fun resumeCaptureIfNeededForQso() {
        if (_state.value.isOperating && !_state.value.isCapturing && !_state.value.isTransmitting) {
            beginCapture()
        }
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
        val unclean = capture.consumeStopCleanFailureCount()
        if (unclean > 0) {
            notify("Audio thread didn't stop cleanly — recovering", SnackbarEvent.Tag.ERROR)
        }
        decodeController.reset()
        _state.update { it.copy(isCapturing = false) }
    }

    /** Phase 6 (RELY-02/03): rebuild the capture chain after device removal or unclean stop. */
    private fun restartCapture() {
        stopCapture()
        beginCapture()
    }

    /** Phase 6 (RELY-01/07): operator taps the "CAT unreachable — tap to retry" chip. */
    fun retryCat() {
        rigSession.retryCat()
        readRig()
    }

    fun clearDecodes() {
        decodeController.clearDecodes()
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(usbDetachReceiver) }
        runCatching {
            val am = getApplication<Application>().getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            am.unregisterAudioDeviceCallback(audioDeviceCallback)
        }
        playback.stop()
        // Phase 5 final PTT release — every PTT-touching controller gets its own
        // unconditional release in its close() too (belt-and-suspenders SAFETY-01d).
        rigSession.releasePttBlocking()
        stopOperating()
        capture.stop()
        rig.close()
        txOrchestrator.close()
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
