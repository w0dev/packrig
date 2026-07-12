package net.ft8vc.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import net.ft8vc.app.controllers.AppRfState
import net.ft8vc.app.controllers.CaptureWatchdog
import net.ft8vc.app.controllers.DecodeController
import net.ft8vc.app.controllers.MonitorGate
import net.ft8vc.app.controllers.QrzUploadController
import net.ft8vc.app.controllers.QsoSessionController
import net.ft8vc.app.controllers.RigSession
import net.ft8vc.app.controllers.SettingsBridge
import net.ft8vc.app.controllers.TxCaptureControl
import net.ft8vc.app.controllers.TxOrchestrator
import net.ft8vc.app.controllers.WorkedBeforeCache
import net.ft8vc.app.ui.Waterfall
import net.ft8vc.app.ui.bandLabelForFreqLoose
import net.ft8vc.app.settings.KeystoreCipher
import net.ft8vc.app.settings.PttPreference
import net.ft8vc.app.settings.SettingsRepository
import net.ft8vc.audio.AudioInputs
import net.ft8vc.audio.AudioOutputs
import net.ft8vc.audio.CaptureLifecycle
import net.ft8vc.audio.UsbAudioCapture
import net.ft8vc.audio.UsbAudioPlayback
import net.ft8vc.core.ActivationProfile
import net.ft8vc.core.AnswerPolicy
import net.ft8vc.core.AppInfo
import net.ft8vc.core.DecodeCategory
import net.ft8vc.core.DecodeViewMode
import net.ft8vc.core.QsoSnapshot
import net.ft8vc.core.QsoTxStep
import net.ft8vc.core.TxSlotParity
import net.ft8vc.data.Logbook
import net.ft8vc.data.RoomLogbook
import net.ft8vc.data.adif.AdifImportException
import net.ft8vc.data.adif.AdifReader
import net.ft8vc.data.db.Ft8vcDatabase
import net.ft8vc.data.model.QsoContact
import net.ft8vc.data.qrz.HttpQrzClient
import net.ft8vc.data.qrz.QrzUploadQueue
import net.ft8vc.app.ui.bandLabelForLogging
import net.ft8vc.ft8native.Ft8Native
import net.ft8vc.rig.PttMethod
import net.ft8vc.rig.ProbeResult
import net.ft8vc.rig.RigController
import net.ft8vc.rig.RigProfile
import net.ft8vc.rig.RigProfiles
import net.ft8vc.rig.RigRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    private val roomLogbook = RoomLogbook(Ft8vcDatabase.get(app))
    private val logbook: Logbook = roomLogbook
    private val workedBeforeCache = WorkedBeforeCache(logbook)

    private val qrzClient = HttpQrzClient()
    private val qrzQueue = QrzUploadQueue(roomLogbook.qrzQueueStore(), qrzClient)
    private val qrzController = QrzUploadController(
        settings = settingsRepo.settings,
        queue = qrzQueue,
        client = qrzClient,
        cipher = KeystoreCipher,
        setEnabledPref = settingsRepo::setQrzUploadEnabled,
        setApiKeyPref = { settingsRepo.setQrzApiKey(it) },
        persistLastError = settingsRepo::setQrzLastError,
        registerConnectivityListener = ::registerQrzConnectivityListener,
        scope = viewModelScope,
    )

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
        /** USB-probe / halt status; fallback when TxSlice.txStatus is null (see [mergedTxStatus]). */
        val txStatus: String? = null,
        val operateStatus: String? = null,
        val contactCount: Int = 0,
        /** Operator-applied clock correction (ms); mirrored into OperateUiState for the Settings/chip display. */
        val appliedClockOffsetMs: Long = 0L,
        /** Capture watchdog gave up after exhausting restarts; drives the retry chip. */
        val captureFailed: Boolean = false,
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
    private val captureLifecycle = CaptureLifecycle(capture)
    private val captureWatchdog = CaptureWatchdog()
    private val playback = UsbAudioPlayback(app)
    private val rig = RigController(app)
    private val rigSession = RigSession(
        rig = rig,
        catControl = rig,
        digirigPresenceProvider = { rig.isDigirigReady },
    )
    private val clockCorrection = net.ft8vc.core.ClockCorrection()

    private val decodeController = DecodeController(
        decoder = Ft8Native,
        scope = viewModelScope,
        clock = clockCorrection::now,
        workedBeforeLookup = { call ->
            // Synchronous wrapper around the suspending in-memory cache. Safe on
            // the decode dispatcher: first lookup per call hits Room once, every
            // subsequent lookup is an in-memory map read. Decode batches arrive
            // at 15-second intervals.
            runBlocking { workedBeforeCache.classify(call, currentBandLabel()) }
        },
    )

    private fun currentBandLabel(): String? =
        bandLabelForFreqLoose(state.value.effectiveDialFreqHz)
    val waterfall = Waterfall(bins = decodeController.binCount).also {
        decodeController.spectrumSink = it::addColumn
    }
    val maxAudioFreqHz: Int = decodeController.maxAudioFreqHz

    private val abandonedPartners = net.ft8vc.core.AbandonedPartners()

    private val qsoSession = QsoSessionController(
        clock = clockCorrection::now,
        scope = viewModelScope,
        transmitFn = ::transmitForQsoLoop,
        transmitIntoCurrentSlotFn = ::transmitIntoCurrentSlotForQsoLoop,
        lateStartTxEnabledProvider = { settingsBridge.slice.value.lateStartTxEnabled },
        onQsoComplete = ::onQsoComplete,
        notifyFn = ::notify,
        resumeCaptureIfNeeded = ::resumeCaptureIfNeededForQso,
        abandonedPartners = abandonedPartners,
    )

    private val txOrchestrator = TxOrchestrator(
        decoder = Ft8Native,
        playback = playback,
        rigSession = rigSession,
        scope = viewModelScope,
        clock = clockCorrection::now,
        notifyFn = ::notify,
        outputDeviceIdProvider = { AudioOutputs.firstUsb(getApplication())?.id },
        captureControl = TxCaptureControl(
            isCapturing = { state.value.isCapturing },
            isOperating = { state.value.isOperating },
            stopCapture = ::stopCapture,
            beginCapture = ::beginCapture,
        ),
        lateStartTxEnabledProvider = { settingsBridge.slice.value.lateStartTxEnabled },
    )

    private var txThread: Thread? = null
    private var lastDialFreqHz: Long? = null

    init {
        viewModelScope.launch { settingsRepo.migrateLegacyRadioModel() }
        state = combine(
            kotlinx.coroutines.flow.combine(settingsBridge.slice, _viewState) { s, v -> s to v },
            rigSession.slice,
            decodeController.slice,
            txOrchestrator.slice,
            kotlinx.coroutines.flow.combine(qsoSession.slice, qrzController.slice) { q, z -> q to z },
        ) { (settings, view), rig, decode, tx, (qso, qrz) ->
            OperateUiState(
                myCall = settings.myCall,
                myGrid = settings.myGrid,
                licenseAcknowledged = settings.licenseAcknowledged,
                potaModeEnabled = settings.potaModeEnabled,
                potaParkRef = settings.potaParkRef,
                useDarkTheme = settings.useDarkTheme,
                decodeViewMode = settings.decodeViewMode,
                cq73OnlyFilter = settings.cq73OnlyFilter,
                decodeColors = settings.decodeColors,
                devices = view.devices,
                selectedDeviceId = settings.selectedAudioDeviceId ?: view.selectedDeviceId,
                audioDeviceManuallySelected = settings.selectedAudioDeviceId != null,
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
                txStatus = mergedTxStatus(tx.txStatus, view.txStatus),
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
                earlyDecodeEnabled = settings.earlyDecodeEnabled,
                sendRr73 = settings.sendRr73,
                autoCqResumeEnabled = settings.autoCqResumeEnabled,
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
                catBaud = settings.catBaud,
                radioModelId = settings.radioModelId,
                catPortOverride = settings.catPortOverride,
                rigProfiles = settings.rigProfiles,
                selectedRigProfileId = settings.selectedRigProfileId,
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
                captureFailed = view.captureFailed,
                decodeFailureRecent = decode.decodeFailureRecent,
                zeroSampleSlots = decode.zeroSampleSlots,
                clockOffsetSeconds = decode.clockOffsetSeconds,
                appliedClockOffsetMs = view.appliedClockOffsetMs,
                operateStatus = view.operateStatus,
                contactCount = view.contactCount,
                lastAdifBackupAtMs = settings.lastAdifBackupAtMs,
                userBlockedCalls = qso.userBlockedCalls,
                qrz = qrz,
            ).let { it.copy(rigHasCat = it.computeRigHasCat()) }
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

    // ── Phase 7 (HYG-04) ADIF backup on backgrounding ────
    private val processLifecycleObserver = object : androidx.lifecycle.DefaultLifecycleObserver {
        override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
            AdifAutoBackup.scheduleBackupAfterQso(getApplication(), logbook, settingsRepo)
            // Android 9+ mutes mic input for backgrounded apps — a monitor-only
            // capture would feed the watchdog silent slots, so stop it here and
            // let onStart bring it back. Operating sessions are left alone.
            if (MonitorGate.shouldStopOnBackground(state.value.isCapturing, state.value.isOperating)) {
                stopCapture()
            }
        }
    }

    // ── Phase 6 (RELY-02a): AudioDeviceCallback — first of two hot-swap signals ────
    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
            if (removedDevices == null || removedDevices.isEmpty()) return
            val anyInput = removedDevices.any { it.isSource }
            if (!anyInput) return
            val action = MonitorGate.onUsbAudioInputRemoved(
                isCapturing = _viewState.value.isCapturing,
                isOperating = _viewState.value.isOperating,
                usbInputStillPresent = AudioInputs.list(getApplication()).any { it.isUsb },
            )
            when (action) {
                MonitorGate.RemovalAction.IGNORE -> Unit
                MonitorGate.RemovalAction.STOP_QUIETLY -> stopCapture()
                MonitorGate.RemovalAction.RESTART_WITH_NOTICE -> {
                    notify("Audio device removed — restarting capture", SnackbarEvent.Tag.ERROR)
                    restartCapture()
                }
            }
        }
    }

    init {
        waterfall.floorOffsetDb = WATERFALL_FLOOR_OFFSET_DB_DEFAULT
        // Settings → controller setters (the only mirror left: settings need to
        // be pushed INTO controllers so their loops see fresh values).
        viewModelScope.launch {
            // Tracks the last PTT preference actually pushed into the rig, so the
            // mirror below only reacts to real changes (not every slice emission).
            var lastAppliedPttPreference: PttPreference? = null
            settingsBridge.slice.collect { s ->
                lastDialFreqHz = s.lastDialFreqHz
                decodeController.setInputGain(s.inputGain)
                decodeController.setStationContext(s.myCall, s.myGrid)
                decodeController.setEarlyDecodeEnabled(s.earlyDecodeEnabled)
                qsoSession.updateStationProfile(s.myCall, s.myGrid, s.potaModeEnabled, s.potaParkRef)
                qsoSession.setTxEnabled(s.txEnabledInSettings)
                qsoSession.setAutoSeqEnabled(s.autoSeqEnabled)
                qsoSession.setAnswerWhenCalledEnabled(s.answerWhenCalledEnabled)
                qsoSession.setAutoAnswerCqEnabled(s.autoAnswerCqEnabled)
                qsoSession.setAnswerPolicy(s.answerPolicy)
                qsoSession.setMaxUnansweredTxCycles(s.maxUnansweredTxCycles)
                qsoSession.setSendRr73(s.sendRr73)
                qsoSession.setAutoCqResumeEnabled(s.autoCqResumeEnabled)
                qsoSession.setDefaultTxSlotParity(s.txSlotParity)
                // CAT baud mirror + live apply (spec 2026-07-04-radio-settings).
                // Handles both a user change and the startup race where the Digirig
                // bound at the default before DataStore emitted a persisted value.
                if (rig.catBaud != s.catBaud) {
                    rig.catBaud = s.catBaud
                    if (rig.isDigirigReady && !state.value.isTransmitting) {
                        viewModelScope.launch(rigSession.catDispatcher) {
                            rig.rebind()
                            withContext(Dispatchers.Main) { prepareRig() }
                        }
                    }
                }
                // Radio-model mirror: resolve the selected id to a descriptor and
                // apply it to the controller, rebinding like the CAT-baud mirror.
                // The whole body runs on the CAT dispatcher: setDescriptor closes
                // a live backend (blocking USB I/O) and contends the controller
                // monitor with rebind — neither may pin Main (field ANR class,
                // 2026-07-03). setDescriptor/rebind are idempotent, so a stale
                // duplicate launch from a rapid settings burst is harmless.
                val wantModel = s.radioModelId?.let { RigRegistry.byId(it) }
                if (rig.descriptor?.id != wantModel?.id || rig.catPortOverride != s.catPortOverride) {
                    viewModelScope.launch(rigSession.catDispatcher) {
                        rig.setDescriptor(wantModel)
                        rig.catPortOverride = s.catPortOverride
                        if (!state.value.isTransmitting) {
                            rig.rebind()
                            withContext(Dispatchers.Main) { prepareRig() }
                        }
                    }
                }
                // PTT preference mirror (phase 2: a model's default PTT method —
                // and the operator's explicit choice — must actually take effect).
                // Forced modes are a volatile write; AUTO re-probes CAT off Main.
                // The picker is TX-guarded, so a mid-TX method flip cannot happen
                // from the UI (a CAT→RTS flip mid-key would strand TX1 unkeyed).
                if (lastAppliedPttPreference != s.pttPreference) {
                    lastAppliedPttPreference = s.pttPreference
                    when (s.pttPreference) {
                        PttPreference.CAT -> rig.useCatPtt = true
                        PttPreference.RTS -> rig.useCatPtt = false
                        PttPreference.AUTO -> if (rig.isDigirigReady) {
                            viewModelScope.launch(rigSession.catDispatcher) {
                                rig.configurePttFromCatProbe()
                            }
                        }
                    }
                }
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
        // Capture heartbeat watchdog: a stalled/dead capture thread stops delivering
        // frames entirely, so the zero-sample slice watchdog (which needs frames
        // flowing) can't see it. Poll on a timer instead.
        viewModelScope.launch {
            while (isActive) {
                delay(CAPTURE_WATCHDOG_TICK_MS)
                val ui = state.value
                if (!ui.isCapturing) continue
                val devicePresent = AudioInputs.list(getApplication()).any { it.isUsb }
                when (captureWatchdog.poll(ui.isCapturing, ui.isTransmitting, devicePresent)) {
                    CaptureWatchdog.Decision.Recover -> {
                        notify("Audio stalled — restarting capture", SnackbarEvent.Tag.ERROR)
                        restartCapture()
                    }
                    CaptureWatchdog.Decision.GiveUp -> {
                        _viewState.update { it.copy(captureFailed = true) }
                        stopCapture()
                        notify("Audio capture failed — tap to retry", SnackbarEvent.Tag.ERROR)
                    }
                    CaptureWatchdog.Decision.Idle -> {}
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
                val timeUtc = formatRowTimeUtc(ev.utcMillis)
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


    /**
     * USB_DEVICE_ATTACHED (MainActivity.onNewIntent). The attach re-enumerated
     * the bus, so any held Digirig backend points at dead descriptors — rebind
     * before re-probing, then let [prepareRig] restore PTT/CAT/TX state.
     */
    fun onUsbAttached() {
        // Serial close/open on the CAT thread (serializes with in-flight CAT
        // ops and keeps blocking USB I/O off main), then re-probe on main.
        viewModelScope.launch(rigSession.catDispatcher) {
            rig.rebind()
            withContext(Dispatchers.Main) {
                rigSession.refreshDigirigPresence()
                refreshDevices()
            }
        }
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

    /** Select an input device manually, or pass null to return to automatic routing. */
    fun selectDevice(id: Int?) {
        val wasActive = state.value.isCapturing
        if (wasActive) stopCapture()
        _viewState.update { it.copy(selectedDeviceId = id) }
        viewModelScope.launch { settingsRepo.setSelectedAudioDeviceId(id) }
        if (id == null) refreshDevices()
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

    fun setEarlyDecodeEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setEarlyDecodeEnabled(enabled) }
    }

    fun setSendRr73(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setSendRr73(enabled) }
    }

    fun setAutoCqResumeEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoCqResumeEnabled(enabled) }
    }

    fun clearAbandonedPartners() = qsoSession.clearAbandonedPartners()

    fun setCq73OnlyFilter(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setCq73OnlyFilter(enabled) }
    }

    fun setDecodeViewMode(mode: DecodeViewMode) {
        viewModelScope.launch { settingsRepo.setDecodeViewMode(mode) }
    }

    fun setDecodeColor(category: DecodeCategory, argb: Int) {
        viewModelScope.launch { settingsRepo.setDecodeColor(category, argb) }
    }

    fun resetDecodeColors() {
        viewModelScope.launch { settingsRepo.resetDecodeColors() }
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

    fun setCatBaud(baud: Int) {
        viewModelScope.launch { settingsRepo.setCatBaud(baud) }
    }

    /** Persist a new/edited rig profile. [onRejected] fires when the cap or name rule blocks it. */
    fun saveRigProfile(profile: RigProfile, onRejected: () -> Unit = {}) {
        viewModelScope.launch {
            if (!settingsRepo.saveRigProfile(profile)) onRejected()
        }
    }

    fun deleteRigProfile(id: String) {
        viewModelScope.launch { settingsRepo.deleteRigProfile(id) }
    }

    fun selectRigProfile(id: String?) {
        viewModelScope.launch { settingsRepo.selectRigProfile(id) }
    }

    fun setCatPortOverride(index: Int?) {
        viewModelScope.launch { settingsRepo.setCatPortOverride(index) }
    }

    /** Operator-facing names of the attached rig's serial ports (empty when none). */
    fun serialPortDisplayNames(): List<String> = rig.catPortDisplayNames()

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
        val hz = freqHz.coerceIn(300, 4000)
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
        // If capture is already running (e.g. restarted via the retry chip),
        // adopt it — don't restart the audio chain or wipe the
        // waterfall/decodes the operator was just looking at.
        val adoptMonitorCapture = state.value.isCapturing
        if (!adoptMonitorCapture) {
            waterfall.clear()
            decodeController.reset()
        }
        prepareRig()
        restoreLastBandIfNeeded()
        if (!adoptMonitorCapture) beginCapture()
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
        // Async: a USB serial call wedged in blocking I/O must not pin the main
        // thread (field ANR, 2026-07-03). The TX path's four-layer PTT defense
        // still releases independently; onCleared keeps the blocking release.
        rigSession.releasePttAsync()
        qsoSession.stopQso()
        if (wasTransmitting) {
            _viewState.update { it.copy(txStatus = "TX halted") }
        }
        if (announce) notify("Transmit halted")
    }

    /**
     * Apply the operator's PTT preference at rig-ready time: AUTO probes CAT
     * (blocking ~1 s on timeout — call on the CAT dispatcher), CAT/RTS force
     * the method without probing. Returns the method label for status display.
     */
    private fun applyPttMethod(): String =
        when (settingsBridge.slice.value.pttPreference) {
            PttPreference.AUTO -> rig.configurePttFromCatProbe()
            PttPreference.CAT -> {
                rig.useCatPtt = true
                "CAT"
            }
            PttPreference.RTS -> {
                rig.useCatPtt = false
                "RTS"
            }
        }

    private fun prepareRig() {
        when (rig.state()) {
            RigController.State.NoModel -> {
                _viewState.update {
                    it.copy(
                        pttReady = false,
                        catReady = false,
                        txStatus = "Select your radio model in Settings",
                    )
                }
            }
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
                onRigReattached()
                viewModelScope.launch(rigSession.catDispatcher) {
                    val method = applyPttMethod()
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
                    onRigReattached()
                    viewModelScope.launch(rigSession.catDispatcher) {
                        val method = applyPttMethod()
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

    /**
     * Rig probe found the Digirig usable (fresh start or USB reattach). Clears
     * the SAFETY-02 RX_ONLY latch when the license acknowledgment is already
     * on record — 2026-07-03 field report: with the acknowledgment persisted
     * true, the license dialog (the only other notifyUsbReady caller) can
     * never re-show, so a mid-session replug left TX dead-ended in RX_ONLY.
     */
    private fun onRigReattached() {
        txOrchestrator.notifyRigReady(settingsBridge.slice.value.licenseAcknowledged)
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

    /** No-CAT rigs: the operator picked a band — persist it as the manual dial. */
    fun setManualDialFrequency(hz: Long) {
        viewModelScope.launch { settingsRepo.setLastDialFreqHz(hz) }
    }

    fun setRigDataUsb() {
        if (!rig.isCatReady) return
        viewModelScope.launch { rigSession.setDataMode() }
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
        rigSession.releasePttAsync()
        qsoSession.stopQso()
    }

    fun blockStation(call: String) {
        qsoSession.blockStation(call)
    }

    fun unblockStation(call: String) = qsoSession.unblockStation(call)

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

    /** First-TX late entry for the QSO loop (Answer/Resume/auto-answer). */
    private suspend fun transmitIntoCurrentSlotForQsoLoop(message: String): Boolean {
        return txOrchestrator.transmitIntoCurrentSlot(message, state.value.txFreqHz)
    }

    private suspend fun onQsoComplete(snapshot: QsoSnapshot) {
        val freq = state.value.effectiveDialFreqHz
        val band = bandLabelForLogging(freq)
        val parks = ActivationProfile.parkRefsForLogging(
            state.value.potaModeEnabled,
            state.value.potaParkRef,
        )
        val contact = QsoContact.fromSnapshot(snapshot, freq, band, parks)
        withContext(Dispatchers.IO) { logbook.log(contact, qrzPending = qrzController.isEnabled) }
        qrzController.onQsoLogged()
        workedBeforeCache.invalidate(contact.dxCall)
        decodeController.reclassifyWorkedBefore(contact.dxCall)
        // Phase 7 (HYG-04): atomic ADIF auto-export on ApplicationScope so the
        // backup outlives this ViewModel if the user pauses the app mid-write.
        AdifAutoBackup.scheduleBackupAfterQso(getApplication(), logbook, settingsRepo)
    }

    /** Flush the QRZ queue when the default network comes back (quiet self-heal). */
    private fun registerQrzConnectivityListener(onAvailable: () -> Unit) {
        val manager = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = onAvailable()
                },
            )
        } catch (_: RuntimeException) {
            // Too many callbacks or missing service — QRZ retries still fire
            // on app start and each logged QSO; connectivity is best-effort.
        }
    }

    fun setQrzUploadEnabled(enabled: Boolean) = qrzController.setEnabled(enabled)
    fun setQrzApiKey(key: String) = qrzController.setApiKey(key)
    fun testQrzConnection() = qrzController.testConnection()

    /** Phase 7 (UX-06): user-triggered backup from the Log tab's logbook tools menu. */
    fun backupAdifNow() {
        viewModelScope.launch {
            val result = AdifAutoBackup.backupNow(getApplication(), logbook, settingsRepo)
            if (result != null) {
                notify(AdifAutoBackup.backupSnackbarText(result.mirrored), SnackbarEvent.Tag.TRANSIENT)
            } else {
                notify("ADIF backup failed", SnackbarEvent.Tag.ERROR)
            }
        }
    }

    /** Import an ADIF file picked on the Log tab; merge with duplicate-skip. */
    fun importAdif(uri: Uri) {
        viewModelScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    val text = getApplication<Application>().contentResolver
                        .openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw AdifImportException("Could not read the selected file")
                    val profile = settingsRepo.settings.first()
                    val read = AdifReader.read(
                        text,
                        fallbackMyCall = profile.myCall,
                        fallbackMyGrid = profile.myGrid,
                    )
                    val merged = logbook.importContacts(read.contacts)
                    read.contacts.map { it.dxCall }.toSet()
                        .forEach { workedBeforeCache.invalidate(it) }
                    Triple(merged.imported, merged.duplicates, read.skipped)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                notify(t.message ?: "ADIF import failed", SnackbarEvent.Tag.ERROR)
                return@launch
            }
            val (imported, duplicates, skipped) = outcome
            val unreadable = if (skipped > 0) ", $skipped unreadable" else ""
            notify("Imported $imported QSOs ($duplicates duplicates skipped$unreadable)")
            if (imported > 0) {
                // Refresh backup + Documents mirror to reflect the merged log.
                AdifAutoBackup.scheduleBackupAfterQso(getApplication(), logbook, settingsRepo)
            }
        }
    }

    private fun resumeCaptureIfNeededForQso() {
        if (state.value.isOperating && !state.value.isCapturing && !state.value.isTransmitting) {
            beginCapture()
        }
    }

    // Capture start/stop run AudioRecord framework calls that can block for
    // seconds on a wedged USB device (field ANR, 2026-07-03), so both are queued
    // onto the CaptureLifecycle serial thread. isCapturing is updated
    // synchronously (optimistically for start) so callers that read it right
    // after — selectDevice, pauseForTx/resumeAfterTx, the RELY-02/03 restart
    // paths — see the same values as with the old blocking calls; a failed
    // start corrects the flags when its callback lands.

    private fun beginCapture() {
        _viewState.update { it.copy(isCapturing = true) }
        captureWatchdog.onCaptureStarted()
        captureLifecycle.start(
            state.value.selectedDeviceId,
            { frames ->
                captureWatchdog.onFrame()
                decodeController.onFrames(frames)
            },
        ) { t ->
            _viewState.update { it.copy(isCapturing = false, isOperating = false) }
            notify(t.message ?: "Capture failed", SnackbarEvent.Tag.ERROR)
        }
    }

    private fun resumeCapture() {
        if (state.value.isCapturing || state.value.isTransmitting) return
        beginCapture()
    }

    private fun stopCapture(onStopped: () -> Unit = {}) {
        _viewState.update { it.copy(isCapturing = false) }
        captureWatchdog.reset()
        captureLifecycle.stop {
            // Runs after the engine actually stopped, so the reset can't race
            // frames still draining from the capture thread.
            val unclean = capture.consumeStopCleanFailureCount()
            if (unclean > 0) {
                notify("Audio thread didn't stop cleanly — recovering", SnackbarEvent.Tag.ERROR)
            }
            decodeController.reset()
            onStopped()
        }
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

    /** Operator taps the "Audio capture failed — tap to retry" chip. */
    fun retryCapture() {
        _viewState.update { it.copy(captureFailed = false) }
        captureWatchdog.reset()
        restartCapture()
    }

    /**
     * Apply the currently measured DT residual to the shared slot-timing clock,
     * shifting RX slot collection, early-decode timing, TX keying, and slot
     * parity together. No-op when no residual estimate is available yet.
     */
    fun alignClock() {
        val residual = decodeController.slice.value.clockOffsetSeconds ?: return
        clockCorrection.applyResidualSeconds(residual)
        decodeController.realignClockEstimate()
        _viewState.update { it.copy(appliedClockOffsetMs = clockCorrection.appliedOffsetMs) }
    }

    /** Clear any applied clock correction (Settings "Reset"). */
    fun resetClockAlignment() {
        clockCorrection.reset()
        decodeController.realignClockEstimate()
        _viewState.update { it.copy(appliedClockOffsetMs = 0L) }
    }

    fun clearDecodes() {
        decodeController.clearDecodes()
    }

    /** Test CAT from the profile editor. TX-guarded; runs on the CAT dispatcher. */
    fun testCatProfile(draft: RigProfile, onResult: (String) -> Unit) {
        if (state.value.isTransmitting) {
            onResult("Can't test while transmitting")
            return
        }
        viewModelScope.launch(rigSession.catDispatcher) {
            val descriptor = RigProfiles.resolve(draft)
            val result = if (descriptor == null) {
                ProbeResult.NoCat
            } else {
                rig.probe(descriptor, draft.baud ?: descriptor.defaultBaud)
            }
            withContext(Dispatchers.Main) { onResult(probeResultText(result)) }
        }
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
        captureLifecycle.close()
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
        /** Poll interval for the capture-stall watchdog monitor coroutine. */
        const val CAPTURE_WATCHDOG_TICK_MS = 1_000L
    }
}

/** Plain-language copy for Test CAT outcomes (spec: Diagnostics section). */
fun probeResultText(result: ProbeResult): String = when (result) {
    is ProbeResult.Sync -> "Sync OK — rig reports %.3f MHz".format(Locale.ROOT, result.freqHz / 1_000_000.0)
    ProbeResult.Garbage -> "Received data but couldn't understand it — likely a wrong baud rate"
    // Baud leads: on Yaesu rigs a baud mismatch reads as silence, not garbage —
    // the rig never parses the corrupted query, so it never replies (field-
    // verified on the FT-891, 2026-07-11).
    ProbeResult.Silence -> "No response — check the baud rate, CAT port, cable, and the rig's CAT menu"
    ProbeResult.NoDevice -> "No USB serial device attached"
    ProbeResult.NoPermission -> "USB permission not granted — connect the rig and allow access"
    ProbeResult.NoCat -> "This rig setup has no CAT to test"
}
