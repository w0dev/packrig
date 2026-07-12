# WSJT-X Log Timing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Log the QSO at receipt of the partner's RR73/RRR on the answerer side (WSJT-X behavior), while still transmitting the courtesy 73 and never writing a duplicate log row at completion.

**Architecture:** Two small changes. (1) `QsoMachine` (pure core) gains a `confirmedByPartner` flag set when the partner's RRR/RR73 advances `SendingRReport → SendingSeventyThree`, and `snapshot()` is relaxed to return a log record in that confirmed state. (2) `QsoSessionController` gains a per-QSO `qsoLogged` guard: the existing `handleQsoComplete` becomes `maybeLogQso()`, called both at the early confirmation moment (from the decode path) and at `Complete`, logging exactly once. Initiator paths, manual control, and the resume-after-roger path are byte-identical to today.

**Tech Stack:** Kotlin, JUnit4, kotlinx-coroutines-test. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-07-04-wsjtx-log-timing-design.md`

## Global Constraints

- Behavior parity: the 73 still transmits in the same slot as v1.0 — only the log moment (and its snackbar) moves earlier. No RX/TX/CAT timing changes.
- Initiator paths (RR73 mode and RRR mode) must be unchanged.
- Manual-control mode and `resumeAnswererAfterRoger` keep logging at `Complete` (after the 73 TX).
- The normal answerer flow must never show the "Re-confirmed — already logged" snackbar.
- Kotlin official style, 4-space indent, no new top-level dependencies.
- All existing tests must keep passing (`:core:test`, `:app:testDebugUnitTest`).

---

### Task 1: `QsoMachine.confirmedByPartner` + relaxed `snapshot()`

**Files:**
- Modify: `core/src/main/java/net/ft8vc/core/QsoMachine.kt`
- Test: `core/src/test/java/net/ft8vc/core/QsoMachineTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces (Task 2 relies on these exact members):
  - `val confirmedByPartner: Boolean` (public getter, private setter) on `QsoMachine`.
  - `fun snapshot(completedAtEpochMs: Long = System.currentTimeMillis()): QsoSnapshot?` now returns non-null when `state == QsoState.Complete` **or** (`state == QsoState.SendingSeventyThree && confirmedByPartner`). Signature unchanged.

- [ ] **Step 1: Write the failing tests**

Append to `core/src/test/java/net/ft8vc/core/QsoMachineTest.kt` (inside the class, using the existing `decode(...)` helper):

