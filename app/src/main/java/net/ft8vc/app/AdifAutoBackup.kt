package net.ft8vc.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.ft8vc.app.settings.SettingsRepository
import net.ft8vc.core.ActivationProfile
import net.ft8vc.core.AppInfo
import net.ft8vc.data.Logbook
import net.ft8vc.data.adif.AdifExportContext
import java.io.File

/**
 * Phase 7 (HYG-04): atomic ADIF auto-export.
 *
 * Triggered after every QSO commit and via the Settings → Logbook "Backup
 * now" button. Writes are atomic (write to .tmp file, then rename) so a
 * crash during write can never corrupt the existing backup. Runs on an
 * application-scoped coroutine so the export survives ViewModel
 * destruction (e.g. process pause mid-write).
 *
 * The destination is the app-private external dir (no permission needed,
 * survives uninstall via Android backup if android:allowBackup is true).
 */
object AdifAutoBackup {

    private const val TAG = "AdifAutoBackup"
    private const val FILE_NAME = "ft8vc-logbook.adi"
    private const val TMP_NAME = "ft8vc-logbook.adi.tmp"

    /** Application-scoped scope. Survives any single ViewModel lifecycle. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun scheduleBackupAfterQso(
        context: Context,
        logbook: Logbook,
        settings: SettingsRepository,
    ) {
        applicationScope.launch { backupNow(context, logbook, settings) }
    }

    @Volatile private var dailyTimerStarted: Boolean = false

    /**
     * Phase 7 (HYG-04): daily rolling timer. Idempotent — first caller wins,
     * subsequent calls no-op. The timer lives on [applicationScope] so it
     * survives any ViewModel teardown for the lifetime of the process.
     */
    fun startDailyTimerIfNotRunning(
        context: Context,
        logbook: Logbook,
        settings: SettingsRepository,
        intervalMs: Long = 24 * 60 * 60 * 1000L,
    ) {
        if (dailyTimerStarted) return
        dailyTimerStarted = true
        applicationScope.launch {
            while (isActive) {
                delay(intervalMs)
                backupNow(context, logbook, settings)
            }
        }
    }

    /** Write the current logbook to disk atomically. Returns the final file path on success, null on failure. */
    suspend fun backupNow(
        context: Context,
        logbook: Logbook,
        settings: SettingsRepository,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val s = settings.settingsFirst()
            val parkRef = if (s.potaModeEnabled) ActivationProfile.normalizeParkRef(s.potaParkRef) else null
            val adif = logbook.exportAdif(
                AdifExportContext(
                    programId = AppInfo.APP_NAME,
                    programVersion = AppInfo.VERSION_NAME,
                    potaEnabled = s.potaModeEnabled,
                    potaParkRef = parkRef,
                ),
            )
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val target = File(dir, FILE_NAME)
            val tmp = File(dir, TMP_NAME)
            tmp.writeText(adif)
            if (!tmp.renameTo(target)) {
                Log.w(TAG, "renameTo failed; falling back to copy+delete")
                target.writeText(adif)
                tmp.delete()
            }
            settings.setLastAdifBackupAtMs(System.currentTimeMillis())
            target
        } catch (t: Throwable) {
            Log.e(TAG, "ADIF backup failed", t)
            null
        }
    }
}

/** Read-once helper since SettingsRepository exposes a Flow. */
private suspend fun SettingsRepository.settingsFirst() = settings.first()
