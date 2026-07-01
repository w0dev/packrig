# POTA Activator Logging Correctness — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Park refs are stored per QSO at completion time (multi-park capable), exports produce one pota.app-ready ADIF per activation `(park, UTC day)`, and wrong park assignments are fixable in bulk from the Log screen.

**Architecture:** A nullable normalized-CSV `potaParkRefs` column rides on `QsoEntity`/`QsoContact` (Room migration 1→2). `ActivationProfile` (core) owns the CSV format and validation. ADIF stamping moves from export-time settings (`AdifExportContext.potaEnabled/potaParkRef` — deleted) to per-record fields, with an `activationParkRef` override for single-park activation files. Activation grouping, filtering, and filenames are pure functions in a new `Activations` object (data module). Log screen gains an activation-picker export sheet and a selection-mode bulk "Set parks…" action.

**Tech Stack:** Kotlin 2.3.21, Room 2.7.2 (KSP), Jetpack Compose (Material 3), JUnit 4, Room `MigrationTestHelper` for instrumented tests.

**Spec:** `docs/superpowers/specs/2026-07-01-pota-activator-logging-design.md`

## Global Constraints

- RX/TX/CAT/QSO-sequencing code paths are untouched. This plan changes core (`ActivationProfile` only), data, and app UI/VM layers.
- No new runtime dependencies. New test-only dependency allowed: `androidx.room:room-testing` (instrumented tests).
- Park ref format: existing regex `^[A-Z]{1,4}-\d{4,5}(@[A-Z0-9-]+)?$` (case-insensitive input, stored uppercase). CSV joins with `,` and no spaces.
- `null` `potaParkRefs` = home (non-POTA) QSO. Empty string is never stored.
- ADIF version stays 3.1.4; every export path stays fail-closed through `AdifValidator.validateExport`.
- UTC day (`yyyyMMdd`) is the activation grouping unit.
- Activation filename: `CALL@PARK-YYYYMMDD.adi`; characters outside `[A-Za-z0-9@-]` in the callsign replaced with `-`.
- Unit test commands: `./gradlew :core:testDebugUnitTest`, `./gradlew :data:testDebugUnitTest`, `./gradlew :app:testDebugUnitTest`. Instrumented: `./gradlew :data:connectedDebugAndroidTest` (needs a connected device/emulator — if none is attached, defer those steps and run them before phase promotion; do not skip silently).
- Commit style: conventional commits (`feat(core): …`, `feat(data): …`, `test(data): …`, `docs: …`), each ending with the Claude co-author trailer used in this repo.

---

### Task 1: Park-list helpers in ActivationProfile (core)

**Files:**
- Modify: `core/src/main/java/net/ft8vc/core/ActivationProfile.kt`
- Test: `core/src/test/java/net/ft8vc/core/ActivationProfileTest.kt`

**Interfaces:**
- Consumes: existing `ActivationProfile.normalizeParkRef(ref: String): String?` and `isValidParkRef(ref: String): Boolean`.
- Produces (used by Tasks 5–11):
  - `fun parseParkRefs(raw: String?): List<String>` — tolerant split on commas, normalized, invalid/blank entries dropped.
  - `fun formatParkRefs(refs: List<String>): String?` — normalized, deduped CSV; `null` when empty.
  - `fun isValidParkRefList(raw: String): Boolean` — strict: at least one entry AND every entry valid.
  - `fun parkRefsForLogging(potaEnabled: Boolean, raw: String): String?` — CSV to stamp on a completed QSO, `null` when POTA off or nothing valid.

- [ ] **Step 1: Write the failing tests**

Append to `core/src/test/java/net/ft8vc/core/ActivationProfileTest.kt` (inside the class):

```kotlin
    @Test
    fun parsesAndFormatsParkRefLists() {
        assertEquals(listOf("US-3315", "US-0891"), ActivationProfile.parseParkRefs(" us-3315 , us-0891 "))
        assertEquals(emptyList<String>(), ActivationProfile.parseParkRefs(null))
        assertEquals(emptyList<String>(), ActivationProfile.parseParkRefs("banana"))
        assertEquals("US-3315,US-0891", ActivationProfile.formatParkRefs(listOf("us-3315", "US-0891", "us-3315")))
        assertNull(ActivationProfile.formatParkRefs(emptyList()))
    }

    @Test
    fun validatesParkRefLists() {
        assertTrue(ActivationProfile.isValidParkRefList("US-3315"))
        assertTrue(ActivationProfile.isValidParkRefList("us-3315, us-0891"))
        assertFalse(ActivationProfile.isValidParkRefList("US-3315, banana"))
        assertFalse(ActivationProfile.isValidParkRefList(""))
        assertFalse(ActivationProfile.isValidParkRefList(" , "))
    }

    @Test
    fun parkRefsForLoggingRespectsPotaMode() {
        assertNull(ActivationProfile.parkRefsForLogging(false, "US-3315"))
        assertEquals("US-3315", ActivationProfile.parkRefsForLogging(true, "us-3315"))
        assertEquals("US-3315,US-0891", ActivationProfile.parkRefsForLogging(true, "US-3315, US-0891"))
        assertNull(ActivationProfile.parkRefsForLogging(true, ""))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.ActivationProfileTest"`
Expected: FAIL — unresolved references `parseParkRefs`, `formatParkRefs`, `isValidParkRefList`, `parkRefsForLogging` (compile error).

- [ ] **Step 3: Implement the helpers**

Add to the `ActivationProfile` object body (after `isValidParkRef`):

