# CQ-Origin Auto-Resume Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-resume CQ ("Resume CQ after QSO") fires only when the ended session originated from the operator calling CQ — never after answering/resuming someone else's CQ (S&P).

**Architecture:** Add one boolean `runOriginatedFromCq` to `QsoSessionController`, set from a new `cqOrigin` parameter on the single session chokepoint `startQsoLoop()`. Gate `maybeAutoResumeCq()` on that flag. The flag is deliberately **never reset** by `stopQsoInternal()` — it holds "origin of the most-recently-started session" and is only ever read in the brief window after a session ends (before any new session starts), which avoids an ordering hazard where two callers invoke `stopQsoInternal()` immediately before `maybeAutoResumeCq()`.

**Tech Stack:** Kotlin, Coroutines, JUnit4 + kotlinx-coroutines-test (existing `QsoSessionControllerTest` harness with `UnconfinedTestDispatcher` and an `AtomicLong` fake clock).

## Global Constraints

- Tech stack: Kotlin + Coroutines; no new dependencies.
- Platform: Android `minSdk = 28`; Java 17 / JVM 17.
- Behavior parity: RX/TX/CAT/QSO byte-equivalent on the reference FT-891 + Digirig. This change only narrows *when* auto-resume fires; it must not alter the reference RX/TX/CAT audio path.
- No DataStore/settings-schema change: the existing `autoCqResumeEnabled` key, default `false`, and `enabled = state.txEnabled` gating are unchanged.
- License gate / receive-only default: untouched.
- Naming: camelCase members, PascalCase types, no wildcard imports, 4-space indent, no semicolons.

---

## Design Notes: the four `maybeAutoResumeCq` call sites

`maybeAutoResumeCq()` is invoked from four places in `QsoSessionController.kt` (verified):

| Line | Context | Reaches `maybeAutoResumeCq` after `stopQsoInternal()`? | Desired with gate |
|---|---|---|---|
| `:322` | decode-path `Complete` (inline teardown, no `stopQsoInternal`) | No | Resume iff CQ-origin |
| `:331` | `dxAnsweredAnotherStation` — S&P only, calls `stopQsoInternal()` at `:329` first | Yes | Never (origin always non-CQ) |
| `:417` | TX-path `afterTransmit` `Complete` (no `stopQsoInternal`) | No | Resume iff CQ-origin |
| `:455` | `abandonForNoReply` (dx != null), calls `stopQsoInternal()` at `:453` first | Yes | Resume iff CQ-origin |

Because `:331` and `:455` call `stopQsoInternal()` *before* reading the flag, `stopQsoInternal()` must **not** reset `runOriginatedFromCq` — otherwise a legitimate CQ-run session that gets abandoned for no-reply at `:455` would be wrongly suppressed. Setting the flag only in `startQsoLoop()` (from the param) keeps it correct at all four sites.

The six `startQsoLoop()` call sites and their origin:

| Line | Caller | `cqOrigin` |
|---|---|---|
| `:202` | `startCq()` | `true` |
| `:220` | `answerCq()` | `false` |
| `:485` | auto-resume restart in `maybeAutoResumeCq()` | `true` |
| `:517` | `resumeFromOpportunity()` (manual resume-from-decode + answer-when-called) | `false` |
| `:548` | `tryAutoAnswerCq()` | `false` |
| (`:344`) | signature — add param | — |

Note `:202` and `:485` are the *identical* source line `startQsoLoop(machine, hearingSlotParity = null)` and both take `cqOrigin = true`, so a `replace_all` edit is safe for those two.

---

