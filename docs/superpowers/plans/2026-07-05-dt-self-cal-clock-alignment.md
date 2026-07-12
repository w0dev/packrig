# DT Self-Calibration — Manual Clock Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the operator apply the app's already-measured DT clock offset to the FT8 slot grid with one tap, correcting RX and TX timing coherently, with no network or location dependency.

**Architecture:** A single pure-logic `ClockCorrection` holder in `core` exposes a corrected `now()` (`rawClock() - offsetMs`). `OperateViewModel` injects `clockCorrection::now` into the three existing `clock: () -> Long` seams (`DecodeController`, `TxOrchestrator`, `QsoSessionController`) so applying an offset shifts RX slot collection, early-decode timing, TX keying, and slot parity together. Application is operator-triggered from a tappable status-bar chip and a Settings row; the offset is in-memory only.

**Tech Stack:** Kotlin, Coroutines + StateFlow, Jetpack Compose, JUnit4 + kotlinx-coroutines-test + Turbine.

## Global Constraints

- Land on `unstable` (`net.ft8vc.unstable`); promote only after FT-891 + Digirig field verification.
- Behavior parity: with no operator tap, offset is 0 and slot timing is byte-identical to today.
- No new top-level dependencies; no new Android permissions (no INTERNET, no location).
- Pure FT8/timing logic stays in `core` with no Android imports (`ClockCorrection` joins `SlotTiming`, `ClockOffsetEstimator`).
- Kotlin Official style, 4-space indent, one top-level public type per file, no wildcard imports.
- `minSdk = 28`, JVM 17.

---

### Task 1: `ClockCorrection` core holder

**Files:**
- Create: `core/src/main/java/net/ft8vc/core/ClockCorrection.kt`
- Test: `core/src/test/java/net/ft8vc/core/ClockCorrectionTest.kt`

**Interfaces:**
- Consumes: nothing (leaf).
- Produces: `class ClockCorrection(rawClock: () -> Long = { System.currentTimeMillis() })` with `fun now(): Long`, `val appliedOffsetMs: Long`, `fun applyResidualSeconds(residualSeconds: Float)`, `fun reset()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockCorrectionTest {

    @Test fun `now equals raw clock when no offset applied`() {
        val cc = ClockCorrection(rawClock = { 1_000_000L })
        assertEquals(1_000_000L, cc.now())
        assertEquals(0L, cc.appliedOffsetMs)
    }

    @Test fun `applying positive residual rolls now back (fast-clock correction)`() {
        var raw = 1_000_000L
        val cc = ClockCorrection(rawClock = { raw })
        cc.applyResidualSeconds(1.3f)          // fast clock, per ClockOffsetEstimator sign
        assertEquals(1300L, cc.appliedOffsetMs)
        assertEquals(998_700L, cc.now())        // raw - 1300
    }

    @Test fun `applying negative residual rolls now forward (slow-clock correction)`() {
        val cc = ClockCorrection(rawClock = { 1_000_000L })
        cc.applyResidualSeconds(-0.5f)
        assertEquals(-500L, cc.appliedOffsetMs)
        assertEquals(1_000_500L, cc.now())
    }

    @Test fun `residuals accumulate so repeated applies converge`() {
        val cc = ClockCorrection(rawClock = { 1_000_000L })
        cc.applyResidualSeconds(1.0f)
        cc.applyResidualSeconds(0.2f)
        assertEquals(1200L, cc.appliedOffsetMs)
    }

    @Test fun `reset returns to zero offset`() {
        val cc = ClockCorrection(rawClock = { 1_000_000L })
        cc.applyResidualSeconds(2.0f)
        cc.reset()
        assertEquals(0L, cc.appliedOffsetMs)
        assertEquals(1_000_000L, cc.now())
    }

    @Test fun `rounding is to nearest millisecond`() {
        val cc = ClockCorrection(rawClock = { 0L })
        cc.applyResidualSeconds(0.6807f)        // 680.7 ms -> 681
        assertEquals(681L, cc.appliedOffsetMs)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.ClockCorrectionTest"`
Expected: FAIL — `ClockCorrection` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package net.ft8vc.core

/**
 * Holds an operator-applied correction between the raw device clock and FT8
 * band time. [now] is the corrected epoch-ms that all slot timing reads.
 *
 * The correction is applied as a *residual*: each apply adds whatever error
 * remains on top of the current offset, so repeated applies converge toward
 * zero measured DT bias. Sign follows [ClockOffsetEstimator]: a positive
 * residual means a fast phone clock, and rolling [now] back by that amount
 * re-centres decode DT on the nominal signal-start.
 *
 * In-memory only; a fresh process starts at zero. [now] is read on the audio
 * capture thread while apply/reset run on Main, so [offsetMs] is @Volatile.
 */