```kotlin
    /** Tolerant split of a comma-separated input into normalized refs; invalid/blank entries dropped. */
    fun parseParkRefs(raw: String?): List<String> =
        raw.orEmpty().split(',').mapNotNull { normalizeParkRef(it) }

    /** Normalized, deduped CSV for storage/ADIF, or null when nothing valid remains. */
    fun formatParkRefs(refs: List<String>): String? =
        refs.mapNotNull { normalizeParkRef(it) }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(",")

    /** Strict gate: at least one entry and EVERY comma-separated entry is a valid park ref. */
    fun isValidParkRefList(raw: String): Boolean {
        val entries = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return entries.isNotEmpty() && entries.all { isValidParkRef(it) }
    }

    /** CSV to stamp on a completed QSO, or null when POTA mode is off or no ref is valid. */
    fun parkRefsForLogging(potaEnabled: Boolean, raw: String): String? {
        if (!potaEnabled) return null
        return formatParkRefs(parseParkRefs(raw))
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.ActivationProfileTest"`
Expected: PASS (all tests, including the pre-existing ones).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/ft8vc/core/ActivationProfile.kt core/src/test/java/net/ft8vc/core/ActivationProfileTest.kt
git commit -m "feat(core): park-ref list parse/format/validate helpers in ActivationProfile"
```

---

### Task 2: Room schema export bootstrap (v1 schema JSON)

The migration test in Task 4 needs Room's exported schema JSONs. The v1 JSON must be generated **before** the entity changes in Task 3.

**Files:**
- Modify: `data/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `data/src/main/java/net/ft8vc/data/db/Ft8vcDatabase.kt` (only `exportSchema`)
- Create (generated): `data/schemas/net.ft8vc.data.db.Ft8vcDatabase/1.json`

**Interfaces:**
- Produces: committed `schemas/` directory consumed by `MigrationTestHelper` in Task 4; `androidTest` dependency wiring consumed by Tasks 4 and 9.

- [ ] **Step 1: Add room-testing to the version catalog**

In `gradle/libs.versions.toml`, after the `androidx-room-compiler` line (line 54), add:

```toml
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
```

- [ ] **Step 2: Wire schema export and androidTest deps in the data module**

In `data/build.gradle.kts`:

Inside the `android { }` block, after the `compileOptions { }` block, add:

```kotlin
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
```

After the `kotlin { }` block, add a top-level `ksp` block:

```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

In the `dependencies { }` block, after `testImplementation(libs.junit)`, add:

```kotlin
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.coroutines.android)
```

- [ ] **Step 3: Enable schema export on the database**

In `data/src/main/java/net/ft8vc/data/db/Ft8vcDatabase.kt` change:

```kotlin
@Database(entities = [QsoEntity::class], version = 1, exportSchema = false)
```

to:

```kotlin
@Database(entities = [QsoEntity::class], version = 1, exportSchema = true)
```

- [ ] **Step 4: Generate and verify the v1 schema**

Run: `./gradlew :data:kspDebugKotlin`
Expected: BUILD SUCCESSFUL, and `data/schemas/net.ft8vc.data.db.Ft8vcDatabase/1.json` exists (verify with `ls data/schemas/net.ft8vc.data.db.Ft8vcDatabase/`).

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml data/build.gradle.kts data/src/main/java/net/ft8vc/data/db/Ft8vcDatabase.kt data/schemas
git commit -m "chore(data): enable Room schema export and instrumented-test deps"
```

---

### Task 3: potaParkRefs column — entity, contact, mappers, migration

**Files:**
- Modify: `data/src/main/java/net/ft8vc/data/db/QsoEntity.kt`
- Modify: `data/src/main/java/net/ft8vc/data/model/QsoContact.kt`
- Modify: `data/src/main/java/net/ft8vc/data/Logbook.kt` (mappers in `RoomLogbook`)
- Modify: `data/src/main/java/net/ft8vc/data/db/Ft8vcDatabase.kt` (version 2 + migration)
- Create (generated): `data/schemas/net.ft8vc.data.db.Ft8vcDatabase/2.json`
- Test: `data/src/test/java/net/ft8vc/data/model/QsoContactTest.kt`

**Interfaces:**
- Consumes: `net.ft8vc.core.QsoSnapshot` (existing).
- Produces (used by Tasks 4–10):
  - `QsoContact.potaParkRefs: String?` (normalized CSV or null) and `QsoContact.fromSnapshot(snapshot: QsoSnapshot, freqHz: Long?, band: String?, potaParkRefs: String? = null): QsoContact`.
  - `QsoEntity.potaParkRefs: String?` column on `qso_contacts`.
  - `Ft8vcDatabase.MIGRATION_1_2: Migration` (registered via `addMigrations`).

- [ ] **Step 1: Write the failing test**

Create `data/src/test/java/net/ft8vc/data/model/QsoContactTest.kt`:

```kotlin
package net.ft8vc.data.model

import net.ft8vc.core.QsoRole
import net.ft8vc.core.QsoSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QsoContactTest {

    private val snapshot = QsoSnapshot(
        myCall = "W0DEV",
        myGrid = "EM26",
        dxCall = "K1ABC",
        dxGrid = "FN42",
        reportSent = -8,
        reportRcvd = -15,
        role = QsoRole.Initiator,
        completedAtEpochMs = 1_700_000_000_000L,
    )

    @Test
    fun fromSnapshotCarriesParkRefs() {
        val contact = QsoContact.fromSnapshot(snapshot, 14_074_000L, "20m", "US-3315,US-0891")
        assertEquals("US-3315,US-0891", contact.potaParkRefs)
    }

    @Test
    fun fromSnapshotDefaultsToNoParks() {
        val contact = QsoContact.fromSnapshot(snapshot, 14_074_000L, "20m")
        assertNull(contact.potaParkRefs)
    }
}
```