## Task 1: Gate auto-resume on CQ-origin sessions

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/controllers/QsoSessionController.kt`
- Test: `app/src/test/java/net/ft8vc/app/controllers/QsoSessionControllerTest.kt`

**Interfaces:**
- Consumes: existing controller test harness — `controller.startCq()`, `controller.answerCq(DecodeRow)`, `controller.resumeFromDecode(DecodeRow)`, `controller.onDecodeBatch(List<QsoDecode>, TxSlotParity)`, `controller.setAutoCqResumeEnabled(Boolean)`, `controller.slice.value` (fields `qsoActive: Boolean`, `qsoState: String?`), `completedSnapshots`, `notifications`, helpers `decodeRowCq(call, grid)` and `decodeRowDirected(message)`.
- Produces: `startQsoLoop(machine: QsoMachine, hearingSlotParity: TxSlotParity?, cqOrigin: Boolean)` (private) and private field `runOriginatedFromCq: Boolean`. No public API change.

- [ ] **Step 1: Add the failing/changed tests**

In `QsoSessionControllerTest.kt`, add these three new tests (place them next to the existing `autoResumeCq_*` tests, after `autoResumeCq_notWhenTxDisabledAtFireTime` at ~line 353):

```kotlin
@Test
fun autoResumeCq_notAfterAnsweringCq() = runTest {
    // S&P: we answered K1ABC's CQ. Completing must NOT leave us calling CQ.
    controller.setAutoCqResumeEnabled(true)
    controller.answerCq(decodeRowCq("K1ABC", "FN42"))
    // Their directed reply, our R-report exchange, then their 73 → Complete.
    controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC -03", -8)), TxSlotParity.EVEN)
    controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC RR73", -8)), TxSlotParity.EVEN)
    assertEquals(1, completedSnapshots.size)
    assertFalse("answered-CQ session must not auto-resume CQ", controller.slice.value.qsoActive)
    assertFalse(notifications.any { it.first.contains("resuming CQ", ignoreCase = true) })
}

@Test
fun autoResumeCq_notAfterResumeFromDecode() = runTest {
    // Resuming a specific station is S&P-like → no auto-resume on completion.
    controller.setAutoCqResumeEnabled(true)
    // Initiator grid-reply opportunity: same machine state as startCq + received FN42.
    controller.resumeFromDecode(decodeRowDirected("W0DEV K1ABC FN42"))
    controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC R-15", -8)), TxSlotParity.ODD)
    controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC 73", -8)), TxSlotParity.ODD)
    assertEquals(1, completedSnapshots.size)
    assertFalse("resume-from-decode session must not auto-resume CQ", controller.slice.value.qsoActive)
}

@Test
fun autoResumeCq_carriesForwardAcrossRestart() = runTest {
    // A CQ-origin session that auto-resumes must remain CQ-origin, so the NEXT
    // completion resumes again — a running station keeps running.
    controller.setAutoCqResumeEnabled(true)
    controller.startCq()
    controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -8)), TxSlotParity.ODD)
    controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC R-15", -8)), TxSlotParity.ODD)
    controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC 73", -8)), TxSlotParity.ODD)
    assertEquals(1, completedSnapshots.size)
    assertEquals("Calling CQ…", controller.slice.value.qsoState) // resumed CQ #1

    // Second QSO on the auto-resumed CQ, different partner.
    controller.onDecodeBatch(listOf(QsoDecode("W0DEV K2XYZ EM12", -8)), TxSlotParity.ODD)
    controller.onDecodeBatch(listOf(QsoDecode("W0DEV K2XYZ R-12", -8)), TxSlotParity.ODD)
    controller.onDecodeBatch(listOf(QsoDecode("W0DEV K2XYZ 73", -8)), TxSlotParity.ODD)
    assertEquals(2, completedSnapshots.size)
    assertTrue("auto-resumed CQ must itself auto-resume", controller.slice.value.qsoActive)
    assertEquals("Calling CQ…", controller.slice.value.qsoState) // resumed CQ #2
}
```

Then **change the assertion** of the existing `dxAnswersAnotherStation_autoResumesCqWhenEnabled` test (currently at ~line 402) to reflect the new S&P semantics. Replace the whole test body with:

```kotlin
@Test
fun dxAnswersAnotherStation_doesNotAutoResumeCq_afterAnswering() = runTest {
    // We answered K1ABC (S&P). They pick another caller → we stop. Even with
    // auto-resume on, an answered-CQ session must NOT leave us calling CQ.
    controller.setAutoCqResumeEnabled(true)
    controller.answerCq(decodeRowCq("K1ABC", "FN42"))
    controller.onDecodeBatch(
        listOf(QsoDecode("N0XYZ K1ABC +03", -5)),
        slotParity = TxSlotParity.EVEN,
    )
    assertFalse(controller.slice.value.qsoActive)
    assertNotEquals("Calling CQ…", controller.slice.value.qsoState)
}
```

Add the import for `assertNotEquals` if not present (top of file, with the other `org.junit.Assert.*` imports):

```kotlin
import org.junit.Assert.assertNotEquals
```

- [ ] **Step 2: Run the tests — verify RED**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.QsoSessionControllerTest" -q
```
Expected: FAILS. `autoResumeCq_notAfterAnsweringCq`, `autoResumeCq_notAfterResumeFromDecode`, and `dxAnswersAnotherStation_doesNotAutoResumeCq_afterAnswering` fail because today auto-resume fires regardless of origin (the S&P sessions still show `qsoActive == true` / "Calling CQ…"). `autoResumeCq_carriesForwardAcrossRestart` should already pass (guards against regression). If `carriesForwardAcrossRestart` fails at RED, note the partner/message sequence may need adjustment — confirm the second QSO's decodes drive to `Complete` before proceeding.

