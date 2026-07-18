# First-Launch License Acknowledgment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `docs/superpowers/specs/2026-07-17-first-launch-license-ack-design.md`: a one-time, non-dismissable license/TX dialog at the navigation root on first launch, replacing the transmit-time popup.

**Architecture:** `SettingsSlice` gains a `hydrated` flag (default false, true on every real DataStore emission) that flows into `OperateUiState.settingsLoaded`; a pure rule `FirstLaunchLicense.shows(settingsLoaded, licenseAcknowledged)` drives an `AlertDialog` hosted in `PackRigApp`; the `gateOnLicense` machinery in `OperateScreen` is deleted.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3 `AlertDialog` + `DialogProperties`), JUnit4. No new dependencies.

## Global Constraints

- The downstream hard gate stays byte-untouched: `txOrchestrator.notifyRigReady(settingsBridge.slice.value.licenseAcknowledged)` (`OperateViewModel.kt:849`) and everything in `TxOrchestrator` — the license-gate milestone constraint ("TX stays gated behind license acknowledgment; nothing weakens the receive-only default").
- `acknowledgeLicense()` (`OperateViewModel.kt:675`) keeps its SAFETY-02 `notifyUsbReady` side effect unchanged.
- Persisted key `LICENSE_ACK` and `SettingsRepository.setLicenseAcknowledged` are reused as-is — no new preference.
- Dialog copy is fixed by the spec (structure approved by the owner):
  - Title: `Before you get on the air`
  - Body: `PackRig can key your radio and transmit. Transmitting requires a valid amateur radio license for your jurisdiction — you are responsible for lawful operation; this app and its authors are not. Receiving and decoding need no license.\n\nYou can change this anytime in Settings → General → Enable transmit.`
  - Confirm: `I understand — turn on TX`  Dismiss: `Just looking around (RX only)`
- Non-dismissable: `DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)`.
- Kotlin official style, 4-space indent, KDoc on public APIs.
- Test commands: `./gradlew :app:testDebugUnitTest` (module gate); full sweep `./gradlew testDebugUnitTest` in the final task.
- **Do NOT install anything to any connected phone in this plan** — the owner's field phone runs an unrelated branch build mid-verification; device smoke check is an owner gate, recorded, not executed.

---

### Task 1: Hydration flag through the settings pipeline

**Files:**
- Modify: `app/src/main/java/net/packrig/app/controllers/SettingsBridge.kt` (add `hydrated` to `SettingsSlice` + set it in `toSlice()`)
- Modify: `app/src/main/java/net/packrig/app/OperateUiState.kt:65` area (add `settingsLoaded`)
- Modify: `app/src/main/java/net/packrig/app/OperateViewModel.kt:229` area (map it)
- Test: `app/src/test/java/net/packrig/app/controllers/SettingsBridgeTest.kt`

**Interfaces:**
- Produces: `SettingsSlice.hydrated: Boolean = false` (true on every slice built from a real `StationSettings` emission); `OperateUiState.settingsLoaded: Boolean = false`.