(If `QsoSnapshot`'s constructor differs, mirror the actual parameter list from `core/src/main/java/net/ft8vc/core/QsoSnapshot.kt` — the fields above match `QsoMachine.snapshot()`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :data:testDebugUnitTest --tests "net.ft8vc.data.model.QsoContactTest"`
Expected: FAIL — compile error (no `potaParkRefs` parameter/property).

- [ ] **Step 3: Add the field to QsoContact and fromSnapshot**

In `data/src/main/java/net/ft8vc/data/model/QsoContact.kt`, add the property after `notes` and thread it through `fromSnapshot`:

```kotlin
data class QsoContact(
    val id: Long = 0,
    val utcMillis: Long,
    val myCall: String,
    val myGrid: String,
    val dxCall: String,
    val dxGrid: String?,
    val rstSent: Int?,
    val rstRcvd: Int?,
    val freqHz: Long?,
    val mode: String = "FT8",
    val band: String?,
    val notes: String = "",
    /** Normalized comma-separated POTA park refs captured at QSO completion; null = home QSO. */
    val potaParkRefs: String? = null,
) {
    companion object {
        fun fromSnapshot(
            snapshot: QsoSnapshot,
            freqHz: Long?,
            band: String?,
            potaParkRefs: String? = null,
        ): QsoContact =
            QsoContact(
                utcMillis = snapshot.completedAtEpochMs,
                myCall = snapshot.myCall,
                myGrid = snapshot.myGrid,
                dxCall = snapshot.dxCall,
                dxGrid = snapshot.dxGrid,
                rstSent = snapshot.reportSent,
                rstRcvd = snapshot.reportRcvd,
                freqHz = freqHz,
                mode = "FT8",
                band = band,
                potaParkRefs = potaParkRefs,
            )
    }
}
```

- [ ] **Step 4: Add the entity column and mappers**

In `data/src/main/java/net/ft8vc/data/db/QsoEntity.kt`, add after `notes`:

```kotlin
    val notes: String,
    val potaParkRefs: String? = null,
```

In `data/src/main/java/net/ft8vc/data/Logbook.kt`, add the field to both private mappers in `RoomLogbook`:

```kotlin
    private fun QsoContact.toEntity() = QsoEntity(
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

    private fun QsoEntity.toContact() = QsoContact(
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

- [ ] **Step 5: Bump the database version and add the migration**

Replace `data/src/main/java/net/ft8vc/data/db/Ft8vcDatabase.kt` contents with:

```kotlin
package net.ft8vc.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [QsoEntity::class], version = 2, exportSchema = true)
abstract class Ft8vcDatabase : RoomDatabase() {
    abstract fun qsoDao(): QsoDao

