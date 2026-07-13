package net.packrig.app.controllers

/**
 * Pure decision logic for receive-only monitor captures (capture running
 * without an operating session, e.g. after the capture-failed retry chip).
 * No I/O and no Android dependencies — the ViewModel supplies the
 * environment and acts on the returned decisions.
 */
object MonitorGate {

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
