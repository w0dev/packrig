# Audio Input "Automatic" Label & Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Rename the audio-input picker's empty state to "Automatic (system default)", show whether the current device is manual or auto-picked, and add an "Automatic" dropdown entry that clears a persisted manual selection.

**Architecture:** A pure label-derivation function (unit-tested) consumed by the Compose `DevicePicker`; a presentation-only `audioDeviceManuallySelected` flag derived in `OperateViewModel`'s `combine()`; `selectDevice` becomes nullable, where `null` clears the DataStore key (already supported by `SettingsRepository`) and re-runs the existing USB auto-pick. No change to capture/routing behavior.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3 `ExposedDropdownMenuBox`), JUnit4 JVM unit tests.

Spec: `docs/superpowers/specs/2026-07-12-audio-input-automatic-label-design.md`

## Global Constraints

- No new dependencies.
- RX/TX/CAT behavior parity: capture already skips `setPreferredDevice()` when no id is set; the auto-pick in `refreshDevices()` is untouched.
- Exact copy strings (use verbatim):
  - Empty/automatic label: `Automatic (system default)`
  - Auto-picked label format: `Automatic — <name> (<typeLabel>)` (em dash, U+2014)
  - Manual label format: `<name> (<typeLabel>)` (unchanged)
  - Dropdown first entry: `Automatic (system default)`
  - Audio section info text: `Audio routes automatically: when a USB interface (Digirig or the radio's built-in USB audio) is attached, it's used for RX and TX — no selection needed. Pick a device manually only if automatic routing chooses the wrong one (e.g. a USB hub or multiple audio devices). Adjust input level on the Operate tab while monitoring.`
- Kotlin official style, 4-space indent, no wildcard imports, single top-level public type per file.
- Run unit tests with `./gradlew :app:testDebugUnitTest` (scope with `--tests` per task).

---

### Task 1: Pure label-derivation function

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/settings/AudioInputLabel.kt`
- Test: `app/src/test/java/net/ft8vc/app/settings/AudioInputLabelTest.kt`

**Interfaces:**
- Consumes: `net.ft8vc.audio.AudioInputDevice` (existing: `id: Int`, `name: String`, `type: Int`, `isUsb: Boolean`, derived `typeLabel: String`; USB device type constant is `android.media.AudioDeviceInfo.TYPE_USB_DEVICE`, whose `typeLabel` is `"USB device"`).
- Produces: `fun audioInputDeviceLabel(selected: AudioInputDevice?, manuallySelected: Boolean): String` — Task 4 calls this from `DevicePicker`.

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/net/ft8vc/app/settings/AudioInputLabelTest.kt`:

```kotlin
package net.ft8vc.app.settings

import android.media.AudioDeviceInfo
import net.ft8vc.audio.AudioInputDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioInputLabelTest {

    private val digirig = AudioInputDevice(
        id = 7,
        name = "Digirig",
        type = AudioDeviceInfo.TYPE_USB_DEVICE,
        isUsb = true,
    )

    @Test
    fun manualSelection_showsDeviceNameAndType() {
        assertEquals(
            "Digirig (USB device)",
            audioInputDeviceLabel(selected = digirig, manuallySelected = true),
        )
    }

    @Test
    fun autoPickedDevice_showsAutomaticPrefix() {
        assertEquals(
            "Automatic — Digirig (USB device)",
            audioInputDeviceLabel(selected = digirig, manuallySelected = false),
        )
    }

    @Test
    fun noDevice_showsSystemDefault() {
        assertEquals(
            "Automatic (system default)",
            audioInputDeviceLabel(selected = null, manuallySelected = false),
        )
    }

    @Test
    fun noDevice_ignoresManualFlag() {
        // Defensive: a stale manual id that matches no device still reads as automatic.
        assertEquals(
            "Automatic (system default)",
            audioInputDeviceLabel(selected = null, manuallySelected = true),
        )
    }
}
```

Note: `AudioDeviceInfo.TYPE_USB_DEVICE` is a static int constant — check whether other tests in `app/src/test` reference Android constants directly (e.g. via `unitTests.isReturnDefaultValues = true` in `app/build.gradle.kts`). If the constant is not resolvable on the JVM, replace `type = AudioDeviceInfo.TYPE_USB_DEVICE` with `type = 11` (the framework value of `TYPE_USB_DEVICE`) and expect label `"USB device"` the same way, dropping the import.

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.AudioInputLabelTest"`
Expected: FAIL to compile — `audioInputDeviceLabel` unresolved.

