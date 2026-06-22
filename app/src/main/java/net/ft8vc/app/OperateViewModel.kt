package net.ft8vc.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import net.ft8vc.app.controllers.RigSession
import net.ft8vc.app.controllers.SettingsBridge
import net.ft8vc.app.settings.PttPreference
import net.ft8vc.app.settings.SettingsRepository
import net.ft8vc.app.ui.Waterfall
import net.ft8vc.audio.AudioInputs
import net.ft8vc.audio.AudioOutputs
import net.ft8vc.audio.UsbAudioCapture
import net.ft8vc.audio.UsbAudioPlayback
import net.ft8vc.audio.dsp.SpectrumProcessor
import net.ft8vc.core.ActivationProfile
import net.ft8vc.core.AbandonedPartners
import net.ft8vc.core.AnswerPolicy
import net.ft8vc.core.AnswerSelector
import net.ft8vc.core.AppInfo
import net.ft8vc.core.DecodeDistance
import net.ft8vc.core.DecodeViewMode
import net.ft8vc.core.QsoDecode
import net.ft8vc.core.QsoForm
import net.ft8vc.core.QsoFormLogic
import net.ft8vc.core.QsoMachine
import net.ft8vc.core.QsoMessages
import net.ft8vc.core.QsoResume
import net.ft8vc.core.QsoRx
import net.ft8vc.core.QsoState
import net.ft8vc.core.QsoTxStep
import net.ft8vc.core.SlotCollector
import net.ft8vc.core.SlotTiming
import net.ft8vc.core.StationProfileValidator
import net.ft8vc.core.TxSlotParity
import net.ft8vc.core.TxSlotSelection
import net.ft8vc.core.QsoRole
import net.ft8vc.data.Logbook
import net.ft8vc.data.RoomLogbook
import net.ft8vc.data.db.Ft8vcDatabase
import net.ft8vc.data.model.QsoContact
import net.ft8vc.app.ui.bandLabelForFreq
import net.ft8vc.ft8native.Ft8Native
import net.ft8vc.rig.Ft891Cat
import net.ft8vc.rig.RigController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

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
    private val spectrum = SpectrumProcessor(sampleRate = AppInfo.SAMPLE_RATE_HZ)
    private val slotCollector = SlotCollector(AppInfo.SAMPLE_RATE_HZ)
    private val decodeExecutor = Executors.newSingleThreadExecutor()
    private val utcTimeFormat = SimpleDateFormat("HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val utcClockFormat = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    val waterfall = Waterfall(bins = spectrum.binCount)
    val maxAudioFreqHz: Int = spectrum.freqForBin(spectrum.binCount).toInt()

    private var levelEma = OperateUiState.SILENCE_DBFS
    @Volatile private var inputGain = 1f
    private var gainScratch: ShortArray? = null
    private var lastUiUpdateNs = 0L
    private var txThread: Thread? = null
    private var slotClockJob: Job? = null
    private var qsoTxParity: Int? = null

    private val qsoLock = Any()
    private var qso: QsoMachine? = null
    @Volatile private var qsoRunning = false
    private var qsoThread: Thread? = null
    private var lastLoggedKey: String? = null

    private var lastDialFreqHz: Long? = null
    private val abandonedPartners = AbandonedPartners()

    /** When false, [operateTxText] tracks auto-composed messages from the QSO machine. */
    private var operateTxUserEdited = false

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
                inputGain = s.inputGain.coerceIn(OperateUiState.INPUT_GAIN_MIN, 1f)
            }
        }
        viewModelScope.launch {
            settingsBridge.stationIdentityChanged.collect {
                refreshOperateTxFromStation()
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
            logbook.contactCount().collect { count ->
                _state.update { it.copy(contactCount = count) }
            }
        }
        refreshDevices()
        startSlotClock()
    }

    private fun startSlotClock() {
        slotClockJob?.cancel()
        slotClockJob = viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val parity = qsoTxParity ?: _state.value.txSlotParity.bit
                val isTx = SlotTiming.slotIndexInMinute(now) % 2 == parity
                _state.update {
                    it.copy(
                        slotIndex = SlotTiming.slotIndexInMinute(now),
                        secondsToNextSlot = SlotTiming.secondsUntilNextSlot(now),
                        secondsUntilOurTxSlot = if (isTx) {
                            SlotTiming.secondsUntilNextSlot(now)
                        } else {
                            ((TxSlotSelection.millisUntilNextTxSlot(now, parity) + 999) / 1000).toInt()
                        },
                        utcClock = utcClockFormat.format(Date(now)),
                        isTxSlot = isTx,
                        activeTxSlotParity = qsoTxParity?.let(TxSlotParity::fromBit),
                    )
                }
                delay(250)
            }
        }
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

    fun clearAbandonedPartners() {
        abandonedPartners.clear()
        notify("Cleared abandoned-station blocklist")
    }

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
            refreshOperateTxFromStation()
        }
        viewModelScope.launch { settingsRepo.setPotaModeEnabled(enabled) }
    }

    fun setPotaParkRef(ref: String) {
        _state.update { it.copy(potaParkRef = ref.trim().uppercase(Locale.US)) }
        viewModelScope.launch { settingsRepo.setPotaParkRef(ref) }
    }

    fun setPttPreference(pref: PttPreference) {
        _state.update { it.copy(pttPreference = pref) }
        viewModelScope.launch { settingsRepo.setPttPreference(pref) }
    }

    private fun effectiveCqModifier(): String? =
        ActivationProfile.cqModifier(_state.value.potaModeEnabled)

    private fun newQsoMachine(): QsoMachine {
        val s = _state.value
        return QsoMachine(s.myCall, s.myGrid, effectiveCqModifier())
    }

    fun acknowledgeLicense() {
        viewModelScope.launch { settingsRepo.setLicenseAcknowledged(true) }
    }

    fun setMyCall(call: String) {
        val normalized = call.trim().uppercase(Locale.US)
        val changed = _state.value.myCall != normalized
        _state.update { it.copy(myCall = normalized) }
        if (changed) refreshOperateTxFromStation()
        viewModelScope.launch { settingsRepo.setMyCall(call) }
    }

    fun setMyGrid(grid: String) {
        val normalized = grid.trim().uppercase(Locale.US)
        val changed = _state.value.myGrid != normalized
        _state.update { it.copy(myGrid = normalized) }
        if (changed) refreshOperateTxFromStation()
        viewModelScope.launch { settingsRepo.setMyGrid(grid) }
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
        inputGain = g
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
        slotCollector.reset()
        prepareRig()
        restoreLastBandIfNeeded()
        beginCapture()
        _state.update {
            it.copy(isOperating = true, isCapturing = true, operateStatus = "Operating")
        }
        operateTxUserEdited = false
        syncOperateTxText(defaultOperateTxText())
        publishQsoState()
    }

    fun stopOperating() {
        haltTxInternal(announce = false)
        stopCapture()
        _state.update { it.copy(isOperating = false, operateStatus = null) }
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
        stopQso()
        _state.update {
            it.copy(txStatus = if (wasTransmitting) "TX halted" else it.txStatus)
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

    fun startCq() {
        val s = _state.value
        if (!hasValidStationProfile(s)) return
        if (s.potaModeEnabled && !ActivationProfile.isValidParkRef(s.potaParkRef)) {
            notify("Set a valid POTA park reference in Settings (e.g. US-3315)", SnackbarEvent.Tag.ERROR)
            return
        }
        if (!s.txEnabled) {
            notify("Enable TX in Settings first", SnackbarEvent.Tag.ERROR)
            return
        }
        if (!s.isOperating) startOperating()
        if (!operateTxUserEdited) {
            syncOperateTxText(defaultOperateTxText())
        }
        val machine = newQsoMachine()
        machine.startCq()
        applyOperateTxOverride(machine)
        startQsoLoop(machine)
    }

    fun setOperateTxText(text: String) {
        operateTxUserEdited = true
        _state.update {
            it.copy(operateTxText = text, operateTxStep = QsoTxStep.Custom, operateTxEdited = true)
        }
        synchronized(qsoLock) {
            qso?.setCustomMessage(text.trim().takeIf { it.isNotEmpty() })
        }
    }

    fun selectOperateTxStep(step: QsoTxStep) {
        if (step == QsoTxStep.Custom) {
            operateTxUserEdited = true
            _state.update { it.copy(operateTxStep = QsoTxStep.Custom, operateTxEdited = true) }
            return
        }
        val composed = composeOperateTxForStep(step) ?: return
        operateTxUserEdited = true
        _state.update {
            it.copy(
                operateTxStep = step,
                operateTxText = composed,
                operateTxEdited = true,
            )
        }
        synchronized(qsoLock) {
            qso?.setCustomMessage(composed)
        }
    }

    fun resetOperateTxText() {
        operateTxUserEdited = false
        synchronized(qsoLock) { qso?.clearCustomMessage() }
        syncOperateTxText(autoOperateTxText())
    }

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

    private fun defaultOperateTxText(): String {
        val s = _state.value
        return QsoMessages.cq(s.myCall, s.myGrid, effectiveCqModifier())
    }

    private fun autoOperateTxText(): String? =
        synchronized(qsoLock) { qso?.txMessage() } ?: defaultOperateTxText()

    private fun composeOperateTxForStep(step: QsoTxStep): String? {
        val s = _state.value
        return QsoFormLogic.compose(
            s.myCall,
            s.myGrid,
            effectiveCqModifier(),
            s.operateTxForm.copy(txStep = step),
        )
    }

    private fun currentAutoTxStep(): QsoTxStep =
        synchronized(qsoLock) {
            qso?.let { QsoFormLogic.stepFromState(it.state) }
        } ?: QsoTxStep.Cq

    private fun currentOperateTxForm(): QsoForm =
        synchronized(qsoLock) {
            qso?.let { QsoFormLogic.fromMachine(it) }
        } ?: QsoForm()

    private fun applyOperateTxOverride(machine: QsoMachine) {
        if (!operateTxUserEdited) return
        val text = _state.value.operateTxText.trim()
        if (text.isNotEmpty()) {
            machine.setCustomMessage(text)
        }
    }

    private fun refreshOperateTxFromStation() {
        if (operateTxUserEdited) return
        syncOperateTxText(if (qsoRunning) autoOperateTxText() else defaultOperateTxText())
    }

    private fun syncOperateTxText(auto: String?, step: QsoTxStep = currentAutoTxStep()) {
        val message = auto ?: defaultOperateTxText()
        if (!operateTxUserEdited) {
            _state.update {
                it.copy(
                    operateTxText = message,
                    operateTxStep = step,
                    operateTxEdited = false,
                )
            }
        }
    }

    /** Tap a decode directed at us to resume the QSO sequence (e.g. after Stop QSO). */
    fun resumeFromDecode(row: DecodeRow) {
        val s = _state.value
        if (!hasValidStationProfile(s)) return
        if (!s.txEnabled) {
            notify("Enable TX in Settings first", SnackbarEvent.Tag.ERROR)
            return
        }
        val opp = QsoResume.opportunityFromDecode(s.myCall, QsoDecode(row.message, row.snr)) ?: run {
            notify("Not a directed message to ${s.myCall}", SnackbarEvent.Tag.ERROR)
            return
        }
        if (!s.isOperating) startOperating()
        abandonedPartners.allowResume(opp.dxCall)
        resumeFromOpportunity(opp, "Resuming QSO with ${opp.dxCall}", row.slotParity)
    }

    fun answerCq(row: DecodeRow) {
        val s = _state.value
        if (!hasValidStationProfile(s)) return
        if (!s.txEnabled) {
            notify("Enable TX in Settings first", SnackbarEvent.Tag.ERROR)
            return
        }
        val cq = QsoMessages.parse(row.message) as? QsoRx.Cq ?: run {
            notify("Not a CQ: ${row.message}", SnackbarEvent.Tag.ERROR)
            return
        }
        if (!s.isOperating) startOperating()
        notify("Answering ${cq.call}")
        val machine = newQsoMachine()
        machine.answerCq(cq.call, cq.grid, row.snr)
        startQsoLoop(machine, hearingSlotParity = row.slotParity)
    }

    fun stopQso() {
        playback.stop()
        rigSession.releasePttBlocking()
        cancelTx()
        qsoRunning = false
        qsoThread?.interrupt()
        qsoThread = null
        qsoTxParity = null
        synchronized(qsoLock) { qso = null }
        operateTxUserEdited = false
        _state.update {
            it.copy(
                isTransmitting = false,
                qsoActive = false,
                qsoState = null,
                qsoDx = null,
                nextTxMessage = null,
                operateTxText = defaultOperateTxText(),
                operateTxStep = QsoTxStep.Cq,
                operateTxEdited = false,
                operateTxForm = QsoForm(),
                activeTxSlotParity = null,
            )
        }
        if (_state.value.isOperating && !_state.value.isCapturing) {
            resumeCapture()
        }
    }

    /** Stop and block auto-resume to this partner for the rest of the operating session. */
    fun abandonQso() {
        val dx = synchronized(qsoLock) { qso?.dxCall }
        if (dx != null) abandonedPartners.abandon(dx)
        stopQso()
        notify(dx?.let { call -> "Abandoned QSO with $call" } ?: "QSO stopped")
    }

    private fun excludedDx(): Set<String> = abandonedPartners.snapshot()

    private fun hasValidStationProfile(s: OperateUiState): Boolean {
        if (!StationProfileValidator.isValidCall(s.myCall)) {
            notify(
                "Set a valid callsign in Settings before transmitting",
                SnackbarEvent.Tag.ERROR,
            )
            return false
        }
        if (!StationProfileValidator.isValidGrid(s.myGrid)) {
            notify(
                "Set a valid 4- or 6-char grid in Settings before transmitting",
                SnackbarEvent.Tag.ERROR,
            )
            return false
        }
        return true
    }

    private fun abandonQsoForNoReply() {
        val dx = synchronized(qsoLock) { qso?.dxCall }
        if (dx != null) abandonedPartners.abandon(dx)
        stopQso()
        notify(
            when {
                dx != null -> "No reply from $dx — QSO abandoned"
                else -> "No answers — stopped calling CQ"
            },
        )
    }

    private fun startQsoLoop(machine: QsoMachine, hearingSlotParity: Int? = null) {
        stopQso()
        synchronized(qsoLock) { qso = machine }
        qsoRunning = true
        qsoTxParity = resolveTxParity(machine, hearingSlotParity)
        publishQsoState()

        qsoThread = Thread({
            try {
                val txParity = qsoTxParity ?: return@Thread
                while (qsoRunning) {
                    val wait = SlotTiming.millisUntilNextSlot(System.currentTimeMillis())
                    if (wait > 0) Thread.sleep(wait)
                    if (!qsoRunning) break

                    val slotStart = SlotTiming.slotStart(System.currentTimeMillis())
                    val ourTx = SlotTiming.slotIndexInMinute(slotStart) % 2 == txParity
                    if (!ourTx) continue

                    Thread.sleep(OperateUiState.QSO_TX_GRACE_MS)
                    if (!qsoRunning) break

                    val message = synchronized(qsoLock) { qso?.txMessage() }
                    if (message == null) break

                    transmitMessageNow(message)
                    val noReplyLimitHit = synchronized(qsoLock) {
                        qso?.recordTransmitted()
                        qso?.noReplyLimitExceeded(_state.value.maxUnansweredTxCycles) == true
                    }
                    publishQsoState()

                    if (noReplyLimitHit) {
                        abandonQsoForNoReply()
                        break
                    }

                    val complete = synchronized(qsoLock) { qso?.state == QsoState.Complete }
                    if (complete) {
                        handleQsoComplete()
                        break
                    }
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) {
                notify(t.message ?: "QSO failed", SnackbarEvent.Tag.ERROR)
            } finally {
                qsoRunning = false
                qsoTxParity = null
                if (!Thread.currentThread().isInterrupted) {
                    if (_state.value.isOperating && !_state.value.isCapturing && !_state.value.isTransmitting) {
                        beginCapture()
                    }
                    publishQsoState()
                }
            }
        }, "ft8vc-qso").also { it.start() }
    }

    private fun resolveTxParity(machine: QsoMachine, hearingSlotParity: Int?): Int {
        if (hearingSlotParity != null && machine.role == QsoRole.Answerer) {
            return TxSlotSelection.answerParity(hearingSlotParity)
        }
        return _state.value.txSlotParity.bit
    }

    private fun handleQsoComplete() {
        val snapshot = synchronized(qsoLock) {
            qso?.snapshot(System.currentTimeMillis())
        } ?: return
        val key = "${snapshot.dxCall}:${snapshot.completedAtEpochMs}"
        if (key == lastLoggedKey) return
        lastLoggedKey = key

        val freq = _state.value.rigFreqHz
        val band = bandLabelForFreq(freq)
        val contact = QsoContact.fromSnapshot(snapshot, freq, band)

        viewModelScope.launch(Dispatchers.IO) {
            logbook.log(contact)
        }

        notify("QSO complete with ${snapshot.dxCall} — logged", SnackbarEvent.Tag.QSO_COMPLETE)
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
        if (resume && (qsoRunning || _state.value.isOperating)) resumeCapture()
    }

    private fun publishQsoState() {
        val machineSnapshot = synchronized(qsoLock) {
            qso?.let { m -> Triple(m.state, m.dxCall, m.isActive) to QsoFormLogic.fromMachine(m) }
        }
        if (machineSnapshot == null) {
            val nextMsg = defaultOperateTxText()
            syncOperateTxText(nextMsg, QsoTxStep.Cq)
            _state.update {
                it.copy(
                    qsoActive = false,
                    qsoState = null,
                    qsoDx = null,
                    nextTxMessage = nextMsg,
                    operateTxForm = QsoForm(),
                )
            }
            return
        }
        val (triple, form) = machineSnapshot
        val (st, dx, active) = triple
        val autoStep = QsoFormLogic.stepFromState(st)
        val nextMsg = synchronized(qsoLock) { qso?.txMessage() }
        syncOperateTxText(nextMsg, autoStep)
        _state.update {
            it.copy(
                qsoActive = active,
                qsoState = qsoStateLabel(st, dx),
                qsoDx = dx,
                nextTxMessage = nextMsg,
                operateTxEdited = operateTxUserEdited,
                operateTxForm = form,
            )
        }
    }

    private fun qsoStateLabel(state: QsoState, dx: String?): String = when (state) {
        QsoState.Idle -> "${_state.value.myCall} ${_state.value.myGrid}"
        QsoState.CallingCq -> if (effectiveCqModifier() != null) "Calling CQ POTA…" else "Calling CQ…"
        QsoState.Answering -> "Answering ${dx ?: "?"}…"
        QsoState.SendingReport -> "QSO ${dx ?: "?"} — Report"
        QsoState.SendingRReport -> "QSO ${dx ?: "?"} — R-report"
        QsoState.SendingRoger -> "QSO ${dx ?: "?"} — RRR"
        QsoState.SendingSeventyThree -> "QSO ${dx ?: "?"} — 73"
        QsoState.Complete -> "QSO complete${dx?.let { " with $it" } ?: ""}"
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
            capture.start(_state.value.selectedDeviceId, ::onFrames)
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
        slotCollector.reset()
        _state.update {
            it.copy(isCapturing = false, levelDbfs = OperateUiState.SILENCE_DBFS, clip = false)
        }
    }

    private fun onFrames(frames: ShortArray) {
        val pcm = scaledFrames(frames, inputGain)
        var sumSq = 0.0
        var peak = 0
        for (s in pcm) {
            val a = if (s.toInt() == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else Math.abs(s.toInt())
            if (a > peak) peak = a
            sumSq += s.toDouble() * s.toDouble()
        }
        val rms = Math.sqrt(sumSq / frames.size)
        val instDb = (20.0 * Math.log10(rms / 32768.0 + 1e-9)).toFloat()
        levelEma += 0.3f * (instDb - levelEma)
        val clip = peak >= 32000

        spectrum.process(pcm) { column -> waterfall.addColumn(column) }
        slotCollector.add(pcm, System.currentTimeMillis()) { samples, slotStart ->
            decodeExecutor.execute { decodeSlot(samples, slotStart) }
        }

        val now = System.nanoTime()
        if (now - lastUiUpdateNs >= 30_000_000L) {
            lastUiUpdateNs = now
            _state.update {
                it.copy(
                    levelDbfs = levelEma.coerceIn(OperateUiState.SILENCE_DBFS, 0f),
                    clip = clip,
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

    private fun decodeSlot(samples: ShortArray, slotStartEpochMs: Long) {
        val results = Ft8Native.decode(samples, AppInfo.SAMPLE_RATE_HZ)
        val time = utcTimeFormat.format(Date(slotStartEpochMs))
        val myCall = _state.value.myCall
        val myGrid = _state.value.myGrid
        val slotDecodes = results.map { r ->
            QsoDecode(r.message.trim(), r.snr)
        }
        val slotParity = TxSlotSelection.slotParity(slotStartEpochMs)
        val rows = results
            .sortedByDescending { it.score }
            .map { r ->
                val message = r.message.trim()
                DecodeRow(
                    timeUtc = time,
                    snr = r.snr,
                    dtSeconds = r.dtSeconds,
                    freqHz = Math.round(r.freqHz),
                    message = message,
                    isCq = message.startsWith("CQ"),
                    isToMe = QsoResume.isDirectedToMe(myCall, message),
                    distanceKm = DecodeDistance.kmFromMessage(myGrid, message),
                    slotParity = slotParity,
                )
            }
        _state.update { s ->
            val combined = rows + s.decodes
            s.copy(
                decodes = combined.take(OperateUiState.MAX_DECODE_ROWS),
                lastSlotDecodeCount = rows.size,
            )
        }

        val s = _state.value
        if (qsoRunning && s.autoSeqEnabled) {
            val excluded = excludedDx()
            val advanced = synchronized(qsoLock) {
                qso?.onDecodes(slotDecodes, s.answerPolicy, excluded) ?: false
            }
            if (advanced) {
                operateTxUserEdited = false
                publishQsoState()
                val complete = synchronized(qsoLock) { qso?.state == QsoState.Complete }
                if (complete) handleQsoComplete()
            }
        } else if (!qsoRunning && s.isOperating && s.txEnabled && s.myCall.isNotBlank()) {
            if (s.answerWhenCalledEnabled) {
                tryAnswerWhenCalled(slotDecodes, slotParity)
            }
            if (!qsoRunning && s.autoAnswerCqEnabled) {
                tryAutoAnswerCq(slotDecodes, slotParity)
            }
        }
    }

    private fun tryAnswerWhenCalled(slotDecodes: List<QsoDecode>, hearingSlotParity: Int) {
        if (qsoRunning || slotDecodes.isEmpty()) return
        val s = _state.value
        val opp = QsoResume.findOpportunity(
            s.myCall,
            s.myGrid,
            slotDecodes,
            s.answerPolicy,
            excludedDx(),
        ) ?: return
        resumeFromOpportunity(opp, "Answering ${opp.dxCall}", hearingSlotParity)
    }

    private fun tryAutoAnswerCq(slotDecodes: List<QsoDecode>, hearingSlotParity: Int) {
        if (qsoRunning || slotDecodes.isEmpty()) return
        val s = _state.value
        val picked = AnswerSelector.selectCq(
            s.myCall,
            s.myGrid,
            slotDecodes,
            s.answerPolicy,
            excludedDx(),
        ) ?: return
        val cq = QsoMessages.parse(picked.message) as? QsoRx.Cq ?: return
        if (!s.isOperating) startOperating()
        notify("Answering ${cq.call}")
        val machine = newQsoMachine()
        machine.answerCq(cq.call, cq.grid, picked.snr)
        startQsoLoop(machine, hearingSlotParity = hearingSlotParity)
    }

    private fun resumeFromOpportunity(opp: QsoResume.Opportunity, snackbar: String, hearingSlotParity: Int) {
        val machine = newQsoMachine()
        QsoResume.apply(machine, opp)
        notify(snackbar)
        startQsoLoop(
            machine,
            hearingSlotParity = if (machine.role == QsoRole.Answerer) hearingSlotParity else null,
        )
    }

    fun clearDecodes() {
        _state.update { it.copy(decodes = emptyList(), lastSlotDecodeCount = -1) }
    }

    override fun onCleared() {
        playback.stop()
        rigSession.releasePttBlocking()
        stopOperating()
        slotClockJob?.cancel()
        capture.stop()
        rig.close()
        decodeExecutor.shutdownNow()
        rigSession.close()
        super.onCleared()
    }

    private companion object {
        /** Floor-offset dB used by the spectrum/waterfall renderer at the default brightness (0.6). */
        const val WATERFALL_FLOOR_OFFSET_DB_DEFAULT = 24f - 0.6f * 32f
    }
}
