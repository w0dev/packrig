package net.ft8vc.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import net.ft8vc.audio.dsp.Upsampler
import net.ft8vc.core.AppInfo

/**
 * Plays mono 12 kHz PCM out through a USB sound card (Digirig TX audio).
 *
 * USB interfaces usually run at 48 kHz, so [playBlocking] upsamples before
 * writing to [AudioTrack].
 */
class UsbAudioPlayback(private val context: Context) {

    /** Candidate playback rates and the upsample factor from 12 kHz. */
    private val rateOptions = listOf(48_000 to 4, 24_000 to 2, 12_000 to 1)

    @Volatile
    private var activeTrack: AudioTrack? = null

    /** Stop in-progress [playBlocking] and release the current [AudioTrack]. */
    fun stop() {
        val track = activeTrack
        if (track == null) return
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
        }
        runCatching { track.release() }
        if (activeTrack === track) {
            activeTrack = null
        }
    }

    /**
     * Play [samples12k] to completion on the calling thread. [preferredDeviceId]
     * is an [AudioDeviceInfo] output id, or null for default routing.
     * Returns false if playback was interrupted by [stop].
     */
    fun playBlocking(samples12k: ShortArray, preferredDeviceId: Int?): Boolean {
        if (samples12k.isEmpty()) return true
        val trackPair = openTrack(preferredDeviceId)
            ?: throw IllegalStateException("Unable to open any AudioTrack configuration")
        val (track, factor) = trackPair
        applyPreferredDevice(track, preferredDeviceId)
        val pcm = if (factor == 1) samples12k else Upsampler.linear(samples12k, factor)
        var completed = false
        try {
            runCatching { track.setVolume(AudioTrack.getMaxVolume()) }
            activeTrack = track
            track.play()
            var offset = 0
            while (offset < pcm.size && activeTrack === track) {
                val written = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) {
                    Log.e(TAG, "AudioTrack.write error: $written")
                    break
                }
                offset += written
            }
            completed = offset >= pcm.size && activeTrack === track
            if (activeTrack === track) {
                track.stop()
            }
        } finally {
            if (activeTrack === track) {
                activeTrack = null
            }
            track.release()
        }
        return completed
    }

    private fun openTrack(preferredDeviceId: Int?): Pair<AudioTrack, Int>? {
        for ((rate, factor) in rateOptions) {
            val minBuf = AudioTrack.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) continue
            val bufferBytes = maxOf(minBuf, rate * 2 * 2 / 5)

            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            }.getOrNull()

            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                Log.i(TAG, "Playback @ ${rate}Hz factor=$factor")
                return track to factor
            }
            track?.release()
        }
        return null
    }

    private fun applyPreferredDevice(track: AudioTrack, deviceId: Int?) {
        if (deviceId == null) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val device: AudioDeviceInfo? = am
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.id == deviceId }
        if (device != null) {
            val ok = track.setPreferredDevice(device)
            Log.i(TAG, "setPreferredDevice(${device.productName})=$ok")
        }
    }

    companion object {
        private const val TAG = "UsbAudioPlayback"
        val SAMPLE_RATE_HZ: Int = AppInfo.SAMPLE_RATE_HZ
    }
}
