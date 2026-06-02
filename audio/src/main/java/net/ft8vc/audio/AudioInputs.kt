package net.ft8vc.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** A selectable audio input device. */
data class AudioInputDevice(
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
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            else -> "Input $type"
        }
}

object AudioInputs {

    private val usbTypes = setOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )

    /** List input devices, USB first (the Digirig is what we want to pick). */
    fun list(context: Context): List<AudioInputDevice> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .map {
                AudioInputDevice(
                    id = it.id,
                    name = it.productName?.toString()?.ifBlank { "Audio input" } ?: "Audio input",
                    type = it.type,
                    isUsb = it.type in usbTypes,
                )
            }
            .sortedByDescending { it.isUsb }
    }

    /** The first USB input device, if any (a reasonable default for the Digirig). */
    fun firstUsb(context: Context): AudioInputDevice? = list(context).firstOrNull { it.isUsb }
}
