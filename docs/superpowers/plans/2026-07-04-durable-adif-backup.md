# Durable ADIF Backup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mirror every logbook backup to `Documents/ft8vc/` (survives uninstall) and add ADIF import with merge/duplicate-skip so a backup can be restored.

**Architecture:** A pure-Kotlin `AdifReader` + `AdifImportMerge` in the `data` module (JVM-tested), a new `Logbook.importContacts()` on `RoomLogbook`, a best-effort `DocumentsAdifMirror` (MediaStore, API 29+) called from `AdifAutoBackup.backupNow()`, and an "Import ADIF…" button in Settings → Logbook wired to `OperateViewModel.importAdif(uri)`.

**Tech Stack:** Kotlin, Room 2.7.2, MediaStore (`MediaStore.Files`), AndroidX Activity Result (`OpenDocument`), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-07-04-durable-adif-backup-design.md`

## Global Constraints

- Work in worktree `.claude/worktrees/adif-durable-backup`, branch `claude/adif-durable-backup`.
- minSdk 28: `MediaStore.MediaColumns.RELATIVE_PATH` needs API 29 — the mirror must no-op below `Build.VERSION_CODES.Q`.
- Behavior parity: the existing app-private atomic backup, its triggers, and "Last backup" status text are unchanged. Mirror failures never fail the private backup and never show an error snackbar.
- No new dependencies. No Operate-screen UI.
- `data` module is pure Kotlin/JVM-testable (Room interfaces only); no Android imports in `AdifReader`/`AdifImportMerge`.
- Run all gradle commands from the worktree root: `cd /Users/bsmirks/git/ft8vc/.claude/worktrees/adif-durable-backup`.
- Duplicate rule (spec): same `dxCall` (case-insensitive) + same `band` (null matches null) + `|Δ utcMillis| < 90_000` ms.
- Commit messages end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: `AdifReader` — parse ADIF text into contacts

**Files:**
- Create: `data/src/main/java/net/ft8vc/data/adif/AdifReader.kt`
- Test: `data/src/test/java/net/ft8vc/data/adif/AdifReaderTest.kt`

**Interfaces:**
- Consumes: `QsoContact` (`data/src/main/java/net/ft8vc/data/model/QsoContact.kt`), `AdifNormalizer.isValidBand(value: String): Boolean` (internal, same package), `AdifWriter.export(contacts, context): String` (tests only).
- Produces (used by Tasks 2 and 4):
  - `object AdifReader { fun read(text: String, fallbackMyCall: String = "", fallbackMyGrid: String = ""): AdifReadResult }`
  - `data class AdifReadResult(val contacts: List<QsoContact>, val skipped: Int)`
  - `class AdifImportException(message: String) : Exception(message)`

- [ ] **Step 1: Write the failing tests**

Create `data/src/test/java/net/ft8vc/data/adif/AdifReaderTest.kt`:

```kotlin
package net.ft8vc.data.adif