- [ ] **Step 3: Implement the flag, param, gate, and six call sites**

In `QsoSessionController.kt`:

**(a)** Add the field next to `pendingAutoCqResume` (~line 132):

```kotlin
    /** Set when a completed/abandoned QSO should auto-restart CQ; cleared by manual stop. */
    private var pendingAutoCqResume: Boolean = false

    /**
     * True when the active session originated from the operator calling CQ (run mode).
     * Gates auto-resume CQ so S&P sessions (answer/resume) don't leave the operator
     * running. Set only in [startQsoLoop] from its cqOrigin param; deliberately NOT
     * reset by [stopQsoInternal] — it holds the most-recently-started session's origin
     * and is read only in the window right after a session ends.
     */
    private var runOriginatedFromCq: Boolean = false
```

**(b)** Add the gate at the top of `maybeAutoResumeCq()` (~line 469), right after the enabled check:

```kotlin
    private fun maybeAutoResumeCq(snackbar: String, finishedJob: Job? = null) {
        if (!autoCqResumeEnabled) return
        if (!runOriginatedFromCq) return
        pendingAutoCqResume = true
```

**(c)** Add the `cqOrigin` parameter to `startQsoLoop()` and assign the field after `stopQsoInternal()` (~line 344):

```kotlin
    private suspend fun startQsoLoop(
        machine: QsoMachine,
        hearingSlotParity: TxSlotParity?,
        cqOrigin: Boolean,
    ) {
        stopQsoInternal()
        runOriginatedFromCq = cqOrigin
        qso = machine
        qsoTxParity = resolveTxParity(machine, hearingSlotParity)
        publishQsoState()
```

**(d)** Update the six call sites:

- `startCq()` (~line 202) and the auto-resume restart (~line 485) are the identical line `startQsoLoop(machine, hearingSlotParity = null)` and both take `cqOrigin = true`. Use a replace-all so both become:
```kotlin
            startQsoLoop(machine, hearingSlotParity = null, cqOrigin = true)
```

- `answerCq()` (~line 220):
```kotlin
            startQsoLoop(machine, hearingSlotParity = row.slotParity, cqOrigin = false)
```

- `resumeFromOpportunity()` (~line 517):
```kotlin
        startQsoLoop(
            machine,
            hearingSlotParity = if (machine.role == QsoRole.Answerer) hearingSlotParity else null,
            cqOrigin = false,
        )
```

- `tryAutoAnswerCq()` (~line 548):
```kotlin
        startQsoLoop(machine, hearingSlotParity = hearingSlotParity, cqOrigin = false)
```

Do **not** add any assignment to `runOriginatedFromCq` inside `stopQsoInternal()`.