    companion object {
        @Volatile private var instance: Ft8vcDatabase? = null

        /** v1 → v2: per-QSO POTA park refs (normalized CSV; null = home QSO). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE qso_contacts ADD COLUMN potaParkRefs TEXT")
            }
        }

        fun get(context: Context): Ft8vcDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    Ft8vcDatabase::class.java,
                    "ft8vc_logbook.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
```

- [ ] **Step 6: Run tests and generate the v2 schema**

Run: `./gradlew :data:testDebugUnitTest --tests "net.ft8vc.data.model.QsoContactTest"` — Expected: PASS.
Run: `./gradlew :data:kspDebugKotlin` — Expected: BUILD SUCCESSFUL and `data/schemas/net.ft8vc.data.db.Ft8vcDatabase/2.json` exists.
Run: `./gradlew :data:testDebugUnitTest :app:testDebugUnitTest` — Expected: PASS (nothing else referenced the changed constructors positionally; if a compile error surfaces, fix the call site by naming arguments).

- [ ] **Step 7: Commit**

```bash
git add data/src data/schemas
git commit -m "feat(data): per-QSO potaParkRefs column with Room 1->2 migration"
```

---

### Task 4: Instrumented migration test

**Files:**
- Create: `data/src/androidTest/java/net/ft8vc/data/db/MigrationTest.kt`

**Interfaces:**
- Consumes: `Ft8vcDatabase.MIGRATION_1_2` (Task 3), committed schema JSONs (Tasks 2–3).

- [ ] **Step 1: Write the migration test**

Create `data/src/androidTest/java/net/ft8vc/data/db/MigrationTest.kt`:

```kotlin
package net.ft8vc.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        Ft8vcDatabase::class.java,
    )

    @Test
    fun migrate1To2_preservesRowsAndAddsNullParkColumn() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO qso_contacts " +
                    "(utcMillis, myCall, myGrid, dxCall, dxGrid, rstSent, rstRcvd, freqHz, mode, band, notes) " +
                    "VALUES (1700000000000, 'W0DEV', 'EM26', 'K1ABC', 'FN42', -8, -15, 14074000, 'FT8', '20m', '')",
            )
        }
        helper.runMigrationsAndValidate(DB_NAME, 2, true, Ft8vcDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT dxCall, potaParkRefs FROM qso_contacts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("K1ABC", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
```

- [ ] **Step 2: Run on a connected device/emulator**

Run: `./gradlew :data:connectedDebugAndroidTest`
Expected: PASS (`MigrationTest > migrate1To2_preservesRowsAndAddsNullParkColumn`).
If no device is attached: verify it compiles with `./gradlew :data:assembleDebugAndroidTest` (Expected: BUILD SUCCESSFUL), note the deferred run, and execute before phase promotion.

- [ ] **Step 3: Commit**

```bash
git add data/src/androidTest
git commit -m "test(data): instrumented Room 1->2 migration test"
```

---

### Task 5: Per-record ADIF stamping; delete global stamping

**Files:**
- Modify: `data/src/main/java/net/ft8vc/data/adif/AdifExportContext.kt`
- Modify: `data/src/main/java/net/ft8vc/data/adif/AdifNormalizer.kt:47-52`
- Modify: `app/src/main/java/net/ft8vc/app/LogViewModel.kt:35-53`
- Modify: `app/src/main/java/net/ft8vc/app/AdifAutoBackup.kt:73-108`
- Test: `data/src/test/java/net/ft8vc/data/adif/AdifWriterTest.kt`

**Interfaces:**
- Consumes: `QsoContact.potaParkRefs` (Task 3).
- Produces (used by Task 7's export):
  - `AdifExportContext(programId, programVersion, adifVersion, activationParkRef: String? = null)` — `potaEnabled`/`potaParkRef` REMOVED.
  - Stamping rule in `AdifNormalizer.normalizeRecord`: `activationParkRef` set → every record gets `MY_SIG=POTA`, `MY_SIG_INFO=<that park>`; else contact has parks → `MY_SIG=POTA`, `MY_SIG_INFO=<contact CSV>`; else no POTA fields.

- [ ] **Step 1: Rewrite the POTA tests in AdifWriterTest**

In `data/src/test/java/net/ft8vc/data/adif/AdifWriterTest.kt`, DELETE the two tests `exportsPotaFieldsWhenEnabled` and `rejectsPotaExportWithoutParkRef`, and add:

```kotlin
    @Test
    fun stampsPerRecordParkRefsInFullExport() {
        val potaContact = contact.copy(potaParkRefs = "US-3315,US-0891")
        val adif = AdifWriter.export(listOf(potaContact))
        assertTrue(adif.contains("<MY_SIG:4>POTA"))
        assertTrue(adif.contains("<MY_SIG_INFO:15>US-3315,US-0891"))
    }

    @Test
    fun omitsPotaFieldsForHomeQsos() {
        val adif = AdifWriter.export(listOf(contact))
        assertFalse(adif.contains("MY_SIG"))
    }

    @Test
    fun activationExportStampsEveryRecordWithSinglePark() {
        val twoFer = contact.copy(potaParkRefs = "US-3315,US-0891")
        val adif = AdifWriter.export(
            listOf(twoFer, contact.copy(dxCall = "K2DEF", potaParkRefs = "US-3315")),
            AdifExportContext(activationParkRef = "US-3315"),
        )
        assertTrue(adif.contains("<MY_SIG_INFO:7>US-3315"))
        assertFalse(adif.contains("US-0891"))
    }

    @Test(expected = AdifExportException::class)
    fun rejectsInvalidActivationParkRef() {
        AdifWriter.export(listOf(contact), AdifExportContext(activationParkRef = "banana"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :data:testDebugUnitTest --tests "net.ft8vc.data.adif.AdifWriterTest"`
Expected: FAIL — compile error (`activationParkRef` unknown; old tests removed).

- [ ] **Step 3: Update AdifExportContext**

Replace the data class in `data/src/main/java/net/ft8vc/data/adif/AdifExportContext.kt`:

```kotlin
/** Context applied when exporting contacts to ADIF. */
data class AdifExportContext(
    val programId: String = "FT8VC",
    val programVersion: String = "1.0.0",
    val adifVersion: String = "3.1.4",
    /** When set (single-activation export), every record is stamped with this one park. */
    val activationParkRef: String? = null,
)
```

- [ ] **Step 4: Update AdifNormalizer stamping**

In `data/src/main/java/net/ft8vc/data/adif/AdifNormalizer.kt`, replace the block at lines 47–52:

```kotlin
        if (context.potaEnabled) {
            val parkRef = context.potaParkRef?.let { ActivationProfile.normalizeParkRef(it) }
                ?: throw AdifExportException("POTA mode enabled but park reference is missing or invalid")
            fields["MY_SIG"] = ActivationProfile.POTA_SIG
            fields["MY_SIG_INFO"] = parkRef
        }
```

with:

```kotlin
        val parkForRecord = context.activationParkRef
            ?.let {
                ActivationProfile.normalizeParkRef(it)
                    ?: throw AdifExportException("Invalid activation park ref: $it")
            }
            ?: contact.potaParkRefs
        if (parkForRecord != null) {
            fields["MY_SIG"] = ActivationProfile.POTA_SIG
            fields["MY_SIG_INFO"] = parkForRecord
        }
```

- [ ] **Step 5: Simplify LogViewModel.exportAdif**

In `app/src/main/java/net/ft8vc/app/LogViewModel.kt`, replace the `exportAdif` function (lines 35–53) with:

```kotlin
    suspend fun exportAdif(): String =
        logbook.exportAdif(
            AdifExportContext(
                programId = AppInfo.APP_NAME,
                programVersion = AppInfo.VERSION_NAME,
            ),
        )
```

Remove the now-unused imports `net.ft8vc.core.ActivationProfile` and `net.ft8vc.data.adif.AdifExportException` (keep `settingsRepo` — Task 7 uses it).

- [ ] **Step 6: Simplify AdifAutoBackup.backupNow**

In `app/src/main/java/net/ft8vc/app/AdifAutoBackup.kt`, inside `backupNow`, replace:

```kotlin
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
```

with:

```kotlin
            val adif = logbook.exportAdif(
                AdifExportContext(
                    programId = AppInfo.APP_NAME,
                    programVersion = AppInfo.VERSION_NAME,
                ),
            )
```

Delete the now-unused import `net.ft8vc.core.ActivationProfile` and the private `settingsFirst` extension at the bottom of the file (the `settings` parameter is still used by `settings.setLastAdifBackupAtMs(...)`).

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :data:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add data/src app/src/main/java/net/ft8vc/app/LogViewModel.kt app/src/main/java/net/ft8vc/app/AdifAutoBackup.kt
git commit -m "feat(data): per-record ADIF POTA stamping, drop export-time park settings"
```

---

### Task 6: Snapshot park refs at QSO completion

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt:679-688`

**Interfaces:**
- Consumes: `ActivationProfile.parkRefsForLogging` (Task 1), `QsoContact.fromSnapshot(..., potaParkRefs)` (Task 3).
- Produces: every newly logged contact carries the parks that were active at completion; settings changes afterward cannot alter it.

- [ ] **Step 1: Thread parks into onQsoComplete**

In `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`, replace `onQsoComplete` (lines 679–688):

```kotlin
    private suspend fun onQsoComplete(snapshot: QsoSnapshot) {
        val freq = state.value.rigFreqHz
        val band = bandLabelForFreq(freq)
        val parks = ActivationProfile.parkRefsForLogging(
            state.value.potaModeEnabled,
            state.value.potaParkRef,
        )
        val contact = QsoContact.fromSnapshot(snapshot, freq, band, parks)
        withContext(Dispatchers.IO) { logbook.log(contact) }
        workedBeforeCache.invalidate(contact.dxCall)
        // Phase 7 (HYG-04): atomic ADIF auto-export on ApplicationScope so the
        // backup outlives this ViewModel if the user pauses the app mid-write.
        AdifAutoBackup.scheduleBackupAfterQso(getApplication(), logbook, settingsRepo)
    }
```

Add `import net.ft8vc.core.ActivationProfile` if not already imported.

- [ ] **Step 2: Build and run app unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. (The capture decision logic itself is pure and covered by `parkRefsForLoggingRespectsPotaMode` in Task 1.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateViewModel.kt
git commit -m "feat(app): snapshot POTA park refs onto contacts at QSO completion"
```

---

### Task 7: Activation grouping and filenames (pure logic)

**Files:**
- Create: `data/src/main/java/net/ft8vc/data/Activations.kt`
- Test: `data/src/test/java/net/ft8vc/data/ActivationsTest.kt`

**Interfaces:**
- Consumes: `QsoContact.potaParkRefs` (Task 3), `ActivationProfile.parseParkRefs` (Task 1).
- Produces (used by Task 8):
  - `data class Activation(val parkRef: String, val utcDate: String /* yyyyMMdd */, val qsoCount: Int)`
  - `Activations.utcDateOf(utcMillis: Long): String`
  - `Activations.groupActivations(contacts: List<QsoContact>): List<Activation>` — newest first; a two-fer QSO appears under each of its parks; home QSOs excluded.
  - `Activations.contactsFor(contacts: List<QsoContact>, parkRef: String, utcDate: String): List<QsoContact>`
  - `Activations.fileName(myCall: String, parkRef: String, utcDate: String): String`

- [ ] **Step 1: Write the failing tests**

Create `data/src/test/java/net/ft8vc/data/ActivationsTest.kt`:

```kotlin
package net.ft8vc.data

import net.ft8vc.data.model.QsoContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationsTest {

    // 2026-07-01T23:59:30Z and 2026-07-02T00:00:30Z straddle a UTC day boundary.
    private val justBeforeMidnight = 1_782_777_570_000L
    private val justAfterMidnight = 1_782_777_630_000L

    private fun contact(dx: String, utc: Long, parks: String?) = QsoContact(
        utcMillis = utc,
        myCall = "W0DEV",
        myGrid = "EM26",
        dxCall = dx,
        dxGrid = null,
        rstSent = -5,
        rstRcvd = -10,
        freqHz = 14_074_000L,
        band = "20m",
        potaParkRefs = parks,
    )

    @Test
    fun utcDateUsesUtcNotLocal() {
        assertEquals("20260701", Activations.utcDateOf(justBeforeMidnight))
        assertEquals("20260702", Activations.utcDateOf(justAfterMidnight))
    }

    @Test
    fun groupsByParkAndUtcDayExcludingHomeQsos() {
        val contacts = listOf(
            contact("K1ABC", justBeforeMidnight, "US-3315,US-0891"), // two-fer
            contact("K2DEF", justBeforeMidnight, "US-3315"),
            contact("K3GHI", justAfterMidnight, "US-3315"),          // next UTC day
            contact("K4JKL", justBeforeMidnight, null),              // home QSO
        )
        val activations = Activations.groupActivations(contacts)
        assertEquals(3, activations.size)
        assertTrue(activations.contains(Activation("US-3315", "20260701", 2)))
        assertTrue(activations.contains(Activation("US-0891", "20260701", 1)))
        assertTrue(activations.contains(Activation("US-3315", "20260702", 1)))
    }

    @Test
    fun contactsForSelectsExactlyTheActivationsQsos() {
        val twoFer = contact("K1ABC", justBeforeMidnight, "US-3315,US-0891")
        val other = contact("K3GHI", justAfterMidnight, "US-3315")
        val home = contact("K4JKL", justBeforeMidnight, null)
        val group = Activations.contactsFor(listOf(twoFer, other, home), "US-0891", "20260701")
        assertEquals(listOf(twoFer), group)
    }

    @Test
    fun fileNameSanitizesCallsign() {
        assertEquals(
            "W0DEV-P@US-3315-20260701.adi",
            Activations.fileName("W0DEV/P", "US-3315", "20260701"),
        )
        assertEquals(
            "W0DEV@US-3315-20260701.adi",
            Activations.fileName("w0dev", "US-3315", "20260701"),
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :data:testDebugUnitTest --tests "net.ft8vc.data.ActivationsTest"`
Expected: FAIL — unresolved reference `Activations` / `Activation` (compile error).

- [ ] **Step 3: Implement Activations**

Create `data/src/main/java/net/ft8vc/data/Activations.kt`:

```kotlin
package net.ft8vc.data

import net.ft8vc.core.ActivationProfile
import net.ft8vc.data.model.QsoContact
import java.util.Calendar
import java.util.TimeZone

/** One POTA upload unit: every logged QSO at [parkRef] during UTC day [utcDate] (yyyyMMdd). */
data class Activation(
    val parkRef: String,
    val utcDate: String,
    val qsoCount: Int,
)

/** Pure grouping/filtering/naming for POTA activation exports. */
object Activations {

    fun utcDateOf(utcMillis: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = utcMillis
        return "%04d%02d%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** Derived activation groups, newest day first. A two-fer QSO appears under each of its parks. */
    fun groupActivations(contacts: List<QsoContact>): List<Activation> =
        contacts
            .flatMap { c ->
                ActivationProfile.parseParkRefs(c.potaParkRefs)
                    .map { park -> park to utcDateOf(c.utcMillis) }
            }
            .groupingBy { it }
            .eachCount()
            .map { (key, count) -> Activation(parkRef = key.first, utcDate = key.second, qsoCount = count) }
            .sortedWith(compareByDescending<Activation> { it.utcDate }.thenBy { it.parkRef })

    /** The QSOs belonging to one activation. */
    fun contactsFor(contacts: List<QsoContact>, parkRef: String, utcDate: String): List<QsoContact> =
        contacts.filter { c ->
            utcDateOf(c.utcMillis) == utcDate &&
                ActivationProfile.parseParkRefs(c.potaParkRefs).contains(parkRef)
        }

    /** Upload filename `CALL@PARK-YYYYMMDD.adi`; unsafe callsign chars become '-'. */
    fun fileName(myCall: String, parkRef: String, utcDate: String): String {
        val safeCall = myCall.uppercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
        return "$safeCall@$parkRef-$utcDate.adi"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :data:testDebugUnitTest --tests "net.ft8vc.data.ActivationsTest"`
Expected: PASS. (If the two epoch constants land on unexpected dates, recompute: the test only requires the first to be 20260701 UTC and the second 20260702 UTC — adjust constants, never the assertions' *intent*.)

- [ ] **Step 5: Commit**

```bash
git add data/src/main/java/net/ft8vc/data/Activations.kt data/src/test/java/net/ft8vc/data/ActivationsTest.kt
git commit -m "feat(data): activation grouping, filtering, and upload filenames"
```

---

### Task 8: Activation picker export (LogViewModel + LogScreen)

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/LogViewModel.kt`
- Modify: `app/src/main/java/net/ft8vc/app/ui/log/LogScreen.kt`

**Interfaces:**
- Consumes: `Activations` / `Activation` (Task 7), `AdifExportContext.activationParkRef` (Task 5).
- Produces:
  - `LogViewModel.activations: StateFlow<List<Activation>>`
  - `suspend fun LogViewModel.exportActivation(activation: Activation): Pair<String, String>` — (fileName, adif content); throws `AdifExportException` on validation failure.

- [ ] **Step 1: Add activations flow and exportActivation to LogViewModel**

In `app/src/main/java/net/ft8vc/app/LogViewModel.kt`:

Add imports:

```kotlin
import net.ft8vc.data.Activation
import net.ft8vc.data.Activations
import net.ft8vc.data.adif.AdifWriter
```

Add below the `contacts` property:

```kotlin
    private val _activations = MutableStateFlow<List<Activation>>(emptyList())
    val activations: StateFlow<List<Activation>> = _activations.asStateFlow()
```

Change the `init` collector to also derive activations:

```kotlin
    init {
        viewModelScope.launch {
            logbook.contacts().collect { list ->
                _contacts.value = list
                _activations.value = Activations.groupActivations(list)
            }
        }
    }
```

Add after `exportAdif`:

```kotlin
    /** Build one activation's upload file. Returns (fileName, adif content). */
    suspend fun exportActivation(activation: Activation): Pair<String, String> {
        val myCall = settingsRepo.settings.first().myCall
        val group = Activations.contactsFor(_contacts.value, activation.parkRef, activation.utcDate)
        val adif = AdifWriter.export(
            group,
            AdifExportContext(
                programId = AppInfo.APP_NAME,
                programVersion = AppInfo.VERSION_NAME,
                activationParkRef = activation.parkRef,
            ),
        )
        return Activations.fileName(myCall, activation.parkRef, activation.utcDate) to adif
    }
```

- [ ] **Step 2: Extract a shared share helper and add the picker sheet in LogScreen**

In `app/src/main/java/net/ft8vc/app/ui/log/LogScreen.kt`:

Add imports:

```kotlin
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.Park
import androidx.compose.material3.ModalBottomSheet
import net.ft8vc.data.Activation
```

(`LazyColumn` is already imported; skip duplicates.)

Add a private top-level function at the bottom of the file:

```kotlin
private fun shareAdif(context: android.content.Context, fileName: String, adif: String) {
    val file = File(context.cacheDir, fileName)
    file.writeText(adif)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
    )
}
```

Replace the body of the existing full-export `IconButton`'s `scope.launch { ... }` with:

```kotlin
                            scope.launch {
                                try {
                                    shareAdif(context, "ft8vc_export.adi", vm.exportAdif())
                                } catch (e: AdifExportException) {
                                    snackbarHostState.showSnackbar(e.message ?: "ADIF export failed")
                                }
                            }
```

(Keep the surrounding `IconButton(onClick = { ... }, enabled = contacts.isNotEmpty())` structure; only the launch body changes.)

Inside `LogScreen`, add state + collection near `showClearConfirm`:

```kotlin
    val activations by vm.activations.collectAsStateWithLifecycle()
    var showActivations by remember { mutableStateOf(false) }
```

Add a POTA action in the `TopAppBar` `actions` block, BEFORE the existing share button:

```kotlin
                    IconButton(
                        onClick = { showActivations = true },
                        enabled = activations.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Park, contentDescription = "POTA activation export")
                    }
```

Add the sheet after the `showClearConfirm` dialog block, at the end of the composable:

```kotlin
    if (showActivations) {
        ModalBottomSheet(onDismissRequest = { showActivations = false }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("POTA activations", style = MaterialTheme.typography.titleMedium)
                Text(
                    "One file per park per UTC day — upload each to pota.app",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn {
                    items(activations, key = { "${it.parkRef}:${it.utcDate}" }) { activation ->
                        val dateLabel = activation.utcDate.let {
                            "${it.take(4)}-${it.substring(4, 6)}-${it.takeLast(2)}"
                        }
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "${activation.parkRef} · $dateLabel · ${activation.qsoCount} QSOs",
                                fontFamily = FontFamily.Monospace,
                            )
                            IconButton(onClick = {
                                scope.launch {
                                    try {
                                        val (name, adif) = vm.exportActivation(activation)
                                        shareAdif(context, name, adif)
                                    } catch (e: AdifExportException) {
                                        snackbarHostState.showSnackbar(e.message ?: "Export failed")
                                    }
                                }
                            }) {
                                Icon(Icons.Filled.Share, contentDescription = "Share ${activation.parkRef}")
                            }
                        }
                    }
                }
            }
        }
    }