- [x] **Step 3: Write minimal implementation**

Create `app/src/main/java/net/ft8vc/app/settings/AudioInputLabel.kt`:

```kotlin
package net.ft8vc.app.settings

import net.ft8vc.audio.AudioInputDevice

/**
 * Label for the Settings audio-input picker.
 *
 * Three states: a manual (persisted) pick shows the device as-is; an
 * auto-picked device is prefixed with "Automatic" so the operator can see
 * routing chose it; no device at all reads as the healthy system default —
 * never as a missing selection.
 */
fun audioInputDeviceLabel(selected: AudioInputDevice?, manuallySelected: Boolean): String =
    when {
        selected == null -> "Automatic (system default)"
        manuallySelected -> "${selected.name} (${selected.typeLabel})"
        else -> "Automatic — ${selected.name} (${selected.typeLabel})"
    }
```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.AudioInputLabelTest"`
Expected: PASS (4 tests).

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/AudioInputLabel.kt \
        app/src/test/java/net/ft8vc/app/settings/AudioInputLabelTest.kt
git commit -m "feat(app): pure label derivation for audio input picker states"
```

---

### Task 2: `audioDeviceManuallySelected` flag in UI state

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt:79` (Rx section, next to `selectedDeviceId`)
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt:238` (the `combine()` assembly)

**Interfaces:**
- Consumes: `SettingsBridge` slice field `selectedAudioDeviceId: Int?` (existing, `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt:95`).
- Produces: `OperateUiState.audioDeviceManuallySelected: Boolean` (default `false`) — Task 4 reads it in `DevicePicker`.

No JVM test seam exists for the `combine()` assembly (it lives inside the AndroidViewModel); the derivation is a single expression verified by compilation here and exercised end-to-end in the Task 5 device check. The flag's consumer logic is already covered by Task 1's tests.

- [x] **Step 1: Add the field to `OperateUiState`**

In `app/src/main/java/net/ft8vc/app/OperateUiState.kt`, directly below `val selectedDeviceId: Int? = null,` (line 79):

```kotlin
    val selectedDeviceId: Int? = null,
    /** True when [selectedDeviceId] came from a persisted manual pick, not the USB auto-pick. */
    val audioDeviceManuallySelected: Boolean = false,
```

- [x] **Step 2: Derive it in the ViewModel combine**

In `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`, directly below the `selectedDeviceId` line in the `OperateUiState(...)` construction (line 238):

```kotlin
                selectedDeviceId = settings.selectedAudioDeviceId ?: view.selectedDeviceId,
                audioDeviceManuallySelected = settings.selectedAudioDeviceId != null,
```

- [x] **Step 3: Verify the app module compiles and existing tests pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no failures. (Known flake: `reset_clearsLevelMeter` in DecodeController tests is a pre-existing timing race — re-run once before suspecting this change.)

- [x] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateUiState.kt \
        app/src/main/java/net/ft8vc/app/OperateViewModel.kt
git commit -m "feat(app): expose whether audio device selection is manual in UI state"
```

---

### Task 3: Nullable `selectDevice` — clearing back to automatic

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt:555-561` (`selectDevice`)

**Interfaces:**
- Consumes: `SettingsRepository.setSelectedAudioDeviceId(id: Int?)` (existing — `null` removes the DataStore key, `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt:95`); `refreshDevices()` (existing — re-runs the first-USB auto-pick into view state).
- Produces: `fun selectDevice(id: Int?)` — Task 4's `DevicePicker` calls it with `null` for the Automatic entry and a device id otherwise. The existing call site `vm::selectDevice` in `SettingsScreen.kt` keeps working once `DevicePicker`'s `onSelect` parameter is `(Int?) -> Unit` (Task 4).

Like Task 2, `selectDevice` sits on the AndroidViewModel with no JVM test seam (it touches `AudioInputs.list(getApplication())` via `refreshDevices()` and the DataStore-backed repo); the repository's `null`-clears-key behavior already exists. Verified by compilation here plus the Task 5 device check.

- [x] **Step 1: Make `selectDevice` nullable and re-run the auto-pick on clear**

Replace the existing function at `app/src/main/java/net/ft8vc/app/OperateViewModel.kt:555-561`:

```kotlin
    /** Select an input device manually, or pass null to return to automatic routing. */
    fun selectDevice(id: Int?) {
        val wasActive = state.value.isCapturing
        if (wasActive) stopCapture()
        _viewState.update { it.copy(selectedDeviceId = id) }
        viewModelScope.launch { settingsRepo.setSelectedAudioDeviceId(id) }
        if (id == null) refreshDevices()
        if (wasActive) beginCapture()
    }
