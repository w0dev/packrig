package net.ft8vc.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import net.ft8vc.audio.dsp.FirDecimator
import net.ft8vc.core.AppInfo

/**
 * Captures mono PCM from an input device and decimates it to 12 kHz.
 *
 * USB sound cards (Digirig) usually run at 48 kHz, so we capture at the highest
 * supported integer multiple of 12 kHz and decimate. The audio source is chosen
 * to avoid AGC/noise-suppression, and those effects are explicitly disabled,
 * because any nonlinear processing wrecks FT8 fidelity.
 */
class UsbAudioCapture(private val context: Context) : AudioEngine {

    override val outputSampleRateHz: Int = AppInfo.SAMPLE_RATE_HZ

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val effects = mutableListOf<() -> Unit>()

    /** Candidate capture rates and the decimation factor to reach 12 kHz. */
    private val rateOptions = listOf(48_000 to 4, 24_000 to 2, 12_000 to 1)

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the caller before start().
    override fun start(preferredDeviceId: Int?, onFrames: (ShortArray) -> Unit) {
        if (running) return

        val (rec, factor) = openRecord(preferredDeviceId)
            ?: throw IllegalStateException("Unable to open any AudioRecord configuration")
        record = rec
        applyPreferredDevice(rec, preferredDeviceId)
        disableInputEffects(rec.audioSessionId)

        val decimator = FirDecimator.lowPass(inputRate = outputSampleRateHz * factor, factor = factor)
        val readBuf = ShortArray(4096)

        rec.startRecording()
        running = true
        thread = Thread({
            while (running) {
                val read = rec.read(readBuf, 0, readBuf.size)
                if (read > 0) {
                    val chunk = if (read == readBuf.size) readBuf else readBuf.copyOf(read)
                    val decimated = if (factor == 1) chunk.copyOf() else decimator.process(chunk)
                    if (decimated.isNotEmpty()) onFrames(decimated)
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord.read error: $read")
                    break
                }
            }
        }, "ft8vc-capture").also { it.start() }
    }

    override fun stop() {
        running = false
        val t = thread
        thread = null
        try {
            if (t != null && t.isAlive) {
                t.interrupt()
                t.join(STOP_JOIN_TIMEOUT_MS)
                if (t.isAlive) {
                    Log.w(TAG, "capture thread didn't stop within ${STOP_JOIN_TIMEOUT_MS}ms")
                    stopCleanFailureCount.incrementAndGet()
                }
            }
        } finally {
            record?.runCatching {
                stop()
                release()
            }
            record = null
            effects.forEach { it() }
            effects.clear()
        }
    }

    /** Phase 6 (RELY-03): count of times the capture thread didn't stop within the join window. */
    private val stopCleanFailureCount = java.util.concurrent.atomic.AtomicInteger(0)
    fun consumeStopCleanFailureCount(): Int = stopCleanFailureCount.getAndSet(0)

    @SuppressLint("MissingPermission")
    private fun openRecord(preferredDeviceId: Int?): Pair<AudioRecord, Int>? {
        for ((rate, factor) in rateOptions) {
            val minBuf = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) continue
            val bufferBytes = maxOf(minBuf, rate * 2 * 2 / 5) // ~400 ms

            for (source in preferredSources()) {
                val rec = runCatching {
                    AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build(),
                        )
                        .setBufferSizeInBytes(bufferBytes)
                        .build()
                }.getOrNull()

                if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "Capture @ ${rate}Hz source=$source factor=$factor")
                    return rec to factor
                }
                rec?.release()
            }
        }
        return null
    }

    private fun preferredSources(): List<Int> = listOf(
        MediaRecorder.AudioSource.UNPROCESSED,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC,
    )

    private fun applyPreferredDevice(rec: AudioRecord, deviceId: Int?) {
        if (deviceId == null) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val device: AudioDeviceInfo? = am
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.id == deviceId }
        if (device != null) {
            val ok = rec.setPreferredDevice(device)
            Log.i(TAG, "setPreferredDevice(${device.productName})=$ok")
        }
    }

    /** Disable any input DSP that would distort FT8 audio. */
    private fun disableInputEffects(sessionId: Int) {
        runCatching {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.let {
                    it.enabled = false
                    effects += { it.release() }
                }
            }
        }
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.let {
                    it.enabled = false
                    effects += { it.release() }
                }
            }
        }
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.let {
                    it.enabled = false
                    effects += { it.release() }
                }
            }
        }
    }

    companion object {
        private const val TAG = "UsbAudioCapture"
        /** Phase 6 (RELY-03): bound for the cooperative stop() handshake. */
        private const val STOP_JOIN_TIMEOUT_MS = 500L
    }
}
