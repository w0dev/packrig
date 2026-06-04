package net.ft8vc.app.settings

/** PTT keying strategy for the Digirig serial port. */
enum class PttPreference {
    AUTO,
    CAT,
    RTS,
}

/** Persisted station and operating preferences. */
data class StationSettings(
    val myCall: String = "TEST",
    val myGrid: String = "FN31",
    val txToneHz: Int = 1000,
    val selectedAudioDeviceId: Int? = null,
    val pttPreference: PttPreference = PttPreference.AUTO,
    val licenseAcknowledged: Boolean = false,
    val txEnabledInSettings: Boolean = false,
    val autoSeqEnabled: Boolean = true,
    val answerWhenCalledEnabled: Boolean = true,
    val waterfallBrightness: Float = 0.6f,
    /** RX PCM scale 0.1–1.0; attenuate when the level meter clips. */
    val inputGain: Float = 1f,
    val lastDialFreqHz: Long? = null,
)
