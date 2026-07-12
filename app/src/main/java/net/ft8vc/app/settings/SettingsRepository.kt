package net.ft8vc.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.ft8vc.core.AnswerPolicy
import net.ft8vc.core.DecodeCategory
import net.ft8vc.core.DecodeViewMode
import net.ft8vc.core.TxSlotParity
import net.ft8vc.rig.RigController
import net.ft8vc.rig.RigProfile
import net.ft8vc.rig.RigRegistry

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ft8vc_settings",
)

class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext

    val settings: Flow<StationSettings> = appContext.settingsDataStore.data.map { prefs ->
        StationSettings(
            myCall = prefs[Keys.MY_CALL] ?: "",
            myGrid = prefs[Keys.MY_GRID] ?: "",
            txToneHz = prefs[Keys.TX_TONE_HZ] ?: 1000,
            selectedAudioDeviceId = prefs[Keys.AUDIO_DEVICE_ID],
            pttPreference = prefs[Keys.PTT_PREFERENCE]?.let { PttPreference.valueOf(it) }
                ?: PttPreference.AUTO,
            catBaud = coerceCatBaud(prefs[Keys.CAT_BAUD] ?: RigController.DEFAULT_CAT_BAUD),
            radioModelId = prefs[Keys.RADIO_MODEL],
            catPortOverride = prefs[Keys.CAT_PORT_OVERRIDE],
            licenseAcknowledged = prefs[Keys.LICENSE_ACK] ?: false,
            txEnabledInSettings = prefs[Keys.TX_ENABLED] ?: false,
            autoSeqEnabled = prefs[Keys.AUTO_SEQ] ?: true,
            answerWhenCalledEnabled = prefs[Keys.ANSWER_WHEN_CALLED] ?: true,
            autoAnswerCqEnabled = prefs[Keys.AUTO_ANSWER_CQ] ?: false,
            lateStartTxEnabled = prefs[Keys.LATE_START_TX_ENABLED] ?: true,
            earlyDecodeEnabled = prefs[Keys.EARLY_DECODE_ENABLED] ?: true,
            sendRr73 = prefs[Keys.SEND_RR73] ?: true,
            autoCqResumeEnabled = prefs[Keys.AUTO_CQ_RESUME] ?: false,
            answerPolicy = prefs[Keys.ANSWER_POLICY]?.let { AnswerPolicy.valueOf(it) }
                ?: AnswerPolicy.FIRST,
            maxUnansweredTxCycles = prefs[Keys.MAX_UNANSWERED_TX] ?: 5,
            inputGain = prefs[Keys.INPUT_GAIN] ?: 1f,
            lastDialFreqHz = prefs[Keys.LAST_DIAL_FREQ_HZ],
            potaModeEnabled = prefs[Keys.POTA_MODE] ?: false,
            potaParkRef = prefs[Keys.POTA_PARK_REF] ?: "",
            cq73OnlyFilter = prefs[Keys.CQ73_FILTER] ?: false,
            decodeViewMode = prefs[Keys.DECODE_VIEW_MODE]?.let { DecodeViewMode.valueOf(it) }
                ?: DecodeViewMode.OPERATE,
            txSlotParity = prefs[Keys.TX_SLOT_PARITY]?.let { TxSlotParity.valueOf(it) }
                ?: TxSlotParity.EVEN,
            useDarkTheme = prefs[Keys.USE_DARK_THEME] ?: true,
            decodeColors = DecodeColorScheme(
                ownTx = prefs[Keys.DECODE_COLOR_OWN_TX] ?: DecodeColorScheme.DEFAULT_OWN_TX,
                partner = prefs[Keys.DECODE_COLOR_PARTNER] ?: DecodeColorScheme.DEFAULT_PARTNER,
                myCall = prefs[Keys.DECODE_COLOR_MY_CALL] ?: DecodeColorScheme.DEFAULT_MY_CALL,
                cqNew = prefs[Keys.DECODE_COLOR_CQ_NEW] ?: DecodeColorScheme.DEFAULT_CQ_NEW,
                cqWorkedOtherBand = prefs[Keys.DECODE_COLOR_CQ_WORKED_OTHER]
                    ?: DecodeColorScheme.DEFAULT_CQ_WORKED_OTHER_BAND,
                cqWorkedThisBand = prefs[Keys.DECODE_COLOR_CQ_WORKED_THIS]
                    ?: DecodeColorScheme.DEFAULT_CQ_WORKED_THIS_BAND,
            ),
            lastAdifBackupAtMs = prefs[Keys.LAST_ADIF_BACKUP_AT_MS],
            rigProfiles = RigProfileJson.decode(prefs[Keys.RIG_PROFILES]),
            selectedRigProfileId = prefs[Keys.SELECTED_RIG_PROFILE],
            qrzUploadEnabled = prefs[Keys.QRZ_UPLOAD_ENABLED] ?: false,
            qrzApiKeyEncrypted = prefs[Keys.QRZ_API_KEY_ENC],
            qrzLastError = prefs[Keys.QRZ_LAST_ERROR],
        ).withRigProfileApplied()
    }

    suspend fun setMyCall(call: String) {
        appContext.settingsDataStore.edit { it[Keys.MY_CALL] = call.trim().uppercase() }
    }

    suspend fun setMyGrid(grid: String) {
        appContext.settingsDataStore.edit { it[Keys.MY_GRID] = grid.trim().uppercase() }
    }

    suspend fun setTxToneHz(hz: Int) {
        appContext.settingsDataStore.edit { it[Keys.TX_TONE_HZ] = hz.coerceIn(300, 4000) }
    }

    suspend fun setSelectedAudioDeviceId(id: Int?) {
        appContext.settingsDataStore.edit {
            if (id == null) it.remove(Keys.AUDIO_DEVICE_ID) else it[Keys.AUDIO_DEVICE_ID] = id
        }
    }

    suspend fun setPttPreference(pref: PttPreference) {
        appContext.settingsDataStore.edit { it[Keys.PTT_PREFERENCE] = pref.name }
    }

    suspend fun setCatBaud(baud: Int) {
        appContext.settingsDataStore.edit { it[Keys.CAT_BAUD] = coerceCatBaud(baud) }
    }

    suspend fun setRadioModel(id: String) {
        appContext.settingsDataStore.edit { it[Keys.RADIO_MODEL] = id }
    }

    suspend fun setCatPortOverride(index: Int?) {
        appContext.settingsDataStore.edit {
            if (index == null) it.remove(Keys.CAT_PORT_OVERRIDE) else it[Keys.CAT_PORT_OVERRIDE] = index
        }
    }

    /** Add or update a profile. First saved profile auto-selects. False = rejected (cap/name). */
    suspend fun saveRigProfile(profile: RigProfile): Boolean {
        var saved = false
        appContext.settingsDataStore.edit { prefs ->
            val current = RigProfileJson.decode(prefs[Keys.RIG_PROFILES])
            val updated = RigProfileList.upsert(current, profile) ?: return@edit
            prefs[Keys.RIG_PROFILES] = RigProfileJson.encode(updated)
            if (prefs[Keys.SELECTED_RIG_PROFILE] == null) {
                prefs[Keys.SELECTED_RIG_PROFILE] = profile.id
            }
            saved = true
        }
        return saved
    }

    /** Delete a profile; selection falls back to the first remaining profile. */
    suspend fun deleteRigProfile(id: String) {
        appContext.settingsDataStore.edit { prefs ->
            val remaining = RigProfileList.delete(RigProfileJson.decode(prefs[Keys.RIG_PROFILES]), id)
            prefs[Keys.RIG_PROFILES] = RigProfileJson.encode(remaining)
            val selection = RigProfileList.selectionAfterDelete(
                remaining, deletedId = id, currentSelection = prefs[Keys.SELECTED_RIG_PROFILE],
            )
            if (selection == null) {
                prefs.remove(Keys.SELECTED_RIG_PROFILE)
            } else {
                prefs[Keys.SELECTED_RIG_PROFILE] = selection
            }
        }
    }

    /** Select a saved rig by id, or clear the selection (None) with null. */
    suspend fun selectRigProfile(id: String?) {
        appContext.settingsDataStore.edit { prefs ->
            if (id == null) {
                prefs.remove(Keys.SELECTED_RIG_PROFILE)
            } else if (RigProfileJson.decode(prefs[Keys.RIG_PROFILES]).any { it.id == id }) {
                prefs[Keys.SELECTED_RIG_PROFILE] = id
            }
        }
    }

    /**
     * One-time upgrade: turn the legacy RADIO_MODEL selection into the first
     * saved profile, carrying the legacy baud/port/PTT knobs so behavior is
     * byte-identical (spec: parity regression + field gate 1). Legacy knob
     * keys stay as pre-migration fallback values; only RADIO_MODEL is cleared.
     */
    suspend fun migrateLegacyRadioModel() {
        appContext.settingsDataStore.edit { prefs ->
            val legacy = prefs[Keys.RADIO_MODEL] ?: return@edit
            if (prefs[Keys.RIG_PROFILES] == null) {
                val profile = buildMigratedProfile(
                    modelId = legacy,
                    baud = prefs[Keys.CAT_BAUD],
                    catPortOverride = prefs[Keys.CAT_PORT_OVERRIDE],
                    pttPreference = prefs[Keys.PTT_PREFERENCE]?.let { name ->
                        PttPreference.entries.firstOrNull { it.name == name }
                    },
                )
                prefs[Keys.RIG_PROFILES] = RigProfileJson.encode(listOf(profile))
                prefs[Keys.SELECTED_RIG_PROFILE] = profile.id
            }
            prefs.remove(Keys.RADIO_MODEL)
        }
    }

    suspend fun setLicenseAcknowledged(ack: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.LICENSE_ACK] = ack }
    }

    suspend fun setTxEnabledInSettings(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.TX_ENABLED] = enabled }
    }

    suspend fun setAutoSeqEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.AUTO_SEQ] = enabled }
    }

    suspend fun setAnswerWhenCalledEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.ANSWER_WHEN_CALLED] = enabled }
    }

    suspend fun setAutoAnswerCqEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.AUTO_ANSWER_CQ] = enabled }
    }

    suspend fun setLateStartTxEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.LATE_START_TX_ENABLED] = enabled }
    }

    suspend fun setEarlyDecodeEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.EARLY_DECODE_ENABLED] = enabled }
    }

    suspend fun setSendRr73(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.SEND_RR73] = enabled }
    }

    suspend fun setAutoCqResumeEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.AUTO_CQ_RESUME] = enabled }
    }

    suspend fun setAnswerPolicy(policy: AnswerPolicy) {
        appContext.settingsDataStore.edit { it[Keys.ANSWER_POLICY] = policy.name }
    }

    suspend fun setMaxUnansweredTxCycles(cycles: Int) {
        appContext.settingsDataStore.edit {
            it[Keys.MAX_UNANSWERED_TX] = cycles.coerceIn(0, 20)
        }
    }

    suspend fun setInputGain(value: Float) {
        appContext.settingsDataStore.edit {
            it[Keys.INPUT_GAIN] = value.coerceIn(INPUT_GAIN_MIN, 1f)
        }
    }

    suspend fun setLastDialFreqHz(hz: Long?) {
        appContext.settingsDataStore.edit {
            if (hz == null) it.remove(Keys.LAST_DIAL_FREQ_HZ) else it[Keys.LAST_DIAL_FREQ_HZ] = hz
        }
    }

    suspend fun setPotaModeEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.POTA_MODE] = enabled }
    }

    suspend fun setPotaParkRef(ref: String) {
        appContext.settingsDataStore.edit { it[Keys.POTA_PARK_REF] = ref.trim().uppercase() }
    }

    suspend fun setCq73OnlyFilter(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.CQ73_FILTER] = enabled }
    }

    suspend fun setDecodeViewMode(mode: DecodeViewMode) {
        appContext.settingsDataStore.edit { it[Keys.DECODE_VIEW_MODE] = mode.name }
    }

    suspend fun setTxSlotParity(parity: TxSlotParity) {
        appContext.settingsDataStore.edit { it[Keys.TX_SLOT_PARITY] = parity.name }
    }

    suspend fun setUseDarkTheme(value: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.USE_DARK_THEME] = value }
    }

    /** Phase 7 (UX-06, HYG-04): epoch ms of the most recent successful ADIF auto-backup. */
    suspend fun setLastAdifBackupAtMs(value: Long) {
        appContext.settingsDataStore.edit { it[Keys.LAST_ADIF_BACKUP_AT_MS] = value }
    }

    suspend fun setQrzUploadEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.QRZ_UPLOAD_ENABLED] = enabled }
    }

    /** Encrypts via AndroidKeyStore before persisting; blank clears the key. */
    suspend fun setQrzApiKey(plaintext: String, cipher: QrzKeyCipher = KeystoreCipher) {
        val encrypted = plaintext.trim().takeIf { it.isNotEmpty() }?.let(cipher::encrypt)
        appContext.settingsDataStore.edit {
            if (encrypted == null) it.remove(Keys.QRZ_API_KEY_ENC)
            else it[Keys.QRZ_API_KEY_ENC] = encrypted
        }
    }

    suspend fun setQrzLastError(message: String?) {
        appContext.settingsDataStore.edit {
            if (message == null) it.remove(Keys.QRZ_LAST_ERROR)
            else it[Keys.QRZ_LAST_ERROR] = message
        }
    }

    /** Persist one decode-row category color. No-op for the non-configurable OTHER. */
    suspend fun setDecodeColor(category: DecodeCategory, argb: Int) {
        val key = decodeColorKey(category) ?: return
        appContext.settingsDataStore.edit { it[key] = argb }
    }

    /** Remove all decode color overrides — the settings flow falls back to defaults. */
    suspend fun resetDecodeColors() {
        appContext.settingsDataStore.edit { prefs ->
            DecodeCategory.entries.forEach { category ->
                decodeColorKey(category)?.let { prefs.remove(it) }
            }
        }
    }

    private fun decodeColorKey(category: DecodeCategory): Preferences.Key<Int>? =
        when (category) {
            DecodeCategory.OWN_TX -> Keys.DECODE_COLOR_OWN_TX
            DecodeCategory.PARTNER -> Keys.DECODE_COLOR_PARTNER
            DecodeCategory.MY_CALL -> Keys.DECODE_COLOR_MY_CALL
            DecodeCategory.CQ_NEW -> Keys.DECODE_COLOR_CQ_NEW
            DecodeCategory.CQ_WORKED_OTHER_BAND -> Keys.DECODE_COLOR_CQ_WORKED_OTHER
            DecodeCategory.CQ_WORKED_THIS_BAND -> Keys.DECODE_COLOR_CQ_WORKED_THIS
            DecodeCategory.OTHER -> null
        }

    private object Keys {
        val MY_CALL = stringPreferencesKey("my_call")
        val MY_GRID = stringPreferencesKey("my_grid")
        val TX_TONE_HZ = intPreferencesKey("tx_tone_hz")
        val AUDIO_DEVICE_ID = intPreferencesKey("audio_device_id")
        val PTT_PREFERENCE = stringPreferencesKey("ptt_preference")
        val CAT_BAUD = intPreferencesKey("cat_baud")
        val RADIO_MODEL = stringPreferencesKey("radio_model")
        val CAT_PORT_OVERRIDE = intPreferencesKey("cat_port_override")
        val LICENSE_ACK = booleanPreferencesKey("license_ack")
        val TX_ENABLED = booleanPreferencesKey("tx_enabled")
        val AUTO_SEQ = booleanPreferencesKey("auto_seq")
        val ANSWER_WHEN_CALLED = booleanPreferencesKey("answer_when_called")
        val AUTO_ANSWER_CQ = booleanPreferencesKey("auto_answer_cq")
        val LATE_START_TX_ENABLED = booleanPreferencesKey("late_start_tx_enabled")
        val EARLY_DECODE_ENABLED = booleanPreferencesKey("early_decode_enabled")
        val SEND_RR73 = booleanPreferencesKey("send_rr73")
        val AUTO_CQ_RESUME = booleanPreferencesKey("auto_cq_resume")
        val ANSWER_POLICY = stringPreferencesKey("answer_policy")
        val MAX_UNANSWERED_TX = intPreferencesKey("max_unanswered_tx")
        val INPUT_GAIN = floatPreferencesKey("input_gain")
        val LAST_DIAL_FREQ_HZ = longPreferencesKey("last_dial_freq_hz")
        val POTA_MODE = booleanPreferencesKey("pota_mode")
        val POTA_PARK_REF = stringPreferencesKey("pota_park_ref")
        val CQ73_FILTER = booleanPreferencesKey("cq73_filter")
        val DECODE_VIEW_MODE = stringPreferencesKey("decode_view_mode")
        val TX_SLOT_PARITY = stringPreferencesKey("tx_slot_parity")
        val USE_DARK_THEME = booleanPreferencesKey("use_dark_theme")
        val DECODE_COLOR_OWN_TX = intPreferencesKey("decode_color_own_tx")
        val DECODE_COLOR_PARTNER = intPreferencesKey("decode_color_partner")
        val DECODE_COLOR_MY_CALL = intPreferencesKey("decode_color_my_call")
        val DECODE_COLOR_CQ_NEW = intPreferencesKey("decode_color_cq_new")
        val DECODE_COLOR_CQ_WORKED_OTHER = intPreferencesKey("decode_color_cq_worked_other")
        val DECODE_COLOR_CQ_WORKED_THIS = intPreferencesKey("decode_color_cq_worked_this")
        val LAST_ADIF_BACKUP_AT_MS = longPreferencesKey("last_adif_backup_at_ms")
        val RIG_PROFILES = stringPreferencesKey("rig_profiles")
        val SELECTED_RIG_PROFILE = stringPreferencesKey("selected_rig_profile")
        val QRZ_UPLOAD_ENABLED = booleanPreferencesKey("qrz_upload_enabled")
        val QRZ_API_KEY_ENC = stringPreferencesKey("qrz_api_key_enc")
        val QRZ_LAST_ERROR = stringPreferencesKey("qrz_last_error")
    }

    companion object {
        const val INPUT_GAIN_MIN = 0.1f

        /** FT-891 menu 05-06 (CAT RATE) choices — the only valid CAT bauds. */
        val CAT_BAUD_OPTIONS = listOf(4800, 9600, 19200, 38400)

        /** Unknown values fall back to the rig-module default (38400). */
        fun coerceCatBaud(baud: Int): Int =
            if (baud in CAT_BAUD_OPTIONS) baud else RigController.DEFAULT_CAT_BAUD

        /** Pure migration mapping (unit-tested; the DataStore edit is device-verified). */
        fun buildMigratedProfile(
            modelId: String,
            baud: Int?,
            catPortOverride: Int?,
            pttPreference: PttPreference?,
        ): RigProfile = RigProfile(
            id = java.util.UUID.randomUUID().toString(),
            name = RigRegistry.byId(modelId)?.displayName ?: modelId,
            presetId = modelId,
            catProtocolId = null,
            baud = baud,
            catPortIndex = catPortOverride,
            pttMethod = pttPreference?.toPttMethod(),
        )
    }
}