```

- [ ] **Step 3: Build and test**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/LogViewModel.kt app/src/main/java/net/ft8vc/app/ui/log/LogScreen.kt
git commit -m "feat(app): POTA activation picker export on the Log screen"
```

---

### Task 9: Bulk park update (DAO + Logbook + LogViewModel)

**Files:**
- Modify: `data/src/main/java/net/ft8vc/data/db/QsoDao.kt`
- Modify: `data/src/main/java/net/ft8vc/data/Logbook.kt`
- Modify: `app/src/main/java/net/ft8vc/app/LogViewModel.kt`
- Test: `data/src/androidTest/java/net/ft8vc/data/db/QsoDaoTest.kt`

**Interfaces:**
- Consumes: `ActivationProfile.isValidParkRefList` / `formatParkRefs` / `parseParkRefs` (Task 1), androidTest infra (Task 2).
- Produces (used by Task 10):
  - `QsoDao.updateParkRefs(ids: List<Long>, potaParkRefs: String?)`
  - `Logbook.setParkRefs(ids: List<Long>, potaParkRefs: String?)`
  - `LogViewModel.setParksOnContacts(ids: List<Long>, rawInput: String, onDone: (Boolean) -> Unit)` — `false` on invalid input (nothing written); blank input clears parks.

- [ ] **Step 1: Write the instrumented DAO test**

