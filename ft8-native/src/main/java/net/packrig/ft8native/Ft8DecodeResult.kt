package net.packrig.ft8native

/**
 * One decoded FT8 message. Field order/types must match the JNI constructor
 * signature `(Ljava/lang/String;IFFI)V` used in ft8_jni.cpp.
 *
 * @property message decoded text (e.g. "CQ K1ABC FN42")
 * @property snr SNR in dB. As returned by the native decoder this is always 0;
 *           the real value is set by net.packrig.core.SnrEstimator before display.
 * @property dtSeconds time offset of the signal within the slot
 * @property freqHz audio frequency of the signal
 * @property score raw Costas sync score (decoder confidence)
 */
data class Ft8DecodeResult(
    val message: String,
    val snr: Int,
    val dtSeconds: Float,
    val freqHz: Float,
    val score: Int,
)