class ClockCorrection(private val rawClock: () -> Long = { System.currentTimeMillis() }) {

    @Volatile
    private var offsetMs: Long = 0L

    fun now(): Long = rawClock() - offsetMs

    val appliedOffsetMs: Long get() = offsetMs

    /** Apply the remaining residual (seconds, signed) on top of the current correction. */
    fun applyResidualSeconds(residualSeconds: Float) {
        offsetMs += Math.round(residualSeconds * 1000f)
    }

    fun reset() {
        offsetMs = 0L
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.ClockCorrectionTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/ft8vc/core/ClockCorrection.kt core/src/test/java/net/ft8vc/core/ClockCorrectionTest.kt
git commit -m "feat(core): ClockCorrection holder for DT self-cal offset"
```

---

### Task 2: Lock the alignment-blip guardrail (SlotCollector clock-jump regression)

The design's "one-time slot flush at alignment" behavior already exists in
`SlotCollector.add` (a changed `slotStart` flushes the in-progress slot). This
task pins it with a regression test so a future change can't silently break the
re-alignment blip. Test only — no production change.

**Files:**
- Test: `core/src/test/java/net/ft8vc/core/SlotCollectorClockJumpTest.kt`

**Interfaces:**
- Consumes: `SlotCollector(sampleRate)`, `SlotCollector.add(frames, nowMillisUtc, onSlot)`, `SlotTiming.slotStart`.
- Produces: nothing.

- [ ] **Step 1: Write the failing test**

```kotlin
package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotCollectorClockJumpTest {

    private val rate = AppInfo.SAMPLE_RATE_HZ
    // A UTC instant a little past a slot boundary.
    private val slot0 = SlotTiming.slotStart(1_700_000_000_000L)

    @Test fun `clock jump flushes in-progress slot and re-aligns to new grid`() {
        val collector = SlotCollector(rate)
        val flushed = mutableListOf<Long>()

        // Fill ~90% of slot 0 (enough to exceed the 85% min-fraction gate).
        val nearlyFull = ShortArray((rate * AppInfo.SLOT_SECONDS * 0.9f).toInt()) { 1 }
        collector.add(nearlyFull, slot0 + 100L) { _, slotStart -> flushed += slotStart }
        assertTrue("no flush before a boundary is crossed", flushed.isEmpty())

        // Operator applies a correction: the corrected clock jumps into the NEXT slot.
        val jumped = slot0 + SlotTiming.SLOT_MS + 50L
        collector.add(ShortArray(1) { 1 }, jumped) { _, slotStart -> flushed += slotStart }

        // The misaligned in-progress slot 0 flushed exactly once, tagged to slot 0.
        assertEquals(listOf(slot0), flushed)
    }
}
```

- [ ] **Step 2: Run test to verify it passes immediately (documents existing behavior)**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.SlotCollectorClockJumpTest"`
Expected: PASS. (This behavior already exists; the test is a guardrail. If it
FAILS, stop — `SlotCollector` does not flush on boundary as the design assumes,
and the wiring in Task 4 would misbehave.)

- [ ] **Step 3: Commit**

```bash
git add core/src/test/java/net/ft8vc/core/SlotCollectorClockJumpTest.kt
git commit -m "test(core): lock SlotCollector re-alignment flush on clock jump"
```

---

### Task 3: `DecodeController.realignClockEstimate()`

Resets the DT-offset estimator window and publishes `clockOffsetSeconds = null`
so the offset chip rebuilds from post-correction slots instead of lingering on
stale pre-correction DTs.

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt`
- Test: `app/src/test/java/net/ft8vc/app/controllers/DecodeControllerClockRealignTest.kt`

**Interfaces:**
- Consumes: existing private `clockOffset: ClockOffsetEstimator`, `_slice: MutableStateFlow<DecodeSlice>`.
- Produces: `fun realignClockEstimate()` (public, callable from `OperateViewModel`).

- [ ] **Step 1: Write the failing test**

```kotlin
package net.ft8vc.app.controllers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.ft8vc.ft8native.Ft8DecodeResult
import net.ft8vc.ft8native.fakes.Ft8DecoderFake
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class DecodeControllerClockRealignTest {

    // A known slot-boundary-aligned epoch (matches the sibling DecodeControllerTest constant).
    private val slot0Start = 1_700_000_000_000L
    private val clockMs = AtomicLong(slot0Start)
    private lateinit var decoder: Ft8DecoderFake
    private lateinit var scope: CoroutineScope
    private lateinit var controller: DecodeController

    @Before fun setUp() {
        clockMs.set(slot0Start)
        decoder = Ft8DecoderFake()
        scope = CoroutineScope(UnconfinedTestDispatcher())
        controller = DecodeController(
            decoder = decoder,
            scope = scope,
            executor = Executors.newSingleThreadExecutor(),
            decodeDispatcher = UnconfinedTestDispatcher(),
            clock = { clockMs.get() },
        )
        controller.setStationContext("W0DEV", "EM26")
        controller.setEarlyDecodeEnabled(false)   // keep the test to the FULL boundary pass
    }

    @After fun tearDown() {
        controller.close()
        scope.cancel()
    }

    @Test fun `realign clears the published clock offset`() = runTest {
        // Four DTs in one FULL slot -> pooled >= MIN_SAMPLES -> non-null offset.
        decoder.queueDecodeResults(
            listOf(
                Ft8DecodeResult("CQ K1ABC FN42", -8, 2.0f, 1000f, 50),
                Ft8DecodeResult("CQ K2DEF FN30", -8, 2.0f, 1100f, 50),
                Ft8DecodeResult("CQ K3GHI FN20", -8, 2.0f, 1200f, 50),
                Ft8DecodeResult("CQ K4JKL FN10", -8, 2.0f, 1300f, 50),
            ),
        )
        // Fill slot 0 then cross the boundary to trigger the FULL decode pass.
        controller.onFrames(ShortArray(180_000) { 1000 })
        clockMs.set(clockMs.get() + 15_001L)
        controller.onFrames(ShortArray(1))

        assertNotNull(controller.slice.value.clockOffsetSeconds)

        controller.realignClockEstimate()

        assertNull(controller.slice.value.clockOffsetSeconds)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.DecodeControllerClockRealignTest"`
Expected: FAIL — `realignClockEstimate` unresolved.

- [ ] **Step 3: Add the method to `DecodeController`**

Add immediately after the existing `reset()` method (around
`DecodeController.kt:176`):

```kotlin
    /**
     * Discard the DT-offset estimator window and clear the published offset.
     * Called after the operator applies a clock correction so the chip rebuilds
     * from post-correction slots rather than lingering on stale DTs.
     */
    fun realignClockEstimate() {
        clockOffset.reset()
        _slice.update { it.copy(clockOffsetSeconds = null) }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.DecodeControllerClockRealignTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt app/src/test/java/net/ft8vc/app/controllers/DecodeControllerClockRealignTest.kt
git commit -m "feat(decode): realignClockEstimate to clear offset after correction"
```

---

### Task 4: Wire the shared corrected clock + align/reset into `OperateViewModel`

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (view-state class ~88-101; controller construction 126, 145, 155; combine block ~181-199; add public methods)
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt` (add field near line 162)

**Interfaces:**
- Consumes: `ClockCorrection` (Task 1), `DecodeController.realignClockEstimate()` (Task 3), `DecodeController.slice.value.clockOffsetSeconds`.
- Produces: `OperateViewModel.alignClock()`, `OperateViewModel.resetClockAlignment()`; `OperateUiState.appliedClockOffsetMs: Long`.

- [ ] **Step 1: Add `appliedClockOffsetMs` to `OperateUiState`**

In `OperateUiState.kt`, under the "Clock health" block (after line 162):

```kotlin
    /** Estimated phone-clock offset vs FT8 band time (median DT), null when unknown. */
    val clockOffsetSeconds: Float? = null,
    /** Operator-applied clock correction (ms), 0 when unaligned. In-memory only. */
    val appliedClockOffsetMs: Long = 0L,
```

- [ ] **Step 2: Add `appliedClockOffsetMs` to the internal view-state**

In `OperateViewModel.kt`, in `private data class OperateViewState(` (line 88), add a field before the closing paren (after `contactCount`):

```kotlin
        val contactCount: Int = 0,
        /** Operator-applied clock correction (ms); mirrored into OperateUiState for the Settings/chip display. */
        val appliedClockOffsetMs: Long = 0L,
    )
```

- [ ] **Step 3: Declare the shared `ClockCorrection` before the controllers**

In `OperateViewModel.kt`, immediately before `private val decodeController = DecodeController(` (line 126):

```kotlin
    private val clockCorrection = net.ft8vc.core.ClockCorrection()

    private val decodeController = DecodeController(
```

- [ ] **Step 4: Inject the corrected clock into all three controllers**

In the `DecodeController(` constructor call (line 126), add:

```kotlin
    private val decodeController = DecodeController(
        decoder = Ft8Native,
        scope = viewModelScope,
        clock = clockCorrection::now,
        workedBeforeLookup = { call ->
```

In the `QsoSessionController(` call (line 145), add `clock = clockCorrection::now,` as a parameter (place it first, before `scope`):

```kotlin
    private val qsoSession = QsoSessionController(
        clock = clockCorrection::now,
        scope = viewModelScope,
        transmitFn = ::transmitForQsoLoop,
```

In the `TxOrchestrator(` call (line 155), add `clock = clockCorrection::now,` (place it after `scope = viewModelScope,`):

```kotlin
        rigSession = rigSession,
        scope = viewModelScope,
        clock = clockCorrection::now,
        notifyFn = ::notify,
```

- [ ] **Step 5: Map the applied offset into `OperateUiState` in the combine block**

In the `OperateUiState(` builder inside `combine` (around line 182-199), add the field. Place it next to the existing clock line (`clockOffsetSeconds = decode.clockOffsetSeconds,` lives in that builder):

```kotlin
                appliedClockOffsetMs = view.appliedClockOffsetMs,
```

- [ ] **Step 6: Add the `alignClock()` and `resetClockAlignment()` methods**

Add near the other public operator actions in `OperateViewModel` (e.g. beside `retryCat`). The residual is read from the decode controller's current slice:

```kotlin
    /**
     * Apply the currently measured DT residual to the shared slot-timing clock,
     * shifting RX slot collection, early-decode timing, TX keying, and slot
     * parity together. No-op when no residual estimate is available yet.
     */
    fun alignClock() {
        val residual = decodeController.slice.value.clockOffsetSeconds ?: return
        clockCorrection.applyResidualSeconds(residual)
        decodeController.realignClockEstimate()
        _viewState.update { it.copy(appliedClockOffsetMs = clockCorrection.appliedOffsetMs) }
    }

    /** Clear any applied clock correction (Settings "Reset"). */
    fun resetClockAlignment() {
        clockCorrection.reset()
        decodeController.realignClockEstimate()
        _viewState.update { it.copy(appliedClockOffsetMs = 0L) }
    }
```

- [ ] **Step 7: Build to verify wiring compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Fixes any parameter-order mismatch surfaced by the
three constructor edits.)

- [ ] **Step 8: Run the existing controller + VM unit tests for no regressions**

Run: `./gradlew :app:testDebugUnitTest :core:testDebugUnitTest`
Expected: PASS (existing suites unaffected; offset defaults to 0 → timing unchanged).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateViewModel.kt app/src/main/java/net/ft8vc/app/OperateUiState.kt
git commit -m "feat(vm): shared corrected clock + alignClock/resetClockAlignment"
```

---

### Task 5: Make the offset chip tappable + wire `onAlignClock` through Operate

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/OperateStatusBar.kt` (signature 54-63; chip block 118-139)
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt` (call site 100-110)

**Interfaces:**
- Consumes: `OperateViewModel.alignClock()` (Task 4), `state.clockOffsetSeconds`.
- Produces: `OperateStatusBar` gains `onAlignClock: () -> Unit = {}`.

- [ ] **Step 1: Add the callback parameter to `OperateStatusBar`**

In the `fun OperateStatusBar(` signature (line 54), add after `onRetryCat`:

```kotlin
    onRetryCat: () -> Unit = {},
    onAlignClock: () -> Unit = {},
    modifier: Modifier = Modifier,
```

- [ ] **Step 2: Make the chip `Surface` clickable**

In the clock chip block (lines 126-137), add a `clickable` modifier and update the
tooltip copy to say tapping aligns:

```kotlin
                    WithTooltip(
                        text = "Phone clock differs from FT8 band time — tap to align to the band",
                    ) {
                        Surface(
                            shape = Ft8Compact.chipShape,
                            color = color.copy(alpha = 0.2f),
                            modifier = Modifier.clickable(onClick = onAlignClock),
                        ) {
                            Text(
                                text = "Clock %+.1fs".format(Locale.US, off),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = color,
                                maxLines = 1,
                            )
                        }
                    }
```

(Verify `androidx.compose.foundation.clickable` is imported; add the import if the
inspector flags it unresolved.)

- [ ] **Step 3: Pass the VM callback from `OperateScreen`**

In the `OperateStatusBar(` call (line 100), add:

```kotlin
                onRetryCat = vm::retryCat,
                onAlignClock = vm::alignClock,
                modifier = Modifier.fillMaxWidth(),
```

- [ ] **Step 4: Build to verify UI compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/operate/OperateStatusBar.kt app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt
git commit -m "feat(ui): tap the clock-offset chip to align to band time"
```

---

### Task 6: Settings "Clock alignment" section

Always-available entry point: shows applied correction + live residual, with
Align now (enabled only when a residual estimate exists) and Reset.

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `state.clockOffsetSeconds`, `state.appliedClockOffsetMs`, `vm::alignClock`, `vm::resetClockAlignment`.
- Produces: nothing.

- [ ] **Step 1: Add the section**

Insert a new `SettingsSection` in the `SettingsScreen` column (e.g. after the
audio device / rig sections, following the existing `SettingsSection("Title") { }`
idiom). Use `String.format` for the offsets:

```kotlin
            SettingsSection("Clock alignment") {
                val residual = state.clockOffsetSeconds
                val appliedS = state.appliedClockOffsetMs / 1000f
                Text(
                    "Applied correction: %+.1f s".format(java.util.Locale.US, appliedS),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (residual != null) {
                        "Residual vs band time: %+.1f s".format(java.util.Locale.US, residual)
                    } else {
                        "Residual vs band time: not enough decodes yet"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = vm::alignClock,
                        enabled = residual != null,
                    ) { Text("Align now") }
                    OutlinedButton(
                        onClick = vm::resetClockAlignment,
                        enabled = state.appliedClockOffsetMs != 0L,
                    ) { Text("Reset") }
                }
            }
```

(Verify imports for `Button`, `OutlinedButton`, `Row`, `Arrangement`,
`FontWeight` — most are already used elsewhere in this file; add any the
inspector flags.)

- [ ] **Step 2: Build to verify Settings compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt
git commit -m "feat(settings): clock alignment section with align/reset"
```

---

### Task 7: Full verification + field check

**Files:** none (verification only).

- [ ] **Step 1: Full unit-test + assemble**

Run: `./gradlew :core:testDebugUnitTest :app:testDebugUnitTest assembleDebug`
Expected: All PASS, APK assembles.

- [ ] **Step 2: Field verification on the reference rig (FT-891 + Digirig)**

Manual, per the milestone's field-verification bar. Confirm:
1. With no tap, decodes arrive and a QSO completes exactly as before (parity).
2. Let RX run until the offset chip appears (or Settings shows a residual); tap
   the chip / Settings "Align now"; confirm the chip fades toward ~0 over the
   next few slots and decodes keep arriving.
3. Start a TX / answer a CQ after aligning; confirm keying still lands in the
   correct slot and a full QSO completes.
4. Settings "Reset" returns applied correction to +0.0 s.

- [ ] **Step 3: Commit any field-fix follow-ups (if needed), else done**

---

## Self-Review

**Spec coverage:**
- Manual apply → Tasks 4 (`alignClock`), 5 (chip), 6 (Settings). ✓
- Both trigger surfaces, chip conditional → Task 5 (chip stays inside the existing `WARN_S` guard), Task 6 (always-available Settings). ✓
- In-memory only → Task 1 (`ClockCorrection` holds a plain field), Task 4 (`_viewState`, no DataStore). ✓
- Shared corrected clock across all three seams → Task 4 Steps 3-4. ✓
- Estimator reset on apply → Task 3 + Task 4 Step 6. ✓
- One-time slot-flush guardrail → Task 2. ✓
- Nothing-to-apply when null → Task 4 Step 6 (`?: return`), Task 6 (`enabled = residual != null`). ✓
- `appliedClockOffsetMs` display field → Task 4 Steps 1-2, 5. ✓
- Behavior parity (offset defaults 0) → Task 4 Step 8, Task 7 Step 2.1. ✓
- Field verification on FT-891 → Task 7 Step 2. ✓

**Placeholder scan:** none — every code step shows complete code.

**Type consistency:** `ClockCorrection.now/appliedOffsetMs/applyResidualSeconds/reset` used identically in Tasks 1, 4. `realignClockEstimate()` defined in Task 3, called in Task 4. `alignClock`/`resetClockAlignment` defined in Task 4, referenced in Tasks 5-6. `appliedClockOffsetMs` (Long) consistent across OperateViewState, OperateUiState, and Settings display. ✓