import net.ft8vc.data.model.QsoContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AdifReaderTest {

    private val contact = QsoContact(
        utcMillis = 1_700_000_000_000L, // 2023-11-14 22:13:20 UTC
        myCall = "W0DEV",
        myGrid = "EM26",
        dxCall = "K1ABC",
        dxGrid = "FN42",
        rstSent = -8,
        rstRcvd = -15,
        freqHz = 14_074_000L,
        mode = "FT8",
        band = "20m",
    )

    @Test
    fun roundTripsAdifWriterOutput() {
        val adif = AdifWriter.export(listOf(contact, contact.copy(dxCall = "N5XYZ", utcMillis = 1_700_000_300_000L)))
        val result = AdifReader.read(adif)
        assertEquals(2, result.contacts.size)
        assertEquals(0, result.skipped)
        val parsed = result.contacts[0]
        assertEquals(contact.utcMillis / 1000, parsed.utcMillis / 1000) // second precision
        assertEquals("K1ABC", parsed.dxCall)
        assertEquals("FN42", parsed.dxGrid)
        assertEquals("W0DEV", parsed.myCall)
        assertEquals("EM26", parsed.myGrid)
        assertEquals(-8, parsed.rstSent)
        assertEquals(-15, parsed.rstRcvd)
        assertEquals(14_074_000L, parsed.freqHz)
        assertEquals("FT8", parsed.mode)
        assertEquals("20m", parsed.band)
    }

    @Test
    fun roundTripsPotaParkRefs() {
        val adif = AdifWriter.export(listOf(contact.copy(potaParkRefs = "US-3315")))
        val parsed = AdifReader.read(adif).contacts.single()
        assertEquals("US-3315", parsed.potaParkRefs)
    }

    @Test
    fun parsesLowercaseTagsAndMinutePrecisionTime() {
        val adif = "<call:5>K1ABC<qso_date:8>20231114<time_on:4>2213" +
            "<band:3>20m<mode:3>FT8<eor>"
        val parsed = AdifReader.read(adif, fallbackMyCall = "W0DEV", fallbackMyGrid = "EM26").contacts.single()
        assertEquals("K1ABC", parsed.dxCall)
        assertEquals("W0DEV", parsed.myCall) // fallback: file has no STATION_CALLSIGN
        assertEquals("EM26", parsed.myGrid)
        assertEquals("20m", parsed.band)
        // 2023-11-14 22:13:00 UTC
        assertEquals(1_699_999_980_000L, parsed.utcMillis)
    }

    @Test
    fun ignoresHeaderAndUnknownFields() {
        val adif = "<ADIF_VER:5>3.1.4<PROGRAMID:5>FT8VC<EOH>\n" +
            "<CALL:5>K1ABC<QSO_DATE:8>20231114<TIME_ON:6>221320<BAND:3>20m<QSL_RCVD:1>N<EOR>\n"
        val result = AdifReader.read(adif)
        assertEquals(1, result.contacts.size)
        assertEquals("K1ABC", result.contacts.single().dxCall)
    }

    @Test
    fun skipsRecordsMissingCallOrTime() {
        val adif = "<CALL:5>K1ABC<QSO_DATE:8>20231114<TIME_ON:6>221320<BAND:3>20m<EOR>" +
            "<QSO_DATE:8>20231114<TIME_ON:6>221320<BAND:3>20m<EOR>" + // no CALL
            "<CALL:5>N5XYZ<BAND:3>20m<EOR>" // no date/time
        val result = AdifReader.read(adif)
        assertEquals(1, result.contacts.size)
        assertEquals(2, result.skipped)
    }

    @Test
    fun invalidBandBecomesNull() {
        val adif = "<CALL:5>K1ABC<QSO_DATE:8>20231114<TIME_ON:6>221320<BAND:4>999m<FREQ:9>14.074000<EOR>"
        val parsed = AdifReader.read(adif).contacts.single()
        assertNull(parsed.band)
        assertEquals(14_074_000L, parsed.freqHz)
    }

    @Test
    fun garbageInputThrows() {
        assertThrows(AdifImportException::class.java) { AdifReader.read("not adif at all") }
    }

    @Test
    fun allRecordsUnreadableThrows() {
        assertThrows(AdifImportException::class.java) {
            AdifReader.read("<QSO_DATE:8>20231114<EOR>")
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :data:test --tests "net.ft8vc.data.adif.AdifReaderTest" 2>&1 | tail -20`
Expected: compile FAILURE — `AdifReader`, `AdifReadResult`, `AdifImportException` unresolved.

- [ ] **Step 3: Implement `AdifReader`**

Create `data/src/main/java/net/ft8vc/data/adif/AdifReader.kt`:

```kotlin
package net.ft8vc.data.adif

import net.ft8vc.data.model.QsoContact
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToLong

class AdifImportException(message: String) : Exception(message)

data class AdifReadResult(val contacts: List<QsoContact>, val skipped: Int)

/**
 * Parses ADIF (.adi) text into [QsoContact]s. Tolerant by design: tag names are
 * case-insensitive, unknown fields are ignored, and records missing a CALL or a
 * parseable QSO_DATE+TIME_ON are skipped and counted rather than failing the file.
 * [fallbackMyCall]/[fallbackMyGrid] fill STATION_CALLSIGN / MY_GRIDSQUARE when the
 * file lacks them (foreign logger exports).
 */
object AdifReader {

    private val FIELD = Regex("<([A-Za-z0-9_]+)(?::(\\d+))?(?::[^>]*)?>")

    fun read(
        text: String,
        fallbackMyCall: String = "",
        fallbackMyGrid: String = "",
    ): AdifReadResult {
        val contacts = mutableListOf<QsoContact>()
        var skipped = 0
        var fields = mutableMapOf<String, String>()
        // Everything before <EOH> is header; files without a header start in records.
        var inHeader = text.contains("<eoh>", ignoreCase = true)
        var i = 0
        while (i < text.length) {
            val m = FIELD.find(text, i) ?: break
            val name = m.groupValues[1].lowercase()
            val len = m.groupValues[2].toIntOrNull()
            i = m.range.last + 1
            when {
                name == "eoh" -> {
                    inHeader = false
                    fields = mutableMapOf()
                }
                name == "eor" -> {
                    if (!inHeader && fields.isNotEmpty()) {
                        val contact = toContact(fields, fallbackMyCall, fallbackMyGrid)
                        if (contact != null) contacts += contact else skipped++
                    }
                    fields = mutableMapOf()
                }
                len != null -> {
                    val end = (i + len).coerceAtMost(text.length)
                    fields[name] = text.substring(i, end)
                    i = end
                }
            }
        }
        if (contacts.isEmpty()) {
            throw AdifImportException(
                if (skipped == 0) "No ADIF records found"
                else "No usable QSO records in file ($skipped unreadable)",
            )
        }
        return AdifReadResult(contacts, skipped)
    }

    private fun toContact(
        f: Map<String, String>,
        fallbackMyCall: String,
        fallbackMyGrid: String,
    ): QsoContact? {
        val call = f["call"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        val utcMillis = parseUtcMillis(f["qso_date"], f["time_on"]) ?: return null
        return QsoContact(
            utcMillis = utcMillis,
            myCall = f["station_callsign"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                ?: f["operator"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                ?: fallbackMyCall,
            myGrid = f["my_gridsquare"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                ?: fallbackMyGrid,
            dxCall = call,
            dxGrid = f["gridsquare"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
            rstSent = f["rst_sent"]?.trim()?.toIntOrNull(),
            rstRcvd = f["rst_rcvd"]?.trim()?.toIntOrNull(),
            freqHz = f["freq"]?.trim()?.toDoubleOrNull()?.let { (it * 1_000_000).roundToLong() },
            mode = f["mode"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "FT8",
            band = f["band"]?.trim()?.lowercase()?.takeIf { AdifNormalizer.isValidBand(it) },
            notes = f["notes"]?.trim() ?: f["comment"]?.trim() ?: "",
            potaParkRefs = f["my_sig_info"]?.trim()?.takeIf {
                it.isNotEmpty() && f["my_sig"]?.trim().equals("POTA", ignoreCase = true)
            } ?: f["pota_ref"]?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** yyyyMMdd + HHmmss (or HHmm) → UTC epoch millis; null when unparseable. */
    private fun parseUtcMillis(date: String?, time: String?): Long? {
        val d = date?.trim() ?: return null
        val t = time?.trim() ?: return null
        if (d.length != 8 || d.any { !it.isDigit() }) return null
        if ((t.length != 6 && t.length != 4) || t.any { !it.isDigit() }) return null
        val month = d.substring(4, 6).toInt()
        val day = d.substring(6, 8).toInt()
        if (month !in 1..12 || day !in 1..31) return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(
            d.substring(0, 4).toInt(), month - 1, day,
            t.substring(0, 2).toInt(), t.substring(2, 4).toInt(),
            if (t.length == 6) t.substring(4, 6).toInt() else 0,
        )
        return cal.timeInMillis
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :data:test --tests "net.ft8vc.data.adif.AdifReaderTest" 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add data/src/main/java/net/ft8vc/data/adif/AdifReader.kt data/src/test/java/net/ft8vc/data/adif/AdifReaderTest.kt
git commit -m "feat(data): AdifReader parses ADIF text into contacts

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Merge logic + `Logbook.importContacts()`

**Files:**
- Create: `data/src/main/java/net/ft8vc/data/adif/AdifImportMerge.kt`
- Modify: `data/src/main/java/net/ft8vc/data/Logbook.kt` (interface + `RoomLogbook`)
- Modify: `data/src/main/java/net/ft8vc/data/db/QsoDao.kt` (add `insertAll`)
- Test: `data/src/test/java/net/ft8vc/data/adif/AdifImportMergeTest.kt`

**Interfaces:**
- Consumes: `QsoContact`.
- Produces (used by Task 4):
  - `object AdifImportMerge { const val DUP_WINDOW_MS = 90_000L; fun partition(existing: List<QsoContact>, incoming: List<QsoContact>): Pair<List<QsoContact>, Int> }` — returns (contacts to insert, duplicate count).
  - On `Logbook` interface: `suspend fun importContacts(incoming: List<QsoContact>): ImportResult`
  - `data class ImportResult(val imported: Int, val duplicates: Int)` (top level in `Logbook.kt`)
  - On `QsoDao`: `@Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(entities: List<QsoEntity>)` — a single `@Insert` list call is one Room transaction (all-or-nothing per spec).

- [ ] **Step 1: Write the failing tests**

Create `data/src/test/java/net/ft8vc/data/adif/AdifImportMergeTest.kt`:

```kotlin
package net.ft8vc.data.adif

import net.ft8vc.data.model.QsoContact
import org.junit.Assert.assertEquals
import org.junit.Test

class AdifImportMergeTest {

    private fun qso(call: String, utc: Long, band: String? = "20m") = QsoContact(
        utcMillis = utc, myCall = "W0DEV", myGrid = "EM26",
        dxCall = call, dxGrid = null, rstSent = null, rstRcvd = null,
        freqHz = null, band = band,
    )

    @Test
    fun insertsNewContactsAndCountsDuplicates() {
        val existing = listOf(qso("K1ABC", 1_000_000L))
        val incoming = listOf(qso("K1ABC", 1_000_000L), qso("N5XYZ", 2_000_000L))
        val (toInsert, duplicates) = AdifImportMerge.partition(existing, incoming)
        assertEquals(listOf("N5XYZ"), toInsert.map { it.dxCall })
        assertEquals(1, duplicates)
    }

    @Test
    fun duplicateWindowBoundaries() {
        val existing = listOf(qso("K1ABC", 1_000_000L))
        // 89 s away: duplicate. 91 s away: distinct.
        val (toInsert, duplicates) = AdifImportMerge.partition(
            existing,
            listOf(qso("K1ABC", 1_000_000L + 89_000L), qso("K1ABC", 1_000_000L + 91_000L)),
        )
        assertEquals(1, toInsert.size)
        assertEquals(1, duplicates)
    }

    @Test
    fun callMatchIsCaseInsensitiveAndBandMatters() {
        val existing = listOf(qso("K1ABC", 1_000_000L, band = "20m"))
        val (toInsert, duplicates) = AdifImportMerge.partition(
            existing,
            listOf(
                qso("k1abc", 1_000_000L, band = "20m"), // dup, case-insensitive
                qso("K1ABC", 1_000_000L, band = "40m"), // different band → distinct
                qso("K1ABC", 1_000_000L, band = null),  // null band ≠ "20m" → distinct
            ),
        )
        assertEquals(2, toInsert.size)
        assertEquals(1, duplicates)
    }

    @Test
    fun fileInternalDuplicatesCollapse() {
        val (toInsert, duplicates) = AdifImportMerge.partition(
            emptyList(),
            listOf(qso("K1ABC", 1_000_000L), qso("K1ABC", 1_000_000L)),
        )
        assertEquals(1, toInsert.size)
        assertEquals(1, duplicates)
    }

    @Test
    fun reimportOfOwnExportIsFullNoOp() {
        val existing = (0 until 5).map { qso("K${it}ABC", it * 600_000L) }
        val (toInsert, duplicates) = AdifImportMerge.partition(existing, existing)
        assertEquals(0, toInsert.size)
        assertEquals(5, duplicates)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :data:test --tests "net.ft8vc.data.adif.AdifImportMergeTest" 2>&1 | tail -20`
Expected: compile FAILURE — `AdifImportMerge` unresolved.

- [ ] **Step 3: Implement merge + Logbook + DAO**

Create `data/src/main/java/net/ft8vc/data/adif/AdifImportMerge.kt`:

```kotlin
package net.ft8vc.data.adif

import net.ft8vc.data.model.QsoContact
import kotlin.math.abs

/** Duplicate-skip merge for ADIF import (spec 2026-07-04-durable-adif-backup). */
object AdifImportMerge {

    /** Same call + band within this window is the same QSO (minute-precision files). */
    const val DUP_WINDOW_MS = 90_000L

    /**
     * Split [incoming] into (contacts to insert, duplicate count) against
     * [existing]. Accepted contacts join the comparison set so duplicates
     * inside the imported file itself also collapse.
     */
    fun partition(
        existing: List<QsoContact>,
        incoming: List<QsoContact>,
    ): Pair<List<QsoContact>, Int> {
        val accepted = mutableListOf<QsoContact>()
        val seen = existing.toMutableList()
        var duplicates = 0
        for (candidate in incoming) {
            if (seen.any { isDuplicate(it, candidate) }) {
                duplicates++
            } else {
                accepted += candidate
                seen += candidate
            }
        }
        return accepted to duplicates
    }

    private fun isDuplicate(a: QsoContact, b: QsoContact): Boolean =
        a.dxCall.equals(b.dxCall, ignoreCase = true) &&
            a.band == b.band &&
            abs(a.utcMillis - b.utcMillis) < DUP_WINDOW_MS
}
```

In `data/src/main/java/net/ft8vc/data/db/QsoDao.kt`, add below the existing `insert`:

```kotlin
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<QsoEntity>)
```

In `data/src/main/java/net/ft8vc/data/Logbook.kt`:

Add import: `import net.ft8vc.data.adif.AdifImportMerge`

Add to the `Logbook` interface after `setParkRefs`:

```kotlin
    /** Merge [incoming] into the log, skipping duplicates (call+band+90 s window). */
    suspend fun importContacts(incoming: List<QsoContact>): ImportResult
```

Add top-level in the same file:

```kotlin
/** Outcome of an ADIF import merge. */
data class ImportResult(val imported: Int, val duplicates: Int)
```

Add to `RoomLogbook` after `setParkRefs`:

```kotlin
    override suspend fun importContacts(incoming: List<QsoContact>): ImportResult {
        val existing = dao.getAll().map { it.toContact() }
        val (toInsert, duplicates) = AdifImportMerge.partition(existing, incoming)
        // Single list @Insert = one Room transaction: all-or-nothing.
        dao.insertAll(toInsert.map { it.copy(id = 0).toEntity() })
        return ImportResult(imported = toInsert.size, duplicates = duplicates)
    }
```

- [ ] **Step 4: Run tests and build to verify**

Run: `./gradlew :data:test 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL` (KSP regenerates the DAO impl; all data tests pass)

- [ ] **Step 5: Commit**

```bash
git add data/src/main/java/net/ft8vc/data/adif/AdifImportMerge.kt data/src/main/java/net/ft8vc/data/Logbook.kt data/src/main/java/net/ft8vc/data/db/QsoDao.kt data/src/test/java/net/ft8vc/data/adif/AdifImportMergeTest.kt
git commit -m "feat(data): importContacts merge with duplicate-skip

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: `DocumentsAdifMirror` + hook into `AdifAutoBackup`

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/DocumentsAdifMirror.kt`
- Modify: `app/src/main/java/net/ft8vc/app/AdifAutoBackup.kt:93` (inside `backupNow`, after `settings.setLastAdifBackupAtMs(...)`)

**Interfaces:**
- Consumes: nothing from earlier tasks (parallel-safe with Tasks 1–2).
- Produces: `object DocumentsAdifMirror { fun write(context: Context, adif: String): Boolean }` — best-effort, returns false on any failure, never throws.

No JVM test is practical (MediaStore requires a device); this task is compile-verified here and behavior-verified on-device in Task 5. Keep the class small and obvious.

- [ ] **Step 1: Implement `DocumentsAdifMirror`**

Create `app/src/main/java/net/ft8vc/app/DocumentsAdifMirror.kt`:

```kotlin
package net.ft8vc.app

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * Best-effort mirror of the ADIF backup into shared storage
 * (Documents/ft8vc/ft8vc-logbook.adi) so a copy survives uninstall — the
 * app-private backup in getExternalFilesDir dies with the app (logbook loss,
 * 2026-07-04 field report). MediaStore-only, so no storage permissions.
 *
 * After a reinstall the previous install's file is no longer ours to write;
 * inserting the same display name makes MediaStore create a uniquified
 * sibling (e.g. "ft8vc-logbook (1).adi") and the stale copy remains as
 * history. Failures are logged and swallowed: the mirror must never break
 * the private backup (spec 2026-07-04-durable-adif-backup).
 */
object DocumentsAdifMirror {

    private const val TAG = "DocumentsAdifMirror"
    private const val DISPLAY_NAME = "ft8vc-logbook.adi"
    private const val RELATIVE_PATH = "Documents/ft8vc/"

    /** Write [adif] to Documents/ft8vc. Returns false on any failure. No-op below API 29. */
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
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
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
```

- [ ] **Step 2: Hook into `AdifAutoBackup.backupNow`**

In `app/src/main/java/net/ft8vc/app/AdifAutoBackup.kt`, the current end of the `try` block is:

```kotlin
            settings.setLastAdifBackupAtMs(System.currentTimeMillis())
            target
```

Change to:

```kotlin
            settings.setLastAdifBackupAtMs(System.currentTimeMillis())
            DocumentsAdifMirror.write(context, adif)
            target
```

Also update the class KDoc line that reads
`* The destination is the app-private external dir (no permission needed,`
`* survives uninstall via Android backup if android:allowBackup is true).`
to:

```kotlin
 * The destination is the app-private external dir (no permission needed), plus
 * a best-effort mirror in Documents/ft8vc via [DocumentsAdifMirror] — the
 * private copy is deleted on uninstall; the mirror survives it.
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/DocumentsAdifMirror.kt app/src/main/java/net/ft8vc/app/AdifAutoBackup.kt
git commit -m "feat(app): mirror ADIF backup to Documents/ft8vc via MediaStore

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Import UI — `OperateViewModel.importAdif` + Settings button

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (add `importAdif` directly after `backupAdifNow`, ~line 780)
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt` (Logbook section, after the "Backup now" button at ~line 278–288)

**Interfaces:**
- Consumes: `AdifReader.read(text, fallbackMyCall, fallbackMyGrid): AdifReadResult`, `AdifImportException` (Task 1); `logbook.importContacts(incoming): ImportResult` (Task 2); `AdifAutoBackup.scheduleBackupAfterQso(...)` (existing); existing VM members `logbook`, `settingsRepo`, `workedBeforeCache`, `notify(text, tag)`.
- Produces: `fun importAdif(uri: Uri)` on `OperateViewModel` (public, called from `SettingsScreen`).

- [ ] **Step 1: Add `importAdif` to `OperateViewModel`**

Add imports (check which already exist): `android.net.Uri`, `net.ft8vc.data.adif.AdifReader` (`Dispatchers`, `withContext`, `first` are already imported; verify).

Directly after the `backupAdifNow()` function:

```kotlin
    /** Import an ADIF file picked in Settings → Logbook; merge with duplicate-skip. */
    fun importAdif(uri: Uri) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val text = getApplication<Application>().contentResolver
                        .openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw AdifImportException("Could not read the selected file")
                    val profile = settingsRepo.settings.first()
                    val read = AdifReader.read(
                        text,
                        fallbackMyCall = profile.myCall,
                        fallbackMyGrid = profile.myGrid,
                    )
                    val merged = logbook.importContacts(read.contacts)
                    read.contacts.map { it.dxCall }.toSet()
                        .forEach { workedBeforeCache.invalidate(it) }
                    Triple(merged.imported, merged.duplicates, read.skipped)
                }
            }
            outcome.fold(
                onSuccess = { (imported, duplicates, skipped) ->
                    val unreadable = if (skipped > 0) ", $skipped unreadable" else ""
                    notify("Imported $imported QSOs ($duplicates duplicates skipped$unreadable)")
                    if (imported > 0) {
                        // Refresh backup + Documents mirror to reflect the merged log.
                        AdifAutoBackup.scheduleBackupAfterQso(getApplication(), logbook, settingsRepo)
                    }
                },
                onFailure = { t ->
                    notify(t.message ?: "ADIF import failed", SnackbarEvent.Tag.ERROR)
                },
            )
        }
    }
```

Also add the import for `AdifImportException`: `import net.ft8vc.data.adif.AdifImportException`.

Note: `settingsRepo.settings.first()` — confirm the settings data class field names (`myCall`, `myGrid`) match `LogViewModel.kt:53` usage (`settingsRepo.settings.first().myCall`). If the flow property has a different name in this file (`settingsRepo` is `SettingsRepository(app)` at `OperateViewModel.kt:67`), match whatever `LogViewModel` uses.

- [ ] **Step 2: Add the Settings button**

In `SettingsScreen.kt`, add imports:

```kotlin
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
```

(Skip any that already exist.) Inside the composable, near the other `remember` declarations at the top of the Logbook section (or immediately before the Button at line ~278):

```kotlin
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> uri?.let(vm::importAdif) }
```

After the existing "Backup now" `Button(...) { Text("Backup now") }` block, add:

```kotlin
                OutlinedButton(
                    // .adi files have no registered MIME type; filter in the reader instead.
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Import ADIF…")
                }
```

And update the caption text below from
`"ADIF auto-exports after every QSO to app-private external storage."` to:

```kotlin
                Text(
                    "ADIF auto-exports after every QSO to app-private storage " +
                        "and Documents/ft8vc (survives uninstall).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
```

- [ ] **Step 3: Compile and run the full unit suites**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :data:test :core:test 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateViewModel.kt app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt
git commit -m "feat(app): Import ADIF button in Settings with merge/dup-skip

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Build + on-device verification

**Files:** none (verification only)

- [ ] **Step 1: Full build + all unit tests**

Run: `./gradlew assembleDebug :data:test :core:test :app:testDebugUnitTest 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Pre-install logbook safety pull (field phone)**

Per memory `connected-tests-wipe-app-data`: before anything that reinstalls, pull a copy:

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB shell "run-as net.ft8vc.debug cat databases/ft8vc.db" > /tmp/ft8vc-logbook-safety.db 2>/dev/null || true
$ADB shell "run-as net.ft8vc.debug ls files/ databases/"   # confirm actual DB filename; adjust if it differs
```

- [ ] **Step 3: Install and verify on device (installDebug preserves data)**

```bash
ANDROID_HOME=~/Library/Android/sdk ./gradlew installDebug
```

Manual checklist (walk the user through it or drive via adb):
1. Settings → Logbook → **Backup now** → snackbar "ADIF backup written".
2. `$ADB shell ls -la /sdcard/Documents/ft8vc/` → `ft8vc-logbook.adi` exists, size > 0.
3. `$ADB shell cat /sdcard/Documents/ft8vc/ft8vc-logbook.adi | head -5` → ADIF header + records match current log.
4. **Import ADIF…** → pick `Documents/ft8vc/ft8vc-logbook.adi` → snackbar "Imported 0 QSOs (N duplicates skipped)" (self-import is a no-op; N = current log size).
5. Log tab count unchanged after step 4.

- [ ] **Step 4: Uninstall-survival check (destructive to app data — get user OK first; the mirror + safety pull make it recoverable)**

```bash
$ADB uninstall net.ft8vc.debug
$ADB shell ls /sdcard/Documents/ft8vc/        # mirror file still present
ANDROID_HOME=~/Library/Android/sdk ./gradlew installDebug
```

Then in-app: Import ADIF… → pick the mirror file → all QSOs restored (imported N). Note: a fresh "Backup now" after this creates `ft8vc-logbook (1).adi` (previous file is unowned) — expected per spec.

- [ ] **Step 5: Commit any fixes found, then hand off**

Use superpowers:verification-before-completion, then superpowers:finishing-a-development-branch (target branch: `readiness`).