```kotlin
    // ── WSJT-X log timing: answerer is loggable at RRR/RR73 receipt ────

    @Test
    fun answererLoggableAtRogerReceipt() {
        val m = QsoMachine("K1ABC", "FN42")
        m.answerCq("W0DEV", "EM26", snr = -8)
        m.onDecodes(decode("K1ABC W0DEV -03", snr = -8))
        assertEquals(QsoState.SendingRReport, m.state)
        // Not confirmed yet — nothing to log before their roger arrives.
        assertFalse(m.confirmedByPartner)
        assertNull(m.snapshot(1000L))

        assertTrue(m.onDecodes(decode("K1ABC W0DEV RRR")))
        assertEquals(QsoState.SendingSeventyThree, m.state)
        assertTrue(m.confirmedByPartner)
        val snap = m.snapshot(1000L)
        assertNotNull(snap)
        assertEquals("W0DEV", snap!!.dxCall)
        assertEquals(-8, snap.reportSent)
        assertEquals(-3, snap.reportRcvd)
        assertEquals(QsoRole.Answerer, snap.role)
        assertEquals(1000L, snap.completedAtEpochMs)
    }

    @Test
    fun answererLoggableAtRr73Receipt() {
        val m = QsoMachine("K1ABC", "FN42")
        m.answerCq("W0DEV", "EM26", snr = -8)
        m.onDecodes(decode("K1ABC W0DEV -03", snr = -8))

        assertTrue(m.onDecodes(decode("K1ABC W0DEV RR73")))
        assertEquals(QsoState.SendingSeventyThree, m.state)
        assertTrue(m.confirmedByPartner)
        assertNotNull(m.snapshot(1000L))
        // The courtesy 73 is still the next TX.
        assertEquals("W0DEV K1ABC 73", m.txMessage())
    }

    @Test
    fun confirmedSnapshotSurvivesMarkTransmitted() {
        val m = QsoMachine("K1ABC", "FN42")
        m.answerCq("W0DEV", "EM26", snr = -8)
        m.onDecodes(decode("K1ABC W0DEV -03", snr = -8))
        m.onDecodes(decode("K1ABC W0DEV RR73"))
        m.markTransmitted()
        assertEquals(QsoState.Complete, m.state)
        assertNotNull(m.snapshot(1000L))
    }

    @Test
    fun resumeAnswererAfterRoger_isNotConfirmed_logsOnlyAtComplete() {
        // Tapping a stray RRR/RR73 decode to resume: the machine has no report
        // data, so it must keep the v1.0 behavior — loggable only at Complete.
        val m = QsoMachine("K1ABC", "FN42")
        m.resumeAnswererAfterRoger("W0DEV")
        assertEquals(QsoState.SendingSeventyThree, m.state)
        assertFalse(m.confirmedByPartner)
        assertNull(m.snapshot(1000L))
        m.markTransmitted()
        assertNotNull(m.snapshot(1000L))
    }

    @Test
    fun initiatorNotLoggableBeforeComplete() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        m.onDecodes(decode("W0DEV K1ABC FN42"))
        m.onDecodes(decode("W0DEV K1ABC R-15"))
        assertEquals(QsoState.SendingRoger, m.state)
        assertFalse(m.confirmedByPartner)
        assertNull(m.snapshot(1000L))
    }

    @Test
    fun manualControl_neverConfirms_logsOnlyAtComplete() {
        // Under manual control onDecodes must not advance, so the partner's
        // RR73 cannot early-confirm — the user owns the flow; log at Complete.
        val m = QsoMachine("K1ABC", "FN42")
        m.answerCq("W0DEV", "EM26", snr = -8)
        m.onDecodes(decode("K1ABC W0DEV -03", snr = -8))
        m.setManualControl(true)
        assertFalse(m.onDecodes(decode("K1ABC W0DEV RR73")))
        assertFalse(m.confirmedByPartner)
        assertNull(m.snapshot(1000L))
    }

    @Test
    fun resetClearsConfirmedByPartner() {
        val m = QsoMachine("K1ABC", "FN42")
        m.answerCq("W0DEV", "EM26", snr = -8)
        m.onDecodes(decode("K1ABC W0DEV -03", snr = -8))
        m.onDecodes(decode("K1ABC W0DEV RR73"))
        assertTrue(m.confirmedByPartner)
        m.reset()
        assertFalse(m.confirmedByPartner)
        assertNull(m.snapshot(1000L))
    }
```

Add these imports if not already present at the top of the test file: `org.junit.Assert.assertNotNull` and confirm `QsoRole` is imported (same package `net.ft8vc.core`, so no import needed for it).

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests "net.ft8vc.core.QsoMachineTest" 2>&1 | tail -20`
Expected: FAIL — compile error `unresolved reference: confirmedByPartner` (the property doesn't exist yet).

- [ ] **Step 3: Implement in `QsoMachine.kt`**

Three edits to `core/src/main/java/net/ft8vc/core/QsoMachine.kt`:

(a) Add the property after `unansweredTxCycles` (around line 67):

```kotlin
    /**
     * True once the partner confirmed our R-report (their RRR/RR73 arrived).
     * From that moment the QSO is loggable even though our courtesy 73 has
     * not been transmitted yet (WSJT-X logs at confirmation, not after 73).
     */
    var confirmedByPartner: Boolean = false
        private set
