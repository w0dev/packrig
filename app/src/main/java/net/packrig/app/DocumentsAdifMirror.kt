package net.packrig.app

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * Best-effort mirror of the ADIF backup into shared storage
 * (Documents/packrig/packrig-logbook.adi) so a copy survives uninstall — the
 * app-private backup in getExternalFilesDir dies with the app (logbook loss,
 * 2026-07-04 field report). MediaStore-only, so no storage permissions.
 *
 * After a reinstall the previous install's file is no longer ours to write;
 * inserting the same display name makes MediaStore create a uniquified
 * sibling (e.g. "packrig-logbook (1).adi") and the stale copy remains as
 * history. Failures are logged and swallowed: the mirror must never break
 * the private backup (spec 2026-07-04-durable-adif-backup).
 */
object DocumentsAdifMirror {

    private const val TAG = "DocumentsAdifMirror"
    private const val DISPLAY_NAME = "packrig-logbook.adi"
    private const val RELATIVE_PATH = "Documents/packrig/"

    /** Write [adif] to Documents/packrig. Returns false on any failure. No-op below API 29. */
    fun write(context: Context, adif: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val owned = findOwnedEntry(resolver, collection)
            if (owned != null && overwrite(resolver, owned, adif)) return true
            val fresh = resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, DISPLAY_NAME)
                    // text/plain would make MediaStore rename the file to .adi.txt,
                    // which also breaks findOwnedEntry's exact-name re-find.
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                },
            ) ?: return false
            overwrite(resolver, fresh, adif)
        } catch (t: Throwable) {
            Log.w(TAG, "Documents mirror failed", t)
            false
        }
    }

    /**
     * Find our previously created entry. MediaStore only returns rows this
     * install owns for non-media files, so a hit is writable; a file from a
     * previous install simply doesn't show up.
     */
    private fun findOwnedEntry(resolver: ContentResolver, collection: Uri): Uri? {
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(DISPLAY_NAME, RELATIVE_PATH),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return Uri.withAppendedPath(collection, cursor.getLong(0).toString())
            }
        }
        return null
    }

    private fun overwrite(resolver: ContentResolver, uri: Uri, adif: String): Boolean =
        try {
            resolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(adif.toByteArray(Charsets.UTF_8))
                true
            } ?: false
        } catch (se: SecurityException) {
            Log.w(TAG, "Mirror entry not writable (stale ownership)", se)
            false
        }
}