- [ ] **Step 1: Write the failing test** — append to `SettingsBridgeTest.kt` (follow the file's existing harness for constructing the bridge and pushing a settings emission; the existing tests show the idiom):

```kotlin
    @Test
    fun slice_defaultIsNotHydrated_emissionMarksHydrated() = runTest {
        // Default slice (before any DataStore emission) must not claim hydration —
        // the first-launch license dialog keys off this to avoid flashing at
        // already-acknowledged users while settings load.
        assertFalse(SettingsSlice().hydrated)
        val bridge = makeBridge()               // use the file's existing factory/harness
        emitSettings(StationSettings())          // use the file's existing emission helper
        assertTrue(bridge.slice.value.hydrated)
    }
```

Adapt `makeBridge()`/`emitSettings(...)` to the file's actual helpers — the assertions are the contract, the harness idiom is the file's. If the file constructs the bridge inline with a fake repository flow, do the same here.

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "net.packrig.app.controllers.SettingsBridgeTest"`
Expected: compile error — `hydrated` unresolved.

- [ ] **Step 3: Implement.** In `SettingsSlice`, add as the first field (with KDoc):

```kotlin
    /** False only for the pre-DataStore default slice; true once real settings
     *  have been read. Gates first-launch UI that must not flash on defaults. */
    val hydrated: Boolean = false,
```

In `toSlice()`, add `hydrated = true,` as the first assignment. In `OperateUiState`, next to `licenseAcknowledged`:

```kotlin
    /** True once settings have been read from DataStore (see SettingsSlice.hydrated). */
    val settingsLoaded: Boolean = false,
```

In the `OperateUiState(...)` construction in `OperateViewModel` (line ~229), add `settingsLoaded = settings.hydrated,` beside `licenseAcknowledged = settings.licenseAcknowledged,`.

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "net.packrig.app.controllers.SettingsBridgeTest"`
Expected: PASS (all cases, pre-existing included).

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat(app): hydration flag on the settings slice"
```

---

### Task 2: First-launch dialog + rule at the navigation root

**Files:**
- Create: `app/src/main/java/net/packrig/app/ui/nav/FirstLaunchLicense.kt`
- Modify: `app/src/main/java/net/packrig/app/ui/nav/Ft8NavHost.kt` (host the dialog in `PackRigApp`)
- Test: `app/src/test/java/net/packrig/app/ui/nav/FirstLaunchLicenseTest.kt` (create)

**Interfaces:**
- Consumes: Task 1's `OperateUiState.settingsLoaded`; existing `OperateViewModel.acknowledgeLicense()` and `OperateViewModel.setTxEnabled(Boolean)`.
- Produces: `FirstLaunchLicense.shows(settingsLoaded: Boolean, licenseAcknowledged: Boolean): Boolean`; `@Composable fun FirstLaunchLicenseDialog(onEnableTx: () -> Unit, onRxOnly: () -> Unit)`.

- [ ] **Step 1: Write the failing test** — create `FirstLaunchLicenseTest.kt`:

```kotlin
package net.packrig.app.ui.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLaunchLicenseTest {

    @Test
    fun shows_onlyWhenHydratedAndUnacknowledged() {
        assertTrue(FirstLaunchLicense.shows(settingsLoaded = true, licenseAcknowledged = false))
        // The load-bearing case: defaults while DataStore hydrates must NOT
        // flash the dialog at already-acknowledged users.
        assertFalse(FirstLaunchLicense.shows(settingsLoaded = false, licenseAcknowledged = false))
        assertFalse(FirstLaunchLicense.shows(settingsLoaded = true, licenseAcknowledged = true))
        assertFalse(FirstLaunchLicense.shows(settingsLoaded = false, licenseAcknowledged = true))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "net.packrig.app.ui.nav.FirstLaunchLicenseTest"`
Expected: compile error — `FirstLaunchLicense` unresolved.

- [ ] **Step 3: Implement** — create `FirstLaunchLicense.kt`:

```kotlin
package net.packrig.app.ui.nav

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/** Pure show/hide rule for the first-launch license dialog (spec 2026-07-17). */
object FirstLaunchLicense {

    /** True only once settings are hydrated AND the license was never
     *  acknowledged — the hydration guard stops a one-frame flash at
     *  already-acknowledged users while DataStore loads. */
    fun shows(settingsLoaded: Boolean, licenseAcknowledged: Boolean): Boolean =
        settingsLoaded && !licenseAcknowledged
}

/**
 * One-time, non-dismissable license/TX acknowledgment shown at first launch
 * (spec 2026-07-17-first-launch-license-ack-design). Both buttons persist the
 * acknowledgment; they differ only in whether Enable transmit turns on.
 */
@Composable
fun FirstLaunchLicenseDialog(
    onEnableTx: () -> Unit,
    onRxOnly: () -> Unit,
) {
    AlertDialog(
        // Non-dismissable by design: one of the two buttons must be tapped.
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text("Before you get on the air") },
        text = {
            Text(
                "PackRig can key your radio and transmit. Transmitting requires " +
                    "a valid amateur radio license for your jurisdiction — you are " +
                    "responsible for lawful operation; this app and its authors " +
                    "are not. Receiving and decoding need no license.\n\n" +
                    "You can change this anytime in Settings → General → Enable transmit.",
            )
        },
        confirmButton = {
            TextButton(onClick = onEnableTx) { Text("I understand — turn on TX") }
        },
        dismissButton = {
            TextButton(onClick = onRxOnly) { Text("Just looking around (RX only)") }
        },
    )
}
```

In `Ft8NavHost.kt`, inside `PackRigApp` after the `Scaffold(...) { ... }` content (still inside `PackRigTheme`), add:

```kotlin
        if (FirstLaunchLicense.shows(operateState.settingsLoaded, operateState.licenseAcknowledged)) {
            FirstLaunchLicenseDialog(
                onEnableTx = {
                    operateVm.acknowledgeLicense()
                    operateVm.setTxEnabled(true)
                },
                onRxOnly = { operateVm.acknowledgeLicense() },
            )
        }
```

(Once `acknowledgeLicense()` persists, the settings flow re-emits with `licenseAcknowledged = true` and the dialog leaves composition — no local dismissed-state needed.)

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "net.packrig.app.ui.nav.FirstLaunchLicenseTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat(app): first-launch license acknowledgment dialog at the nav root"
```

---

### Task 3: Delete the transmit-time gate + full sweep

**Files:**
- Modify: `app/src/main/java/net/packrig/app/ui/operate/OperateScreen.kt` (delete `gateOnLicense` machinery)
- Test: full app + repo sweep (no new tests; deletions are covered by compilation + existing suites)

**Interfaces:**
- Consumes: nothing new. TX actions call the ViewModel directly.

- [ ] **Step 1: Delete** from `OperateScreen.kt`:
  - `var showLicenseDialog by remember { mutableStateOf(false) }` (line ~52)
  - `var pendingTxAction by remember { mutableStateOf<(() -> Unit)?>(null) }` (line ~54)
  - the whole `fun gateOnLicense(action: () -> Unit) { ... }` block (lines ~58-65)
  - the whole `if (showLicenseDialog) { AlertDialog( ... ) }` block (lines ~173-203, title "Confirm before transmitting")

  Rewire the three call sites to direct calls:

```kotlin
                onAnswerCq = { row -> vm.answerCq(row) },
                onResume = { row -> vm.resumeFromDecode(row) },
```

```kotlin
                onStartCq = { vm.startCq() },
```

  Then `grep -n "gateOnLicense\|pendingTxAction\|showLicenseDialog" app/src/main/java/net/packrig/app/ui/operate/OperateScreen.kt` — must return nothing. Remove any imports the deletions orphaned **only if** the compiler/IDE confirms they are now unused (`AlertDialog` is likely still used by the block-confirm dialog in the same file — check before removing).

- [ ] **Step 2: Full sweep**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL, zero failures. If any existing test referenced the deleted wiring, update it to the direct-call wiring — but assertions about `TxOrchestrator`/`AppRfState` gating must survive unchanged.

- [ ] **Step 3: Assemble**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. Do not install to any device (owner gate).

- [ ] **Step 4: Commit**

```bash
git add app/src
git commit -m "feat(app): remove the transmit-time license popup — first-launch dialog owns the acknowledgment"
```

- [ ] **Step 5: Record owner gates (do not claim done):**
  - [ ] Device smoke check (owner): fresh install or cleared app data → dialog shows once over the start tab; back-press does not dismiss; "turn on TX" leaves Enable transmit on; "RX only" leaves it off; relaunch shows no dialog. (Requires clearing `net.packrig.debug` data — coordinate with the CI-V build currently on the field phone.)
