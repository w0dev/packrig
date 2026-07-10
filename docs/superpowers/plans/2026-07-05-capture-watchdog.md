# Capture Watchdog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect a silent RX capture stall (no frames arriving while we believe we're capturing) and recover by recreating the `AudioRecord`, with debounce/grace/retry-cap guards and an honest, tappable failure chip.

**Architecture:** A pure `CaptureWatchdog` state machine (time injected) holds the stall/grace/retry-cap logic. `OperateViewModel` stamps it on each frame, ticks a monitor coroutine that calls `poll(...)`, and executes the returned `Decision` (Recover → `restartCapture()`; GiveUp → stop + `captureFailed` chip). No change to the audio module's interface.

**Tech Stack:** Kotlin, Coroutines + StateFlow, Jetpack Compose, JUnit4.

## Global Constraints

- Land on `unstable` via a worktree off `multi-rig`; promote only after FT-891 + Digirig field verification.
- Behavior parity: in the healthy steady state (frames arriving continuously) the watchdog sits `Idle` and changes nothing about RX/TX/CAT timing or fidelity — it acts only when frames have already stopped.
- No new top-level dependencies; no new permissions; no change to `AudioEngine` / `CaptureLifecycle` / `UsbAudioCapture` public interfaces.
- `CaptureWatchdog` has no Android imports (pure, unit-testable).
- Kotlin Official style, 4-space indent, one top-level public type per file, no wildcard imports, no semicolons.
- Tunable constants (companion object): `STALL_THRESHOLD_MS = 3000`, `RESTART_GRACE_MS = 3000`, `MAX_RESTARTS = 3`, monitor tick 1000 ms.

---

### Task 1: `CaptureWatchdog` pure state machine

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/controllers/CaptureWatchdog.kt`
- Test: `app/src/test/java/net/ft8vc/app/controllers/CaptureWatchdogTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `class CaptureWatchdog(stallThresholdMs: Long, restartGraceMs: Long, maxRestarts: Int, clock: () -> Long)` with `fun onFrame()`, `fun onCaptureStarted()`, `fun reset()`, `fun poll(isCapturing: Boolean, isTransmitting: Boolean, devicePresent: Boolean): CaptureWatchdog.Decision`; and `sealed interface Decision { object Idle; object Recover; object GiveUp }`. Companion consts `STALL_THRESHOLD_MS`, `RESTART_GRACE_MS`, `MAX_RESTARTS` (defaults for the ctor).

- [ ] **Step 1: Write the failing test**

