# Log Tab Logbook Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the logbook maintenance controls (Backup now, Import ADIF…) from the Settings screen into a Log-tab overflow menu, plus an empty-state import button — same functionality, relocated UI.

**Architecture:** UI-only relocation. `backupAdifNow()`/`importAdif()` stay on `OperateViewModel` (import invalidates its worked-before cache; snackbars already surface app-wide via the `Ft8NavHost` host). `LogScreen` gains three slim parameters (`lastAdifBackupAtMs`, `onBackupNow`, `onImportAdif`) that `Ft8NavHost` supplies from `operateVm`, which it already holds. The Settings "Logbook" section is deleted.

**Tech Stack:** Kotlin + Jetpack Compose (Material 3), JUnit 4 unit tests, Gradle.

**Spec:** `docs/superpowers/specs/2026-07-11-log-tab-logbook-tools-design.md`

## Global Constraints

- Branch: `unstable`. No new dependencies.
- Behavior parity: same functions, same snackbar feedback, same `OpenDocument("*/*")` picker (`.adi` has no registered MIME type; the reader filters).
- The "QRZ Logbook" Settings section stays untouched.
- Kotlin official style, 4-space indent, no wildcard imports, one top-level public type per file.
- Run all Gradle commands from the repo root `/Users/bsmirks/git/ft8vc`.

---

### Task 1: Pure `lastBackupLabel` formatter

