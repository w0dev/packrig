package net.ft8vc.app

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import net.ft8vc.app.controllers.AppRfState
import net.ft8vc.app.settings.DecodeColorScheme
import net.ft8vc.app.settings.PttPreference
import net.ft8vc.audio.AudioInputDevice
import net.ft8vc.core.AnswerPolicy
import net.ft8vc.core.AppInfo
import net.ft8vc.core.DecodeViewMode
import net.ft8vc.core.QsoForm
import net.ft8vc.core.QsoTxStep
import net.ft8vc.core.TxSlotParity
import net.ft8vc.rig.RigController

@Immutable
data class DecodeRow(
    /** Stable cross-pass key for Compose LazyColumn. See [DecodeRowKey.stableId]. */
    val id: Long,
    val timeUtc: String,
    val snr: Int,
    val dtSeconds: Float,
    val freqHz: Int,
    val message: String,
    val isCq: Boolean,
    val isToMe: Boolean = false,
    /** Great-circle km when message carries a 4-char grid; null otherwise. */
    val distanceKm: Int? = null,
    /** ISO 3166 alpha-2 code for the sender callsign; null when unresolvable. */
    val countryCode: String? = null,
    /** UTC slot parity (Even/Odd) when this decode was received. */
    val slotParity: TxSlotParity = TxSlotParity.EVEN,
    /** Whether this row was received (RX) or synthesized from our own TX. */
    val source: net.ft8vc.core.DecodeRowSource = net.ft8vc.core.DecodeRowSource.Rx,
    /** Whether the sender's callsign has been worked before. */
    val workedBefore: net.ft8vc.core.WorkedBefore = net.ft8vc.core.WorkedBefore.Never,
    /**
     * Which decode pass produced this row. Used only for telemetry + dedup
     * bookkeeping. UI must NOT branch on this — early and full rows render
     * pixel-identically.
     */
    val passSource: net.ft8vc.core.DecodePassSource = net.ft8vc.core.DecodePassSource.Full,
)

/**
 * Operate-screen state.
 *
 * Fields are grouped into logical "substate" sections below. A future refactor
 * will lift each group into its own data class (e.g. [RxState], [TxState],
 * [StationState]), pass smaller substates to screens, and let Compose skip
 * recomposition when an unrelated group is the only thing that changed.
 *
 * Until that happens, keep edits inside the right section so the boundaries
 * stay easy to extract.
 */
