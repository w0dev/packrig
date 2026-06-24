# Self-TX Decode Row Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the synthetic self-TX row appear at the *start* of transmit (WSJT-X behavior) and render its time as `HHmmss` to match RX decode rows.

**Architecture:** Two independent changes. (1) A new pure top-level formatter `formatRowTimeUtc(utcMillis)` in the `app` module produces the `HHmmss` UTC string; `OperateViewModel`'s `txLog` collector uses it instead of `LocalTime.toString()`. (2) In `TxOrchestrator.runTxBody`, the `_txLog.tryEmit(...)` call moves from after `playback.playBlocking` (success-only) to the commit-to-key point, with the `if (result)` guard dropped.

**Tech Stack:** Kotlin, kotlinx.coroutines, JUnit4, `java.time`.

## Global Constraints

- Behavior on the reference rig (Yaesu FT-891 + Digirig) must stay byte-equivalent for RX/TX/CAT/QSO; only the two stated UI deltas (row timing, time format) change. (from spec: "Out of scope")
- PTT/CAT/safety semantics are untouched — the fail-closed re-check, watchdog, and timeout layers stay exactly as they are; only the `_txLog` emit point moves. (from spec: "Out of scope")
- The synthetic row's `timeUtc` format string and zone must match RX exactly: `HHmmss`, UTC. (from spec: "Change 2")
- No new top-level dependencies. (from CLAUDE.md milestone constraints)
- Kotlin Official style: 4-space indent, no semicolons, no wildcard imports, one top-level public type per file.

---

### Task 1: `HHmmss` UTC time format for the self-TX row

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/DecodeRowTime.kt`
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt:322-340` (the `txOrchestrator.txLog.collect` block)
- Test: `app/src/test/java/net/ft8vc/app/DecodeRowTimeTest.kt`

