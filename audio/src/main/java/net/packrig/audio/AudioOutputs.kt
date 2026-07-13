package net.packrig.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** A selectable audio output device. */
data class AudioOutputDevice(
    val id: Int,
    val name: String,
    val type: Int,
    val isUsb: Boolean,
) {
    val typeLabel: String
        get() = when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            else -> "Output $type"
        }
}

object AudioOutputs {

    private val usbTypes = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )

    /** List output devices, USB first (the Digirig sound card). */
    fun list(context: Context): List<AudioOutputDevice> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map {
                AudioOutputDevice(
                    id = it.id,
                    name = it.productName?.toString()?.ifBlank { "Audio output" } ?: "Audio output",
                    type = it.type,
                    isUsb = it.type in usbTypes,
                )
            }
            .sortedByDescending { it.isUsb }
    }

    fun firstUsb(context: Context): AudioOutputDevice? = list(context).firstOrNull { it.isUsb }
}