- [ ] **Step 4: Run the tests — verify GREEN**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.QsoSessionControllerTest" -q
```
Expected: PASS. All new tests plus the renamed `dxAnswersAnotherStation_doesNotAutoResumeCq_afterAnswering` pass; the pre-existing `autoResumeCq_restartsAfterCompletion`, `autoResumeCq_disabled_staysStopped`, `autoResumeCq_notAfterManualAbandon`, `autoResumeCq_notWhenTxDisabledAtFireTime`, and the other `dxAnswersAnotherStation_*` tests remain green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/controllers/QsoSessionController.kt \
        app/src/test/java/net/ft8vc/app/controllers/QsoSessionControllerTest.kt
git commit -m "feat(qso): gate auto-resume CQ on CQ-origin sessions

Only sessions started via Call CQ auto-resume CQ. Answering or resuming
someone's CQ (S&P) no longer leaves the operator calling CQ on completion
or partner-abandon. Flag set at the single startQsoLoop chokepoint and never
reset by stopQsoInternal, keeping all four maybeAutoResumeCq call sites correct.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Align the Settings toggle copy with the new semantics

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt:259`

**Interfaces:**
- Consumes: nothing new. Pure UI string change on the existing `AutoToggleRow` for `autoCqResumeEnabled`.
- Produces: nothing consumed downstream.

- [ ] **Step 1: Update the subtitle text**

In `SettingsScreen.kt`, change the `AutoToggleRow` subtitle (~line 259):

```kotlin
                AutoToggleRow(
                    title = "Resume CQ after QSO",
                    subtitle = "After a QSO you started by calling CQ, keep calling CQ",
                    checked = state.autoCqResumeEnabled,
                    onCheckedChange = vm::setAutoCqResumeEnabled,
                    enabled = state.txEnabled,
                )
```

Title, `checked`, `onCheckedChange`, and `enabled` are unchanged.

- [ ] **Step 2: Compile the app module**

Run:
```bash
./gradlew :app:compileDebugKotlin -q
```
Expected: BUILD SUCCESSFUL (no test for a UI string; a clean compile is the check).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt
git commit -m "docs(settings): clarify Resume CQ toggle applies to CQ-origin QSOs

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Full app unit-test regression sweep

**Files:** none (verification only).

- [ ] **Step 1: Run the full app unit-test suite**

Run:
```bash
./gradlew :app:testDebugUnitTest -q
```
Expected: BUILD SUCCESSFUL. In particular no regressions in `TxOrchestrator*`, `TxCaptureControlTest`, `EarlyDecodeLateTxIntegrationTest`, or `QsoSessionControllerTest`. If anything fails, stop and investigate before claiming completion (superpowers:verification-before-completion).

- [ ] **Step 2: Confirm no stray production behavior change**

Run:
```bash
git diff --stat main...HEAD -- app/src/main
```
Expected: only `QsoSessionController.kt` and `SettingsScreen.kt` are touched under `app/src/main`. No changes to `core/`, `audio/`, `rig/`, `data/`, or `ft8-native/`.

---

## Self-Review Notes

- **Spec coverage:** decision 1 (gate the ghost path too) — the single gate in `maybeAutoResumeCq` covers the `:455` `abandonForNoReply` path and the `:331` `dxAnsweredAnotherStation` path; decision 2 (resume-from-decode non-CQ) — `resumeFromOpportunity` passes `cqOrigin = false` (Task 1d), tested by `autoResumeCq_notAfterResumeFromDecode`; decision 3 (no new setting) — only a subtitle string changes (Task 2), no DataStore key.
- **Deviation from spec:** the spec described "reset the flag to false" in `stopQsoInternal` and "two paths." Planning found **four** `maybeAutoResumeCq` call sites and an ordering hazard, so this plan sets the flag **only** in `startQsoLoop` and never resets it in `stopQsoInternal`. Behavior matches the spec's intent; the mechanism is more precise.
- **Field verification (post-merge, before promotion):** on FT-891 + Digirig — (a) Call CQ, work a station, confirm CQ resumes; (b) answer a station's CQ, confirm the app does NOT start calling CQ after logging. The `abandonForNoReply` CQ-origin-preservation (CQ → answered → ghost → resumes) is guaranteed by the no-reset design but is not unit-tested, as the harness does not drive TX cycles; cover it in field check (a) by walking away mid-QSO if convenient.
