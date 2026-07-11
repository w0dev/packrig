# Auto RX-Monitor on Radio Connect — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Audio capture (waterfall + decodes) starts automatically whenever a radio's USB audio is connected, the app is foregrounded, and RECORD_AUDIO is granted — without touching CAT/PTT/QSO until the operator presses Start.

**Architecture:** A pure decision object (`MonitorGate`, patterned on `CaptureWatchdog`) holds all gating logic and is fully unit-tested. `OperateViewModel` wires it to three triggers (USB attach → `refreshDevices`, process-lifecycle `onStart`, first settings emission) and two stop paths (process-lifecycle `onStop`, USB audio removal). A new `autoMonitorEnabled` DataStore pref (default ON) flows through the existing `StationSettings → SettingsBridge → SettingsSlice → OperateUiState` pipeline.

**Tech Stack:** Kotlin, Jetpack Compose, DataStore Preferences, AndroidX ProcessLifecycleOwner, JUnit4 + MockK + Turbine (existing app test stack).

**Spec:** `docs/superpowers/specs/2026-07-11-auto-rx-monitor-design.md` (approved 2026-07-11).

## Global Constraints

- No new top-level dependencies.
- Monitor mode is receive-only by construction: it may only call `beginCapture()` / `stopCapture()` — never `prepareRig()`, PTT, CAT, or QSO-session methods.
- Monitor never prompts for RECORD_AUDIO; if not granted it silently does not start.
- No foreground service; monitor capture stops when the app leaves the foreground.
- UX must not crowd main-screen real estate (milestone rule): the only new Operate-screen element is one `CompactChip`.
- Kotlin official style, 4-space indent, no wildcard imports, KDoc on new public types.
- Behavior when the operator presses **Stop** while operating: capture stops and monitor does NOT immediately restart (Stop wins until the next trigger: replug, background→foreground, or app relaunch). This is intentional — do not "fix" it.

---

### Task 0: Feature worktree

**Files:** none (git setup)

This feature lands on its own branch off `multi-rig` (NOT stacked on `rig-profiles`). The spec commit `57967a8` currently lives on `rig-profiles` and must be cherry-picked.

- [ ] **Step 1: Create the worktree and branch**

```bash
cd /Users/bsmirks/git/ft8vc
git worktree add .claude/worktrees/auto-rx-monitor -b auto-rx-monitor multi-rig
cp local.properties .claude/worktrees/auto-rx-monitor/local.properties
```

(Fresh worktrees need `local.properties` copied from the main checkout — known project gotcha.)

- [ ] **Step 2: Cherry-pick the spec commit**

```bash
cd .claude/worktrees/auto-rx-monitor
git cherry-pick 57967a8
```

Expected: clean cherry-pick adding `docs/superpowers/specs/2026-07-11-auto-rx-monitor-design.md`.

- [ ] **Step 3: Sanity build**

```bash
./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.CaptureWatchdogTest" -q
```

Expected: BUILD SUCCESSFUL (proves the worktree compiles and unit tests run).

All subsequent tasks run inside `.claude/worktrees/auto-rx-monitor/`.

---

### Task 1: MonitorGate — pure gating logic (TDD)

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/controllers/MonitorGate.kt`
- Test: `app/src/test/java/net/ft8vc/app/controllers/MonitorGateTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin object).
- Produces (used verbatim by Task 3):
  - `MonitorGate.shouldStartMonitor(autoMonitorEnabled: Boolean, appInForeground: Boolean, usbAudioInputPresent: Boolean, recordAudioGranted: Boolean, isCapturing: Boolean, isTransmitting: Boolean): Boolean`
  - `MonitorGate.shouldStopOnBackground(isCapturing: Boolean, isOperating: Boolean): Boolean`
  - `MonitorGate.onUsbAudioInputRemoved(isCapturing: Boolean, isOperating: Boolean, usbInputStillPresent: Boolean): MonitorGate.RemovalAction`
  - `enum class RemovalAction { IGNORE, STOP_QUIETLY, RESTART_WITH_NOTICE }` (nested in `MonitorGate`)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/ft8vc/app/controllers/MonitorGateTest.kt`:

```kotlin
package net.ft8vc.app.controllers