```

Behavior notes (already satisfied by the code above — do not add more):
- Stop/restart capture around the change is preserved for both paths.
- `refreshDevices()` before `beginCapture()` so a restarted capture prefers the re-auto-picked USB device.
- Persisting `null` removes the DataStore key, so `settings.selectedAudioDeviceId` becomes `null` and the combine falls back to the auto-picked view-state id (`settings.selectedAudioDeviceId ?: view.selectedDeviceId`), flipping `audioDeviceManuallySelected` to `false`.

- [x] **Step 2: Verify the app module compiles and existing tests pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no failures.

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateViewModel.kt
git commit -m "feat(app): selectDevice(null) clears manual pick back to automatic"
```

---

### Task 4: DevicePicker UI — Automatic entry, label states, info text

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt:110-118` (Audio section info text)
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt:427-456` (`DevicePicker`)

**Interfaces:**
- Consumes: `audioInputDeviceLabel(selected, manuallySelected)` (Task 1); `OperateUiState.audioDeviceManuallySelected` (Task 2); `selectDevice(id: Int?)` via the existing `onSelect = vm::selectDevice` call site (Task 3).
- Produces: user-facing UI only; nothing downstream consumes this.

Compose UI has no JVM test in this project's conventions; the label logic it renders is covered by Task 1's tests. Visual verification happens in Task 5.

- [x] **Step 1: Replace the Audio section info text**

At `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt:110-118`, replace the section body text:

```kotlin
            SettingsSection("Audio") {
                DevicePicker(state = state, onSelect = vm::selectDevice)
                Text(
                    "Audio routes automatically: when a USB interface (Digirig or the " +
                        "radio's built-in USB audio) is attached, it's used for RX and TX — " +
                        "no selection needed. Pick a device manually only if automatic " +
                        "routing chooses the wrong one (e.g. a USB hub or multiple audio " +
                        "devices). Adjust input level on the Operate tab while monitoring.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
```

- [x] **Step 2: Rework `DevicePicker`**

Replace the function at `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt:427-456`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePicker(state: OperateUiState, onSelect: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.devices.firstOrNull { it.id == state.selectedDeviceId }
    val label = audioInputDeviceLabel(selected, state.audioDeviceManuallySelected)

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (!state.isCapturing) expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            enabled = !state.isCapturing && !state.isTransmitting,
            label = { Text("Audio input") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Automatic (system default)") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            state.devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text("${device.name} (${device.typeLabel})") },
                    onClick = {
                        expanded = false
                        onSelect(device.id)
                    },
                )
            }
        }
    }
}
```

Note: `AudioInputLabel.kt` is in the same `net.ft8vc.app.settings` package as `SettingsScreen.kt`, so no import is needed for `audioInputDeviceLabel`.

- [x] **Step 3: Verify the app compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt
git commit -m "feat(app): Automatic entry and label states in audio input picker"
```

---

### Task 5: Full verification

**Files:** none (verification only).

- [x] **Step 1: Run the full app unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (modulo the known `reset_clearsLevelMeter` flake — re-run once if it trips).

- [x] **Step 2: Assemble the unstable debug build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Device smoke check (deferred to owner if no device attached)**

On a device (wireless adb per project practice — pull the logbook first if running anything that could wipe data; plain install/launch does not):
1. Settings → Audio with no USB attached → field shows `Automatic (system default)`.
2. Attach Digirig → field shows `Automatic — Digirig (USB device)` (auto-pick, name may differ per device).
3. Pick the Digirig manually from the dropdown → field shows `Digirig (USB device)`.
4. Open the dropdown → first entry is `Automatic (system default)`; select it → field returns to the `Automatic — …` form and the persisted key is cleared (survives app restart: kill and relaunch, field still shows `Automatic — …`).
5. Start decoding with automatic selection → decodes still arrive (behavior parity).

If no device is available in this session, report the smoke check as pending — do not claim it done.
