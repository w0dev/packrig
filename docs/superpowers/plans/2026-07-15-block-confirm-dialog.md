# Block-Confirmation Dialog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Long-pressing a decode row asks "Block \<call\>?" (OK/Cancel + "Don't ask me again" checkbox) before blocking, gated by a new default-on "Confirm before blocking" setting with a Switch in Settings → Blocklist.

**Architecture:** One new boolean setting `blockConfirmEnabled` plumbed along the existing `cq73OnlyFilter` path (DataStore key → `StationSettings` → `SettingsBridge` slice → `OperateUiState`, plus a `OperateViewModel` setter). `OperateScreen` gains a remembered `pendingBlockCall` and a private `BlockConfirmDialog` composable; the long-press callback either opens the dialog or blocks directly. Blocking semantics (`blockStation`, `AbandonedPartners`) untouched.

**Tech Stack:** Kotlin, Jetpack Compose Material 3 (`AlertDialog`, `Checkbox`, `Switch`), DataStore Preferences, JUnit4 + MockK + Turbine (existing `SettingsBridgeTest` pattern).

**Spec:** `docs/superpowers/specs/2026-07-15-block-confirm-dialog-design.md`

## Global Constraints

- `blockConfirmEnabled` defaults to **true** everywhere (StationSettings, SettingsSlice, OperateUiState, DataStore fallback).
- Blocking behavior itself must not change; only a confirmation gate in front of it. RX/TX/CAT untouched.
- Dialog copy exactly: title `Block <call>?`, body `Their decodes will be hidden. You can unblock in Settings.`, checkbox `Don't ask me again`, buttons `OK` / `Cancel`.
- Kotlin official style, 4-space indent, no new dependencies.
- Land on `unstable`.

---

### Task 1: Settings plumbing for `blockConfirmEnabled` (TDD)

**Files:**
- Modify: `app/src/main/java/net/packrig/app/settings/StationSettings.kt:65` (add field after `cq73OnlyFilter`)
- Modify: `app/src/main/java/net/packrig/app/settings/SettingsRepository.kt:58` (read), `:252` area (setter), `:343` area (key)
- Modify: `app/src/main/java/net/packrig/app/controllers/SettingsBridge.kt:82` (toSlice) and `:117` (slice field)
- Modify: `app/src/main/java/net/packrig/app/OperateUiState.kt:73` (add field)
- Modify: `app/src/main/java/net/packrig/app/OperateViewModel.kt:234` area (state mapping) and `:608` area (setter)
- Test: `app/src/test/java/net/packrig/app/settings/StationSettingsDefaultsTest.kt` (add test)
- Test: `app/src/test/java/net/packrig/app/controllers/SettingsBridgeTest.kt` (add test)

**Interfaces:**
- Consumes: existing `cq73OnlyFilter` plumbing as the pattern.
- Produces (used by Task 2):
  - `OperateUiState.blockConfirmEnabled: Boolean` (default `true`)
  - `OperateViewModel.setBlockConfirmEnabled(enabled: Boolean)`

- [ ] **Step 1: Write the failing tests**

In `app/src/test/java/net/packrig/app/settings/StationSettingsDefaultsTest.kt`, add inside the class:

```kotlin
    @Test
    fun blockConfirmEnabledDefaultsTrue() {
        val s = StationSettings()
        assertTrue("Block confirmation must default to ON", s.blockConfirmEnabled)
    }
```

In `app/src/test/java/net/packrig/app/controllers/SettingsBridgeTest.kt`, add inside the class (same pattern as `sliceCarriesLateStartTxEnabled`):

```kotlin
    @Test
    fun sliceCarriesBlockConfirmEnabled() = runTest {
        val (repo, flow) = makeRepo(initial = defaultSettings.copy(blockConfirmEnabled = false))
        val bridge = SettingsBridge(repo, bridgeScope)

        bridge.slice.test {
            assertEquals(false, awaitItem().blockConfirmEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "net.packrig.app.settings.StationSettingsDefaultsTest" --tests "net.packrig.app.controllers.SettingsBridgeTest"`
Expected: FAILED — compilation error, unresolved reference `blockConfirmEnabled`.

- [ ] **Step 3: Implement the plumbing**

`app/src/main/java/net/packrig/app/settings/StationSettings.kt` — after `val cq73OnlyFilter: Boolean = false,` (line 65) add:

```kotlin
    val blockConfirmEnabled: Boolean = true,
```

`app/src/main/java/net/packrig/app/settings/SettingsRepository.kt`:

After `cq73OnlyFilter = prefs[Keys.CQ73_FILTER] ?: false,` (line 58) add:

```kotlin
            blockConfirmEnabled = prefs[Keys.BLOCK_CONFIRM] ?: true,
```

After the `setCq73OnlyFilter` function (line 252-254) add:

```kotlin
    suspend fun setBlockConfirmEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.BLOCK_CONFIRM] = enabled }
    }
```

In the `Keys` object, after `val CQ73_FILTER = booleanPreferencesKey("cq73_filter")` (line 343) add:

```kotlin
        val BLOCK_CONFIRM = booleanPreferencesKey("block_confirm")
```

`app/src/main/java/net/packrig/app/controllers/SettingsBridge.kt`:

In `toSlice()`, after `cq73OnlyFilter = cq73OnlyFilter,` (line 82) add:

```kotlin
        blockConfirmEnabled = blockConfirmEnabled,
```

In `data class SettingsSlice`, after `val cq73OnlyFilter: Boolean = false,` (line 117) add:

```kotlin
    val blockConfirmEnabled: Boolean = true,
```

