package net.packrig.app.settings

import net.packrig.audio.AudioInputDevice

/**
 * Label for the Settings audio-input picker.
 *
 * Three states: a manual (persisted) pick shows the device as-is; an
 * auto-picked device is prefixed with "Automatic" so the operator can see
 * routing chose it; no device at all reads as the healthy system default —
 * never as a missing selection.
 */
fun audioInputDeviceLabel(selected: AudioInputDevice?, manuallySelected: Boolean): String =
    when {
        selected == null -> "Automatic (system default)"
        manuallySelected -> "${selected.name} (${selected.typeLabel})"
        else -> "Automatic — ${selected.name} (${selected.typeLabel})"
    }
