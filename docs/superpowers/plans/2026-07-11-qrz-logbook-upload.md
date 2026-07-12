# QRZ Logbook Auto-Upload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Optional automatic upload of logged QSOs to the operator's QRZ.com logbook, with a quiet self-healing retry queue and Keystore-encrypted API key.

**Architecture:** A pure wire-format module (`QrzWire`) and a narrow-seam upload queue (`QrzUploadQueue` over `QrzQueueStore`) live in the `data` module and are fully JVM-testable with fakes. The `app` module adds `KeystoreCipher` (AndroidKeyStore AES-GCM), settings persistence, a `QrzUploadController` following the existing controller/slice pattern, wiring in `OperateViewModel`, and a Settings section. Spec: `docs/superpowers/specs/2026-07-11-qrz-logbook-upload-design.md`.

**Tech Stack:** Kotlin, Room, DataStore Preferences, `HttpURLConnection`, AndroidKeyStore, JUnit4 + kotlinx-coroutines-test.

## Global Constraints

- No new top-level dependencies. Test-only deps from the existing version catalog (`libs.kotlinx.coroutines.test`) are allowed.
- Endpoint is exactly `https://logbook.qrz.com/api`; HTTPS only, 10 s connect/read timeouts.
- Upload failures are quiet: no snackbars, no Operate-screen UI. Only Settings shows status.
- The API key is never logged, never placed in error strings, never stored in plaintext.
- Existing rows migrate to `qrzUploadState = 'NOT_QUEUED'` and are never uploaded. ADIF imports also insert as `NOT_QUEUED`.
- RX/TX/CAT behavior untouched. TX license gating untouched.
- Kotlin official style, 4-space indent, no wildcard imports, one top-level public type per file (small related types in the same file are acceptable per existing codebase practice, e.g. `SettingsBridge.kt` holds `SettingsSlice`).
- JVM unit tests must pass locally (`./gradlew :data:testDebugUnitTest :app:testDebugUnitTest`). Instrumented tests (migration, KeystoreCipher) are written but only run on a device by the owner — do NOT run `connectedAndroidTest` (it wipes app data).

## Branch setup (before Task 1)

Work happens on a new `qrz-upload` branch off `unstable`, carrying the two spec/plan commits from `log-call-search`:

```bash
cd /Users/bsmirks/git/ft8vc
git checkout -b qrz-upload unstable
git cherry-pick ed79fc2 fc5b7e4   # QRZ spec + Keystore amendment
# plan commit gets cherry-picked too once it exists
```

---

### Task 1: QRZ wire format — parse responses, encode requests

**Files:**
- Create: `data/src/main/java/net/ft8vc/data/qrz/QrzWire.kt`
- Test: `data/src/test/java/net/ft8vc/data/qrz/QrzWireTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin + `java.net.URLEncoder/URLDecoder`).
- Produces:
  - `object QrzWire`
  - `fun QrzWire.parse(body: String): Map<String, String>` — uppercase keys, URL-decoded values.
  - `fun QrzWire.encodeForm(params: Map<String, String>): String` — `KEY=V&KEY2=V2`, values URL-encoded UTF-8.
  - `fun QrzWire.isDuplicateReason(reason: String?): Boolean`

QRZ's Logbook API (`https://logbook.qrz.com/api`) speaks `&`-delimited `KEY=VALUE` in both directions. Responses look like `RESULT=OK&LOGID=123&COUNT=1`, `RESULT=FAIL&REASON=Unable to add QSO to database: duplicate`, `RESULT=AUTH&REASON=invalid api key`. Values may contain URL-escapes.

- [x] **Step 1: Write the failing tests**