data class OperateUiState(
    // ── Station (operator profile) ────────────────────────────────────────
    val myCall: String = DEFAULT_MY_CALL,
    val myGrid: String = DEFAULT_MY_GRID,
    val licenseAcknowledged: Boolean = false,
    val potaModeEnabled: Boolean = false,
    val potaParkRef: String = "",

    // ── Display (theme + decode filters) ──────────────────────────────────
    val useDarkTheme: Boolean = true,
    val decodeViewMode: DecodeViewMode = DecodeViewMode.OPERATE,
    /** Operate-screen only: decode list shows CQ / 73 / RR73 (plus QSO partner when active). */
    val cq73OnlyFilter: Boolean = false,
    /** User-configurable decode row colors. */
    val decodeColors: DecodeColorScheme = DecodeColorScheme.DEFAULT,
    /** Show CQ decode labels on the Spectrum waterfall. */
    val spectrumMarkersEnabled: Boolean = true,

    // ── Rx (capture, decodes, level metering) ─────────────────────────────
    val devices: List<AudioInputDevice> = emptyList(),
    val selectedDeviceId: Int? = null,
    val isOperating: Boolean = false,
    val isCapturing: Boolean = false,
    val levelDbfs: Float = SILENCE_DBFS,
    val clip: Boolean = false,
    val sampleRateHz: Int = AppInfo.SAMPLE_RATE_HZ,
    val waterfallVersion: Long = 0L,
    val decodes: ImmutableList<DecodeRow> = persistentListOf(),
    val lastSlotDecodeCount: Int = -1,
    val decodeFailureCount: Long = 0L,
    val inputGain: Float = 1f,

    // ── Tx (transmit settings + composed message) ─────────────────────────
    val txEnabled: Boolean = false,
    val txMessage: String = DEFAULT_TX_MESSAGE,
    val txFreqHz: Int = DEFAULT_TX_FREQ_HZ,
    /** Next message [QsoMachine] will send on a TX slot, when a QSO is active. */
    val nextTxMessage: String? = null,
    val txStatus: String? = null,
    val isTransmitting: Boolean = false,
    val pttReady: Boolean = false,
    /** Next TX line on Operate (auto-filled from QSO machine; user may override). */
    val operateTxText: String = "",
    val operateTxStep: QsoTxStep = QsoTxStep.Cq,
    val operateTxEdited: Boolean = false,
    /** DX/reports synced from [QsoMachine] for TX dropdown previews. */
    val operateTxForm: QsoForm = QsoForm(),

    // ── AutoTx (operator preferences for auto QSO) ────────────────────────
    val autoSeqEnabled: Boolean = true,
    val answerWhenCalledEnabled: Boolean = true,
    val autoAnswerCqEnabled: Boolean = false,
    val answerPolicy: AnswerPolicy = AnswerPolicy.FIRST,
    val maxUnansweredTxCycles: Int = 5,
    val lateStartTxEnabled: Boolean = true,
    val earlyDecodeEnabled: Boolean = true,
    val sendRr73: Boolean = true,
    val autoCqResumeEnabled: Boolean = false,

    // ── Qso (active contact) ──────────────────────────────────────────────
    val qsoActive: Boolean = false,
    val qsoState: String? = null,
    val qsoDx: String? = null,

    // ── Rig (CAT + dial state) ────────────────────────────────────────────
    val catReady: Boolean = false,
    val rigFreqHz: Long? = null,
    val rigMode: String? = null,
    val catStatus: String? = null,
    val catBusy: Boolean = false,
    val lastDialFreqHz: Long? = null,
    val pttPreference: PttPreference = PttPreference.AUTO,
    /** CAT serial baud from settings — must match FT-891 menu 05-06 (CAT RATE). */
    val catBaud: Int = RigController.DEFAULT_CAT_BAUD,
    /** Selected radio model id, or null if none chosen yet. */
    val radioModelId: String? = null,
    /** Manual CAT-port override index within the model's serial ports, or null for auto. */
    val catPortOverride: Int? = null,

    // ── Slot (UTC slot timing + parity) ───────────────────────────────────
    val slotIndex: Int = 0,
    val secondsToNextSlot: Int = 15,
    val isTxSlot: Boolean = false,
    /** Seconds until the next slot matching [txSlotParity] (or active QSO parity). */
    val secondsUntilOurTxSlot: Int = 15,
    val txSlotParity: TxSlotParity = TxSlotParity.EVEN,
    val activeTxSlotParity: TxSlotParity? = null,
    val utcClock: String = "00:00:00",

    // ── RF safety + native-lib handshake (Phase 5) ────────────────────────
    val appRfState: AppRfState = AppRfState.READY,
    val nativeVersion: String = "not loaded",
    val nativeLoaded: Boolean = false,
    /** Latches true after the watchdog or outer timeout forced PTT release; cleared via Settings ack. */
    val txSafetyHaltActive: Boolean = false,
    /** True between USB detach and reconnect — persistent chip in Operate header. */
    val digirigDisconnected: Boolean = false,

    // ── Reliability hardening (Phase 6) ──────────────────────────────────
    /** Latched after 3 consecutive CAT timeouts; cleared by `retryCat`. */
    val catUnreachable: Boolean = false,
    /** Latched when the capture watchdog exhausts its restart budget; cleared by `retryCapture`. */
    val captureFailed: Boolean = false,
    /** True for at least one decode failure in the last 5 slots (auto-clears). */
    val decodeFailureRecent: Boolean = false,
    /** Consecutive slots with all-zero PCM. >2 cross-checked with AudioManager triggers capture recreate. */
    val zeroSampleSlots: Int = 0,

    // ── Clock health ──────────────────────────────────────────────────────
    /** Estimated phone-clock offset vs FT8 band time (median DT), null when unknown. */
    val clockOffsetSeconds: Float? = null,
    /** Operator-applied clock correction (ms), 0 when unaligned. In-memory only. */
    val appliedClockOffsetMs: Long = 0L,

    // ── Misc ──────────────────────────────────────────────────────────────
    val operateStatus: String? = null,
    val contactCount: Int = 0,
    /** Phase 7 (UX-06): epoch ms of the most recent successful ADIF auto-backup, null if never. */
    val lastAdifBackupAtMs: Long? = null,
    val userBlockedCalls: List<String> = emptyList(),
) {
    companion object {
        const val INPUT_GAIN_MIN = 0.1f
        const val SILENCE_DBFS = -100f
        const val MAX_DECODE_ROWS = 500
        const val DEFAULT_TX_MESSAGE = ""
        const val DEFAULT_TX_FREQ_HZ = 1000
        const val DEFAULT_MY_CALL = ""
        const val DEFAULT_MY_GRID = ""
        const val QSO_TX_GRACE_MS = 300L
    }
}

/**
 * Merge rule for [OperateUiState.txStatus]: the TxOrchestrator slice status
 * and the VM-residual status (USB-probe strings, halt notice) both feed the
 * one displayed line. The status bar renders it only while transmitting, and
 * the slice always carries the live message ("TX: …", "Sent: …") during a
 * transmit — the VM residual is a fallback, never an override, or a stale
 * "TX halted" would mask every later TX (v1.0 parity: slice wins).
 */
fun mergedTxStatus(sliceTxStatus: String?, viewTxStatus: String?): String? =
    sliceTxStatus ?: viewTxStatus
