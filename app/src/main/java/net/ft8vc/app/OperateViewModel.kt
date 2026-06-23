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
import net.ft8vc.app.controllers.WorkedBeforeCache
import net.ft8vc.app.ui.Waterfall
import net.ft8vc.app.ui.bandLabelForFreqLoose
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Thin orchestrator: constructs the five controllers (SettingsBridge,
 * RigSession, DecodeController, QsoSessionController, TxOrchestrator),
 * wires them together, dispatches UI intents into the appropriate
 * controller, and assembles [OperateUiState] via the [combine] flow
 * below. The VM holds no mutable mirror of any controller slice — slices
 * are the source of truth, and a small VM-residual [OperateViewState]
 * holds only state that doesn't belong to any controller (devices list,
 * isOperating, isCapturing, USB plumbing status, contact count).
 */
class OperateViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepo = SettingsRepository(app)
    private val settingsBridge = SettingsBridge(settingsRepo, viewModelScope)
    private val logbook: Logbook = RoomLogbook(Ft8vcDatabase.get(app))
    private val workedBeforeCache = WorkedBeforeCache(logbook)

    /**
     * Phase 5 (REFACTOR-06) follow-up — combine() flow assembly.
     *
     * Holds VM-owned residual state that doesn't live in any controller slice:
     * USB device list + selected ID, operating/capturing flags, USB plumbing
     * status (pttReady, catReady, USB-probe txStatus), the manual txMessage
     * field, the operateStatus string, and the logbook contact count.
     *
     * Everything else in [OperateUiState] is derived from the controller slices
     * by the [combine] below — VM no longer mirrors slice fields into a
     * MutableStateFlow.
     */
    private data class OperateViewState(
        val devices: List<net.ft8vc.audio.AudioInputDevice> = emptyList(),
        /** Settings provides the persisted selection; this overrides only when settings is null. */
        val selectedDeviceId: Int? = null,
        val isOperating: Boolean = false,
        val isCapturing: Boolean = false,
        val pttReady: Boolean = false,
        val catReady: Boolean = false,
        val txMessage: String = "",
        /** USB-probe / halt status; takes precedence over TxSlice.txStatus when non-null. */
        val txStatus: String? = null,
        val operateStatus: String? = null,
        val contactCount: Int = 0,
    )

    private val _viewState = MutableStateFlow(OperateViewState())

    // The combine() flow assembly is declared further down, after all
    // controllers are constructed (Kotlin's forward-reference rules).
    lateinit var state: StateFlow<OperateUiState>
        private set

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
        workedBeforeLookup = { call ->
            // Synchronous wrapper around the suspending in-memory cache. Safe on
            // the decode dispatcher: first lookup per call hits Room once, every
            // subsequent lookup is an in-memory map read. Decode batches arrive
            // at 15-second intervals.
            runBlocking { workedBeforeCache.classify(call, currentBandLabel()) }
        },
    )

    private fun currentBandLabel(): String? =
        bandLabelForFreqLoose(state.value.rigFreqHz)
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
                if (state.value.isCapturing) stopCapture()
            }
            override fun resumeAfterTx() {
                if (state.value.isOperating && !state.value.isCapturing) beginCapture()
            }
        },
    )

    private var txThread: Thread? = null
    private var lastDialFreqHz: Long? = null

    init {
        state = combine(
            kotlinx.coroutines.flow.combine(settingsBridge.slice, _viewState) { s, v -> s to v },
            rigSession.slice,
            decodeController.slice,
            txOrchestrator.slice,
            qsoSession.slice,
        ) { (settings, view), rig, decode, tx, qso ->
            OperateUiState(
                myCall = settings.myCall,
                myGrid = settings.myGrid,
                licenseAcknowledged = settings.licenseAcknowledged,
                potaModeEnabled = settings.potaModeEnabled,
                potaParkRef = settings.potaParkRef,
                useDarkTheme = settings.useDarkTheme,
                decodeViewMode = settings.decodeViewMode,
                cq73OnlyFilter = settings.cq73OnlyFilter,
                devices = view.devices,
                selectedDeviceId = settings.selectedAudioDeviceId ?: view.selectedDeviceId,
                isOperating = view.isOperating,
                isCapturing = view.isCapturing,
                levelDbfs = decode.levelDbfs,
                clip = decode.clip,
                sampleRateHz = AppInfo.SAMPLE_RATE_HZ,
                waterfallVersion = decode.waterfallVersion,
                decodes = decode.decodes,
                lastSlotDecodeCount = decode.lastSlotDecodeCount,
                decodeFailureCount = decode.decodeFailureCount,
                inputGain = settings.inputGain,
                txEnabled = settings.txEnabledInSettings,
                txMessage = view.txMessage,
                txFreqHz = settings.txToneHz,
                nextTxMessage = qso.nextTxMessage,
                txStatus = view.txStatus ?: tx.txStatus,
                isTransmitting = tx.isTransmitting,
                pttReady = view.pttReady,
                operateTxText = qso.operateTxText,
                operateTxStep = qso.operateTxStep,
                operateTxEdited = qso.operateTxEdited,
                operateTxForm = qso.operateTxForm,
                autoSeqEnabled = settings.autoSeqEnabled,
                answerWhenCalledEnabled = settings.answerWhenCalledEnabled,
                autoAnswerCqEnabled = settings.autoAnswerCqEnabled,
                answerPolicy = settings.answerPolicy,
                maxUnansweredTxCycles = settings.maxUnansweredTxCycles,
                lateStartTxEnabled = settings.lateStartTxEnabled,
                qsoActive = qso.qsoActive,
                qsoState = qso.qsoState,
                qsoDx = qso.qsoDx,
                catReady = view.catReady,
                rigFreqHz = rig.rigFreqHz,
                rigMode = rig.rigMode,
                catStatus = rig.catStatus,
                catBusy = rig.catBusy,
                lastDialFreqHz = settings.lastDialFreqHz,
                pttPreference = settings.pttPreference,
                slotIndex = qso.slotIndex,
                secondsToNextSlot = qso.secondsToNextSlot,
                isTxSlot = qso.isTxSlot,
                secondsUntilOurTxSlot = qso.secondsUntilOurTxSlot,
                txSlotParity = settings.txSlotParity,
                activeTxSlotParity = qso.activeTxSlotParity,
                utcClock = qso.utcClock,
                appRfState = tx.appRfState,
                nativeVersion = tx.nativeVersion,
                nativeLoaded = tx.nativeLoaded,
                txSafetyHaltActive = tx.txSafetyHaltActive,
                digirigDisconnected = tx.digirigDisconnected,
                catUnreachable = rig.catUnreachable,
                decodeFailureRecent = decode.decodeFailureRecent,
                zeroSampleSlots = decode.zeroSampleSlots,
                operateStatus = view.operateStatus,
                contactCount = view.contactCount,
                lastAdifBackupAtMs = settings.lastAdifBackupAtMs,
            )
        }.distinctUntilChanged().stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            OperateUiState(),
        )
    }

    // ── USB detach receiver: triggers EMERGENCY-HALT-like routing per SAFETY-02 ────
    private val usbDetachReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            if (intent.action != android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED) return
            txOrchestrator.notifyUsbDetached()
            qsoSession.stopQso()
            rigSession.refreshDigirigPresence()
        }
    }

    // ── Phase 7 (HYG-04): ADIF backup on app pause ────
    private val processLifecycleObserver = object : androidx.lifecycle.DefaultLifecycleObserver {
        override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
            AdifAutoBackup.scheduleBackupAfterQso(getApplication(), logbook, settingsRepo)
        }
    }

    // ── Phase 6 (RELY-02a): AudioDeviceCallback — first of two hot-swap signals ────
    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
            if (removedDevices == null || removedDevices.isEmpty()) return
            val anyInput = removedDevices.any { it.isSource }
            if (anyInput && _viewState.value.isCapturing) {
                notify("Audio device removed — restarting capture", SnackbarEvent.Tag.ERROR)
                restartCapture()
            }
        }
    }

    init {
        waterfall.floorOffsetDb = WATERFALL_FLOOR_OFFSET_DB_DEFAULT
        // Settings → controller setters (the only mirror left: settings need to
        // be pushed INTO controllers so their loops see fresh values).
        viewModelScope.launch {
            settingsBridge.slice.collect { s ->
                lastDialFreqHz = s.lastDialFreqHz
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
        // Decode-controller cross-checks (zero-sample watchdog) — observed here
        // rather than via the slice collect since it's an action, not a mirror.
        viewModelScope.launch {
            decodeController.slice.collect { d ->
                if (d.zeroSampleSlots > 2 && _viewState.value.isCapturing) {
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
            decodeController.decodesOut.collect { batch ->
                qsoSession.onDecodeBatch(batch.decodes, batch.slotParity)
            }
        }
        viewModelScope.launch {
            txOrchestrator.txLog.collect { ev ->
                val timeUtc = java.time.Instant.ofEpochMilli(ev.utcMillis)
                    .atZone(java.time.ZoneOffset.UTC)
                    .toLocalTime()
                    .withNano(0)
                    .toString()
                val row = DecodeRow(
                    id = ev.utcMillis,
                    timeUtc = timeUtc,
                    snr = 0,
                    dtSeconds = 0f,
                    freqHz = ev.freqHz,
                    message = ev.message,
                    isCq = false,
                    isToMe = false,
                    distanceKm = null,
                    source = net.ft8vc.core.DecodeRowSource.Tx,
                    slotParity = net.ft8vc.core.TxSlotSelection.slotParity(ev.utcMillis),
                )
                decodeController.appendSyntheticRow(row)
            }
        }
        viewModelScope.launch {
            logbook.contactCount().collect { count ->
                _viewState.update { it.copy(contactCount = count) }
            }
        }
        refreshDevices()
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

        // Phase 7 (HYG-04): daily ADIF backup timer + app-pause backup hook.
        AdifAutoBackup.startDailyTimerIfNotRunning(app, logbook, settingsRepo)
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
    }


    fun refreshDevices() {
        val devices = AudioInputs.list(getApplication())
        _viewState.update { v ->
            val saved = v.selectedDeviceId
            val stillValid = devices.any { it.id == saved }
            val selected = if (stillValid) saved else devices.firstOrNull { it.isUsb }?.id
            v.copy(devices = devices, selectedDeviceId = selected)
        }
        if (state.value.isOperating || state.value.txEnabled) prepareRig()
    }

    fun selectDevice(id: Int) {
        val wasActive = state.value.isCapturing
        if (wasActive) stopCapture()
        _viewState.update { it.copy(selectedDeviceId = id) }
        viewModelScope.launch { settingsRepo.setSelectedAudioDeviceId(id) }
        if (wasActive) beginCapture()
    }

    fun setTxEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setTxEnabledInSettings(enabled) }
        if (enabled) prepareRig()
    }

    fun setAutoSeqEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoSeqEnabled(enabled) }
    }

    fun setAnswerWhenCalledEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAnswerWhenCalledEnabled(enabled) }
    }

    fun setAutoAnswerCqEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoAnswerCqEnabled(enabled) }
    }

    fun setAnswerPolicy(policy: AnswerPolicy) {
        viewModelScope.launch { settingsRepo.setAnswerPolicy(policy) }
    }

    fun setMaxUnansweredTxCycles(cycles: Int) {
        viewModelScope.launch { settingsRepo.setMaxUnansweredTxCycles(cycles) }
    }

    fun setLateStartTxEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setLateStartTxEnabled(enabled) }
    }

    fun clearAbandonedPartners() = qsoSession.clearAbandonedPartners()

    fun setCq73OnlyFilter(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setCq73OnlyFilter(enabled) }
    }

    fun setDecodeViewMode(mode: DecodeViewMode) {
        viewModelScope.launch { settingsRepo.setDecodeViewMode(mode) }
    }

    fun setTxSlotParity(parity: TxSlotParity) {
        if (state.value.qsoActive) return
        viewModelScope.launch { settingsRepo.setTxSlotParity(parity) }
    }

    fun setPotaModeEnabled(enabled: Boolean) {
        if (state.value.potaModeEnabled != enabled) {
            qsoSession.refreshOperateTxFromStation()
        }
        viewModelScope.launch { settingsRepo.setPotaModeEnabled(enabled) }
    }

    fun setPotaParkRef(ref: String) {
        viewModelScope.launch { settingsRepo.setPotaParkRef(ref) }
    }

    fun setPttPreference(pref: PttPreference) {
        viewModelScope.launch { settingsRepo.setPttPreference(pref) }
    }

    fun acknowledgeLicense() {
        viewModelScope.launch { settingsRepo.setLicenseAcknowledged(true) }
        // Per SAFETY-02: license re-acknowledgment after a USB reconnect is the
        // explicit user action that transitions RX_ONLY → READY.
        if (rig.isDigirigReady) txOrchestrator.notifyUsbReady()
    }

    fun setMyCall(call: String) {
        viewModelScope.launch { settingsRepo.setMyCall(call) }
    }

    fun setMyGrid(grid: String) {
        viewModelScope.launch { settingsRepo.setMyGrid(grid) }
    }

    fun setTxMessage(message: String) {
        _viewState.update { it.copy(txMessage = message) }
    }

    fun setTxFreqHz(freqHz: Int) {
        val hz = freqHz.coerceIn(300, 3000)
        viewModelScope.launch { settingsRepo.setTxToneHz(hz) }
    }

    fun setUseDarkTheme(value: Boolean) {
        viewModelScope.launch { settingsRepo.setUseDarkTheme(value) }
    }

    fun setInputGain(gain: Float) {
        val g = gain.coerceIn(OperateUiState.INPUT_GAIN_MIN, 1f)
        decodeController.setInputGain(g)
        viewModelScope.launch { settingsRepo.setInputGain(g) }
    }

    /** Master operate toggle: RX + rig prep (+ TX path when enabled). */
    fun toggleOperate() {
        if (state.value.isOperating) {
            stopOperating()
        } else {
            startOperating()
        }
    }

    fun startOperating() {
        if (state.value.isOperating) return
        waterfall.clear()
        decodeController.reset()
        prepareRig()
        restoreLastBandIfNeeded()
        beginCapture()
        _viewState.update {
            it.copy(isOperating = true, isCapturing = true, operateStatus = "Operating")
        }
        qsoSession.setOperating(true)
        qsoSession.refreshOperateTxFromStation()
    }

    fun stopOperating() {
        haltTxInternal(announce = false)
        stopCapture()
        _viewState.update { it.copy(isOperating = false, operateStatus = null) }
        qsoSession.setOperating(false)
    }

    /** Immediately stop RF output: release PTT, stop audio, cancel pending and active QSO TX. */
    fun haltTx() {
        haltTxInternal(announce = true)
    }

    private fun haltTxInternal(announce: Boolean) {
        val wasTransmitting = state.value.isTransmitting
        playback.stop()
        rigSession.releasePttBlocking()
        qsoSession.stopQso()
        if (wasTransmitting) {
            _viewState.update { it.copy(txStatus = "TX halted") }
        }
        if (announce) notify("Transmit halted")
    }

    private fun prepareRig() {
        when (rig.state()) {
            RigController.State.NoDevice -> {
                val usb = rig.usbDeviceSummary()
                _viewState.update {
                    it.copy(
                        pttReady = false,
                        txStatus = "CP2102 serial not found (PTT no-op). USB: $usb",
                    )
                }
                rigSession.refreshDigirigPresence()
            }
            RigController.State.Ready -> {
                _viewState.update { it.copy(pttReady = true, txStatus = "Digirig PTT ready") }
                rigSession.refreshDigirigPresence()
                viewModelScope.launch(rigSession.catDispatcher) {
                    val method = rig.configurePttFromCatProbe()
                    _viewState.update { it.copy(pttReady = true, txStatus = "Digirig PTT ready ($method)") }
                    onRigReady()
                }
            }
            RigController.State.NeedsPermission -> {
                _viewState.update { it.copy(txStatus = "Requesting USB permission…") }
                rig.ensureReady { ready ->
                    if (!ready) {
                        _viewState.update {
                            it.copy(pttReady = false, txStatus = "USB permission denied — PTT is no-op")
                        }
                        return@ensureReady
                    }
                    _viewState.update { it.copy(pttReady = true, txStatus = "Digirig PTT ready") }
                    rigSession.refreshDigirigPresence()
                    viewModelScope.launch(rigSession.catDispatcher) {
                        val method = rig.configurePttFromCatProbe()
                        _viewState.update { it.copy(pttReady = true, txStatus = "Digirig PTT ready ($method)") }
                        onRigReady()
                    }
                }
            }
        }
    }

    private fun onRigReady() {
        if (!rig.isCatReady) return
        _viewState.update { it.copy(catReady = true) }
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
        if (state.value.rigFreqHz == hz) return
        setRigFrequency(hz)
    }

    // ── UI actions: thin delegators to QsoSessionController ─────────────

    fun startCq() {
        if (!state.value.isOperating) startOperating()
        qsoSession.startCq()
    }

    fun answerCq(row: DecodeRow) {
        if (!state.value.isOperating) startOperating()
        qsoSession.answerCq(row)
    }

    fun resumeFromDecode(row: DecodeRow) {
        if (!state.value.isOperating) startOperating()
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
        if (state.value.isTransmitting) return
        val msg = state.value.operateTxText.trim()
        if (!state.value.isOperating) startOperating()
        viewModelScope.launch {
            txOrchestrator.transmitAfterSlotBoundary(msg, state.value.txFreqHz)
        }
    }

    fun transmitNextSlot() {
        if (state.value.isTransmitting) return
        val message = state.value.txMessage.trim()
        viewModelScope.launch {
            txOrchestrator.transmitAfterSlotBoundary(message, state.value.txFreqHz)
        }
    }

    /** Clear the latched RF-safety halt so TX can resume after operator review. */
    fun acknowledgeSafetyHalt() {
        txOrchestrator.acknowledgeAndResetEmergency()
    }

    // ── Callbacks injected into QsoSessionController ────────────────────

    private suspend fun transmitForQsoLoop(message: String): Boolean {
        return txOrchestrator.transmit(message, state.value.txFreqHz)
    }

    private suspend fun onQsoComplete(snapshot: QsoSnapshot) {
        val freq = state.value.rigFreqHz
        val band = bandLabelForFreq(freq)
        val contact = QsoContact.fromSnapshot(snapshot, freq, band)
        withContext(Dispatchers.IO) { logbook.log(contact) }
        workedBeforeCache.invalidate(contact.dxCall)
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
        if (state.value.isOperating && !state.value.isCapturing && !state.value.isTransmitting) {
            beginCapture()
        }
    }

    private fun beginCapture() {
        try {
            capture.start(state.value.selectedDeviceId, decodeController::onFrames)
            _viewState.update { it.copy(isCapturing = true) }
        } catch (t: Throwable) {
            _viewState.update { it.copy(isCapturing = false, isOperating = false) }
            notify(t.message ?: "Capture failed", SnackbarEvent.Tag.ERROR)
        }
    }

    private fun resumeCapture() {
        if (state.value.isCapturing || state.value.isTransmitting) return
        beginCapture()
    }

    private fun stopCapture() {
        capture.stop()
        val unclean = capture.consumeStopCleanFailureCount()
        if (unclean > 0) {
            notify("Audio thread didn't stop cleanly — recovering", SnackbarEvent.Tag.ERROR)
        }
        decodeController.reset()
        _viewState.update { it.copy(isCapturing = false) }
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
        runCatching {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
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
        runBlocking { workedBeforeCache.clear() }
        super.onCleared()
    }

    private companion object {
        /** Floor-offset dB used by the spectrum/waterfall renderer at the default brightness (0.6). */
        const val WATERFALL_FLOOR_OFFSET_DB_DEFAULT = 24f - 0.6f * 32f
    }
}