Create `data/src/androidTest/java/net/ft8vc/data/db/QsoDaoTest.kt`:

```kotlin
package net.ft8vc.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QsoDaoTest {

    @Test
    fun bulkUpdateAndClearParkRefs() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            Ft8vcDatabase::class.java,
        ).build()
        try {
            val dao = db.qsoDao()
            val id1 = dao.insert(entity("K1ABC", 1_000L))
            dao.insert(entity("K2DEF", 2_000L))

            dao.updateParkRefs(listOf(id1), "US-3315,US-0891")
            var byCall = dao.getAll().associateBy { it.dxCall }
            assertEquals("US-3315,US-0891", byCall.getValue("K1ABC").potaParkRefs)
            assertNull(byCall.getValue("K2DEF").potaParkRefs)

            dao.updateParkRefs(listOf(id1), null)
            byCall = dao.getAll().associateBy { it.dxCall }
            assertNull(byCall.getValue("K1ABC").potaParkRefs)
        } finally {
            db.close()
        }
    }

    private fun entity(dxCall: String, utcMillis: Long) = QsoEntity(
        utcMillis = utcMillis,
        myCall = "W0DEV",
        myGrid = "EM26",
        dxCall = dxCall,
        dxGrid = null,
        rstSent = null,
        rstRcvd = null,
        freqHz = null,
        mode = "FT8",
        band = null,
        notes = "",
    )
}
```