```kotlin
package net.ft8vc.app.controllers

import net.ft8vc.app.controllers.CaptureWatchdog.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureWatchdogTest {

    private var now = 100_000L
    // Custom (small) timings make the state transitions easy to drive deterministically.
    private fun watchdog(stall: Long = 1_000L, grace: Long = 0L, maxRestarts: Int = 3) =
        CaptureWatchdog(stall, grace, maxRestarts, clock = { now })

    @Test fun `recent frame is idle`() {
        val w = watchdog()
        w.onCaptureStarted()
        now += 500
        w.onFrame()
        assertEquals(Decision.Idle, w.poll(isCapturing = true, isTransmitting = false, devicePresent = true))
    }

    @Test fun `stall past threshold recovers`() {
        val w = watchdog()
        w.onCaptureStarted()
        now += 1_500
        assertEquals(Decision.Recover, w.poll(true, false, true))
    }

    @Test fun `within restart grace is idle even if stalled`() {
        val w = watchdog(stall = 1_000L, grace = 3_000L)
        w.onCaptureStarted()          // grace window: now .. now+3000
        now += 1_500                  // gap 1500 >= stall(1000) but still inside grace
        assertEquals(Decision.Idle, w.poll(true, false, true))
        now += 2_000                  // now past grace, still stalled
        assertEquals(Decision.Recover, w.poll(true, false, true))
    }

    @Test fun `maxRestarts consecutive stalls give up`() {
        val w = watchdog(maxRestarts = 3)
        repeat(3) {
            w.onCaptureStarted()      // simulate the restart the VM would perform
            now += 1_500
            assertEquals(Decision.Recover, w.poll(true, false, true))
        }
        w.onCaptureStarted()
        now += 1_500
        assertEquals(Decision.GiveUp, w.poll(true, false, true))
    }

    @Test fun `healthy interval past grace resets the restart counter`() {
        val w = watchdog()
        w.onCaptureStarted()
        now += 1_500
        assertEquals(Decision.Recover, w.poll(true, false, true))   // count -> 1
        // recovery succeeds: fresh capture + a frame
        w.onCaptureStarted()
        w.onFrame()
        now += 500
        assertEquals(Decision.Idle, w.poll(true, false, true))       // healthy -> count reset to 0
        // a later isolated stall must Recover again, not GiveUp
        now += 2_000
        assertEquals(Decision.Recover, w.poll(true, false, true))
    }

    @Test fun `not capturing suppresses recover`() {
        val w = watchdog()
        w.onCaptureStarted()
        now += 5_000
        assertEquals(Decision.Idle, w.poll(isCapturing = false, isTransmitting = false, devicePresent = true))
    }

    @Test fun `transmitting suppresses recover`() {
        val w = watchdog()
        w.onCaptureStarted()
        now += 5_000
        assertEquals(Decision.Idle, w.poll(true, isTransmitting = true, devicePresent = true))
    }

    @Test fun `absent device suppresses recover`() {
        val w = watchdog()
        w.onCaptureStarted()
        now += 5_000
        assertEquals(Decision.Idle, w.poll(true, false, devicePresent = false))
    }

    @Test fun `tx gap then resume is not a stall`() {
        val w = watchdog()
        w.onCaptureStarted()
        now += 5_000                  // long gap, but it's a TX pause:
        assertEquals(Decision.Idle, w.poll(isCapturing = false, isTransmitting = true, devicePresent = true))
        w.onCaptureStarted()          // TX ends, capture resumes -> VM re-stamps
        now += 500
        assertEquals(Decision.Idle, w.poll(true, false, true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.CaptureWatchdogTest"`
Expected: FAIL — `CaptureWatchdog` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package net.ft8vc.app.controllers

/**
 * Detects a silent RX capture stall — no frames arriving while we believe we are
 * capturing — and decides when to recover or give up. Pure state machine (time
 * injected) so the debounce / grace / retry-cap logic is unit-testable.
 *
 * Threading: [onFrame] is called on the audio capture thread; [poll],
 * [onCaptureStarted] and [reset] run on the ViewModel (main) coroutine. Only
 * [lastFrameAtMs] is written from both, so it is @Volatile; the counters are
 * touched solely by the main-thread methods.
 */