import net.ft8vc.app.controllers.MonitorGate.RemovalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorGateTest {

    // All conditions met — the only combination that starts monitor capture.
    private fun start(
        autoMonitorEnabled: Boolean = true,
        appInForeground: Boolean = true,
        usbAudioInputPresent: Boolean = true,
        recordAudioGranted: Boolean = true,
        isCapturing: Boolean = false,
        isTransmitting: Boolean = false,
    ) = MonitorGate.shouldStartMonitor(
        autoMonitorEnabled = autoMonitorEnabled,
        appInForeground = appInForeground,
        usbAudioInputPresent = usbAudioInputPresent,
        recordAudioGranted = recordAudioGranted,
        isCapturing = isCapturing,
        isTransmitting = isTransmitting,
    )

    @Test fun `starts when all conditions met`() {
        assertTrue(start())
    }

    @Test fun `setting off blocks start`() {
        assertFalse(start(autoMonitorEnabled = false))
    }

    @Test fun `backgrounded app blocks start`() {
        assertFalse(start(appInForeground = false))
    }

    @Test fun `no usb audio input blocks start`() {
        assertFalse(start(usbAudioInputPresent = false))
    }

    @Test fun `missing record permission blocks start`() {
        assertFalse(start(recordAudioGranted = false))
    }

    @Test fun `already capturing blocks start`() {
        assertFalse(start(isCapturing = true))
    }

    @Test fun `transmitting blocks start`() {
        assertFalse(start(isTransmitting = true))
    }

    @Test fun `background stops monitor-only capture`() {
        assertTrue(MonitorGate.shouldStopOnBackground(isCapturing = true, isOperating = false))
    }

    @Test fun `background leaves operating session alone`() {
        assertFalse(MonitorGate.shouldStopOnBackground(isCapturing = true, isOperating = true))
    }

    @Test fun `background with no capture is a no-op`() {
        assertFalse(MonitorGate.shouldStopOnBackground(isCapturing = false, isOperating = false))
    }

    @Test fun `unplug while monitoring with no usb left stops quietly`() {
        assertEquals(
            RemovalAction.STOP_QUIETLY,
            MonitorGate.onUsbAudioInputRemoved(
                isCapturing = true, isOperating = false, usbInputStillPresent = false,
            ),
        )
    }

    @Test fun `unplug while monitoring with another usb input restarts`() {
        assertEquals(
            RemovalAction.RESTART_WITH_NOTICE,
            MonitorGate.onUsbAudioInputRemoved(
                isCapturing = true, isOperating = false, usbInputStillPresent = true,
            ),
        )
    }

    @Test fun `unplug while operating keeps existing restart behavior`() {
        assertEquals(
            RemovalAction.RESTART_WITH_NOTICE,
            MonitorGate.onUsbAudioInputRemoved(
                isCapturing = true, isOperating = true, usbInputStillPresent = false,
            ),
        )
    }

    @Test fun `unplug while not capturing is ignored`() {
        assertEquals(
            RemovalAction.IGNORE,
            MonitorGate.onUsbAudioInputRemoved(
                isCapturing = false, isOperating = false, usbInputStillPresent = false,
            ),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.MonitorGateTest" -q
```

Expected: FAIL — compilation error, `MonitorGate` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/net/ft8vc/app/controllers/MonitorGate.kt`:

```kotlin
package net.ft8vc.app.controllers

/**
 * Pure decision logic for auto RX-monitor (spec
 * 2026-07-11-auto-rx-monitor-design): start receive-only capture when a
 * radio's USB audio is connected, so the waterfall and decode list are live
 * before the operator presses Start. No I/O and no Android dependencies —
 * the ViewModel supplies the environment and acts on the returned decisions.
 */
object MonitorGate {

    /** All conditions required to start monitor capture (receive-only, no rig prep). */
    fun shouldStartMonitor(
        autoMonitorEnabled: Boolean,
        appInForeground: Boolean,
        usbAudioInputPresent: Boolean,
        recordAudioGranted: Boolean,
        isCapturing: Boolean,
        isTransmitting: Boolean,
    ): Boolean =
        autoMonitorEnabled &&
            appInForeground &&
            usbAudioInputPresent &&
            recordAudioGranted &&
            !isCapturing &&
            !isTransmitting

    /**
     * App left the foreground: stop a monitor-only capture. Android 9+ mutes
     * mic input for backgrounded apps, so a background monitor would feed the
     * capture watchdog silent slots and trip its recovery loop. Operating
     * sessions hold KEEP_SCREEN_ON and are left alone.
     */
    fun shouldStopOnBackground(isCapturing: Boolean, isOperating: Boolean): Boolean =
        isCapturing && !isOperating

    /** What to do when a USB audio input device disappears mid-capture. */
    enum class RemovalAction { IGNORE, STOP_QUIETLY, RESTART_WITH_NOTICE }

    /**
     * Monitoring with no USB input left is the expected "operator unplugged
     * the radio" case — stop without a snackbar. Everything else keeps the
     * existing RELY-02a restart-with-notice behavior.
     */
    fun onUsbAudioInputRemoved(
        isCapturing: Boolean,
        isOperating: Boolean,
        usbInputStillPresent: Boolean,
    ): RemovalAction = when {
        !isCapturing -> RemovalAction.IGNORE
        isOperating -> RemovalAction.RESTART_WITH_NOTICE
        usbInputStillPresent -> RemovalAction.RESTART_WITH_NOTICE
        else -> RemovalAction.STOP_QUIETLY
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.MonitorGateTest" -q
```

Expected: BUILD SUCCESSFUL, 14 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/controllers/MonitorGate.kt \
        app/src/test/java/net/ft8vc/app/controllers/MonitorGateTest.kt
git commit -m "feat(app): MonitorGate — pure gating logic for auto RX-monitor"
```

---

### Task 2: `autoMonitorEnabled` setting plumbing

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/StationSettings.kt` (data class, ~line 55)
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt` (read map, setter, Keys)
- Modify: `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt` (`toSlice()` + `SettingsSlice`)
- Test: `app/src/test/java/net/ft8vc/app/controllers/SettingsBridgeTest.kt`

**Interfaces:**
- Consumes: existing `StationSettings` / `SettingsSlice` pipeline.
- Produces (used by Tasks 3 and 4):
  - `StationSettings.autoMonitorEnabled: Boolean` (default `true`)
  - `SettingsSlice.autoMonitorEnabled: Boolean` (default `true`)
  - `suspend fun SettingsRepository.setAutoMonitorEnabled(enabled: Boolean)`

- [ ] **Step 1: Write the failing test**

In `app/src/test/java/net/ft8vc/app/controllers/SettingsBridgeTest.kt`, extend the existing `slice_mapsAllFieldsCorrectly` test: add `autoMonitorEnabled = false` to the `defaultSettings.copy(...)` block and `assertFalse(s.autoMonitorEnabled)` to the assertions:

```kotlin
    @Test
    fun slice_mapsAllFieldsCorrectly() {
        val settings = defaultSettings.copy(
            txToneHz = 1500,
            maxUnansweredTxCycles = 3,
            inputGain = 0.5f,
            answerPolicy = AnswerPolicy.BEST_SNR,
            decodeViewMode = DecodeViewMode.ALL,
            txSlotParity = TxSlotParity.ODD,
            useDarkTheme = false,
            cq73OnlyFilter = true,
            sendRr73 = false,
            autoMonitorEnabled = false,
        )
        val (repo, _) = makeRepo(initial = settings)
        val bridge = SettingsBridge(repo, bridgeScope)

        val s = bridge.slice.value
        assertEquals(1500, s.txToneHz)
        assertEquals(3, s.maxUnansweredTxCycles)
        assertEquals(0.5f, s.inputGain)
        assertEquals(AnswerPolicy.BEST_SNR, s.answerPolicy)
        assertEquals(DecodeViewMode.ALL, s.decodeViewMode)
        assertEquals(TxSlotParity.ODD, s.txSlotParity)
        assertFalse(s.useDarkTheme)
        assertTrue(s.cq73OnlyFilter)
        assertFalse(s.sendRr73)
        assertFalse(s.autoMonitorEnabled)
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.SettingsBridgeTest" -q
```

Expected: FAIL — compilation error, no `autoMonitorEnabled` parameter on `StationSettings`.

- [ ] **Step 3: Add the field to `StationSettings`**

In `app/src/main/java/net/ft8vc/app/settings/StationSettings.kt`, after `val earlyDecodeEnabled: Boolean = true,` add:

```kotlin
    /** Auto-start RX monitor (waterfall + decodes) when a radio's USB audio is connected (spec 2026-07-11-auto-rx-monitor-design). */
    val autoMonitorEnabled: Boolean = true,
```

- [ ] **Step 4: Add key, read mapping, and setter to `SettingsRepository`**

In `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt`:

(a) In the `settings` flow map, after `earlyDecodeEnabled = prefs[Keys.EARLY_DECODE_ENABLED] ?: true,` add:

```kotlin
            autoMonitorEnabled = prefs[Keys.AUTO_MONITOR_ENABLED] ?: true,
```

(b) After `setEarlyDecodeEnabled`, add:

```kotlin
    suspend fun setAutoMonitorEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.AUTO_MONITOR_ENABLED] = enabled }
    }
```

(c) In the `Keys` object, after `val EARLY_DECODE_ENABLED = booleanPreferencesKey("early_decode_enabled")` add:

```kotlin
        val AUTO_MONITOR_ENABLED = booleanPreferencesKey("auto_monitor_enabled")
```

- [ ] **Step 5: Map it through `SettingsBridge`**

In `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt`:

(a) In `StationSettings.toSlice()`, after `earlyDecodeEnabled = earlyDecodeEnabled,` add:

```kotlin
        autoMonitorEnabled = autoMonitorEnabled,
```

(b) In `data class SettingsSlice`, after `val earlyDecodeEnabled: Boolean = true,` add:

```kotlin
    val autoMonitorEnabled: Boolean = true,
```

- [ ] **Step 6: Run test to verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.SettingsBridgeTest" -q
```

Expected: BUILD SUCCESSFUL, all SettingsBridgeTest tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/StationSettings.kt \
        app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt \
        app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt \
        app/src/test/java/net/ft8vc/app/controllers/SettingsBridgeTest.kt
git commit -m "feat(app): autoMonitorEnabled preference (default on)"
```

---

### Task 3: OperateViewModel wiring — triggers, stops, and Start adoption

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt`

**Interfaces:**
- Consumes: `MonitorGate` (Task 1), `SettingsSlice.autoMonitorEnabled` + `SettingsRepository.setAutoMonitorEnabled` (Task 2), existing private `beginCapture()` / `stopCapture()` / `restartCapture()`.
- Produces (used by Task 4/5):
  - `fun OperateViewModel.setAutoMonitorEnabled(enabled: Boolean)`
  - `OperateUiState.autoMonitorEnabled: Boolean` (default `true`)
  - Monitor mode is observable as `isCapturing && !isOperating` (no new state field).

No new unit tests in this task — `OperateViewModel` is an `AndroidViewModel` with no JVM test harness; every decision branch added here is a one-line dispatch into the already-tested `MonitorGate`. Verification is the compile + full-suite run in each step and the field checklist in Task 6.

- [ ] **Step 1: Add foreground tracking + `maybeStartMonitor()`**

In `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`:

(a) Near `private var lastDialFreqHz: Long? = null` (~line 193), add:

```kotlin
    // Auto RX-monitor (spec 2026-07-11-auto-rx-monitor-design). Both written
    // only from the main thread (lifecycle observer / settings collect).
    private var appInForeground = false
    private var settingsLoaded = false
```

(b) Add the guarded entry point (place it right above `refreshDevices`, ~line 504):

```kotlin
    /**
     * Auto RX-monitor: start receive-only capture (waterfall + decodes live,
     * no rig prep, no QSO session) when MonitorGate's conditions hold. Called
     * on every trigger that can newly satisfy them: USB attach → refreshDevices,
     * app foregrounding, and the first settings emission. The settingsLoaded
     * gate prevents a launch race where the slice still holds the default
     * autoMonitorEnabled=true before DataStore has emitted the user's choice.
     */
    private fun maybeStartMonitor() {
        if (!settingsLoaded) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            getApplication(),
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val start = MonitorGate.shouldStartMonitor(
            autoMonitorEnabled = settingsBridge.slice.value.autoMonitorEnabled,
            appInForeground = appInForeground,
            usbAudioInputPresent = _viewState.value.devices.any { it.isUsb },
            recordAudioGranted = granted,
            isCapturing = state.value.isCapturing,
            isTransmitting = state.value.isTransmitting,
        )
        if (start) beginCapture()
    }
```

- [ ] **Step 2: Wire the triggers**

(a) In the `processLifecycleObserver` (~line 302), add `onStart` and extend `onStop`:

```kotlin
    // ── Phase 7 (HYG-04) ADIF backup + auto RX-monitor foreground tracking ────
    private val processLifecycleObserver = object : androidx.lifecycle.DefaultLifecycleObserver {
        override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
            appInForeground = true
            maybeStartMonitor()
        }

        override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
            appInForeground = false
            AdifAutoBackup.scheduleBackupAfterQso(getApplication(), logbook, settingsRepo)
            // Android 9+ mutes mic input for backgrounded apps — a monitor-only
            // capture would feed the watchdog silent slots, so stop it here and
            // let onStart bring it back. Operating sessions are left alone.
            if (MonitorGate.shouldStopOnBackground(state.value.isCapturing, state.value.isOperating)) {
                stopCapture()
            }
        }
    }
```

Note: `ProcessLifecycleOwner.addObserver` (already called at the end of `init`) replays the current lifecycle state, so `onStart` fires at registration when the app launches foregrounded — that IS the launch-with-radio-already-plugged trigger.

(b) In the settings mirror collect inside `init` (the `settingsBridge.slice.collect { s -> ... }` block, ~line 328), add at the END of the collect lambda body:

```kotlin
                if (!settingsLoaded) {
                    settingsLoaded = true
                    maybeStartMonitor()
                }
```

(c) At the end of `refreshDevices()` (~line 513, after the `if (state.value.isOperating || state.value.txEnabled) prepareRig()` line), add:

```kotlin
        maybeStartMonitor()
```

(This covers USB attach: `onUsbAttached()` → `refreshDevices()`.)

- [ ] **Step 3: Unplug path via MonitorGate**

Replace the body of `audioDeviceCallback.onAudioDevicesRemoved` (~line 309):

```kotlin
    // ── Phase 6 (RELY-02a): AudioDeviceCallback — first of two hot-swap signals ────
    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
            if (removedDevices == null || removedDevices.isEmpty()) return
            val anyInput = removedDevices.any { it.isSource }
            if (!anyInput) return
            val action = MonitorGate.onUsbAudioInputRemoved(
                isCapturing = _viewState.value.isCapturing,
                isOperating = _viewState.value.isOperating,
                usbInputStillPresent = AudioInputs.list(getApplication()).any { it.isUsb },
            )
            when (action) {
                MonitorGate.RemovalAction.IGNORE -> Unit
                MonitorGate.RemovalAction.STOP_QUIETLY -> stopCapture()
                MonitorGate.RemovalAction.RESTART_WITH_NOTICE -> {
                    notify("Audio device removed — restarting capture", SnackbarEvent.Tag.ERROR)
                    restartCapture()
                }
            }
        }
    }
```

- [ ] **Step 4: `startOperating()` adopts a running monitor capture**

Replace `startOperating()` (~line 671):

```kotlin
    fun startOperating() {
        if (state.value.isOperating) return
        // Auto RX-monitor: if monitor capture is already running, adopt it —
        // don't restart the audio chain or wipe the waterfall/decodes the
        // operator was just looking at.
        val adoptMonitorCapture = state.value.isCapturing
        if (!adoptMonitorCapture) {
            waterfall.clear()
            decodeController.reset()
        }
        prepareRig()
        restoreLastBandIfNeeded()
        if (!adoptMonitorCapture) beginCapture()
        _viewState.update {
            it.copy(isOperating = true, isCapturing = true, operateStatus = "Operating")
        }
        qsoSession.setOperating(true)
        qsoSession.refreshOperateTxFromStation()
    }
```

- [ ] **Step 5: Setter + UI state field**

(a) In `OperateViewModel`, next to `setAutoSeqEnabled` (~line 528), add:

```kotlin
    fun setAutoMonitorEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoMonitorEnabled(enabled) }
    }
```

(b) In `app/src/main/java/net/ft8vc/app/OperateUiState.kt`, next to `val earlyDecodeEnabled` (search for it), add:

```kotlin
    /** Auto RX-monitor: start receive when a radio's USB audio connects (Settings toggle). */
    val autoMonitorEnabled: Boolean = true,
```

(c) In the `OperateUiState(...)` construction inside `OperateViewModel.init` (the big `combine`, ~line 243 after `earlyDecodeEnabled = settings.earlyDecodeEnabled,`), add:

```kotlin
                earlyDecodeEnabled = settings.earlyDecodeEnabled,
                autoMonitorEnabled = settings.autoMonitorEnabled,
```

(i.e., insert the `autoMonitorEnabled` line directly after the existing `earlyDecodeEnabled` line.)

- [ ] **Step 6: Compile and run the full app unit suite**

```bash
./gradlew :app:testDebugUnitTest -q
```

Expected: BUILD SUCCESSFUL. (Known flake: `DecodeControllerTest.reset_clearsLevelMeter` is a pre-existing 30 ms nanoTime-throttle race — re-run once before suspecting this change.)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateViewModel.kt \
        app/src/main/java/net/ft8vc/app/OperateUiState.kt
git commit -m "feat(app): auto RX-monitor — capture starts when radio connects, Start adopts it"
```

---

### Task 4: Settings UI switch

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt` (Audio section, ~line 109)

**Interfaces:**
- Consumes: `state.autoMonitorEnabled` and `vm::setAutoMonitorEnabled` (Task 3).
- Produces: nothing downstream.

- [ ] **Step 1: Add the switch row**

In `SettingsSection("Audio")` in `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt`, between `DevicePicker(...)` and the explanatory `Text(...)`, add (same Row/Switch pattern as the "Enable transmit" row in the TX section):

```kotlin
            SettingsSection("Audio") {
                DevicePicker(state = state, onSelect = vm::selectDevice)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Start receive when radio connects", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Waterfall and decodes run as soon as USB audio is plugged in",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Switch(
                        checked = state.autoMonitorEnabled,
                        onCheckedChange = vm::setAutoMonitorEnabled,
                    )
                }
                Text(
                    "Use a USB audio interface (Digirig or the radio's built-in USB audio) " +
                        "for RX and TX. Adjust input level on the Operate tab while monitoring.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
```

(All imports — `Row`, `Column`, `Switch`, `FontWeight`, `Arrangement`, `Alignment` — are already present in this file; the TX section uses the identical pattern.)

- [ ] **Step 2: Compile**

```bash
./gradlew :app:compileDebugKotlin -q
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt
git commit -m "feat(ui): Settings switch — start receive when radio connects"
```

---

### Task 5: "Monitoring" chip on the Operate status bar

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/OperateStatusBar.kt` (main status Row, ~line 101)

**Interfaces:**
- Consumes: `state.isCapturing`, `state.isOperating` (existing `OperateUiState` fields), existing `CompactChip(text, modifier, emphasized, onClick)` composable from `CompactUi.kt`.
- Produces: nothing downstream.

- [ ] **Step 1: Add the chip**

In `OperateStatusBar`, inside the main `Row` (the one starting with the `freqLabel` Text, ~line 101), insert directly after the frequency `Text(...)` element:

```kotlin
            if (state.isCapturing && !state.isOperating) {
                CompactChip(text = "Monitoring", emphasized = true)
            }
```

The chip only renders when monitor capture runs without Operate, so it never competes with TX/QSO status elements (which require `isOperating`). This satisfies the milestone's no-crowding rule.

- [ ] **Step 2: Compile**

```bash
./gradlew :app:compileDebugKotlin -q
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/operate/OperateStatusBar.kt
git commit -m "feat(ui): Monitoring chip when RX monitor runs without Operate"
```

---

### Task 6: Full verification + field checklist

**Files:** none new.

- [ ] **Step 1: Full unit suites (app + all modules touched)**

```bash
./gradlew :app:testDebugUnitTest :core:test -q
```

Expected: BUILD SUCCESSFUL (modulo the known `reset_clearsLevelMeter` flake — re-run once if it trips).

- [ ] **Step 2: Assemble the debug APK**

```bash
./gradlew :app:assembleDebug -q
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Record the field-verification checklist**

This feature is NOT done until the FT-891 + Digirig field check passes (project core value). The checklist (from the spec) — to be run by the operator on the field phone:

1. App closed, plug in Digirig → app launches → waterfall live within one 15 s slot, "Monitoring" chip shown, no CAT/PTT activity on the rig.
2. Press Start → CAT reads, PTT probe, QSO flow all work; waterfall/decodes NOT wiped at the Start transition.
3. Press Stop → capture stops and stays stopped (Stop wins until replug/refocus).
4. While monitoring: unplug → capture stops quietly, no snackbar spam.
5. While monitoring: screen off, screen on → monitor pauses and resumes.
6. Settings toggle OFF → replug → waterfall stays dark; press Start still works.
7. Fresh install (no RECORD_AUDIO yet): plug in → no permission prompt, no crash; first Start prompts as before, and monitor works from then on.

Do not claim the feature complete without either running this on the reference rig or explicitly handing the checklist to the operator as the pending gate.

- [ ] **Step 4: Finish the branch**

Use superpowers:finishing-a-development-branch — the branch merges to `multi-rig` (or PR per operator preference) only after the operator accepts the field-gate plan.