```kotlin
package net.ft8vc.data.qrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrzWireTest {

    @Test
    fun parse_okStatusResponse() {
        val fields = QrzWire.parse("RESULT=OK&CALLSIGN=W0DEV&COUNT=42")
        assertEquals("OK", fields["RESULT"])
        assertEquals("W0DEV", fields["CALLSIGN"])
        assertEquals("42", fields["COUNT"])
    }

    @Test
    fun parse_failWithSpacesAndEscapes() {
        val fields = QrzWire.parse("RESULT=FAIL&REASON=Unable%20to%20add%20QSO")
        assertEquals("FAIL", fields["RESULT"])
        assertEquals("Unable to add QSO", fields["REASON"])
    }

    @Test
    fun parse_literalSpacesSurvive() {
        val fields = QrzWire.parse("RESULT=AUTH&REASON=invalid api key")
        assertEquals("invalid api key", fields["REASON"])
    }

    @Test
    fun parse_lowercaseKeysNormalized() {
        assertEquals("OK", QrzWire.parse("result=OK")["RESULT"])
    }

    @Test
    fun parse_malformedPiecesIgnored() {
        val fields = QrzWire.parse("garbage&RESULT=OK&=nokey&novalue=")
        assertEquals("OK", fields["RESULT"])
        assertEquals("", fields["NOVALUE"])
        assertFalse(fields.containsKey("GARBAGE"))
    }

    @Test
    fun parse_htmlErrorPageYieldsNoResult() {
        val fields = QrzWire.parse("<html><body>502 Bad Gateway</body></html>")
        assertFalse(fields.containsKey("RESULT"))
    }

    @Test
    fun encodeForm_escapesAdifPayload() {
        val body = QrzWire.encodeForm(
            linkedMapOf(
                "KEY" to "ABCD-1234",
                "ACTION" to "INSERT",
                "ADIF" to "<call:5>K1ABC <eor>",
            ),
        )
        assertEquals("KEY=ABCD-1234&ACTION=INSERT&ADIF=%3Ccall%3A5%3EK1ABC+%3Ceor%3E", body)
    }

    @Test
    fun isDuplicateReason_matchesQrzDuplicateText() {
        assertTrue(QrzWire.isDuplicateReason("Unable to add QSO to database: duplicate"))
        assertTrue(QrzWire.isDuplicateReason("DUPLICATE entry"))
        assertFalse(QrzWire.isDuplicateReason("invalid api key"))
        assertFalse(QrzWire.isDuplicateReason(null))
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `./gradlew :data:testDebugUnitTest --tests "net.ft8vc.data.qrz.QrzWireTest"`
Expected: compilation failure — `QrzWire` unresolved.

- [x] **Step 3: Write the implementation**

```kotlin
package net.ft8vc.data.qrz

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * QRZ Logbook API wire format: `&`-delimited KEY=VALUE pairs in both
 * directions (https://logbook.qrz.com/api). Pure functions, JVM-testable.
 */
object QrzWire {

    /** Parse a response body into uppercase-keyed, URL-decoded fields. */
    fun parse(body: String): Map<String, String> =
        body.split('&').mapNotNull { piece ->
            val eq = piece.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val key = piece.substring(0, eq).trim().uppercase()
            val value = try {
                URLDecoder.decode(piece.substring(eq + 1), Charsets.UTF_8.name())
            } catch (_: IllegalArgumentException) {
                piece.substring(eq + 1)
            }
            key to value
        }.toMap()

    /** Encode request params as a form body; values URL-encoded UTF-8. */
    fun encodeForm(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, Charsets.UTF_8.name())}"
        }

    /** QRZ rejects re-inserts with a REASON containing "duplicate". */
    fun isDuplicateReason(reason: String?): Boolean =
        reason?.contains("duplicate", ignoreCase = true) == true
}
```

- [x] **Step 4: Run tests to verify they pass**

Run: `./gradlew :data:testDebugUnitTest --tests "net.ft8vc.data.qrz.QrzWireTest"`
Expected: BUILD SUCCESSFUL, 8 tests pass.

- [x] **Step 5: Commit**

```bash
git add data/src/main/java/net/ft8vc/data/qrz/QrzWire.kt data/src/test/java/net/ft8vc/data/qrz/QrzWireTest.kt
git commit -m "feat(data): QRZ logbook API wire format parse/encode"
```

---

### Task 2: QrzClient interface + HTTP implementation

**Files:**
- Create: `data/src/main/java/net/ft8vc/data/qrz/QrzClient.kt`
- Create: `data/src/main/java/net/ft8vc/data/qrz/HttpQrzClient.kt`
- Test: `data/src/test/java/net/ft8vc/data/qrz/QrzOutcomeTest.kt`

**Interfaces:**
- Consumes: `QrzWire.parse`, `QrzWire.encodeForm`, `QrzWire.isDuplicateReason` (Task 1).
- Produces:
  - `sealed interface QrzOutcome` with `data class Success(val callsign: String?, val count: Int?) : QrzOutcome` and `data class Failure(val message: String) : QrzOutcome`
  - `interface QrzClient { suspend fun status(apiKey: String): QrzOutcome; suspend fun insert(apiKey: String, adifRecord: String): QrzOutcome }`
  - `fun interpretResponse(fields: Map<String, String>): QrzOutcome` (top-level in `QrzClient.kt`, used by tests and `HttpQrzClient`) — `RESULT=OK` → Success; `RESULT=FAIL` with duplicate reason → Success; anything else → Failure with the reason (or a generic message).
  - `class HttpQrzClient : QrzClient` — real HTTP, constructor `HttpQrzClient(private val endpoint: String = "https://logbook.qrz.com/api")`.

The interpretation logic is pure and unit-tested; the HTTP shell is deliberately thin (verified on device by the owner via the Test button).

- [x] **Step 1: Write the failing tests**

```kotlin
package net.ft8vc.data.qrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrzOutcomeTest {

    @Test
    fun ok_isSuccessWithStatusFields() {
        val outcome = interpretResponse(mapOf("RESULT" to "OK", "CALLSIGN" to "W0DEV", "COUNT" to "42"))
        assertEquals(QrzOutcome.Success(callsign = "W0DEV", count = 42), outcome)
    }

    @Test
    fun ok_withoutStatusFieldsIsSuccess() {
        assertEquals(QrzOutcome.Success(null, null), interpretResponse(mapOf("RESULT" to "OK", "LOGID" to "9")))
    }

    @Test
    fun duplicateFail_isSuccess() {
        val outcome = interpretResponse(
            mapOf("RESULT" to "FAIL", "REASON" to "Unable to add QSO to database: duplicate"),
        )
        assertTrue(outcome is QrzOutcome.Success)
    }

    @Test
    fun authFailure_isFailureWithReason() {
        val outcome = interpretResponse(mapOf("RESULT" to "AUTH", "REASON" to "invalid api key"))
        assertEquals(QrzOutcome.Failure("invalid api key"), outcome)
    }

    @Test
    fun fail_withoutReasonGetsGenericMessage() {
        assertEquals(QrzOutcome.Failure("QRZ rejected the request"), interpretResponse(mapOf("RESULT" to "FAIL")))
    }

    @Test
    fun missingResult_isFailure() {
        assertEquals(QrzOutcome.Failure("Unrecognized response from QRZ"), interpretResponse(emptyMap()))
    }

    @Test
    fun nonNumericCount_isNull() {
        val outcome = interpretResponse(mapOf("RESULT" to "OK", "COUNT" to "n/a"))
        assertEquals(QrzOutcome.Success(null, null), outcome)
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `./gradlew :data:testDebugUnitTest --tests "net.ft8vc.data.qrz.QrzOutcomeTest"`
Expected: compilation failure — `interpretResponse` / `QrzOutcome` unresolved.

- [x] **Step 3: Write `QrzClient.kt`**

```kotlin
package net.ft8vc.data.qrz

/** Result of one QRZ Logbook API call. Duplicate inserts count as [Success]. */
sealed interface QrzOutcome {
    data class Success(val callsign: String?, val count: Int?) : QrzOutcome
    data class Failure(val message: String) : QrzOutcome
}

/** QRZ Logbook API operations used by the upload queue and the Test button. */
interface QrzClient {
    suspend fun status(apiKey: String): QrzOutcome
    suspend fun insert(apiKey: String, adifRecord: String): QrzOutcome
}

/** Map parsed response fields to an outcome (RESULT=OK, duplicate FAIL → Success). */
fun interpretResponse(fields: Map<String, String>): QrzOutcome {
    val reason = fields["REASON"]
    return when (fields["RESULT"]?.uppercase()) {
        "OK" -> QrzOutcome.Success(fields["CALLSIGN"], fields["COUNT"]?.toIntOrNull())
        "FAIL", "AUTH" ->
            if (QrzWire.isDuplicateReason(reason)) QrzOutcome.Success(null, null)
            else QrzOutcome.Failure(reason ?: "QRZ rejected the request")
        else -> QrzOutcome.Failure("Unrecognized response from QRZ")
    }
}
```

- [x] **Step 4: Write `HttpQrzClient.kt`**

```kotlin
package net.ft8vc.data.qrz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Real HTTP transport for the QRZ Logbook API. Form-encoded POST over
 * HTTPS, 10 s timeouts. Never logs or echoes the API key.
 */
class HttpQrzClient(
    private val endpoint: String = "https://logbook.qrz.com/api",
) : QrzClient {

    override suspend fun status(apiKey: String): QrzOutcome =
        post(linkedMapOf("KEY" to apiKey, "ACTION" to "STATUS"))

    override suspend fun insert(apiKey: String, adifRecord: String): QrzOutcome =
        post(linkedMapOf("KEY" to apiKey, "ACTION" to "INSERT", "ADIF" to adifRecord))

    private suspend fun post(params: Map<String, String>): QrzOutcome =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.doOutput = true
                    connection.setRequestProperty(
                        "Content-Type", "application/x-www-form-urlencoded",
                    )
                    connection.outputStream.use {
                        it.write(QrzWire.encodeForm(params).toByteArray(Charsets.UTF_8))
                    }
                    val code = connection.responseCode
                    if (code != HttpURLConnection.HTTP_OK) {
                        return@withContext QrzOutcome.Failure("QRZ returned HTTP $code")
                    }
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    interpretResponse(QrzWire.parse(body))
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                QrzOutcome.Failure(e.message ?: "Network error")
            }
        }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
```

- [x] **Step 5: Run tests to verify they pass**

Run: `./gradlew :data:testDebugUnitTest --tests "net.ft8vc.data.qrz.QrzOutcomeTest"`
Expected: BUILD SUCCESSFUL, 7 tests pass.

- [x] **Step 6: Commit**

```bash
git add data/src/main/java/net/ft8vc/data/qrz/QrzClient.kt data/src/main/java/net/ft8vc/data/qrz/HttpQrzClient.kt data/src/test/java/net/ft8vc/data/qrz/QrzOutcomeTest.kt
git commit -m "feat(data): QrzClient interface + HttpURLConnection implementation"
```

---

### Task 3: DB schema v3 — qrzUploadState column, DAO queries, pending-at-insert

**Files:**
- Modify: `data/src/main/java/net/ft8vc/data/db/QsoEntity.kt`
- Modify: `data/src/main/java/net/ft8vc/data/db/Ft8vcDatabase.kt`
- Modify: `data/src/main/java/net/ft8vc/data/db/QsoDao.kt`
- Modify: `data/src/main/java/net/ft8vc/data/Logbook.kt`
- Modify: call site `app/src/main/java/net/ft8vc/app/OperateViewModel.kt:983` (only if compilation requires; the new `log` parameter has a default so no call-site change is expected)
- Test: `data/src/androidTest/java/net/ft8vc/data/db/MigrationTest.kt` (add test; written, run on device later)

**Interfaces:**
- Consumes: existing Room setup.
- Produces:
  - `object QrzUploadState { const val NOT_QUEUED = "NOT_QUEUED"; const val PENDING = "PENDING"; const val UPLOADED = "UPLOADED" }` (in `QsoEntity.kt`)
  - `QsoEntity.qrzUploadState: String = QrzUploadState.NOT_QUEUED`
  - `QsoDao.qrzPending(): List<QsoEntity>` (oldest first), `QsoDao.qrzPendingCount(): Int`, `QsoDao.markQrzUploaded(id: Long)`
  - `Logbook.log(contact: QsoContact, qrzPending: Boolean = false): Long`
  - `Ft8vcDatabase.MIGRATION_2_3`, `@Database(version = 3)`

- [x] **Step 1: Add the migration test (device-run later, but written first)**

Append to `data/src/androidTest/java/net/ft8vc/data/db/MigrationTest.kt` inside the class:

```kotlin
    @Test
    fun migrate2To3_existingRowsBecomeNotQueued() {
        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                "INSERT INTO qso_contacts " +
                    "(utcMillis, myCall, myGrid, dxCall, dxGrid, rstSent, rstRcvd, freqHz, mode, band, notes, potaParkRefs) " +
                    "VALUES (1700000000000, 'W0DEV', 'EM26', 'K1ABC', 'FN42', -8, -15, 14074000, 'FT8', '20m', '', NULL)",
            )
        }
        helper.runMigrationsAndValidate(DB_NAME, 3, true, Ft8vcDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT dxCall, qrzUploadState FROM qso_contacts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("K1ABC", cursor.getString(0))
                assertEquals("NOT_QUEUED", cursor.getString(1))
            }
        }
    }
```

- [x] **Step 2: Entity + upload-state constants**

In `QsoEntity.kt`, add below the imports and add the field to the data class:

```kotlin
/** Values for [QsoEntity.qrzUploadState] (spec 2026-07-11-qrz-logbook-upload). */
object QrzUploadState {
    /** Logged before QRZ upload was enabled (or imported) — never uploaded. */
    const val NOT_QUEUED = "NOT_QUEUED"
    const val PENDING = "PENDING"
    const val UPLOADED = "UPLOADED"
}
```

and in the `QsoEntity` constructor after `potaParkRefs`:

```kotlin
    val qrzUploadState: String = QrzUploadState.NOT_QUEUED,
```

- [x] **Step 3: Database version + migration**

In `Ft8vcDatabase.kt`: change `version = 2` to `version = 3`; add after `MIGRATION_1_2`:

```kotlin
        /** v2 → v3: per-QSO QRZ upload state (spec 2026-07-11-qrz-logbook-upload). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE qso_contacts ADD COLUMN qrzUploadState TEXT NOT NULL DEFAULT 'NOT_QUEUED'",
                )
            }
        }
```

and change the builder line to `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`.

- [x] **Step 4: DAO queries**

Append to `QsoDao`:

```kotlin
    @Query("SELECT * FROM qso_contacts WHERE qrzUploadState = 'PENDING' ORDER BY utcMillis ASC")
    suspend fun qrzPending(): List<QsoEntity>

    @Query("SELECT COUNT(*) FROM qso_contacts WHERE qrzUploadState = 'PENDING'")
    suspend fun qrzPendingCount(): Int

    @Query("UPDATE qso_contacts SET qrzUploadState = 'UPLOADED' WHERE id = :id")
    suspend fun markQrzUploaded(id: Long)
```

- [x] **Step 5: `Logbook.log` gains `qrzPending`**

In `Logbook.kt`:
- Interface: `suspend fun log(contact: QsoContact, qrzPending: Boolean = false): Long`
- `RoomLogbook.log`:

```kotlin
    override suspend fun log(contact: QsoContact, qrzPending: Boolean): Long =
        dao.insert(
            contact.toEntity().copy(
                qrzUploadState = if (qrzPending) QrzUploadState.PENDING else QrzUploadState.NOT_QUEUED,
            ),
        )
```

- Add import `net.ft8vc.data.db.QrzUploadState`.
- Change the private `QsoEntity.toContact()` extension into a public top-level extension so the queue store (Task 4) can reuse it: move it out of `RoomLogbook` to the bottom of `Logbook.kt` as

```kotlin
/** Room entity → domain model (shared with the QRZ queue store). */
fun QsoEntity.toContact() = QsoContact(
    id = id,
    utcMillis = utcMillis,
    myCall = myCall,
    myGrid = myGrid,
    dxCall = dxCall,
    dxGrid = dxGrid,
    rstSent = rstSent,
    rstRcvd = rstRcvd,
    freqHz = freqHz,
    mode = mode,
    band = band,
    notes = notes,
    potaParkRefs = potaParkRefs,
)
```

(`toEntity()` stays private inside `RoomLogbook`; `importContacts` continues to insert with the entity default `NOT_QUEUED` — imports are historical data.)

- [x] **Step 6: Build + run data unit tests**

Run: `./gradlew :data:testDebugUnitTest :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (Room KSP regenerates schema `3.json`; existing tests still pass; the `OperateViewModel` call site compiles unchanged thanks to the default parameter).

- [x] **Step 7: Commit**

```bash
git add data/src/main data/src/androidTest data/schemas
git commit -m "feat(data): qrzUploadState column, v3 migration, pending-at-insert"
```

---

### Task 4: QrzUploadQueue with narrow store seam

**Files:**
- Create: `data/src/main/java/net/ft8vc/data/qrz/QrzQueueStore.kt`
- Create: `data/src/main/java/net/ft8vc/data/qrz/QrzUploadQueue.kt`
- Modify: `data/build.gradle.kts` (add `testImplementation(libs.kotlinx.coroutines.test)`)
- Test: `data/src/test/java/net/ft8vc/data/qrz/QrzUploadQueueTest.kt`

**Interfaces:**
- Consumes: `QrzClient`, `QrzOutcome` (Task 2); `QsoDao.qrzPending/qrzPendingCount/markQrzUploaded`, `QsoEntity.toContact()` (Task 3); `AdifWriter.record(contact)`.
- Produces:
  - `interface QrzQueueStore { suspend fun pending(): List<QsoContact>; suspend fun pendingCount(): Int; suspend fun markUploaded(id: Long) }`
  - `class RoomQrzQueueStore(private val dao: QsoDao) : QrzQueueStore` (same file as the interface)
  - `data class QrzQueueStatus(val uploading: Boolean = false, val pendingCount: Int = 0, val lastError: String? = null)`
  - `class QrzUploadQueue(store, client)` with `val status: StateFlow<QrzQueueStatus>`, `suspend fun flush(apiKey: String)`, `suspend fun refreshPendingCount()`

Queue rules (all tested): flush is mutex-serialized; uploads oldest-first; each success marks the row uploaded and decrements pending; first `Failure` stops the pass and records `lastError`; a fully successful pass clears `lastError`; blank key is a no-op.

- [x] **Step 1: Add the test dependency**

In `data/build.gradle.kts` dependencies block, after `testImplementation(libs.junit)`:

```kotlin
    testImplementation(libs.kotlinx.coroutines.test)
```

- [x] **Step 2: Write the failing tests**

```kotlin
package net.ft8vc.data.qrz

import kotlinx.coroutines.test.runTest
import net.ft8vc.data.model.QsoContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun contact(id: Long, utc: Long) = QsoContact(
    id = id, utcMillis = utc, myCall = "W0DEV", myGrid = "EM26",
    dxCall = "K1ABC", dxGrid = "FN42", rstSent = -8, rstRcvd = -15,
    freqHz = 14_074_000, mode = "FT8", band = "20m",
)

private class FakeStore(contacts: List<QsoContact>) : QrzQueueStore {
    val rows = contacts.toMutableList()
    val uploaded = mutableListOf<Long>()
    override suspend fun pending() = rows.sortedBy { it.utcMillis }
    override suspend fun pendingCount() = rows.size
    override suspend fun markUploaded(id: Long) {
        uploaded += id
        rows.removeAll { it.id == id }
    }
}

private class ScriptedClient(private val outcomes: MutableList<QrzOutcome>) : QrzClient {
    val inserted = mutableListOf<String>()
    override suspend fun status(apiKey: String): QrzOutcome = outcomes.removeAt(0)
    override suspend fun insert(apiKey: String, adifRecord: String): QrzOutcome {
        inserted += adifRecord
        return outcomes.removeAt(0)
    }
}

class QrzUploadQueueTest {

    @Test
    fun flush_uploadsOldestFirstAndMarksUploaded() = runTest {
        val store = FakeStore(listOf(contact(2, 2000), contact(1, 1000)))
        val client = ScriptedClient(
            mutableListOf(QrzOutcome.Success(null, null), QrzOutcome.Success(null, null)),
        )
        val queue = QrzUploadQueue(store, client)
        queue.flush("KEY")
        assertEquals(listOf(1L, 2L), store.uploaded)
        assertEquals(0, queue.status.value.pendingCount)
        assertNull(queue.status.value.lastError)
    }

    @Test
    fun flush_stopsAtFirstFailureAndRecordsError() = runTest {
        val store = FakeStore(listOf(contact(1, 1000), contact(2, 2000)))
        val client = ScriptedClient(mutableListOf(QrzOutcome.Failure("invalid api key")))
        val queue = QrzUploadQueue(store, client)
        queue.flush("KEY")
        assertEquals(emptyList<Long>(), store.uploaded)
        assertEquals(1, client.inserted.size)
        assertEquals("invalid api key", queue.status.value.lastError)
        assertEquals(2, queue.status.value.pendingCount)
    }

    @Test
    fun flush_successAfterFailureClearsError() = runTest {
        val store = FakeStore(listOf(contact(1, 1000)))
        val client = ScriptedClient(
            mutableListOf(QrzOutcome.Failure("boom"), QrzOutcome.Success(null, null)),
        )
        val queue = QrzUploadQueue(store, client)
        queue.flush("KEY")
        assertEquals("boom", queue.status.value.lastError)
        queue.flush("KEY")
        assertNull(queue.status.value.lastError)
        assertEquals(0, queue.status.value.pendingCount)
    }

    @Test
    fun flush_blankKeyIsNoOp() = runTest {
        val store = FakeStore(listOf(contact(1, 1000)))
        val client = ScriptedClient(mutableListOf())
        val queue = QrzUploadQueue(store, client)
        queue.flush("  ")
        assertEquals(0, client.inserted.size)
        assertEquals(1, queue.status.value.pendingCount)
    }

    @Test
    fun flush_emptyQueueTouchesNothing() = runTest {
        val store = FakeStore(emptyList())
        val client = ScriptedClient(mutableListOf())
        val queue = QrzUploadQueue(store, client)
        queue.flush("KEY")
        assertEquals(0, client.inserted.size)
        assertNull(queue.status.value.lastError)
    }

    @Test
    fun refreshPendingCount_reflectsStore() = runTest {
        val store = FakeStore(listOf(contact(1, 1000), contact(2, 2000)))
        val queue = QrzUploadQueue(store, ScriptedClient(mutableListOf()))
        queue.refreshPendingCount()
        assertEquals(2, queue.status.value.pendingCount)
    }

    @Test
    fun flush_uploadsAdifRecordPayload() = runTest {
        val store = FakeStore(listOf(contact(1, 1000)))
        val client = ScriptedClient(mutableListOf(QrzOutcome.Success(null, null)))
        QrzUploadQueue(store, client).flush("KEY")
        val payload = client.inserted.single()
        // AdifWriter.record output: field tags + <EOR>
        org.junit.Assert.assertTrue(payload.contains("<EOR>", ignoreCase = true))
        org.junit.Assert.assertTrue(payload.contains("K1ABC"))
    }
}
```

- [x] **Step 3: Run tests to verify they fail**

Run: `./gradlew :data:testDebugUnitTest --tests "net.ft8vc.data.qrz.QrzUploadQueueTest"`
Expected: compilation failure — `QrzQueueStore` / `QrzUploadQueue` unresolved.

- [x] **Step 4: Write `QrzQueueStore.kt`**

```kotlin
package net.ft8vc.data.qrz

import net.ft8vc.data.db.QsoDao
import net.ft8vc.data.model.QsoContact
import net.ft8vc.data.toContact

/** Narrow persistence seam for the QRZ upload queue (fake-able in JVM tests). */
interface QrzQueueStore {
    /** QSOs awaiting upload, oldest first. */
    suspend fun pending(): List<QsoContact>
    suspend fun pendingCount(): Int
    suspend fun markUploaded(id: Long)
}

class RoomQrzQueueStore(private val dao: QsoDao) : QrzQueueStore {
    override suspend fun pending(): List<QsoContact> = dao.qrzPending().map { it.toContact() }
    override suspend fun pendingCount(): Int = dao.qrzPendingCount()
    override suspend fun markUploaded(id: Long) = dao.markQrzUploaded(id)
}
```

- [x] **Step 5: Write `QrzUploadQueue.kt`**

```kotlin
package net.ft8vc.data.qrz

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.ft8vc.data.adif.AdifWriter

/** Observable queue state for the Settings UI (warning icon + error text). */
data class QrzQueueStatus(
    val uploading: Boolean = false,
    val pendingCount: Int = 0,
    val lastError: String? = null,
    /** Completed flush passes; lets observers react exactly once per pass. */
    val flushCount: Int = 0,
)

/**
 * Uploads PENDING QSOs to QRZ oldest-first. One flush at a time (mutex);
 * the pass stops at the first failure so a dead network costs one request.
 * Quiet by design: failures only surface through [status].
 */
class QrzUploadQueue(
    private val store: QrzQueueStore,
    private val client: QrzClient,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(QrzQueueStatus())
    val status: StateFlow<QrzQueueStatus> = _status.asStateFlow()

    suspend fun refreshPendingCount() {
        _status.update { it.copy(pendingCount = store.pendingCount()) }
    }

    suspend fun flush(apiKey: String) {
        if (apiKey.isBlank()) return
        mutex.withLock {
            val pending = store.pending()
            _status.update { it.copy(uploading = true, pendingCount = pending.size) }
            var error: String? = null
            for (contact in pending) {
                when (val outcome = client.insert(apiKey, AdifWriter.record(contact))) {
                    is QrzOutcome.Success -> {
                        store.markUploaded(contact.id)
                        _status.update { it.copy(pendingCount = store.pendingCount()) }
                    }
                    is QrzOutcome.Failure -> {
                        error = outcome.message
                        break
                    }
                }
            }
            _status.update {
                it.copy(
                    uploading = false,
                    pendingCount = store.pendingCount(),
                    lastError = error,
                    flushCount = it.flushCount + 1,
                )
            }
        }
    }
}
```

- [x] **Step 6: Run tests to verify they pass**

Run: `./gradlew :data:testDebugUnitTest --tests "net.ft8vc.data.qrz.QrzUploadQueueTest"`
Expected: BUILD SUCCESSFUL, 7 tests pass.

- [x] **Step 7: Commit**

```bash
git add data/src/main/java/net/ft8vc/data/qrz data/src/test/java/net/ft8vc/data/qrz/QrzUploadQueueTest.kt data/build.gradle.kts
git commit -m "feat(data): QrzUploadQueue — mutex-serialized oldest-first flush"
```

---

### Task 5: KeystoreCipher (app module)

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/settings/KeystoreCipher.kt`
- Test: `app/src/androidTest/java/net/ft8vc/app/settings/KeystoreCipherTest.kt` (written; device-run by owner)

**Interfaces:**
- Consumes: `java.security.KeyStore("AndroidKeyStore")`, `javax.crypto`.
- Produces:
  - `interface QrzKeyCipher { fun encrypt(plaintext: String): String?; fun decrypt(encoded: String): String? }` (seam for JVM tests of the controller)
  - `object KeystoreCipher : QrzKeyCipher` — AES-256-GCM under alias `qrz_api_key`; stores `Base64(iv + ciphertext)`; every failure path returns null (spec: decrypt failure = "no key configured").

- [x] **Step 1: Write the instrumented test (run later on device)**

```kotlin
package net.ft8vc.app.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreCipherTest {

    @Test
    fun encryptDecrypt_roundTrips() {
        val encoded = KeystoreCipher.encrypt("ABCD-1234-EFGH-5678")
        checkNotNull(encoded)
        assertEquals("ABCD-1234-EFGH-5678", KeystoreCipher.decrypt(encoded))
    }

    @Test
    fun encrypt_producesFreshCiphertextEachTime() {
        val a = KeystoreCipher.encrypt("same")
        val b = KeystoreCipher.encrypt("same")
        org.junit.Assert.assertNotEquals(a, b) // fresh IV per encryption
    }

    @Test
    fun decrypt_garbageReturnsNull() {
        assertNull(KeystoreCipher.decrypt("not base64 !!!"))
        assertNull(KeystoreCipher.decrypt("aGVsbG8="))
    }
}
```

- [x] **Step 2: Write the implementation**

```kotlin
package net.ft8vc.app.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypt/decrypt seam so the QRZ controller is JVM-testable with a fake. */
interface QrzKeyCipher {
    /** Base64(iv + ciphertext), or null if the Keystore is unavailable. */
    fun encrypt(plaintext: String): String?

    /** Plaintext, or null on any failure (missing key, restored backup, garbage). */
    fun decrypt(encoded: String): String?
}

/**
 * AES-256-GCM under an AndroidKeyStore key (alias "qrz_api_key"). The key is
 * non-exportable and never leaves the device, so ciphertext restored from a
 * cloud backup onto another device simply decrypts to null — the feature
 * degrades to "no key configured" instead of leaking the API key.
 */
object KeystoreCipher : QrzKeyCipher {

    private const val ALIAS = "qrz_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    override fun encrypt(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }.getOrNull()

    override fun decrypt(encoded: String): String? = runCatching {
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        require(blob.size > GCM_IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            obtainKey(),
            GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES),
        )
        String(cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
```

- [x] **Step 3: Compile check**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/KeystoreCipher.kt app/src/androidTest/java/net/ft8vc/app/settings/KeystoreCipherTest.kt
git commit -m "feat(app): AndroidKeyStore AES-GCM cipher for the QRZ API key"
```

---

### Task 6: Settings persistence — enabled flag, encrypted key, last error

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt`
- Modify: `app/src/main/java/net/ft8vc/app/settings/StationSettings.kt`

**Interfaces:**
- Consumes: `QrzKeyCipher` / `KeystoreCipher` (Task 5).
- Produces:
  - `StationSettings.qrzUploadEnabled: Boolean = false`, `StationSettings.qrzApiKeyEncrypted: String? = null`, `StationSettings.qrzLastError: String? = null`
  - `SettingsRepository.setQrzUploadEnabled(enabled: Boolean)`
  - `SettingsRepository.setQrzApiKey(plaintext: String)` — trims; blank removes the pref; otherwise stores `KeystoreCipher.encrypt(...)` (a null encrypt result also removes — quiet degradation)
  - `SettingsRepository.setQrzLastError(message: String?)`

- [x] **Step 1: StationSettings fields** — add after `lastAdifBackupAtMs`:

```kotlin
    /** Auto-upload logged QSOs to QRZ.com (spec 2026-07-11-qrz-logbook-upload). */
    val qrzUploadEnabled: Boolean = false,
    /** Base64(iv+ciphertext) of the QRZ API key, AndroidKeyStore-encrypted; null = not set. */
    val qrzApiKeyEncrypted: String? = null,
    /** Last QRZ upload/test failure; persisted so the Settings warning survives restart. */
    val qrzLastError: String? = null,
```

- [x] **Step 2: Repository keys, mapping, setters**

In `SettingsRepository.Keys` add:

```kotlin
        val QRZ_UPLOAD_ENABLED = booleanPreferencesKey("qrz_upload_enabled")
        val QRZ_API_KEY_ENC = stringPreferencesKey("qrz_api_key_enc")
        val QRZ_LAST_ERROR = stringPreferencesKey("qrz_last_error")
```

In the `settings` flow mapping add (before `.withRigProfileApplied()`):

```kotlin
            qrzUploadEnabled = prefs[Keys.QRZ_UPLOAD_ENABLED] ?: false,
            qrzApiKeyEncrypted = prefs[Keys.QRZ_API_KEY_ENC],
            qrzLastError = prefs[Keys.QRZ_LAST_ERROR],
```

New setters (near `setLastAdifBackupAtMs`):

```kotlin
    suspend fun setQrzUploadEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.QRZ_UPLOAD_ENABLED] = enabled }
    }

    /** Encrypts via AndroidKeyStore before persisting; blank clears the key. */
    suspend fun setQrzApiKey(plaintext: String, cipher: QrzKeyCipher = KeystoreCipher) {
        val encrypted = plaintext.trim().takeIf { it.isNotEmpty() }?.let(cipher::encrypt)
        appContext.settingsDataStore.edit {
            if (encrypted == null) it.remove(Keys.QRZ_API_KEY_ENC)
            else it[Keys.QRZ_API_KEY_ENC] = encrypted
        }
    }

    suspend fun setQrzLastError(message: String?) {
        appContext.settingsDataStore.edit {
            if (message == null) it.remove(Keys.QRZ_LAST_ERROR)
            else it[Keys.QRZ_LAST_ERROR] = message
        }
    }
```

- [x] **Step 3: Compile + full app unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (pattern-following persistence code; behavior covered via controller tests in Task 7).

- [x] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt app/src/main/java/net/ft8vc/app/settings/StationSettings.kt
git commit -m "feat(app): QRZ settings — enabled flag, Keystore-encrypted key, last error"
```

---

### Task 7: QrzUploadController (app module, TDD)

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/controllers/QrzUploadController.kt`
- Test: `app/src/test/java/net/ft8vc/app/controllers/QrzUploadControllerTest.kt`

**Interfaces:**
- Consumes: `QrzUploadQueue`, `QrzQueueStatus` (Task 4); `QrzClient`, `QrzOutcome` (Task 2); `QrzKeyCipher` (Task 5); `StationSettings` QRZ fields (Task 6).
- Produces (all in `QrzUploadController.kt`):

```kotlin
sealed interface QrzTestStatus {
    data object Idle : QrzTestStatus
    data object Testing : QrzTestStatus
    data class Passed(val message: String) : QrzTestStatus
    data class Failed(val message: String) : QrzTestStatus
}

data class QrzSlice(
    val enabled: Boolean = false,
    val apiKey: String = "",            // decrypted, for the masked settings field
    val warning: Boolean = false,       // enabled && lastError != null && pendingCount > 0
    val lastError: String? = null,
    val pendingCount: Int = 0,
    val testStatus: QrzTestStatus = QrzTestStatus.Idle,
)

class QrzUploadController(
    settings: Flow<StationSettings>,
    private val queue: QrzUploadQueue,
    private val client: QrzClient,
    private val cipher: QrzKeyCipher,
    private val setEnabledPref: suspend (Boolean) -> Unit,
    private val setApiKeyPref: suspend (String) -> Unit,
    private val persistLastError: suspend (String?) -> Unit,
    private val registerConnectivityListener: (onAvailable: () -> Unit) -> Unit,
    private val scope: CoroutineScope,
) {
    val slice: StateFlow<QrzSlice>
    fun onQsoLogged()          // flush trigger after a QSO insert
    fun setEnabled(enabled: Boolean)
    fun setApiKey(plaintext: String)
    fun testConnection()
    val isEnabled: Boolean     // convenience for the log() call site
}
```

Behavior:
- Collects `settings`: decrypts `qrzApiKeyEncrypted` (cache the decryption — only re-decrypt when the encrypted value changes), mirrors `qrzUploadEnabled`/`qrzLastError` into the slice, and on the **first** emission with `enabled && pendingCount > 0` triggers a startup flush (spec trigger 2).
- Combines queue `status` into the slice; persists `lastError` (or null) via `persistLastError` exactly once per completed flush pass (tracked via `QrzQueueStatus.flushCount`) — DataStore keeps the persisted error as the boot-time value, the queue's live value wins afterward.
- `setEnabled(true)` persists then flushes (trigger 3). `setEnabled(false)` persists and clears `testStatus`.
- `setApiKey` persists via `setApiKeyPref` (repository encrypts) and updates the in-memory key immediately so the text field doesn't lag.
- `testConnection()`: `Testing` → `client.status(key)` → `Passed("Connected — <callsign>, <count> QSOs")` (omitting nulls gracefully: fall back to `"Connected"`) + flush (trigger 4), or `Failed(reason)` + `persistLastError(reason)`.
- `registerConnectivityListener` is called once at init; the callback flushes when enabled (trigger 5).
- All flushes are `scope.launch { queue.flush(currentKey) }` — quiet, no exceptions escape (queue never throws).
- Warning rule: `enabled && lastError != null && pendingCount > 0` (per spec).

- [x] **Step 1: Write the failing tests**

```kotlin
package net.ft8vc.app.controllers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.ft8vc.app.settings.QrzKeyCipher
import net.ft8vc.app.settings.StationSettings
import net.ft8vc.data.model.QsoContact
import net.ft8vc.data.qrz.QrzClient
import net.ft8vc.data.qrz.QrzOutcome
import net.ft8vc.data.qrz.QrzQueueStore
import net.ft8vc.data.qrz.QrzUploadQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeCipher : QrzKeyCipher {
    override fun encrypt(plaintext: String) = "enc:$plaintext"
    override fun decrypt(encoded: String) = encoded.removePrefix("enc:").takeIf { encoded.startsWith("enc:") }
}

private class MemoryStore(contacts: List<QsoContact> = emptyList()) : QrzQueueStore {
    val rows = contacts.toMutableList()
    override suspend fun pending() = rows.sortedBy { it.utcMillis }
    override suspend fun pendingCount() = rows.size
    override suspend fun markUploaded(id: Long) { rows.removeAll { it.id == id } }
}

private class StubClient(
    var insertOutcome: QrzOutcome = QrzOutcome.Success(null, null),
    var statusOutcome: QrzOutcome = QrzOutcome.Success("W0DEV", 42),
) : QrzClient {
    var statusCalls = 0
    var insertCalls = 0
    override suspend fun status(apiKey: String): QrzOutcome { statusCalls++; return statusOutcome }
    override suspend fun insert(apiKey: String, adifRecord: String): QrzOutcome { insertCalls++; return insertOutcome }
}

private fun pendingContact(id: Long) = QsoContact(
    id = id, utcMillis = id * 1000, myCall = "W0DEV", myGrid = "EM26",
    dxCall = "K1ABC", dxGrid = null, rstSent = -8, rstRcvd = -15,
    freqHz = 14_074_000, mode = "FT8", band = "20m",
)

@OptIn(ExperimentalCoroutinesApi::class)
class QrzUploadControllerTest {

    private fun TestScope.buildController(
        settings: MutableStateFlow<StationSettings>,
        store: MemoryStore,
        client: StubClient,
        persisted: MutableList<String?> = mutableListOf(),
        onConnectivity: MutableList<() -> Unit> = mutableListOf(),
    ): QrzUploadController = QrzUploadController(
        settings = settings,
        queue = QrzUploadQueue(store, client),
        client = client,
        cipher = FakeCipher(),
        setEnabledPref = { settings.value = settings.value.copy(qrzUploadEnabled = it) },
        setApiKeyPref = { settings.value = settings.value.copy(qrzApiKeyEncrypted = "enc:$it") },
        persistLastError = { persisted += it },
        registerConnectivityListener = { onConnectivity += it },
        scope = this.backgroundScope,
    )

    @Test
    fun slice_decryptsApiKeyFromSettings() = runTest(StandardTestDispatcher()) {
        val settings = MutableStateFlow(StationSettings(qrzApiKeyEncrypted = "enc:ABCD-1234"))
        val controller = buildController(settings, MemoryStore(), StubClient())
        advanceUntilIdle()
        assertEquals("ABCD-1234", controller.slice.value.apiKey)
    }

    @Test
    fun startupWithPendingAndEnabled_flushes() = runTest(StandardTestDispatcher()) {
        val settings = MutableStateFlow(
            StationSettings(qrzUploadEnabled = true, qrzApiKeyEncrypted = "enc:K"),
        )
        val store = MemoryStore(listOf(pendingContact(1)))
        val client = StubClient()
        buildController(settings, store, client)
        advanceUntilIdle()
        assertEquals(1, client.insertCalls)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun onQsoLogged_flushesWhenEnabled() = runTest(StandardTestDispatcher()) {
        val settings = MutableStateFlow(
            StationSettings(qrzUploadEnabled = true, qrzApiKeyEncrypted = "enc:K"),
        )
        val store = MemoryStore()
        val client = StubClient()
        val controller = buildController(settings, store, client)
        advanceUntilIdle()
        store.rows += pendingContact(7)
        controller.onQsoLogged()
        advanceUntilIdle()
        assertEquals(1, client.insertCalls)
    }

    @Test
    fun failedUpload_setsWarningAndPersistsError() = runTest(StandardTestDispatcher()) {
        val settings = MutableStateFlow(
            StationSettings(qrzUploadEnabled = true, qrzApiKeyEncrypted = "enc:K"),
        )
        val store = MemoryStore(listOf(pendingContact(1)))
        val client = StubClient(insertOutcome = QrzOutcome.Failure("invalid api key"))
        val persisted = mutableListOf<String?>()
        val controller = buildController(settings, store, client, persisted)
        advanceUntilIdle()
        assertTrue(controller.slice.value.warning)
        assertEquals("invalid api key", controller.slice.value.lastError)
        assertEquals(listOf<String?>("invalid api key"), persisted)
    }

    @Test
    fun successfulFlush_clearsWarning() = runTest(StandardTestDispatcher()) {
        val settings = MutableStateFlow(
            StationSettings(qrzUploadEnabled = true, qrzApiKeyEncrypted = "enc:K"),
        )
        val store = MemoryStore(listOf(pendingContact(1)))
        val client = StubClient(insertOutcome = QrzOutcome.Failure("boom"))
        val controller = buildController(settings, store, client)
        advanceUntilIdle()
        assertTrue(controller.slice.value.warning)
        client.insertOutcome = QrzOutcome.Success(null, null)
        controller.onQsoLogged()
        advanceUntilIdle()
        assertFalse(controller.slice.value.warning)
        assertEquals(null, controller.slice.value.lastError)
    }

    @Test
    fun testConnection_success_showsCallsignAndCount() = runTest(StandardTestDispatcher()) {
        val settings = MutableStateFlow(StationSettings(qrzApiKeyEncrypted = "enc:K"))
        val controller = buildController(settings, MemoryStore(), StubClient())
        advanceUntilIdle()
        controller.testConnection()
        advanceUntilIdle()
        assertEquals(
            QrzTestStatus.Passed("Connected — W0DEV, 42 QSOs"),
            controller.slice.value.testStatus,
        )
    }

    @Test
    fun testConnection_failure_showsReason() = runTest(StandardTestDispatcher()) {
        val settings = MutableStateFlow(StationSettings(qrzApiKeyEncrypted = "enc:K"))
        val client = StubClient(statusOutcome = QrzOutcome.Failure("invalid api key"))
        val controller = buildController(settings, MemoryStore(), client)
        advanceUntilIdle()
        controller.testConnection()
        advanceUntilIdle()
        assertEquals(QrzTestStatus.Failed("invalid api key"), controller.slice.value.testStatus)
    }

    @Test
    fun connectivityReturn_flushesWhenEnabled() = runTest(StandardTestDispatcher()) {
        val settings = MutableStateFlow(
            StationSettings(qrzUploadEnabled = true, qrzApiKeyEncrypted = "enc:K"),
        )
        val store = MemoryStore()
        val client = StubClient()
        val callbacks = mutableListOf<() -> Unit>()
        buildController(settings, store, client, onConnectivity = callbacks)
        advanceUntilIdle()
        store.rows += pendingContact(3)
        callbacks.single().invoke()
        advanceUntilIdle()
        assertEquals(1, client.insertCalls)
    }

    @Test
    fun disabled_neverFlushes() = runTest(StandardTestDispatcher()) {
        val settings = MutableStateFlow(StationSettings(qrzApiKeyEncrypted = "enc:K"))
        val store = MemoryStore(listOf(pendingContact(1)))
        val client = StubClient()
        val controller = buildController(settings, store, client)
        advanceUntilIdle()
        controller.onQsoLogged()
        advanceUntilIdle()
        assertEquals(0, client.insertCalls)
        assertFalse(controller.slice.value.warning)
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.QrzUploadControllerTest"`
Expected: compilation failure — `QrzUploadController` unresolved. (If `libs.kotlinx.coroutines.test` is missing from `app/build.gradle.kts` testImplementation, add it — it is already in the version catalog.)

- [x] **Step 3: Write the implementation**

```kotlin
package net.ft8vc.app.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.ft8vc.app.settings.QrzKeyCipher
import net.ft8vc.app.settings.StationSettings
import net.ft8vc.data.qrz.QrzClient
import net.ft8vc.data.qrz.QrzOutcome
import net.ft8vc.data.qrz.QrzUploadQueue

/** Progress/result of the user-initiated "Test connection" button. */
sealed interface QrzTestStatus {
    data object Idle : QrzTestStatus
    data object Testing : QrzTestStatus
    data class Passed(val message: String) : QrzTestStatus
    data class Failed(val message: String) : QrzTestStatus
}

/** Everything the QRZ settings section renders. */
data class QrzSlice(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val warning: Boolean = false,
    val lastError: String? = null,
    val pendingCount: Int = 0,
    val testStatus: QrzTestStatus = QrzTestStatus.Idle,
)

/**
 * Owns QRZ upload triggers and settings-facing state
 * (spec 2026-07-11-qrz-logbook-upload). Quiet by design: failures surface
 * only through [slice] — never snackbars, never the Operate screen.
 */
class QrzUploadController(
    settings: Flow<StationSettings>,
    private val queue: QrzUploadQueue,
    private val client: QrzClient,
    private val cipher: QrzKeyCipher,
    private val setEnabledPref: suspend (Boolean) -> Unit,
    private val setApiKeyPref: suspend (String) -> Unit,
    private val persistLastError: suspend (String?) -> Unit,
    registerConnectivityListener: (onAvailable: () -> Unit) -> Unit,
    private val scope: CoroutineScope,
) {

    private val _slice = MutableStateFlow(QrzSlice())
    val slice: StateFlow<QrzSlice> = _slice.asStateFlow()

    val isEnabled: Boolean get() = _slice.value.enabled

    // Decryption cache: encrypted blob → plaintext (re-decrypt only on change).
    private var lastEncrypted: String? = null
    private var startupFlushDone = false
    private var bootError: String? = null
    private var queueTouched = false
    private var lastSeenFlushCount = 0

    init {
        scope.launch {
            settings.collect { s ->
                if (s.qrzApiKeyEncrypted != lastEncrypted) {
                    lastEncrypted = s.qrzApiKeyEncrypted
                    val key = s.qrzApiKeyEncrypted?.let(cipher::decrypt) ?: ""
                    _slice.update { it.copy(apiKey = key) }
                }
                bootError = s.qrzLastError
                _slice.update { it.copy(enabled = s.qrzUploadEnabled) }
                recomputeWarning()
                if (!startupFlushDone) {
                    startupFlushDone = true
                    queue.refreshPendingCount()
                    if (s.qrzUploadEnabled) flush()
                }
            }
        }
        scope.launch {
            queue.status.collect { q ->
                if (q.flushCount > lastSeenFlushCount) {
                    lastSeenFlushCount = q.flushCount
                    queueTouched = true
                    persistLastError(q.lastError)
                }
                _slice.update {
                    it.copy(pendingCount = q.pendingCount, lastError = liveError(q.lastError))
                }
                recomputeWarning()
            }
        }
        registerConnectivityListener {
            if (isEnabled) flush()
        }
    }

    /** Trigger 1: a QSO was just inserted as PENDING. */
    fun onQsoLogged() {
        if (isEnabled) flush()
    }

    fun setEnabled(enabled: Boolean) {
        scope.launch {
            setEnabledPref(enabled)
            if (enabled) flush() else _slice.update { it.copy(testStatus = QrzTestStatus.Idle) }
        }
    }

    fun setApiKey(plaintext: String) {
        _slice.update { it.copy(apiKey = plaintext) }
        scope.launch { setApiKeyPref(plaintext) }
    }

    fun testConnection() {
        val key = _slice.value.apiKey.trim()
        if (key.isEmpty()) {
            _slice.update { it.copy(testStatus = QrzTestStatus.Failed("Enter an API key first")) }
            return
        }
        _slice.update { it.copy(testStatus = QrzTestStatus.Testing) }
        scope.launch {
            when (val outcome = client.status(key)) {
                is QrzOutcome.Success -> {
                    val detail = listOfNotNull(
                        outcome.callsign,
                        outcome.count?.let { "$it QSOs" },
                    ).joinToString(", ")
                    val message = if (detail.isEmpty()) "Connected" else "Connected — $detail"
                    _slice.update { it.copy(testStatus = QrzTestStatus.Passed(message)) }
                    flush()
                }
                is QrzOutcome.Failure -> {
                    _slice.update { it.copy(testStatus = QrzTestStatus.Failed(outcome.message)) }
                    queueTouched = true
                    persistLastError(outcome.message)
                    _slice.update { it.copy(lastError = outcome.message) }
                    recomputeWarning()
                }
            }
        }
    }

    private fun flush() {
        scope.launch {
            queue.flush(_slice.value.apiKey.trim())
            queue.refreshPendingCount()
        }
    }

    /** Persisted boot error until the queue has run once; live queue error after. */
    private fun liveError(queueError: String?): String? =
        if (queueTouched) queueError else queueError ?: bootError

    private fun recomputeWarning() {
        _slice.update {
            it.copy(warning = it.enabled && it.lastError != null && it.pendingCount > 0)
        }
    }
}
```

- [x] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.QrzUploadControllerTest"`
Expected: BUILD SUCCESSFUL, 9 tests pass. Iterate on the controller (not the tests) if transitions misbehave.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/controllers/QrzUploadController.kt app/src/test/java/net/ft8vc/app/controllers/QrzUploadControllerTest.kt
git commit -m "feat(app): QrzUploadController — triggers, warning state, test connection"
```

---

### Task 8: Wire into OperateViewModel, UI state, manifest

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt`
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`

**Interfaces:**
- Consumes: `QrzUploadController`, `QrzSlice` (Task 7); `QrzUploadQueue`, `RoomQrzQueueStore`, `HttpQrzClient` (Tasks 2/4); `Logbook.log(contact, qrzPending)` (Task 3); repository setters (Task 6).
- Produces: `OperateUiState.qrz: QrzSlice = QrzSlice()`; VM pass-throughs `setQrzUploadEnabled`, `setQrzApiKey`, `testQrzConnection` for the Settings screen.

- [x] **Step 1: Manifest permissions** — next to `RECORD_AUDIO`:

```xml
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

(`ACCESS_NETWORK_STATE` is required by `registerDefaultNetworkCallback`; both are install-time normal permissions.)

- [x] **Step 2: UI state** — add to `OperateUiState` (import `net.ft8vc.app.controllers.QrzSlice`):

```kotlin
    /** QRZ auto-upload settings-section state (spec 2026-07-11-qrz-logbook-upload). */
    val qrz: QrzSlice = QrzSlice(),
```

- [x] **Step 3: ViewModel construction** — in `OperateViewModel`, near the `logbook` property:

```kotlin
    private val qrzClient = HttpQrzClient()
    private val qrzQueue = QrzUploadQueue(RoomQrzQueueStore(Ft8vcDatabase.get(app).qsoDao()), qrzClient)
    private val qrzController = QrzUploadController(
        settings = settingsRepo.settings,
        queue = qrzQueue,
        client = qrzClient,
        cipher = KeystoreCipher,
        setEnabledPref = settingsRepo::setQrzUploadEnabled,
        setApiKeyPref = { settingsRepo.setQrzApiKey(it) },
        persistLastError = settingsRepo::setQrzLastError,
        registerConnectivityListener = ::registerQrzConnectivityListener,
        scope = viewModelScope,
    )
```

with the registrar helper (near other private helpers):

```kotlin
    /** Flush the QRZ queue when the default network comes back (quiet self-heal). */
    private fun registerQrzConnectivityListener(onAvailable: () -> Unit) {
        val manager = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = onAvailable()
                },
            )
        } catch (_: RuntimeException) {
            // Too many callbacks or missing service — QRZ retries still fire
            // on app start and each logged QSO; connectivity is best-effort.
        }
    }
```

Imports: `android.content.Context`, `android.net.ConnectivityManager`, `android.net.Network`, `net.ft8vc.app.controllers.QrzUploadController`, `net.ft8vc.app.settings.KeystoreCipher`, `net.ft8vc.data.qrz.HttpQrzClient`, `net.ft8vc.data.qrz.QrzUploadQueue`, `net.ft8vc.data.qrz.RoomQrzQueueStore`.

- [x] **Step 4: Feed the slice into `OperateUiState`**

The state `combine` at `OperateViewModel.kt:~203` is already at coroutines' 5-flow overload limit; nest the new flow like the existing settings/view pairing:

```kotlin
        state = combine(
            kotlinx.coroutines.flow.combine(settingsBridge.slice, _viewState) { s, v -> s to v },
            rigSession.slice,
            decodeController.slice,
            txOrchestrator.slice,
            kotlinx.coroutines.flow.combine(qsoSession.slice, qrzController.slice) { q, z -> q to z },
        ) { (settings, view), rig, decode, tx, (qso, qrz) ->
```

then inside the `OperateUiState(...)` construction add `qrz = qrz,` and update the remaining references from `qso.` (unchanged — only the destructuring tuple shape changes).

- [x] **Step 5: Mark QSOs pending + trigger flush** — in `onQsoComplete` replace the log line:

```kotlin
        withContext(Dispatchers.IO) { logbook.log(contact, qrzPending = qrzController.isEnabled) }
        qrzController.onQsoLogged()
```

- [x] **Step 6: VM pass-throughs** — near `backupAdifNow()`:

```kotlin
    fun setQrzUploadEnabled(enabled: Boolean) = qrzController.setEnabled(enabled)
    fun setQrzApiKey(key: String) = qrzController.setApiKey(key)
    fun testQrzConnection() = qrzController.testConnection()
```

- [x] **Step 7: Compile + full unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing + new tests pass.

- [x] **Step 8: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/net/ft8vc/app/OperateUiState.kt app/src/main/java/net/ft8vc/app/OperateViewModel.kt
git commit -m "feat(app): wire QRZ upload controller into OperateViewModel + manifest"
```

---

### Task 9: Settings UI — QRZ Logbook section

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/settings/QrzSettingsSection.kt`
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `QrzSlice`, `QrzTestStatus` (Task 7); VM pass-throughs (Task 8); private `SettingsSection` composable in `SettingsScreen.kt`.
- Produces: `@Composable fun QrzSettingsSection(qrz: QrzSlice, onSetEnabled: (Boolean) -> Unit, onSetApiKey: (String) -> Unit, onTest: () -> Unit)`.

- [x] **Step 1: Extend `SettingsSection` with an optional title badge**

In `SettingsScreen.kt`, change the private composable (currently `SettingsScreen.kt:431`) to:

```kotlin
@Composable
private fun SettingsSection(
    title: String,
    titleBadge: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
```

and render `titleBadge` in a `Row(verticalAlignment = Alignment.CenterVertically)` next to the existing title `Text` (keep current typography/colors; existing call sites compile unchanged).

- [x] **Step 2: Write `QrzSettingsSection.kt`**

```kotlin
package net.ft8vc.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import net.ft8vc.app.controllers.QrzSlice
import net.ft8vc.app.controllers.QrzTestStatus

/** QRZ Logbook settings: enable toggle, masked API key, test button + result. */
@Composable
fun QrzSettingsSection(
    qrz: QrzSlice,
    onSetEnabled: (Boolean) -> Unit,
    onSetApiKey: (String) -> Unit,
    onTest: () -> Unit,
) {
    var showKey by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Upload QSOs to QRZ", fontWeight = FontWeight.SemiBold)
                Text(
                    "New contacts upload automatically; retries are quiet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = qrz.enabled, onCheckedChange = onSetEnabled)
        }

        OutlinedTextField(
            value = qrz.apiKey,
            onValueChange = onSetApiKey,
            label = { Text("QRZ API key") },
            placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation =
                if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showKey) "Hide API key" else "Show API key",
                    )
                }
            },
        )

        Button(
            onClick = onTest,
            enabled = qrz.apiKey.isNotBlank() && qrz.testStatus != QrzTestStatus.Testing,
        ) {
            Text(if (qrz.testStatus == QrzTestStatus.Testing) "Testing…" else "Test connection")
        }

        when (val status = qrz.testStatus) {
            is QrzTestStatus.Passed -> Text(
                status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            is QrzTestStatus.Failed -> Text(
                status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }

        if (qrz.warning) {
            Text(
                "Not connected — ${qrz.pendingCount} QSO(s) waiting to upload. " +
                    "They retry automatically when QRZ is reachable." +
                    (qrz.lastError?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
```

(Add `import androidx.compose.ui.unit.dp`.)

- [x] **Step 3: Insert the section in `SettingsScreen`**

Between the `SettingsSection("Logbook")` and `SettingsSection("About")` blocks:

```kotlin
            SettingsSection(
                "QRZ Logbook",
                titleBadge = {
                    if (state.qrz.warning) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = "QRZ upload not connected",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            ) {
                QrzSettingsSection(
                    qrz = state.qrz,
                    onSetEnabled = vm::setQrzUploadEnabled,
                    onSetApiKey = vm::setQrzApiKey,
                    onTest = vm::testQrzConnection,
                )
            }
```

(Add imports `androidx.compose.material.icons.filled.Warning`, `androidx.compose.material3.Icon` as needed.)

- [x] **Step 4: Compile + full unit tests + debug build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/QrzSettingsSection.kt app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt
git commit -m "feat(app): QRZ Logbook settings section — toggle, masked key, test button"
```

---

### Task 10: Full verification + wrap-up

**Files:**
- Modify: `docs/superpowers/plans/2026-07-11-qrz-logbook-upload.md` (check boxes)

- [x] **Step 1: Full JVM test suite across all modules**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL. (`DecodeControllerTest.reset_clearsLevelMeter` is a known pre-existing flake — re-run once before suspecting the branch.)

- [x] **Step 2: Release-shape compile check**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Verify no key leakage**

Run: `grep -rn "apiKey" app/src/main data/src/main | grep -i "log\|println\|Log\."`
Expected: no matches (the API key never reaches any log or message string).

- [x] **Step 4: Check all plan boxes, commit**

```bash
git add docs/superpowers/plans/2026-07-11-qrz-logbook-upload.md
git commit -m "docs: check off QRZ upload implementation plan"
```

**Deferred to the owner (device / field):** run `MigrationTest.migrate2To3_existingRowsBecomeNotQueued` and `KeystoreCipherTest` on the field phone (adb-pull the logbook first — connected tests wipe app data); real-key Test button success/failure; airplane-mode QSO flushing on reconnect with the ⚠ clearing; confirm upload appears in the QRZ web logbook.
