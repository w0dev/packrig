package net.ft8vc.app.controllers

/**
 * Pure decision logic for auto RX-monitor (spec
 * 2026-07-11-auto-rx-monitor-design): start receive-only capture when a
 * radio's USB audio is connected, so the waterfall and decode list are live
 * before the operator presses Start. No I/O and no Android dependencies —
 * the ViewModel supplies the environment and acts on the returned decisions.
 */
object MonitorGate {

    /** All conditions required to start monitor capture (receive-only, no rig prep). */
    fun shouldStartMonitor(
        autoMonitorEnabled: Boolean,
        appInForeground: Boolean,
        usbAudioInputPresent: Boolean,
        recordAudioGranted: Boolean,
        isCapturing: Boolean,
        isTransmitting: Boolean,
    ): Boolean =
        autoMonitorEnabled &&
            appInForeground &&
            usbAudioInputPresent &&
            recordAudioGranted &&
            !isCapturing &&
            !isTransmitting

    /**
     * App left the foreground: stop a monitor-only capture. Android 9+ mutes
     * mic input for backgrounded apps, so a background monitor would feed the
     * capture watchdog silent slots and trip its recovery loop. Operating
     * sessions hold KEEP_SCREEN_ON and are left alone.
     */
    fun shouldStopOnBackground(isCapturing: Boolean, isOperating: Boolean): Boolean =
        isCapturing && !isOperating

    /** What to do when a USB audio input device disappears mid-capture. */
    enum class RemovalAction { IGNORE, STOP_QUIETLY, RESTART_WITH_NOTICE }

    /**
     * Monitoring with no USB input left is the expected "operator unplugged
     * the radio" case — stop without a snackbar. Everything else keeps the
     * existing RELY-02a restart-with-notice behavior.
     */
    fun onUsbAudioInputRemoved(
        isCapturing: Boolean,
        isOperating: Boolean,
        usbInputStillPresent: Boolean,
    ): RemovalAction = when {
        !isCapturing -> RemovalAction.IGNORE
        isOperating -> RemovalAction.RESTART_WITH_NOTICE
        usbInputStillPresent -> RemovalAction.RESTART_WITH_NOTICE
        else -> RemovalAction.STOP_QUIETLY
    }
}