`app/src/main/java/net/packrig/app/OperateUiState.kt` — after `val cq73OnlyFilter: Boolean = false,` (line 73) add:

```kotlin
    val blockConfirmEnabled: Boolean = true,
```

`app/src/main/java/net/packrig/app/OperateViewModel.kt`:

In the `OperateUiState(...)` construction, after `cq73OnlyFilter = settings.cq73OnlyFilter,` (line 234) add:

```kotlin
                blockConfirmEnabled = settings.blockConfirmEnabled,
```

After the `setCq73OnlyFilter` function (line 608-610) add:

```kotlin
    fun setBlockConfirmEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setBlockConfirmEnabled(enabled) }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "net.packrig.app.settings.StationSettingsDefaultsTest" --tests "net.packrig.app.controllers.SettingsBridgeTest"`
Expected: BUILD SUCCESSFUL; StationSettingsDefaultsTest 2 tests, SettingsBridgeTest 9 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/packrig/app/settings/StationSettings.kt \
        app/src/main/java/net/packrig/app/settings/SettingsRepository.kt \
        app/src/main/java/net/packrig/app/controllers/SettingsBridge.kt \
        app/src/main/java/net/packrig/app/OperateUiState.kt \
        app/src/main/java/net/packrig/app/OperateViewModel.kt \
        app/src/test/java/net/packrig/app/settings/StationSettingsDefaultsTest.kt \
        app/src/test/java/net/packrig/app/controllers/SettingsBridgeTest.kt
git commit -m "feat(operate): add blockConfirmEnabled setting (default on)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Confirmation dialog + Settings switch

**Files:**
- Modify: `app/src/main/java/net/packrig/app/ui/operate/OperateScreen.kt` (imports, `pendingBlockCall` state, `onBlockSender` lambda at line 140, dialog invocation after the license dialog block at line 167-195, private `BlockConfirmDialog` composable at file bottom)
- Modify: `app/src/main/java/net/packrig/app/settings/SettingsScreen.kt:247` (Switch row under the "Blocklist" header)

**Interfaces:**
- Consumes (from Task 1): `state.blockConfirmEnabled: Boolean`, `vm.setBlockConfirmEnabled(enabled: Boolean)`; existing `vm.blockStation(call: String)`.
- Produces: nothing consumed downstream; UI only.

- [ ] **Step 1: Wire the dialog into `OperateScreen`**

Add imports (file already imports `AlertDialog`, `TextButton`, `Column`, `mutableStateOf`, `remember`, `getValue`, `setValue`):

```kotlin
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
```

Next to the other remembered dialog state (after `var showLicenseDialog by remember { mutableStateOf(false) }`, line 49):

```kotlin
    var pendingBlockCall by remember { mutableStateOf<String?>(null) }
```

Replace the `onBlockSender` wiring (line 140):

```kotlin
                onBlockSender = { call -> vm.blockStation(call) },
```

with:

```kotlin
                onBlockSender = { call ->
                    if (state.blockConfirmEnabled) pendingBlockCall = call else vm.blockStation(call)
                },
```

Immediately after the closing brace of the `if (showLicenseDialog) { ... }` block (lines 167-195), add:

```kotlin
    pendingBlockCall?.let { call ->
        BlockConfirmDialog(
            call = call,
            onConfirm = { dontAskAgain ->
                vm.blockStation(call)
                if (dontAskAgain) vm.setBlockConfirmEnabled(false)
                pendingBlockCall = null
            },
            onDismiss = { pendingBlockCall = null },
        )
    }
```

At the bottom of the file, add the private composable:

```kotlin
@Composable
private fun BlockConfirmDialog(
    call: String,
    onConfirm: (dontAskAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var dontAskAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block $call?") },
        text = {
            Column {
                Text("Their decodes will be hidden. You can unblock in Settings.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = dontAskAgain, onCheckedChange = { dontAskAgain = it })
                    Text("Don't ask me again")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dontAskAgain) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
```

- [ ] **Step 2: Add the Settings switch**

In `app/src/main/java/net/packrig/app/settings/SettingsScreen.kt`, immediately after the "Blocklist" header `Text` (lines 243-247, `Text("Blocklist", ...)`), add (same row pattern as the "Enable transmit" switch at lines 182-199; all names already imported in this file):

```kotlin
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Confirm before blocking", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Ask before a long-press blocks a station",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Switch(
                    checked = state.blockConfirmEnabled,
                    onCheckedChange = vm::setBlockConfirmEnabled,
                )
            }
```

- [ ] **Step 3: Run the full unit suite and build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, no test failures.

- [ ] **Step 4: Install on the field phone and verify by hand**

Run: `~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: `Success`.

Manual check: long-press a decode → dialog appears with the exact copy; Cancel blocks nothing; OK blocks (row disappears, call listed in Settings → Blocklist); reopen dialog, tick "Don't ask me again" + OK → subsequent long-presses block instantly; Settings → Blocklist shows "Confirm before blocking" off; flipping it back on restores the dialog. Unblock the test station afterwards. This is the user's check — report and wait for their verdict.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/packrig/app/ui/operate/OperateScreen.kt \
        app/src/main/java/net/packrig/app/settings/SettingsScreen.kt
git commit -m "feat(operate): confirm before blocking a station from long-press

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Verification

- Unit: `./gradlew :app:testDebugUnitTest` green (defaults + slice mapping covered).
- Manual on phone: dialog flow as in Task 2 Step 4, including the don't-ask-again path and re-enabling from Settings.
- Field gate: none beyond the manual check — display/UX only, no RX/TX/CAT surface.
