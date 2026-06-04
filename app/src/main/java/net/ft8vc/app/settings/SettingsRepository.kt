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

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ft8vc_settings",
)

class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext

    val settings: Flow<StationSettings> = appContext.settingsDataStore.data.map { prefs ->
        StationSettings(
            myCall = prefs[Keys.MY_CALL] ?: "TEST",
            myGrid = prefs[Keys.MY_GRID] ?: "FN31",
            txToneHz = prefs[Keys.TX_TONE_HZ] ?: 1000,
            selectedAudioDeviceId = prefs[Keys.AUDIO_DEVICE_ID],
            pttPreference = prefs[Keys.PTT_PREFERENCE]?.let { PttPreference.valueOf(it) }
                ?: PttPreference.AUTO,
            licenseAcknowledged = prefs[Keys.LICENSE_ACK] ?: false,
            txEnabledInSettings = prefs[Keys.TX_ENABLED] ?: false,
            autoSeqEnabled = prefs[Keys.AUTO_SEQ] ?: true,
            answerWhenCalledEnabled = prefs[Keys.ANSWER_WHEN_CALLED] ?: true,
            waterfallBrightness = prefs[Keys.WATERFALL_BRIGHTNESS] ?: 0.6f,
            inputGain = prefs[Keys.INPUT_GAIN] ?: 1f,
            lastDialFreqHz = prefs[Keys.LAST_DIAL_FREQ_HZ],
        )
    }

    suspend fun setMyCall(call: String) {
        appContext.settingsDataStore.edit { it[Keys.MY_CALL] = call.trim().uppercase() }
    }

    suspend fun setMyGrid(grid: String) {
        appContext.settingsDataStore.edit { it[Keys.MY_GRID] = grid.trim().uppercase() }
    }

    suspend fun setTxToneHz(hz: Int) {
        appContext.settingsDataStore.edit { it[Keys.TX_TONE_HZ] = hz.coerceIn(300, 3000) }
    }

    suspend fun setSelectedAudioDeviceId(id: Int?) {
        appContext.settingsDataStore.edit {
            if (id == null) it.remove(Keys.AUDIO_DEVICE_ID) else it[Keys.AUDIO_DEVICE_ID] = id
        }
    }

    suspend fun setPttPreference(pref: PttPreference) {
        appContext.settingsDataStore.edit { it[Keys.PTT_PREFERENCE] = pref.name }
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

    suspend fun setWaterfallBrightness(value: Float) {
        appContext.settingsDataStore.edit {
            it[Keys.WATERFALL_BRIGHTNESS] = value.coerceIn(0f, 1f)
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

    private object Keys {
        val MY_CALL = stringPreferencesKey("my_call")
        val MY_GRID = stringPreferencesKey("my_grid")
        val TX_TONE_HZ = intPreferencesKey("tx_tone_hz")
        val AUDIO_DEVICE_ID = intPreferencesKey("audio_device_id")
        val PTT_PREFERENCE = stringPreferencesKey("ptt_preference")
        val LICENSE_ACK = booleanPreferencesKey("license_ack")
        val TX_ENABLED = booleanPreferencesKey("tx_enabled")
        val AUTO_SEQ = booleanPreferencesKey("auto_seq")
        val ANSWER_WHEN_CALLED = booleanPreferencesKey("answer_when_called")
        val WATERFALL_BRIGHTNESS = floatPreferencesKey("waterfall_brightness")
        val INPUT_GAIN = floatPreferencesKey("input_gain")
        val LAST_DIAL_FREQ_HZ = longPreferencesKey("last_dial_freq_hz")
    }

    companion object {
        const val INPUT_GAIN_MIN = 0.1f
    }
}
