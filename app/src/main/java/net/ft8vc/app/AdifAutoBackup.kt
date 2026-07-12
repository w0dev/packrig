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
import net.ft8vc.core.AppInfo
import net.ft8vc.data.Logbook
import net.ft8vc.data.adif.AdifExportContext
import java.io.File

/**
 * Phase 7 (HYG-04): atomic ADIF auto-export.
 *
 * Triggered after every QSO commit and via the Log tab's "Backup
 * now" menu item. Writes are atomic (write to .tmp file, then rename) so a
 * crash during write can never corrupt the existing backup. Runs on an
 * application-scoped coroutine so the export survives ViewModel
 * destruction (e.g. process pause mid-write).
 *
 * The destination is the app-private external dir (no permission needed), plus
 * a best-effort mirror in Documents/ft8vc via [DocumentsAdifMirror] — the
 * private copy is deleted on uninstall; the mirror survives it.
 */
object AdifAutoBackup {

    private const val TAG = "AdifAutoBackup"
    private const val FILE_NAME = "ft8vc-logbook.adi"
    private const val TMP_NAME = "ft8vc-logbook.adi.tmp"

    /** Result of [backupNow]: where the private copy lives, and whether the Documents mirror succeeded. */
    data class Outcome(val privateFile: File, val mirrored: Boolean)

    /** Success snackbar copy — only claims Documents/ft8vc when the durable mirror was written. */
    fun backupSnackbarText(mirrored: Boolean): String =
        if (mirrored) "ADIF backup written to Documents/ft8vc"
        else "ADIF backup written (app-private storage only)"

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

    /** Write the current logbook to disk atomically. Returns the [Outcome] on success, null on failure. */
    suspend fun backupNow(
        context: Context,
        logbook: Logbook,
        settings: SettingsRepository,
    ): Outcome? = withContext(Dispatchers.IO) {
        try {
            val adif = logbook.exportAdif(
                AdifExportContext(
                    programId = AppInfo.APP_NAME,
                    programVersion = AppInfo.VERSION_NAME,
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
            Outcome(target, mirrored = DocumentsAdifMirror.write(context, adif))
        } catch (t: Throwable) {
            Log.e(TAG, "ADIF backup failed", t)
            null
        }
    }
}