class CaptureWatchdog(
    private val stallThresholdMs: Long = STALL_THRESHOLD_MS,
    private val restartGraceMs: Long = RESTART_GRACE_MS,
    private val maxRestarts: Int = MAX_RESTARTS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile private var lastFrameAtMs: Long = clock()
    private var restartCount: Int = 0
    private var graceUntilMs: Long = 0L

    /** Audio-thread: a frame arrived. */
    fun onFrame() {
        lastFrameAtMs = clock()
    }

    /** Main: capture (re)started — stamp fresh and open a spin-up grace window. Does NOT clear restartCount. */
    fun onCaptureStarted() {
        val now = clock()
        lastFrameAtMs = now
        graceUntilMs = now + restartGraceMs
    }

    /** Main: capture fully stopped — clear all state. */
    fun reset() {
        lastFrameAtMs = clock()
        restartCount = 0
        graceUntilMs = 0L
    }

    /** Main: monitor tick. May mutate the restart counter. */
    fun poll(isCapturing: Boolean, isTransmitting: Boolean, devicePresent: Boolean): Decision {
        if (!isCapturing || isTransmitting || !devicePresent) return Decision.Idle
        val now = clock()
        val stalled = now - lastFrameAtMs >= stallThresholdMs
        if (!stalled) {
            if (restartCount > 0 && now >= graceUntilMs) restartCount = 0
            return Decision.Idle
        }
        if (now < graceUntilMs) return Decision.Idle          // fresh restart still spinning up
        if (restartCount >= maxRestarts) return Decision.GiveUp
        restartCount += 1
        return Decision.Recover
    }

    sealed interface Decision {
        object Idle : Decision
        object Recover : Decision
        object GiveUp : Decision
    }

    companion object {
        const val STALL_THRESHOLD_MS = 3_000L
        const val RESTART_GRACE_MS = 3_000L
        const val MAX_RESTARTS = 3
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.CaptureWatchdogTest"`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/controllers/CaptureWatchdog.kt app/src/test/java/net/ft8vc/app/controllers/CaptureWatchdogTest.kt
git commit -m "feat(capture): CaptureWatchdog stall-detection state machine"
```

---

### Task 2: Wire the watchdog into `OperateViewModel`

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (imports; view-state class ~88-101; combine block ~181-199; `beginCapture` 847-853; `stopCapture` 860-872; init block — add monitor coroutine near the zero-sample watchdog ~332; add `retryCapture()`)
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt` (add field near `catUnreachable` ~154)

**Interfaces:**
- Consumes: `CaptureWatchdog` (Task 1) — `onFrame()`, `onCaptureStarted()`, `reset()`, `poll(isCapturing, isTransmitting, devicePresent): CaptureWatchdog.Decision`; `AudioInputs.list(context).any { it.isUsb }`.
- Produces: `OperateViewModel.retryCapture()`; `OperateUiState.captureFailed: Boolean`.

- [ ] **Step 1: Add `captureFailed` to `OperateUiState`**

In `OperateUiState.kt`, in the "Reliability hardening (Phase 6)" block right after `catUnreachable` (line 154):

```kotlin
    /** Latched after 3 consecutive CAT timeouts; cleared by `retryCat`. */
    val catUnreachable: Boolean = false,
    /** Latched when the capture watchdog exhausts its restart budget; cleared by `retryCapture`. */
    val captureFailed: Boolean = false,
```

- [ ] **Step 2: Add `captureFailed` to the internal view-state**

In `OperateViewModel.kt`, in `private data class OperateViewState(` (line 88), add a field before the closing paren (after `contactCount`):

```kotlin
        val contactCount: Int = 0,
        /** Capture watchdog gave up after exhausting restarts; drives the retry chip. */
        val captureFailed: Boolean = false,
    )
```

- [ ] **Step 3: Add imports and the watchdog instance**

In `OperateViewModel.kt` imports, add:

```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import net.ft8vc.app.controllers.CaptureWatchdog
import net.ft8vc.audio.AudioInputs
```

(If any already exists, skip it.) Then declare the instance near the other controllers (e.g. just after `captureLifecycle` at line 120):

```kotlin
    private val captureWatchdog = CaptureWatchdog()
```

- [ ] **Step 4: Stamp the watchdog on frames + capture start (edit `beginCapture`)**

Replace `beginCapture()` (lines 847-853) with:

```kotlin
    private fun beginCapture() {
        _viewState.update { it.copy(isCapturing = true) }
        captureWatchdog.onCaptureStarted()
        captureLifecycle.start(
            state.value.selectedDeviceId,
            { frames ->
                captureWatchdog.onFrame()
                decodeController.onFrames(frames)
            },
        ) { t ->
            _viewState.update { it.copy(isCapturing = false, isOperating = false) }
            notify(t.message ?: "Capture failed", SnackbarEvent.Tag.ERROR)
        }
    }
```

- [ ] **Step 5: Reset the watchdog on stop (edit `stopCapture`)**

In `stopCapture()` (line 860), add `captureWatchdog.reset()` right after the `isCapturing = false` update:

```kotlin
    private fun stopCapture(onStopped: () -> Unit = {}) {
        _viewState.update { it.copy(isCapturing = false) }
        captureWatchdog.reset()
        captureLifecycle.stop {
```

(leave the rest of the method unchanged)

- [ ] **Step 6: Add the monitor coroutine**

In the `init { ... }` block, next to the zero-sample watchdog launch (around line 332), add a new launch:

```kotlin
        // Capture heartbeat watchdog: a stalled/dead capture thread stops delivering
        // frames entirely, so the zero-sample slice watchdog (which needs frames
        // flowing) can't see it. Poll on a timer instead.
        viewModelScope.launch {
            while (isActive) {
                delay(CAPTURE_WATCHDOG_TICK_MS)
                val ui = state.value
                if (!ui.isCapturing) continue
                val devicePresent = AudioInputs.list(getApplication()).any { it.isUsb }
                when (captureWatchdog.poll(ui.isCapturing, ui.isTransmitting, devicePresent)) {
                    CaptureWatchdog.Decision.Recover -> {
                        notify("Audio stalled — restarting capture", SnackbarEvent.Tag.ERROR)
                        restartCapture()
                    }
                    CaptureWatchdog.Decision.GiveUp -> {
                        _viewState.update { it.copy(captureFailed = true) }
                        stopCapture()
                        notify("Audio capture failed — tap to retry", SnackbarEvent.Tag.ERROR)
                    }
                    CaptureWatchdog.Decision.Idle -> {}
                }
            }
        }
```

- [ ] **Step 7: Map `captureFailed` into `OperateUiState` and add `retryCapture()`**

In the `combine` block's `OperateUiState(...)` builder (near the other `view.` mappings, ~line 195), add:

```kotlin
                captureFailed = view.captureFailed,
```

Add the companion tick constant (in the `OperateViewModel` companion object; if none exists near the class end, add one):

```kotlin
        private const val CAPTURE_WATCHDOG_TICK_MS = 1_000L
```

Add the public retry method near `retryCat()` (line 881):

```kotlin
    /** Operator taps the "Audio capture failed — tap to retry" chip. */
    fun retryCapture() {
        _viewState.update { it.copy(captureFailed = false) }
        captureWatchdog.reset()
        restartCapture()
    }
```

- [ ] **Step 8: Build to verify wiring compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Run existing suites for parity (no regressions)**

Run: `./gradlew :app:testDebugUnitTest :core:testDebugUnitTest`
Expected: PASS. Note: `DecodeControllerTest.reset_clearsLevelMeter_andPreservesDecodeList` is a KNOWN pre-existing flake (races a 30ms nanoTime UI throttle, ~2 pass / 3 fail); if it is the sole failure, re-run it in isolation to show it also passes and proceed — do not modify it. Any other failure is a real regression from this wiring; investigate it.

(No new VM-level unit test: `OperateViewModel` is an `AndroidViewModel` and isn't unit-instantiable without heavy Android test infra. The decision logic is fully covered by `CaptureWatchdogTest`; the wiring is covered by compile + the Task 4 field check.)

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateViewModel.kt app/src/main/java/net/ft8vc/app/OperateUiState.kt
git commit -m "feat(vm): capture-stall watchdog monitor + retryCapture"
```

---

### Task 3: Failure chip in `OperateStatusBar`

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/OperateStatusBar.kt` (signature ~54-63; reliability row 71-93)
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt` (call site ~100-110)

**Interfaces:**
- Consumes: `OperateViewModel.retryCapture()` (Task 2); `state.captureFailed`.
- Produces: `OperateStatusBar` gains `onRetryCapture: () -> Unit = {}`.

- [ ] **Step 1: Add the callback parameter**

In the `fun OperateStatusBar(` signature, after `onRetryCat` (line 62):

```kotlin
    onRetryCat: () -> Unit = {},
    onRetryCapture: () -> Unit = {},
    modifier: Modifier = Modifier,
```

- [ ] **Step 2: Show the chip in the reliability row**

Update the row guard (line 71) to include `captureFailed`, and add the chip. Change the `if (...)` condition to:

```kotlin
        if (state.catUnreachable || state.captureFailed || state.decodeFailureRecent || state.digirigDisconnected || state.txSafetyHaltActive) {
```

Then inside the `Row`, after the `catUnreachable` chip block (after line 82), add:

```kotlin
                if (state.captureFailed) {
                    CompactChip(
                        text = "Audio capture failed — tap to retry",
                        modifier = Modifier.clickable(onClick = onRetryCapture),
                    )
                }
```

- [ ] **Step 3: Pass the VM callback from `OperateScreen`**

In the `OperateStatusBar(` call (line 100), after `onRetryCat = vm::retryCat,` (line 108):

```kotlin
                onRetryCat = vm::retryCat,
                onRetryCapture = vm::retryCapture,
                modifier = Modifier.fillMaxWidth(),
```

- [ ] **Step 4: Build to verify UI compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/operate/OperateStatusBar.kt app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt
git commit -m "feat(ui): capture-failed retry chip"
```

---

### Task 4: Full verification + field check

**Files:** none (verification only).

- [ ] **Step 1: Full unit-test + assemble**

Run: `./gradlew :core:testDebugUnitTest :app:testDebugUnitTest assembleDebug`
Expected: All PASS (modulo the known `reset_clearsLevelMeter` flake — re-run if it's the sole failure), APK assembles.

- [ ] **Step 2: Field verification on the reference rig (FT-891 + Digirig)**

Manual, per the milestone's field-verification bar. Confirm:
1. Normal operation is unchanged — decodes arrive and a QSO completes exactly as before (steady-state parity; the watchdog stays `Idle`).
2. Induce a stall (e.g., a long session, or a deliberate USB glitch/brief unseat-reseat that triggers a dead capture without a full detach). Confirm the app **auto-restarts capture** (transient "Audio stalled — restarting capture" snackbar) and decodes resume without an app restart.
3. If a stall is made to persist across the restart budget, confirm the **"Audio capture failed — tap to retry"** chip appears and that tapping it restarts capture and clears the chip.
4. Confirm a genuine USB unplug still shows the existing "device removed" path, not the stall-retry loop.

- [ ] **Step 3: Commit any field-fix follow-ups (if needed), else done.**

---

## Self-Review

**Spec coverage:**
- Heartbeat detection (both modes) → Task 1 (`poll` on frame-gap), Task 2 Step 4 (`onFrame` stamp). ✓
- Pure testable state machine → Task 1. ✓
- Recover → recreate AudioRecord → Task 2 Step 6 (`restartCapture`). ✓
- Grace window suppresses spin-up re-fire → Task 1 (`onCaptureStarted`/`poll` grace), tested. ✓
- Cap → GiveUp → stop + honest `isCapturing=false` + persistent chip → Task 2 Step 6 (`stopCapture` sets `isCapturing=false`; `captureFailed=true`), Task 3. ✓
- Consecutive-only counting (healthy resets) → Task 1 (`poll` step 3), tested. ✓
- Guards: not capturing / transmitting / device absent → Task 1 `poll` guard, tested; TX-gap coordination via `onCaptureStarted` on `beginCapture` → Task 2 Step 4, tested in Task 1. ✓
- Device-present check reuses `AudioInputs.list(...).isUsb` → Task 2 Step 6. ✓
- No conflict with zero-sample watchdog / device-removal → complementary by construction (documented in spec; no code change to those paths). ✓
- Retry chip + `retryCapture` → Task 2 Step 7, Task 3. ✓
- Behavior parity (Idle in steady state) → Task 2 Step 9, Task 4 Step 2.1. ✓
- Tunable constants (3 s / 3 s / 3, 1 s tick) → Task 1 companion + Task 2 `CAPTURE_WATCHDOG_TICK_MS`. ✓
- No audio-module interface change → confirmed; all edits are in `app`. ✓

**Placeholder scan:** none — every code step shows complete code.

**Type consistency:** `CaptureWatchdog.onFrame/onCaptureStarted/reset/poll` and `Decision.{Idle,Recover,GiveUp}` used identically in Tasks 1–2. `captureFailed` (Boolean) consistent across `OperateViewState`, `OperateUiState`, combine mapping, and the chip guard. `retryCapture` defined in Task 2, referenced in Task 3. `AudioInputs.list(...).isUsb` matches the existing zero-sample watchdog usage. ✓