- [ ] **Step 2: Verify the test fails to compile**

Run: `./gradlew :data:assembleDebugAndroidTest`
Expected: FAIL — unresolved reference `updateParkRefs`.

- [ ] **Step 3: Add the DAO query and Logbook pass-through**

In `data/src/main/java/net/ft8vc/data/db/QsoDao.kt`, add after `workedBands`:

```kotlin
    @Query("UPDATE qso_contacts SET potaParkRefs = :potaParkRefs WHERE id IN (:ids)")
    suspend fun updateParkRefs(ids: List<Long>, potaParkRefs: String?)
```

In `data/src/main/java/net/ft8vc/data/Logbook.kt`, add to the `Logbook` interface:

```kotlin
    suspend fun setParkRefs(ids: List<Long>, potaParkRefs: String?)
```

and to `RoomLogbook`:

```kotlin
    override suspend fun setParkRefs(ids: List<Long>, potaParkRefs: String?) =
        dao.updateParkRefs(ids, potaParkRefs)
```

- [ ] **Step 4: Run the instrumented test**

Run: `./gradlew :data:connectedDebugAndroidTest`
Expected: PASS (`QsoDaoTest`, `MigrationTest`).
If no device: `./gradlew :data:assembleDebugAndroidTest` must BUILD SUCCESSFUL; defer the run to phase promotion.

- [ ] **Step 5: Add the ViewModel action**

In `app/src/main/java/net/ft8vc/app/LogViewModel.kt`, add import `net.ft8vc.core.ActivationProfile` and (after `exportActivation`):

```kotlin
    /**
     * Replace park refs on [ids]. Blank input clears parks (home QSOs).
     * Invokes [onDone] with false and writes nothing when the input is invalid.
     */
    fun setParksOnContacts(ids: List<Long>, rawInput: String, onDone: (Boolean) -> Unit = {}) {
        val trimmed = rawInput.trim()
        val parks: String? = if (trimmed.isEmpty()) {
            null
        } else {
            if (!ActivationProfile.isValidParkRefList(trimmed)) {
                onDone(false)
                return
            }
            ActivationProfile.formatParkRefs(ActivationProfile.parseParkRefs(trimmed))
        }
        viewModelScope.launch {
            logbook.setParkRefs(ids, parks)
            AdifAutoBackup.scheduleBackupAfterQso(getApplication(), logbook, settingsRepo)
            onDone(true)
        }
    }
```

- [ ] **Step 6: Build and unit-test**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add data/src app/src/main/java/net/ft8vc/app/LogViewModel.kt
git commit -m "feat(data): bulk park-ref update through DAO, Logbook, and LogViewModel"
```

---

### Task 10: Bulk park fix UI (selection mode on the Log screen)

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/ui/log/LogScreen.kt`

**Interfaces:**
- Consumes: `LogViewModel.setParksOnContacts` (Task 9), `Activations.utcDateOf` (Task 7).
- Produces: long-press starts selection; tap toggles; "Day" expands selection to all QSOs sharing a selected QSO's UTC day; "Set parks…" dialog replaces parks (blank clears); park CSV visible on log rows.

- [ ] **Step 1: Add selection state, contextual actions, and the dialog**

In `app/src/main/java/net/ft8vc/app/ui/log/LogScreen.kt`:

Add imports:

```kotlin
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.OutlinedTextField
import net.ft8vc.data.Activations
```

Change the `@OptIn` annotation on `LogScreen` to include both:

```kotlin
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
```

Add state inside `LogScreen` near `showClearConfirm`:

```kotlin
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var showSetParksDialog by remember { mutableStateOf(false) }
    var parksDraft by remember { mutableStateOf("") }
    val selectionActive = selectedIds.isNotEmpty()
```

Replace the `TopAppBar` `title` and `actions` so selection mode takes over the bar. Structure:

```kotlin
            TopAppBar(
                title = {
                    if (selectionActive) {
                        Text("${selectedIds.size} selected")
                    } else {
                        Column {
                            Text("Log (${contacts.size})")
                            Text(
                                "Share exports validated ADIF 3.1",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (selectionActive) {
                        TextButton(onClick = {
                            val days = contacts.filter { it.id in selectedIds }
                                .map { Activations.utcDateOf(it.utcMillis) }
                                .toSet()
                            selectedIds = contacts
                                .filter { Activations.utcDateOf(it.utcMillis) in days }
                                .map { it.id }
                                .toSet()
                        }) { Text("Day") }
                        IconButton(onClick = {
                            parksDraft = ""
                            showSetParksDialog = true
                        }) {
                            Icon(Icons.Filled.EditNote, contentDescription = "Set parks")
                        }
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                        }
                    } else {
                        // ... existing POTA, Share, and Clear IconButtons unchanged ...
                    }
                },
            )
```

(Move the three existing `IconButton`s — POTA export from Task 8, Share, Clear — into the `else` branch verbatim.)

Change the `items` block to pass selection handlers:

```kotlin
                    items(contacts, key = { it.id }) { contact ->
                        LogRow(
                            contact = contact,
                            selected = contact.id in selectedIds,
                            onTap = {
                                if (selectionActive) {
                                    selectedIds =
                                        if (contact.id in selectedIds) selectedIds - contact.id
                                        else selectedIds + contact.id
                                }
                            },
                            onLongPress = { selectedIds = selectedIds + contact.id },
                        )
                    }
```

Replace `LogRow` with:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(
    contact: QsoContact,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val fmt = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RowBetween(contact.dxCall, fmt.format(Date(contact.utcMillis)) + " UTC")
        Text(
            buildString {
                contact.dxGrid?.let { append("$it · ") }
                contact.band?.let { append("$it · ") }
                contact.potaParkRefs?.let { append("$it · ") }
                append("RST ${contact.rstRcvd ?: "?"} / ${contact.rstSent ?: "?"}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

Add `import androidx.compose.foundation.background` at the top of the file (the modifier order above is deliberate: `background` before `padding` so the highlight fills the row).

Add the dialog after the `showClearConfirm` block:

```kotlin
    if (showSetParksDialog) {
        AlertDialog(
            onDismissRequest = { showSetParksDialog = false },
            title = { Text("Set parks on ${selectedIds.size} QSOs") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = parksDraft,
                        onValueChange = { parksDraft = it.uppercase() },
                        label = { Text("Park reference(s)") },
                        placeholder = { Text("US-3315, US-0891") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Replaces parks on all selected QSOs. Leave empty to clear (home QSOs).",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setParksOnContacts(selectedIds.toList(), parksDraft) { ok ->
                        scope.launch {
                            if (ok) {
                                showSetParksDialog = false
                                selectedIds = emptySet()
                            } else {
                                snackbarHostState.showSnackbar(
                                    "Invalid park list — use refs like US-3315, comma-separated",
                                )
                            }
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSetParksDialog = false }) { Text("Cancel") }
            },
        )
    }
```

- [ ] **Step 2: Build and test**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke check (emulator or device)**

Install the debug build. Verify: long-press a log row enters selection with highlight; tap toggles; "Day" selects that UTC day; "Set parks…" with `US-3315, US-0891` updates the rows' park line; blank input clears it; invalid input shows the snackbar and changes nothing.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/log/LogScreen.kt
git commit -m "feat(app): bulk park fix via Log-screen selection mode"
```

---

### Task 11: Multi-ref gates and copy (startCq validation, Settings/Operate text)

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/controllers/QsoSessionController.kt:177-179`
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt:221`
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt` (park field hint, ~lines 99-108)

**Interfaces:**
- Consumes: `ActivationProfile.isValidParkRefList` (Task 1).
- Produces: CQ POTA start is blocked unless every comma-separated ref is valid; UI copy communicates multi-ref support.

- [ ] **Step 1: Switch the startCq gate to list validation**

In `app/src/main/java/net/ft8vc/app/controllers/QsoSessionController.kt`, replace lines 177–179:

```kotlin
        if (potaModeEnabled && !ActivationProfile.isValidParkRef(potaParkRef)) {
            notifyFn("Set a valid POTA park reference in Settings (e.g. US-3315)", SnackbarEvent.Tag.ERROR)
```

with:

```kotlin
        if (potaModeEnabled && !ActivationProfile.isValidParkRefList(potaParkRef)) {
            notifyFn("Set valid POTA park reference(s) in Settings (e.g. US-3315 or US-3315,US-0891)", SnackbarEvent.Tag.ERROR)
```

- [ ] **Step 2: Update the Operate park sheet placeholder**

In `app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt`, in the POTA sheet's `OutlinedTextField`, change:

```kotlin
                    placeholder = { Text("US-3315") },
```

to:

```kotlin
                    placeholder = { Text("US-3315, US-0891") },
```

- [ ] **Step 3: Update the Settings park field hint**

In `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt`, locate the POTA park `OutlinedTextField` (around lines 99–108). Change its `placeholder` from `Text("US-3315")` to `Text("US-3315, US-0891")`, and update the supporting/hint text `"Required for POTA ADIF export"` to `"Required to call CQ POTA — comma-separate for two-fers"`. Any validity indicator in that block that calls `ActivationProfile.isValidParkRef(...)` on the whole field must switch to `ActivationProfile.isValidParkRefList(...)` (read the surrounding code and update every whole-field call; single-entry validation calls, if any, stay).

- [ ] **Step 4: Check for stale references**

Run: `grep -rn "isValidParkRef(" app/src core/src data/src --include="*.kt" | grep -v isValidParkRefList | grep -v "test"`
Expected: only `ActivationProfile.kt` (the definition) and legitimate single-ref uses remain. Any whole-field validation site found must be switched to `isValidParkRefList`.

- [ ] **Step 5: Run all unit tests**

Run: `./gradlew :core:testDebugUnitTest :data:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS. (`QsoSessionControllerTest` has no park-message assertions; if one fails on the new copy, update the expected string to match Step 1.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/controllers/QsoSessionController.kt app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt
git commit -m "feat(app): multi-park input validation and copy for CQ POTA gates"
```

---

### Task 12: Full verification sweep

**Files:** none created — verification only.

- [ ] **Step 1: Run every unit test suite**

Run: `./gradlew :core:testDebugUnitTest :data:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS, zero failures.

- [ ] **Step 2: Run instrumented tests (device required)**

Run: `./gradlew :data:connectedDebugAndroidTest`
Expected: PASS (`MigrationTest`, `QsoDaoTest`). If deferred earlier, this step is mandatory now.

- [ ] **Step 3: Assemble both variants**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Spec conformance read-through**

Re-read `docs/superpowers/specs/2026-07-01-pota-activator-logging-design.md` section by section and confirm each requirement maps to landed code. Record any deviation in the commit message or raise it before completion.

- [ ] **Step 5: Field verification (before promotion, per spec)**

On the reference rig (FT-891 + Digirig): log a park session with POTA mode on, export the activation from the Log screen, upload to pota.app, confirm clean accept. For a two-fer setting (`US-3315,US-0891`), confirm two activation rows appear and both files contain the same QSOs with the correct single park each. Upgrade from a v1-database install (previous unstable build) and confirm existing log rows survive with no parks.