The "Last backup: X ago" string currently computed inline in `SettingsScreen.kt:381-389` becomes a pure, testable function in the log UI package, following the existing `filterByCall` pattern in `LogSearch.kt`.

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/ui/log/BackupLabel.kt`
- Test: `app/src/test/java/net/ft8vc/app/ui/log/BackupLabelTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `fun lastBackupLabel(lastBackupAtMs: Long?, nowMs: Long): String` in package `net.ft8vc.app.ui.log` — Task 2 calls it with `System.currentTimeMillis()` as `nowMs`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/ft8vc/app/ui/log/BackupLabelTest.kt`:

```kotlin
package net.ft8vc.app.ui.log

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupLabelTest {

    private val now = 1_700_000_000_000L

    @Test
    fun never_whenNoBackupTimestamp() {
        assertEquals("Last backup: never", lastBackupLabel(null, now))
    }

    @Test
    fun justNow_underOneMinute() {
        assertEquals("Last backup: just now", lastBackupLabel(now - 59_000, now))
    }

    @Test
    fun minutes_underOneHour() {
        assertEquals("Last backup: 5 min ago", lastBackupLabel(now - 5 * 60_000, now))
    }

    @Test
    fun hours_underOneDay() {
        assertEquals("Last backup: 3 h ago", lastBackupLabel(now - 3 * 3_600_000, now))
    }

    @Test
    fun days_overOneDay() {
        assertEquals("Last backup: 2 d ago", lastBackupLabel(now - 2 * 86_400_000, now))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.ui.log.BackupLabelTest"`
Expected: FAIL to compile — `lastBackupLabel` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/net/ft8vc/app/ui/log/BackupLabel.kt`:

```kotlin
package net.ft8vc.app.ui.log

/**
 * Human-readable age of the last ADIF auto-backup, e.g. "Last backup: 5 min ago".
 * Same buckets the Settings screen used before the controls moved to the Log tab.
 */
fun lastBackupLabel(lastBackupAtMs: Long?, nowMs: Long): String {
    val ms = lastBackupAtMs ?: return "Last backup: never"
    val ageMs = nowMs - ms
    return when {
        ageMs < 60_000 -> "Last backup: just now"
        ageMs < 3_600_000 -> "Last backup: ${ageMs / 60_000} min ago"
        ageMs < 86_400_000 -> "Last backup: ${ageMs / 3_600_000} h ago"
        else -> "Last backup: ${ageMs / 86_400_000} d ago"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.ui.log.BackupLabelTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/log/BackupLabel.kt app/src/test/java/net/ft8vc/app/ui/log/BackupLabelTest.kt
git commit -m "feat(log): pure last-backup age label for Log tab tools"
```

---

### Task 2: Log tab overflow menu, empty-state import, nav wiring

Add the three-dot overflow menu (backup status line, Backup now, Import ADIF…) to the Log top bar's default state, an Import ADIF… button to the empty state, and pass the callbacks from `Ft8NavHost`.

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/ui/log/LogScreen.kt`
- Modify: `app/src/main/java/net/ft8vc/app/ui/nav/Ft8NavHost.kt:119-121`

**Interfaces:**
- Consumes: `lastBackupLabel(lastBackupAtMs: Long?, nowMs: Long): String` from Task 1; existing `OperateViewModel.backupAdifNow(): Unit` and `OperateViewModel.importAdif(uri: Uri): Unit`; `OperateUiState.lastAdifBackupAtMs: Long?`.
- Produces: `LogScreen(vm: LogViewModel, lastAdifBackupAtMs: Long?, onBackupNow: () -> Unit, onImportAdif: (Uri) -> Unit)` — all new params defaulted so existing call sites still compile.

- [ ] **Step 1: Add imports to LogScreen.kt**

In `app/src/main/java/net/ft8vc/app/ui/log/LogScreen.kt`, add these imports (keep alphabetical order within their groups):

```kotlin
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
```

- [ ] **Step 2: Extend the LogScreen signature and local state**

Change the composable signature (currently `fun LogScreen(vm: LogViewModel = viewModel()) {`):

```kotlin
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LogScreen(
    vm: LogViewModel = viewModel(),
    lastAdifBackupAtMs: Long? = null,
    onBackupNow: () -> Unit = {},
    onImportAdif: (Uri) -> Unit = {},
) {
```

Below the existing `val searchFocus = remember { FocusRequester() }` line, add:

```kotlin
    var toolsMenuOpen by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onImportAdif) }
```

- [ ] **Step 3: Add the overflow menu to the top bar's default actions**

In the `actions = { ... }` block's final `else` branch (the default state), immediately after the clear-log `IconButton` (`Icon(Icons.Filled.Delete, contentDescription = "Clear log")` … `}`), add:

```kotlin
                        IconButton(onClick = { toolsMenuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Logbook tools")
                        }
                        DropdownMenu(
                            expanded = toolsMenuOpen,
                            onDismissRequest = { toolsMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(lastBackupLabel(lastAdifBackupAtMs, System.currentTimeMillis()))
                                        Text(
                                            "Auto-exports after every QSO to Documents/ft8vc",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                },
                                onClick = {},
                                enabled = false,
                            )
                            DropdownMenuItem(
                                text = { Text("Backup now") },
                                onClick = {
                                    toolsMenuOpen = false
                                    onBackupNow()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Import ADIF…") },
                                onClick = {
                                    toolsMenuOpen = false
                                    // .adi files have no registered MIME type; filter in the reader instead.
                                    importLauncher.launch(arrayOf("*/*"))
                                },
                            )
                        }
```

Note: the always-enabled menu is intentional — import into an empty log is the restore-after-reinstall path, and backing up an empty log matches the old Settings behavior.

- [ ] **Step 4: Add the empty-state import button**

Replace the empty-state block (currently a single `Text("Complete a QSO on Operate to populate your log.", ...)` inside `if (contacts.isEmpty()) { ... }`):

```kotlin
            if (contacts.isEmpty()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Complete a QSO on Operate to populate your log.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Text("Import ADIF…")
                    }
                }
            } else if (searchQuery.isNotBlank() && filteredContacts.isEmpty()) {
```

(The `else if` line is unchanged — shown for anchoring only.)

- [ ] **Step 5: Wire the params in Ft8NavHost**

In `app/src/main/java/net/ft8vc/app/ui/nav/Ft8NavHost.kt`, change:

```kotlin
                composable(Ft8Destination.Log.route) {
                    LogScreen(vm = logVm)
                }
```

to:

```kotlin
                composable(Ft8Destination.Log.route) {
                    LogScreen(
                        vm = logVm,
                        lastAdifBackupAtMs = operateState.lastAdifBackupAtMs,
                        onBackupNow = operateVm::backupAdifNow,
                        onImportAdif = operateVm::importAdif,
                    )
                }
```

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL, no warnings about unused imports.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/log/LogScreen.kt app/src/main/java/net/ft8vc/app/ui/nav/Ft8NavHost.kt
git commit -m "feat(log): logbook tools overflow menu and empty-state ADIF import"
```

---

### Task 3: Remove the Settings → Logbook section

Delete the relocated section from Settings and update the three doc comments that still point there.

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt:377-410` (and imports at lines 3-4)
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt:1035,1046`
- Modify: `app/src/main/java/net/ft8vc/app/AdifAutoBackup.kt:22-23`

**Interfaces:**
- Consumes: nothing from earlier tasks (independent of Tasks 1–2 at compile level, but lands after them so the controls are never absent from both screens).
- Produces: nothing new.

- [ ] **Step 1: Delete the Logbook section from SettingsScreen.kt**

Remove this entire block (between the `SettingsSection("Display") { ... }` block and the `SettingsSection("QRZ Logbook", ...)` block):

```kotlin
            SettingsSection("Logbook") {
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> uri?.let(vm::importAdif) }
                val lastBackupLabel = state.lastAdifBackupAtMs?.let { ms ->
                    val ageMs = System.currentTimeMillis() - ms
                    when {
                        ageMs < 60_000 -> "Last backup: just now"
                        ageMs < 3_600_000 -> "Last backup: ${ageMs / 60_000} min ago"
                        ageMs < 86_400_000 -> "Last backup: ${ageMs / 3_600_000} h ago"
                        else -> "Last backup: ${ageMs / 86_400_000} d ago"
                    }
                } ?: "Last backup: never"
                Text(lastBackupLabel, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = vm::backupAdifNow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Backup now")
                }
                OutlinedButton(
                    // .adi files have no registered MIME type; filter in the reader instead.
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Import ADIF…")
                }
                Text(
                    "ADIF auto-exports after every QSO to app-private storage " +
                        "and Documents/ft8vc (survives uninstall).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
```

- [ ] **Step 2: Remove the now-unused imports from SettingsScreen.kt**

Delete these two lines (the Logbook section was their only user; `Button` and `OutlinedButton` are used elsewhere in the file and stay):

```kotlin
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
```

- [ ] **Step 3: Update the doc comments**

In `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`:

```kotlin
    /** Phase 7 (UX-06): user-triggered backup from the Settings → Logbook row. */
```
becomes
```kotlin
    /** Phase 7 (UX-06): user-triggered backup from the Log tab's logbook tools menu. */
```

and

```kotlin
    /** Import an ADIF file picked in Settings → Logbook; merge with duplicate-skip. */
```
becomes
```kotlin
    /** Import an ADIF file picked on the Log tab; merge with duplicate-skip. */
```

In `app/src/main/java/net/ft8vc/app/AdifAutoBackup.kt`, the class KDoc line

```kotlin
 * Triggered after every QSO commit and via the Settings → Logbook "Backup
 * now" button. ...
```
becomes
```kotlin
 * Triggered after every QSO commit and via the Log tab's "Backup
 * now" menu item. ...
```

(Keep the rest of that sentence/paragraph exactly as it is.)

- [ ] **Step 4: Compile and run the app unit tests**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass. (Known flake: `reset_clearsLevelMeter` in DecodeController tests is a pre-existing nanoTime-throttle race — re-run once if it's the only failure.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt app/src/main/java/net/ft8vc/app/OperateViewModel.kt app/src/main/java/net/ft8vc/app/AdifAutoBackup.kt
git commit -m "refactor(settings): remove Logbook section, relocated to Log tab"
```

---

## Verification (after all tasks)

- `./gradlew :app:testDebugUnitTest` — full app unit suite green.
- Device smoke check on the unstable build: overflow menu opens; Backup now fires the "ADIF backup written" snackbar; Import ADIF opens the picker and merges a file; empty-state import button appears when the log is empty; Settings no longer shows the Logbook section (QRZ Logbook still present).