```

(b) Set it in the `SendingRReport` branch of `onDecodes` and clear it in `reset()`:

```kotlin
            QsoState.SendingRReport -> advanceIf(decodes) { rx, _ ->
                when {
                    rx is QsoRx.Roger && fromDx(rx.target, rx.sender) -> {
                        confirmedByPartner = true
                        QsoState.SendingSeventyThree
                    }
                    rx is QsoRx.RogerBye && fromDx(rx.target, rx.sender) -> {
                        confirmedByPartner = true
                        QsoState.SendingSeventyThree
                    }
                    else -> null
                }
            }
```

In `reset()`, add `confirmedByPartner = false` alongside the other field resets:

```kotlin
    fun reset() {
        state = QsoState.Idle
        role = null
        dxCall = null
        dxGrid = null
        reportSent = null
        reportRcvd = null
        unansweredTxCycles = 0
        confirmedByPartner = false
        manualControl = false
        customTxMessage = null
    }
```

(c) Relax `snapshot()` (replace the existing state check and its doc comment):

```kotlin
    /**
     * Snapshot for logging. Non-null at [QsoState.Complete], or already at
     * [QsoState.SendingSeventyThree] once [confirmedByPartner] — the partner's
     * RRR/RR73 completes the exchange from their side, so the QSO is loggable
     * before our courtesy 73 transmits (WSJT-X behavior).
     */
    fun snapshot(completedAtEpochMs: Long = System.currentTimeMillis()): QsoSnapshot? {
        val loggable = state == QsoState.Complete ||
            (state == QsoState.SendingSeventyThree && confirmedByPartner)
        if (!loggable) return null
        val dx = dxCall ?: return null
        return QsoSnapshot(
            myCall = myCall,
            myGrid = myGrid,
            dxCall = dx,
            dxGrid = dxGrid,
            reportSent = reportSent,
            reportRcvd = reportRcvd,
            role = role ?: QsoRole.Initiator,
            completedAtEpochMs = completedAtEpochMs,
        )
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "net.ft8vc.core.QsoMachineTest" 2>&1 | tail -20`
Expected: PASS (all tests, new and pre-existing).

Then the whole core suite: `./gradlew :core:test 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/ft8vc/core/QsoMachine.kt core/src/test/java/net/ft8vc/core/QsoMachineTest.kt
git commit -m "feat(core): QsoMachine loggable at partner RRR/RR73 receipt

confirmedByPartner flag set on the SendingRReport -> SendingSeventyThree
transition; snapshot() now returns the log record in that confirmed state
so the controller can log at confirmation (WSJT-X timing) instead of
after our courtesy 73.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: `QsoSessionController` early log + `qsoLogged` guard

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/controllers/QsoSessionController.kt`
- Test: `app/src/test/java/net/ft8vc/app/controllers/QsoSessionControllerTest.kt`

**Interfaces:**
- Consumes (from Task 1): `QsoMachine.snapshot(nowMs)` non-null at `SendingSeventyThree` once `confirmedByPartner`.
- Produces: no public API changes. Internal: `handleQsoComplete()` is renamed to `maybeLogQso()` and gains the once-per-QSO guard; new private field `qsoLogged: Boolean`.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/java/net/ft8vc/app/controllers/QsoSessionControllerTest.kt` (inside the class, before the `// ── Helpers` section). The harness's station is `W0DEV`/`EM26`; the answerer flow answers `K1ABC`'s CQ, so directed traffic arrives as `"W0DEV K1ABC …"`:

```kotlin
    // ── WSJT-X log timing: answerer logs at RR73/RRR receipt ───────────

    @Test
    fun answererLogsAtRr73Receipt_beforeSending73() = runTest {
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        // Their report arrives; we move to SendingRReport. Nothing logged yet.
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC -03", -8)), TxSlotParity.EVEN)
        assertTrue(completedSnapshots.isEmpty())

        // Their RR73 arrives: log NOW, while the courtesy 73 is still pending.
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC RR73", -8)), TxSlotParity.EVEN)
        assertEquals(1, completedSnapshots.size)
        assertEquals("K1ABC", completedSnapshots[0].dxCall)
        assertEquals(-8, completedSnapshots[0].reportSent)
        assertEquals(-3, completedSnapshots[0].reportRcvd)
        assertTrue(notifications.any { it.first.contains("QSO complete with K1ABC") })

        // The loop is still alive and the next TX is our courtesy 73.
        assertTrue(controller.slice.value.qsoActive)
        assertEquals("K1ABC W0DEV 73", controller.slice.value.nextTxMessage)
    }

    @Test
    fun answererLogsAtRrrReceipt_too() = runTest {
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC -03", -8)), TxSlotParity.EVEN)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC RRR", -8)), TxSlotParity.EVEN)
        assertEquals(1, completedSnapshots.size)
        assertTrue(controller.slice.value.qsoActive)
    }

    @Test
    fun earlyLoggedQso_survivesStopBefore73Tx() = runTest {
        // The point of the change: interruption after their RR73 must not lose the QSO.
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC -03", -8)), TxSlotParity.EVEN)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC RR73", -8)), TxSlotParity.EVEN)
        assertEquals(1, completedSnapshots.size)

        controller.stopQso()
        assertEquals(1, completedSnapshots.size)
        assertFalse(controller.slice.value.qsoActive)
        // Normal flow must never surface the dupe-guard's snackbar.
        assertFalse(notifications.any { it.first.contains("already logged") })
    }

    @Test
    fun repeatedRr73AfterEarlyLog_doesNotLogAgain() = runTest {
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC -03", -8)), TxSlotParity.EVEN)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC RR73", -8)), TxSlotParity.EVEN)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC RR73", -8)), TxSlotParity.EVEN)
        assertEquals(1, completedSnapshots.size)
        assertFalse(notifications.any { it.first.contains("already logged") })
    }

    @Test
    fun nextQsoAfterEarlyLog_logsNormally() = runTest {
        // qsoLogged must reset between QSOs.
        controller.answerCq(decodeRowCq("K1ABC", "FN42"))
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC -03", -8)), TxSlotParity.EVEN)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC RR73", -8)), TxSlotParity.EVEN)
        controller.stopQso()
        assertEquals(1, completedSnapshots.size)

        // A different station: full initiator flow completes via their 73.
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV N0XYZ EM12", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV N0XYZ R-15", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV N0XYZ 73", -8)), TxSlotParity.ODD)
        assertEquals(2, completedSnapshots.size)
        assertEquals("N0XYZ", completedSnapshots[1].dxCall)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.QsoSessionControllerTest" 2>&1 | tail -20`
Expected: FAIL — `answererLogsAtRr73Receipt_beforeSending73`, `answererLogsAtRrrReceipt_too`, `earlyLoggedQso_survivesStopBefore73Tx`, `repeatedRr73AfterEarlyLog_doesNotLogAgain` fail on `assertEquals(1, completedSnapshots.size)` with actual 0 (today nothing logs until the 73 TX, which the harness never drives). Pre-existing tests still pass.

- [ ] **Step 3: Implement in `QsoSessionController.kt`**

Four edits:

(a) Add the guard field next to `pendingAutoCqResume` (around line 132):

```kotlin
    /** Set when the current QSO has been logged (possibly before the courtesy 73 TX). */
    private var qsoLogged: Boolean = false
```

(b) In `onDecodeBatch`, call the (renamed) logger right after a decode advances the machine, and drop the duplicate call from the Complete branch. Replace the `if (advanced) { ... }` block with:

```kotlin
                if (advanced) {
                    operateTxUserEdited = false
                    publishQsoState()
                    // WSJT-X log timing: the partner's RRR/RR73 makes the QSO
                    // loggable before our courtesy 73 transmits; Complete-by-decode
                    // (initiator receiving 73) logs here too.
                    maybeLogQso()
                    if (qso?.state == QsoState.Complete) {
                        // Capture before nulling so maybeAutoResumeCq can join the
                        // cancelled loop (symmetric with the TX path in afterTransmit).
                        val finished = qsoLoopJob
                        // Tear the loop down from the decode-path coroutine (safe: this
                        // coroutine is NOT qsoLoopJob, so cancelling it doesn't self-cancel).
                        qsoLoopJob?.cancel()
                        qsoLoopJob = null
                        qsoTxParity = null
                        qso = null
                        operateTxUserEdited = false
                        publishQsoState()
                        maybeAutoResumeCq("QSO logged — resuming CQ", finished)
                    }
                }
```

(c) In `afterTransmit`, replace `handleQsoComplete()` with `maybeLogQso()`:

```kotlin
        if (qso?.state == QsoState.Complete) {
            maybeLogQso()
            maybeAutoResumeCq("QSO logged — resuming CQ")
            return true
        }
```

(d) Rename `handleQsoComplete` to `maybeLogQso` with the once-per-QSO guard, and reset the flag in `stopQsoInternal`. Replace the whole `handleQsoComplete` function with:

```kotlin
    /**
     * Log the current QSO exactly once. Loggable at Complete, or already when
     * the partner's RRR/RR73 arrives (snapshot() is non-null from that moment) —
     * WSJT-X logs at confirmation, concurrent with queuing the courtesy 73.
     * [qsoLogged] (not DupeLogGuard) suppresses the second call at Complete so
     * the normal flow never shows the "Re-confirmed" snackbar.
     */
    private suspend fun maybeLogQso() {
        if (qsoLogged) return
        val nowMs = clock()
        val snapshot = qso?.snapshot(nowMs) ?: return
        qsoLogged = true
        if (!dupeLogGuard.shouldLog(snapshot.dxCall, nowMs)) {
            notifyFn("Re-confirmed ${snapshot.dxCall} — already logged", SnackbarEvent.Tag.TRANSIENT)
            return
        }
        onQsoComplete(snapshot)
        notifyFn("QSO complete with ${snapshot.dxCall} — logged", SnackbarEvent.Tag.QSO_COMPLETE)
    }
```

In `stopQsoInternal`, add `qsoLogged = false` right after `pendingAutoCqResume = false`:

```kotlin
    private suspend fun stopQsoInternal() {
        pendingAutoCqResume = false
        qsoLogged = false
        qsoLoopJob?.cancel()
        ...
```

(`startQsoLoop` calls `stopQsoInternal()` first, so every new machine starts with a clean flag — no separate reset needed there.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.QsoSessionControllerTest" 2>&1 | tail -20`
Expected: PASS — all new tests plus every pre-existing test in the class (in particular `duplicateCompletionWithinWindow_logsOnce`, which exercises the dupe guard through the resume path, and the `autoResumeCq_*` family).

Then the full app + core suites: `./gradlew :core:test :app:testDebugUnitTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/controllers/QsoSessionController.kt app/src/test/java/net/ft8vc/app/controllers/QsoSessionControllerTest.kt
git commit -m "feat(app): log QSO at partner RR73/RRR receipt (WSJT-X timing)

The answerer used to log only after its courtesy 73 finished transmitting
— one slot later than WSJT-X, and a confirmed QSO was lost if Stop/USB
unplug/TX failure hit before the 73 slot. maybeLogQso() (was
handleQsoComplete) now runs from the decode path the moment snapshot()
is available and a per-QSO qsoLogged flag makes the Complete-path call a
no-op, so the 73 still transmits in the same slot and no duplicate row
or 'Re-confirmed' snackbar appears.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Verification (after both tasks)

- `./gradlew :core:test :app:testDebugUnitTest` — full JVM suites green.
- Field check before promotion (reference FT-891 + Digirig): complete one QSO as answerer; confirm the log row and "QSO complete — logged" snackbar appear in the slot the partner's RR73 arrives, the 73 still transmits in the following our-parity slot, and the Log tab shows exactly one entry.
