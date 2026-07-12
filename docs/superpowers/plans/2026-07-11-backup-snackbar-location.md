# Backup Snackbar Location Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The "Backup now" snackbar reports where the ADIF backup landed — "written to Documents/ft8vc" only when the durable mirror actually succeeded, an honest app-private fallback otherwise.

**Architecture:** `AdifAutoBackup.backupNow` stops discarding `DocumentsAdifMirror.write`'s Boolean and returns a nested `Outcome(privateFile, mirrored)` instead of `File?`. A pure `backupSnackbarText(mirrored)` function picks the success message (unit-tested). `OperateViewModel.backupAdifNow` uses it; failure path unchanged.

**Tech Stack:** Kotlin, JUnit 4 unit tests, Gradle.

**Spec:** `docs/superpowers/specs/2026-07-11-backup-snackbar-location-design.md`

## Global Constraints

- Branch: `unstable`. No new dependencies.
- Exact copy: success+mirror = `ADIF backup written to Documents/ft8vc`; success only = `ADIF backup written (app-private storage only)`; failure = `ADIF backup failed` (unchanged).
- Mirror-failed case stays `SnackbarEvent.Tag.TRANSIENT` (private backup succeeded; Android 9 has no mirror by design). Failure stays `ERROR`.
- Callers that ignore `backupNow`'s return value (`scheduleBackupAfterQso`, daily timer) must need no changes.
- Run Gradle from repo root `/Users/bsmirks/git/ft8vc`.

---

### Task 1: `AdifAutoBackup.Outcome` + pure `backupSnackbarText`

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/AdifAutoBackup.kt` (return type of `backupNow` at ~line 72-101; new nested type + function)
- Test: `app/src/test/java/net/ft8vc/app/AdifBackupSnackbarTextTest.kt` (create)

**Interfaces:**
- Consumes: existing `DocumentsAdifMirror.write(context: Context, adif: String): Boolean`.
- Produces: `data class AdifAutoBackup.Outcome(val privateFile: File, val mirrored: Boolean)`; `suspend fun backupNow(context, logbook, settings): Outcome?`; `fun AdifAutoBackup.backupSnackbarText(mirrored: Boolean): String`. Task 2 relies on all three.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/ft8vc/app/AdifBackupSnackbarTextTest.kt`:

```kotlin
package net.ft8vc.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AdifBackupSnackbarTextTest {

    @Test
    fun namesDocumentsDir_whenMirrorSucceeded() {
        assertEquals(
            "ADIF backup written to Documents/ft8vc",
            AdifAutoBackup.backupSnackbarText(mirrored = true),
        )
    }

    @Test
    fun admitsPrivateOnly_whenMirrorFailed() {
        assertEquals(
            "ADIF backup written (app-private storage only)",
            AdifAutoBackup.backupSnackbarText(mirrored = false),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.AdifBackupSnackbarTextTest"`
Expected: FAIL to compile — `backupSnackbarText` unresolved.

- [ ] **Step 3: Implement Outcome and backupSnackbarText**

In `app/src/main/java/net/ft8vc/app/AdifAutoBackup.kt`, inside `object AdifAutoBackup`, add below the `TMP_NAME` constant:

```kotlin
    /** Result of [backupNow]: where the private copy lives, and whether the Documents mirror succeeded. */
    data class Outcome(val privateFile: File, val mirrored: Boolean)

    /** Success snackbar copy — only claims Documents/ft8vc when the durable mirror was written. */
    fun backupSnackbarText(mirrored: Boolean): String =
        if (mirrored) "ADIF backup written to Documents/ft8vc"
        else "ADIF backup written (app-private storage only)"
```

Change `backupNow`'s signature and tail (KDoc updated to match):

```kotlin
    /** Write the current logbook to disk atomically. Returns the [Outcome] on success, null on failure. */
    suspend fun backupNow(
        context: Context,
        logbook: Logbook,
        settings: SettingsRepository,
    ): Outcome? = withContext(Dispatchers.IO) {
```

and replace the last two lines of the `try` block (currently `DocumentsAdifMirror.write(context, adif)` then `target`):

```kotlin
            settings.setLastAdifBackupAtMs(System.currentTimeMillis())
            Outcome(target, mirrored = DocumentsAdifMirror.write(context, adif))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.AdifBackupSnackbarTextTest"`
Expected: PASS (2 tests). (`OperateViewModel`'s `result != null` check compiles unchanged against `Outcome?`.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/AdifAutoBackup.kt app/src/test/java/net/ft8vc/app/AdifBackupSnackbarTextTest.kt
git commit -m "feat(app): backupNow reports whether the Documents mirror succeeded"
```

---

### Task 2: Wire the message into `OperateViewModel.backupAdifNow`

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt:1035-1044`

**Interfaces:**
- Consumes: `AdifAutoBackup.backupNow(...): AdifAutoBackup.Outcome?` and `AdifAutoBackup.backupSnackbarText(mirrored: Boolean): String` from Task 1; existing `notify(text: String, tag: SnackbarEvent.Tag)`.
- Produces: nothing new.

- [ ] **Step 1: Update backupAdifNow**

Replace the body of `backupAdifNow` (currently mapping `result != null` to the fixed "ADIF backup written" string):

```kotlin
    /** Phase 7 (UX-06): user-triggered backup from the Log tab's logbook tools menu. */
    fun backupAdifNow() {
        viewModelScope.launch {
            val result = AdifAutoBackup.backupNow(getApplication(), logbook, settingsRepo)
            if (result != null) {
                notify(AdifAutoBackup.backupSnackbarText(result.mirrored), SnackbarEvent.Tag.TRANSIENT)
            } else {
                notify("ADIF backup failed", SnackbarEvent.Tag.ERROR)
            }
        }
    }
```

- [ ] **Step 2: Compile and run the app unit suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass. (Known flake: `reset_clearsLevelMeter` is a pre-existing nanoTime-throttle race — re-run once if it's the only failure.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateViewModel.kt
git commit -m "feat(app): Backup now snackbar names the Documents/ft8vc location"
```

---

## Verification (after all tasks)

- `./gradlew :app:testDebugUnitTest` green.
- Device smoke check (field phone, API 29+): Backup now shows "ADIF backup written to Documents/ft8vc" and the file is visible in a file manager.