**Interfaces:**
- Produces: `fun formatRowTimeUtc(utcMillis: Long): String` — top-level function in package `net.ft8vc.app`. Returns a 6-character `HHmmss` string in UTC (e.g. `15330000L` → `"041530"`, `0L` → `"000000"`).
- Consumes: nothing from other tasks.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/ft8vc/app/DecodeRowTimeTest.kt`:

```kotlin
package net.ft8vc.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DecodeRowTimeTest {

    @Test
    fun formatsEpochAsAllZeros() {
        assertEquals("000000", formatRowTimeUtc(0L))
    }

    @Test
    fun formatsKnownTimeAsHHmmssInUtc() {
        // 1970-01-01T04:15:30Z = (4*3600 + 15*60 + 30) seconds = 15330 s.
        assertEquals("041530", formatRowTimeUtc(15_330_000L))
    }

    @Test
    fun producesSixDigitsWithNoColons() {
        val s = formatRowTimeUtc(1_700_000_000_000L)
        assertEquals(6, s.length)
        assertFalse("must match RX HHmmss format (no colons)", s.contains(":"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.DecodeRowTimeTest"`
Expected: FAIL — compilation error, `formatRowTimeUtc` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/net/ft8vc/app/DecodeRowTime.kt`:

```kotlin
package net.ft8vc.app

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Formats a UTC epoch-millis instant as the decode-list timestamp string `HHmmss`.
 *
 * Matches the format RX decodes use (see DecodeController's `HHmmss`/UTC formatter)
 * so the synthetic self-TX row is visually identical to received rows.
 */
private val ROW_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC)

fun formatRowTimeUtc(utcMillis: Long): String =
    ROW_TIME_FORMAT.format(Instant.ofEpochMilli(utcMillis))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.DecodeRowTimeTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Wire the formatter into the `txLog` collector**

In `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`, the `txOrchestrator.txLog.collect` block currently builds `timeUtc` like this:

```kotlin
        viewModelScope.launch {
            txOrchestrator.txLog.collect { ev ->
                val timeUtc = java.time.Instant.ofEpochMilli(ev.utcMillis)
                    .atZone(java.time.ZoneOffset.UTC)
                    .toLocalTime()
                    .withNano(0)
                    .toString()
                val row = DecodeRow(
```

Replace the `val timeUtc = …toString()` expression with the helper:

```kotlin
        viewModelScope.launch {
            txOrchestrator.txLog.collect { ev ->
                val timeUtc = formatRowTimeUtc(ev.utcMillis)
                val row = DecodeRow(
```

(The rest of the `DecodeRow(...)` construction and `decodeController.appendSyntheticRow(row)` are unchanged.)

- [ ] **Step 6: Verify the module compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (no unused-import warning for the now-removed inline `java.time` usage — the block no longer references `Instant`/`ZoneOffset` directly, but those are fully-qualified inline references, so no import lines need removing).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/DecodeRowTime.kt \
        app/src/test/java/net/ft8vc/app/DecodeRowTimeTest.kt \
        app/src/main/java/net/ft8vc/app/OperateViewModel.kt
git commit -m "feat(app): render self-TX decode row time as HHmmss to match RX rows"
```

---

### Task 2: Emit the self-TX row at the start of transmit

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt:334` (add emit at commit-to-key) and `:373-375` (remove post-playback emit)
- Test: `app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorTest.kt` (rename one test, add two)

**Interfaces:**
- Consumes: `TxLogEvent(utcMillis: Long, freqHz: Int, message: String)`, `TxOrchestrator.txLog: SharedFlow<TxLogEvent>`, `TxOrchestrator.transmit(message, txFreqHz)`, `TxOrchestrator.emergencyHalt(reason)`, `TxOrchestrator.slice` — all already defined in the production class. Test fakes `ScriptablePlayback` (`failNextWith`), `waitUntil`, and the `onSubscription` latch pattern already exist in `TxOrchestratorTest.kt`.
- Produces: nothing new for later tasks.

- [ ] **Step 1: Write the failing/changed tests**

In `app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorTest.kt`, first **rename** the existing test to reflect the broadened semantics. Change its signature line:

```kotlin
    @Test
    fun txLog_emits_on_successful_transmit() = runBlocking {
```

to:

```kotlin
    @Test
    fun txLog_emits_atTxStart_onSuccessfulTransmit() = runBlocking {
```

(The body is unchanged — a successful transmit still emits exactly one event with the right freq/message.)

Then **add** these two tests directly below it (before the `emergencyHalt_recordsSafetyHaltLatched` test):

```kotlin
    @Test
    fun txLog_emitsAtTxStart_evenWhenPlaybackHalts() = runBlocking {
        val collected = java.util.Collections.synchronizedList(mutableListOf<TxLogEvent>())
        val subscribed = CountDownLatch(1)
        val collectJob = scope.launch {
            orchestrator.txLog
                .onSubscription { subscribed.countDown() }
                .collect { collected += it }
        }
        assertTrue("txLog collector must subscribe", subscribed.await(2, TimeUnit.SECONDS))

        // Playback throws → transmit reports failure, but the row was already logged
        // at TX start (before keying), so it must still be emitted.
        playback.failNextWith = RuntimeException("playback boom")
        val ok = orchestrator.transmit(message = "CQ K1ABC FN42", txFreqHz = 1500)

        assertFalse("transmit should report failure when playback throws", ok)
        waitUntil { collected.isNotEmpty() }
        assertEquals(1, collected.size)
        assertEquals(1500, collected[0].freqHz)
        assertEquals("CQ K1ABC FN42", collected[0].message)
        collectJob.cancel()
    }

    @Test
    fun txLog_doesNotEmit_whenTxBlockedBeforeKeying() = runBlocking {
        val collected = java.util.Collections.synchronizedList(mutableListOf<TxLogEvent>())
        val subscribed = CountDownLatch(1)
        val collectJob = scope.launch {
            orchestrator.txLog
                .onSubscription { subscribed.countDown() }
                .collect { collected += it }
        }
        assertTrue("txLog collector must subscribe", subscribed.await(2, TimeUnit.SECONDS))

        // Safety halt active → TX is blocked before any PTT/audio goes out, so no
        // synthetic row should appear (nothing was transmitted).
        orchestrator.emergencyHalt("Field test halt")
        waitUntil { orchestrator.slice.value.appRfState == AppRfState.EMERGENCY_HALT }

        val ok = orchestrator.transmit(message = "CQ K1ABC FN42", txFreqHz = 1500)

        assertFalse("transmit must be blocked when halted", ok)
        // Give any erroneous emit a chance to surface before asserting absence.
        Thread.sleep(50)
        assertTrue("no synthetic row when TX blocked before keying", collected.isEmpty())
        collectJob.cancel()
    }
```

- [ ] **Step 2: Run tests to verify the new behavior fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.TxOrchestratorTest"`
Expected: `txLog_emitsAtTxStart_evenWhenPlaybackHalts` FAILS (current code only emits `if (result)`, so a thrown playback emits nothing → `waitUntil` times out and `assertEquals(1, …)` fails on size 0). The other two tests pass.

- [ ] **Step 3: Move the emit to the commit-to-key point**

In `app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt`, find this line inside `runTxBody` (just after the fail-closed `when (_slice.value.appRfState)` re-check):

```kotlin
        _slice.update { it.copy(isTransmitting = true, txStatus = "TX: $message") }
```

Insert the emit immediately after it:

```kotlin
        _slice.update { it.copy(isTransmitting = true, txStatus = "TX: $message") }
        // Self-TX row is logged at the START of transmit (WSJT-X behavior): the
        // synthetic decode row appears the instant we commit to keying PTT, not after
        // playback. If the operator halts mid-transmit the row stays. TX blocked before
        // this point (preflight or the fail-closed re-check above) emits nothing —
        // nothing was transmitted.
        _txLog.tryEmit(TxLogEvent(utcMillis = clock(), freqHz = txFreqHz, message = message))
```

- [ ] **Step 4: Remove the old post-playback emit**

Further down in `runTxBody`, delete the success-only emit block:

```kotlin
        if (result) {
            _txLog.tryEmit(TxLogEvent(utcMillis = clock(), freqHz = txFreqHz, message = message))
        }
```

The surrounding lines stay:

```kotlin
        _slice.update {
            it.copy(
                isTransmitting = false,
                txStatus = if (result) "Sent: $message" else "TX halted",
            )
        }
        captureControl.resumeAfterTx()
        return result
```

- [ ] **Step 5: Run the full TxOrchestrator suite to verify all pass**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.TxOrchestratorTest"`
Expected: PASS — all tests green, including the renamed `txLog_emitsAtTxStart_onSuccessfulTransmit`, the new halt-still-emits test, and the new blocked-no-emit test. The 4-layer PTT/watchdog tests are unaffected (they don't subscribe to `txLog`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt \
        app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorTest.kt
git commit -m "feat(app): emit self-TX decode row at TX start, not after playback"
```

---

### Task 3: Full-suite regression check

**Files:** none (verification only)

**Interfaces:** none.

- [ ] **Step 1: Run the app module's unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — no regressions across the app test suite (DecodeRowTimeTest, TxOrchestratorTest, and all existing tests pass).

- [ ] **Step 2: No commit**

Verification task — nothing to commit. If any pre-existing, unrelated test is failing in the working tree, report it rather than "fixing" it under this plan.

---

## Self-Review

**Spec coverage:**
- Spec "Change 1 — emit at TX start" → Task 2 (move emit to commit-to-key, drop `if (result)`, placed after the fail-closed re-check). ✓
- Spec "Change 2 — match RX time format" → Task 1 (`formatRowTimeUtc` → `HHmmss` UTC, wired into the collector). ✓
- Spec "Testing": existing test broadened/renamed (Task 2 Step 1), "emitted even when playback halts" case (Task 2), "not emitted when blocked before keying" case (Task 2), `HHmmss` format assertion (Task 1). ✓
- Spec "no row when blocked before keying" decision → Task 2 `txLog_doesNotEmit_whenTxBlockedBeforeKeying` (exercised via `emergencyHalt`, which blocks at preflight — the same user-visible contract: blocked TX → no row). ✓
- Spec "Out of scope" (no RX format / layout / PTT-safety changes) → respected; only the emit point and one format string move. ✓

**Placeholder scan:** No TBD/TODO/"add error handling"/"similar to Task N". All code shown in full. ✓

**Type consistency:** `formatRowTimeUtc(utcMillis: Long): String` defined in Task 1, consumed once in the same task's Step 5. `TxLogEvent(utcMillis, freqHz, message)` matches the existing data class. `emergencyHalt`, `slice`, `txLog`, `transmit` match existing production signatures. `playback.failNextWith` and the `onSubscription`/`waitUntil` patterns match the existing test file. ✓
