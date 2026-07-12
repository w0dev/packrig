# QSO Sequencing Upgrades Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Initiator RR73 with log-on-send (toggle, default ON), auto-resume CQ after logged/abandoned QSOs (toggle, default OFF), active-CQ acceptance of report and R-report replies, and base-call/hashed callsign matching — WSJT-X-parity sequencing for POTA activations.

**Architecture:** A behavior flag on `QsoMachine` (no new states) switches `SendingRoger` to RR73-and-complete-on-transmit. `handleCqReplies` delegates to the existing `AnswerSelector.selectOpportunity` with a kind filter. A shared `CallsignMatcher` replaces three private matchers. `QsoSessionController` gains a `DupeLogGuard` (10-min window) and a pending-flag auto-resume relaunch on its single-threaded dispatcher. Settings plumb through the established DataStore → StationSettings → SettingsBridge → OperateUiState/controller chain.

**Tech Stack:** Kotlin 2.3.21, Coroutines 1.10.2, JUnit 4, DataStore Preferences, Jetpack Compose (settings rows only).

**Spec:** `docs/superpowers/specs/2026-07-01-qso-sequencing-upgrades-design.md`

## Global Constraints

- RX/TX/CAT untouched: no changes under `audio/`, `rig/`, or `ft8-native/`.
- `send_rr73` DataStore key, default **true**; `auto_cq_resume` DataStore key, default **false**.
- Dupe-guard window: `600_000` ms (10 minutes), keyed by base callsign.
- With `send_rr73` OFF and `auto_cq_resume` OFF, v1.0 sequencing is reproduced exactly, with two spec-sanctioned unconditional corrections: the active-CQ acceptance widening (spec §3) and the resumed-QSO report-field fix (spec §3, Task 3).
- Exact UI copy — Settings rows: "Send RR73 (log on send)" / "OFF sends RRR and waits for 73 (v1.0 behavior)"; "Resume CQ after QSO" / "Keep calling CQ after each logged or abandoned QSO". Snackbars: "QSO logged — resuming CQ", "Resuming CQ", "Re-confirmed <dx> — already logged".
- Report-field semantics everywhere: `reportRcvd` = message payload report; `reportSent` = our measured SNR (`decode.snr`).
- Test commands: `./gradlew :core:testDebugUnitTest`, `./gradlew :app:testDebugUnitTest`. Full sweep adds `:data:testDebugUnitTest :app:assembleDebug`.
- Commit style: conventional commits ending with the trailer line `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- The working tree may contain a pre-existing uncommitted `app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorTest.kt` — never stage, commit, or modify it; always `git add` explicit paths.

---

### Task 1: CallsignMatcher (core)

**Files:**
- Create: `core/src/main/java/net/ft8vc/core/CallsignMatcher.kt`
- Test: `core/src/test/java/net/ft8vc/core/CallsignMatcherTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces (used by Tasks 2 and 6): `CallsignMatcher.base(call: String): String`; `CallsignMatcher.matches(token: String, call: String): Boolean`.

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/java/net/ft8vc/core/CallsignMatcherTest.kt`:

```kotlin
package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsignMatcherTest {

    @Test
    fun baseExtractsCallsignSegment() {
        assertEquals("K1ABC", CallsignMatcher.base("K1ABC"))
        assertEquals("K1ABC", CallsignMatcher.base("K1ABC/P"))
        assertEquals("K1ABC", CallsignMatcher.base("PJ4/K1ABC"))
        assertEquals("K1ABC", CallsignMatcher.base("<PJ4/K1ABC>"))
        assertEquals("K1ABC", CallsignMatcher.base("k1abc/qrp"))
        assertEquals("W0DEV", CallsignMatcher.base("W0DEV-1"))
    }

    @Test
    fun matchesExactIgnoringCaseAndBrackets() {
        assertTrue(CallsignMatcher.matches("k1abc", "K1ABC"))
        assertTrue(CallsignMatcher.matches("<K1ABC>", "K1ABC"))
        assertTrue(CallsignMatcher.matches("<PJ4/K1ABC>", "PJ4/K1ABC"))
    }

    @Test
    fun matchesCompoundFormsViaBase() {
        assertTrue(CallsignMatcher.matches("K1ABC/P", "K1ABC"))
        assertTrue(CallsignMatcher.matches("K1ABC", "K1ABC/QRP"))
        assertTrue(CallsignMatcher.matches("<PJ4/K1ABC>", "K1ABC"))
        assertTrue(CallsignMatcher.matches("PJ4/K1ABC", "K1ABC/P"))
    }

    @Test
    fun rejectsDigitlessBasesAndNonMatches() {
        assertFalse(CallsignMatcher.matches("K2DEF", "K1ABC"))
        // Digitless tokens never base-match (modifiers like DX/POTA/NA).
        assertFalse(CallsignMatcher.matches("POTA/X", "POTA"))
        assertFalse(CallsignMatcher.matches("", "K1ABC"))
        assertFalse(CallsignMatcher.matches("K1ABC", ""))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.CallsignMatcherTest"`
Expected: FAIL — unresolved reference `CallsignMatcher` (compile error).

- [ ] **Step 3: Implement**

Create `core/src/main/java/net/ft8vc/core/CallsignMatcher.kt`:

```kotlin
package net.ft8vc.core

/**
 * Flexible callsign comparison shared by the QSO machine, resume detection,
 * answer selection, and display filtering.
 *
 * Handles compound forms (`K1ABC/P`, `PJ4/K1ABC`) and ft8_lib's hashed forms
 * (`<PJ4/K1ABC>`). Two tokens match when their bracket-stripped forms are
 * equal ignoring case, or when their [base] calls are equal ignoring case AND
 * the base contains a digit — so bare modifiers (DX, POTA, NA) never
 * base-match a callsign.
 */
object CallsignMatcher {

    /**
     * The base callsign: brackets stripped, then the `/`-separated segment
     * that looks most like a callsign (has a digit AND a letter; longest such
     * wins; fallback first segment), minus any `-suffix`, uppercased.
     */
    fun base(call: String): String {
        val segments = stripBrackets(call).split('/').filter { it.isNotEmpty() }
        val candidate = segments
            .filter { seg -> seg.any(Char::isDigit) && seg.any(Char::isLetter) }
            .maxByOrNull { it.length }
            ?: segments.firstOrNull()
            ?: ""
        return candidate.substringBefore('-').uppercase()
    }

    fun matches(token: String, call: String): Boolean {
        val t = stripBrackets(token)
        val c = stripBrackets(call)
        if (t.isBlank() || c.isBlank()) return false
        if (t.equals(c, ignoreCase = true)) return true
        val callBase = base(call)
        return callBase.isNotEmpty() &&
            callBase.any(Char::isDigit) &&
            callBase.equals(base(token), ignoreCase = true)
    }

    private fun stripBrackets(s: String): String =
        s.trim().removePrefix("<").removeSuffix(">")
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.CallsignMatcherTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/ft8vc/core/CallsignMatcher.kt core/src/test/java/net/ft8vc/core/CallsignMatcherTest.kt
git commit -m "feat(core): shared CallsignMatcher for compound and hashed callsigns"
```

---

### Task 2: Wire CallsignMatcher into the four consumers

**Files:**
- Modify: `core/src/main/java/net/ft8vc/core/QsoMachine.kt:331-332` (`fromDx`)
- Modify: `core/src/main/java/net/ft8vc/core/QsoResume.kt:39-54` (`opportunityFromDecode`)
- Modify: `core/src/main/java/net/ft8vc/core/AnswerSelector.kt:107-115` (private `callsignMatches`)
- Modify: `core/src/main/java/net/ft8vc/core/MonitorDecodeFilter.kt:98-105` (private `callsignMatches`)
- Test: `core/src/test/java/net/ft8vc/core/QsoMachineTest.kt` (add one test)

**Interfaces:**
- Consumes: `CallsignMatcher.matches` (Task 1).
- Produces: `QsoMachine.fromDx`, `QsoResume.opportunityFromDecode`/`isDirectedToMe`, `AnswerSelector` target checks, and `MonitorDecodeFilter.messageInvolvesMyCall` all tolerate compound/hashed forms. No signature changes.

- [ ] **Step 1: Write the failing test**

Append to `core/src/test/java/net/ft8vc/core/QsoMachineTest.kt` (inside the class):

```kotlin
    @Test
    fun fromDxMatchesCompoundAndHashedForms() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        // Caller addresses our portable form; we still capture them.
        assertTrue(m.onDecodes(decode("W0DEV/P K1ABC FN42", snr = -8)))
        assertEquals(QsoState.SendingReport, m.state)
        assertEquals("K1ABC", m.dxCall)
        // Their R-report arrives under a hashed compound form of the same call.
        assertTrue(m.onDecodes(decode("W0DEV <PJ4/K1ABC> R-15")))
        assertEquals(QsoState.SendingRoger, m.state)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.QsoMachineTest"`
Expected: FAIL — `fromDxMatchesCompoundAndHashedForms` fails (exact-equality matching rejects `<PJ4/K1ABC>` vs `K1ABC`). The other tests stay green.

- [ ] **Step 3: Wire the consumers**

In `core/src/main/java/net/ft8vc/core/QsoMachine.kt`, replace `fromDx`:

```kotlin
    private fun fromDx(target: String, sender: String): Boolean {
        val dx = dxCall ?: return false
        return CallsignMatcher.matches(target, myCall) && CallsignMatcher.matches(sender, dx)
    }
```

In `core/src/main/java/net/ft8vc/core/QsoResume.kt`, replace every `rx.target == myCall` guard in `opportunityFromDecode` with `CallsignMatcher.matches(rx.target, myCall)` (five occurrences — GridReply, Report, RReport, Roger, RogerBye).

In `core/src/main/java/net/ft8vc/core/AnswerSelector.kt`, replace the private matcher body with a delegate:

```kotlin
    private fun callsignMatches(token: String, myCall: String): Boolean =
        CallsignMatcher.matches(token, myCall)
```

In `core/src/main/java/net/ft8vc/core/MonitorDecodeFilter.kt`, do the same:

```kotlin
    private fun callsignMatches(token: String, myCall: String): Boolean =
        CallsignMatcher.matches(token, myCall)
```

- [ ] **Step 4: Run the full core suite**

Run: `./gradlew :core:testDebugUnitTest`
Expected: PASS. If any existing `MonitorDecodeFilterTest` or `AnswerSelectorTest` assertion codified the old prefix-compound bug (`PJ4/K1ABC` base = `PJ4`), update that expectation to the corrected semantics (`PJ4/K1ABC` base = `K1ABC`) — the spec mandates the new behavior.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/ft8vc/core core/src/test/java/net/ft8vc/core
git commit -m "feat(core): base-call matching in QSO machine, resume, selection, and display filter"
```

---

### Task 3: Correct report-field semantics (payloadReport)

**Files:**
- Modify: `core/src/main/java/net/ft8vc/core/QsoResume.kt:23-28` (`Opportunity`), `:39-54` (`opportunityFromDecode`), `:60-71` (`apply`)
- Modify: `core/src/main/java/net/ft8vc/core/QsoMachine.kt:166-183` (two resume methods)
- Test: `core/src/test/java/net/ft8vc/core/QsoResumeTest.kt` (or wherever `QsoResume` tests live — check with `grep -rl "QsoResume" core/src/test`)

**Interfaces:**
- Consumes: nothing new.
- Produces (used by Task 5):
  - `QsoResume.Opportunity(dxCall, dxGrid, kind, snr, payloadReport: Int? = null)` — `payloadReport` set for `AnswererReport` and `InitiatorRReport` kinds.
  - `QsoMachine.resumeAnswererAfterReport(dxCall: String, reportRcvd: Int, reportSent: Int)`
  - `QsoMachine.resumeInitiatorAfterRReport(dxCall: String, reportRcvd: Int, reportSent: Int)`

- [ ] **Step 1: Write the failing tests**

Add to the QsoResume test file (create `core/src/test/java/net/ft8vc/core/QsoResumeTest.kt` with the standard imports `org.junit.Assert.*` + `org.junit.Test` if none exists):

```kotlin
    @Test
    fun opportunityCarriesPayloadReportDistinctFromMeasuredSnr() {
        // Payload says -10 (their report of us); we measured them at -3.
        val opp = QsoResume.opportunityFromDecode("W0DEV", QsoDecode("W0DEV K1ABC -10", -3))!!
        assertEquals(QsoResume.Kind.AnswererReport, opp.kind)
        assertEquals(-10, opp.payloadReport)
        assertEquals(-3, opp.snr)
    }

    @Test
    fun applySetsDistinctReportFieldsForAnswererReport() {
        val m = QsoMachine("W0DEV", "EM26")
        val opp = QsoResume.opportunityFromDecode("W0DEV", QsoDecode("W0DEV K1ABC -10", -3))!!
        QsoResume.apply(m, opp)
        assertEquals(QsoState.SendingRReport, m.state)
        assertEquals(-10, m.reportRcvd)   // what they sent us
        assertEquals(-3, m.reportSent)    // what we measured and will send
        assertEquals("K1ABC W0DEV R-03", m.txMessage())
    }

    @Test
    fun applySetsDistinctReportFieldsForInitiatorRReport() {
        val m = QsoMachine("W0DEV", "EM26")
        val opp = QsoResume.opportunityFromDecode("W0DEV", QsoDecode("W0DEV K1ABC R-08", -4))!!
        QsoResume.apply(m, opp)
        assertEquals(QsoState.SendingRoger, m.state)
        assertEquals(-8, m.reportRcvd)
        assertEquals(-4, m.reportSent)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.QsoResumeTest"`
Expected: FAIL — no `payloadReport` property (compile error).

- [ ] **Step 3: Implement**

In `core/src/main/java/net/ft8vc/core/QsoResume.kt`:

```kotlin
    data class Opportunity(
        val dxCall: String,
        val dxGrid: String?,
        val kind: Kind,
        /** Our measured SNR of their transmission. */
        val snr: Int,
        /** The report carried in the message payload (Report / RReport kinds). */
        val payloadReport: Int? = null,
    )
```

In `opportunityFromDecode`, populate it for the two report-bearing kinds:

```kotlin
            is QsoRx.Report if CallsignMatcher.matches(rx.target, myCall) ->
                Opportunity(rx.sender, null, Kind.AnswererReport, decode.snr, payloadReport = rx.snr)
            is QsoRx.RReport if CallsignMatcher.matches(rx.target, myCall) ->
                Opportunity(rx.sender, null, Kind.InitiatorRReport, decode.snr, payloadReport = rx.snr)
```

In `apply`:

```kotlin
            Kind.AnswererReport ->
                machine.resumeAnswererAfterReport(opp.dxCall, opp.payloadReport ?: opp.snr, opp.snr)
            Kind.InitiatorRReport ->
                machine.resumeInitiatorAfterRReport(opp.dxCall, opp.payloadReport ?: opp.snr, opp.snr)
```

In `core/src/main/java/net/ft8vc/core/QsoMachine.kt`, replace the two resume methods:

```kotlin
    /** Answerer: we missed our grid TX; initiator sent [reportRcvd] — reply R+[reportSent]. */
    fun resumeAnswererAfterReport(dxCall: String, reportRcvd: Int, reportSent: Int) {
        reset()
        role = QsoRole.Answerer
        this.dxCall = dxCall
        this.reportSent = reportSent
        this.reportRcvd = reportRcvd
        state = QsoState.SendingRReport
    }

    /** Initiator: answerer sent R-[reportRcvd] — next TX is RRR/RR73. */
    fun resumeInitiatorAfterRReport(dxCall: String, reportRcvd: Int, reportSent: Int) {
        reset()
        role = QsoRole.Initiator
        this.dxCall = dxCall
        this.reportSent = reportSent
        this.reportRcvd = reportRcvd
        state = QsoState.SendingRoger
    }
```

- [ ] **Step 4: Fix call sites and run the core suite**

Run: `grep -rn "resumeAnswererAfterReport\|resumeInitiatorAfterRReport" core/src app/src --include="*.kt"` — update any 2-argument call sites (tests) to the 3-argument form with the corrected expectations (`reportRcvd` = payload, `reportSent` = measured). Then:

Run: `./gradlew :core:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src app/src
git commit -m "fix(core): resumed QSOs log the payload report as RST_RCVD, measured SNR as RST_SENT"
```

---

### Task 4: initiatorRr73 flag on QsoMachine

**Files:**
- Modify: `core/src/main/java/net/ft8vc/core/QsoMachine.kt:45-49` (constructor), `:206-219` (`txMessage`), `:221-226` (`markTransmitted`)
- Test: `core/src/test/java/net/ft8vc/core/QsoMachineTest.kt`

**Interfaces:**
- Consumes: `QsoMessages.rr73(dxCall, myCall)` (exists).
- Produces (used by Tasks 5, 7): `QsoMachine(myCall, myGrid, cqModifier = null, initiatorRr73: Boolean = false)`. Flag ON: `SendingRoger` transmits `RR73` and `markTransmitted()` promotes it to `Complete`.

- [ ] **Step 1: Write the failing tests**

Append to `QsoMachineTest.kt`:

```kotlin
    @Test
    fun rr73ModeSendsRr73AndCompletesOnTransmit() {
        val m = QsoMachine("W0DEV", "EM26", initiatorRr73 = true)
        m.startCq()
        m.onDecodes(decode("W0DEV K1ABC FN42", snr = -8))
        m.onDecodes(decode("W0DEV K1ABC R-15"))
        assertEquals(QsoState.SendingRoger, m.state)
        assertEquals("K1ABC W0DEV RR73", m.txMessage())
        m.markTransmitted()
        assertEquals(QsoState.Complete, m.state)
        assertNull(m.txMessage())
        assertFalse(m.isActive)
    }

    @Test
    fun rrrModeUnchangedWhenFlagOff() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        m.onDecodes(decode("W0DEV K1ABC FN42", snr = -8))
        m.onDecodes(decode("W0DEV K1ABC R-15"))
        assertEquals("K1ABC W0DEV RRR", m.txMessage())
        m.markTransmitted()
        assertEquals(QsoState.SendingRoger, m.state) // still waiting for their 73
    }

    @Test
    fun rr73ModeStillAcceptsEarly73BeforeOurTransmit() {
        val m = QsoMachine("W0DEV", "EM26", initiatorRr73 = true)
        m.startCq()
        m.onDecodes(decode("W0DEV K1ABC FN42", snr = -8))
        m.onDecodes(decode("W0DEV K1ABC R-15"))
        assertTrue(m.onDecodes(decode("W0DEV K1ABC 73")))
        assertEquals(QsoState.Complete, m.state)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.QsoMachineTest"`
Expected: FAIL — no `initiatorRr73` parameter (compile error).

- [ ] **Step 3: Implement**

Constructor:

```kotlin
class QsoMachine(
    private val myCall: String,
    private val myGrid: String,
    private val cqModifier: String? = null,
    /** Initiator sends RR73 and completes on transmit (WSJT-X style). OFF = v1.0 RRR. */
    private val initiatorRr73: Boolean = false,
) {
```

`txMessage()` `SendingRoger` branch:

```kotlin
            QsoState.SendingRoger -> dx?.let {
                if (initiatorRr73) QsoMessages.rr73(it, myCall) else QsoMessages.rrr(it, myCall)
            }
```

`markTransmitted()`:

```kotlin
    /** Call after a TX slot completes. Advances terminal states to Complete. */
    fun markTransmitted() {
        if (state == QsoState.SendingSeventyThree ||
            (initiatorRr73 && state == QsoState.SendingRoger)
        ) {
            state = QsoState.Complete
        }
    }
```

- [ ] **Step 4: Run the core suite**

Run: `./gradlew :core:testDebugUnitTest`
Expected: PASS (flag defaults false; existing tests untouched).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/ft8vc/core/QsoMachine.kt core/src/test/java/net/ft8vc/core/QsoMachineTest.kt
git commit -m "feat(core): initiator RR73 with complete-on-transmit behind QsoMachine flag"
```

---

### Task 5: Active CQ accepts report and R-report replies

**Files:**
- Modify: `core/src/main/java/net/ft8vc/core/AnswerSelector.kt:22-37` (`selectOpportunity` gains kind filter)
- Modify: `core/src/main/java/net/ft8vc/core/QsoMachine.kt:301-315` (`handleCqReplies`)
- Test: `core/src/test/java/net/ft8vc/core/QsoMachineTest.kt`

**Interfaces:**
- Consumes: `QsoResume.Opportunity.payloadReport` (Task 3), 3-arg resume methods (Task 3), `initiatorRr73` (Task 4).
- Produces: `AnswerSelector.selectOpportunity(..., allowedKinds: Set<QsoResume.Kind> = QsoResume.Kind.entries.toSet())` — default preserves all existing callers.

- [ ] **Step 1: Write the failing tests**

Append to `QsoMachineTest.kt`:

```kotlin
    @Test
    fun cqAcceptsDirectReportReply() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        // Tx1-skip hunter: payload report -10; we measured them at -5.
        assertTrue(m.onDecodes(decode("W0DEV K3XYZ -10", snr = -5)))
        assertEquals(QsoState.SendingRReport, m.state)
        assertEquals("K3XYZ", m.dxCall)
        assertEquals(-10, m.reportRcvd)
        assertEquals(-5, m.reportSent)
        assertEquals("K3XYZ W0DEV R-05", m.txMessage())
        // They roger; we send 73; complete on transmit.
        assertTrue(m.onDecodes(decode("W0DEV K3XYZ RR73")))
        assertEquals(QsoState.SendingSeventyThree, m.state)
        m.markTransmitted()
        assertEquals(QsoState.Complete, m.state)
    }

    @Test
    fun cqAcceptsRReportTailRepair() {
        val m = QsoMachine("W0DEV", "EM26", initiatorRr73 = true)
        m.startCq()
        // Partner lost our RR73 and retried their R-report while we resumed CQ.
        assertTrue(m.onDecodes(decode("W0DEV K1ABC R-08", snr = -6)))
        assertEquals(QsoState.SendingRoger, m.state)
        assertEquals("K1ABC W0DEV RR73", m.txMessage())
        m.markTransmitted()
        assertEquals(QsoState.Complete, m.state)
    }

    @Test
    fun cqIgnoresStrayRogerBye() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        assertFalse(m.onDecodes(decode("W0DEV K1ABC RR73")))
        assertEquals(QsoState.CallingCq, m.state)
    }

    @Test
    fun cqPrefersActionableReplyOverStrayRoger() {
        val m = QsoMachine("W0DEV", "EM26")
        m.startCq()
        // FIRST policy: the stray RR73 comes first in decode order but is not
        // an accepted kind, so the grid reply must win.
        assertTrue(
            m.onDecodes(
                listOf(
                    QsoDecode("W0DEV K9AAA RR73", -3),
                    QsoDecode("W0DEV K1ABC FN42", -8),
                ),
            ),
        )
        assertEquals(QsoState.SendingReport, m.state)
        assertEquals("K1ABC", m.dxCall)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.QsoMachineTest"`
Expected: FAIL — `cqAcceptsDirectReportReply`, `cqAcceptsRReportTailRepair`, and `cqPrefersActionableReplyOverStrayRoger` fail (CQ only accepts grid replies today). `cqIgnoresStrayRogerBye` passes already.

- [ ] **Step 3: Add the kind filter to selectOpportunity**

In `core/src/main/java/net/ft8vc/core/AnswerSelector.kt`:

```kotlin
    fun selectOpportunity(
        myCall: String,
        myGrid: String,
        decodes: List<QsoDecode>,
        policy: AnswerPolicy,
        excludedDx: Set<String> = emptySet(),
        allowedKinds: Set<QsoResume.Kind> = QsoResume.Kind.entries.toSet(),
    ): QsoResume.Opportunity? {
        val candidates = decodes.mapNotNull { d ->
            QsoResume.opportunityFromDecode(myCall, d)
                ?.takeIf { it.kind in allowedKinds }
                ?.let { opp ->
                    Candidate(d, opp.dxCall, opp.dxGrid ?: gridFromOpportunity(opp, d))
                }
        }.filterNot { isExcluded(it.dxCall, excludedDx) }
        if (candidates.isEmpty()) return null
        val picked = pick(candidates, myGrid, policy) { it.grid }
        return QsoResume.opportunityFromDecode(myCall, picked.decode)
    }
```

- [ ] **Step 4: Rewrite handleCqReplies**

In `core/src/main/java/net/ft8vc/core/QsoMachine.kt`:

```kotlin
    private fun handleCqReplies(
        decodes: List<QsoDecode>,
        answerPolicy: AnswerPolicy,
        excludedDx: Set<String>,
    ): Boolean {
        val opp = AnswerSelector.selectOpportunity(
            myCall, myGrid, decodes, answerPolicy, excludedDx,
            allowedKinds = CQ_REPLY_KINDS,
        ) ?: return false
        when (opp.kind) {
            QsoResume.Kind.InitiatorGridReply ->
                resumeInitiatorAfterGridReply(opp.dxCall, opp.dxGrid ?: "", opp.snr)
            QsoResume.Kind.AnswererReport ->
                resumeAnswererAfterReport(opp.dxCall, opp.payloadReport ?: opp.snr, opp.snr)
            QsoResume.Kind.InitiatorRReport ->
                resumeInitiatorAfterRReport(opp.dxCall, opp.payloadReport ?: opp.snr, opp.snr)
            // Filtered out by CQ_REPLY_KINDS; kept exhaustive for the compiler.
            QsoResume.Kind.AnswererRoger -> return false
        }
        return true
    }
```

Add to the `QsoMachine` file (bottom, next to the class or as a private val in a companion — follow the file's existing constant style; a private top-level val is fine):

```kotlin
/** Reply kinds an active CQ responds to. A stray RRR/RR73 has no exchange to repair. */
private val CQ_REPLY_KINDS = setOf(
    QsoResume.Kind.InitiatorGridReply,
    QsoResume.Kind.AnswererReport,
    QsoResume.Kind.InitiatorRReport,
)
```

- [ ] **Step 5: Run core + app suites**

Run: `./gradlew :core:testDebugUnitTest :app:testDebugUnitTest`
Expected: PASS. Existing pileup/policy tests keep passing (grid-reply-only slots select identically through `selectOpportunity`).

- [ ] **Step 6: Commit**

```bash
git add core/src
git commit -m "feat(core): active CQ accepts direct-report and R-report replies"
```

---

### Task 6: DupeLogGuard + controller wiring

**Files:**
- Create: `core/src/main/java/net/ft8vc/core/DupeLogGuard.kt`
- Test: `core/src/test/java/net/ft8vc/core/DupeLogGuardTest.kt`
- Modify: `app/src/main/java/net/ft8vc/app/controllers/QsoSessionController.kt:127` (`lastLoggedKey` field) and `:419-426` (`handleQsoComplete`)
- Test: `app/src/test/java/net/ft8vc/app/controllers/QsoSessionControllerTest.kt`

**Interfaces:**
- Consumes: `CallsignMatcher.base` (Task 1).
- Produces: `DupeLogGuard(windowMs: Long = 600_000L)` with `shouldLog(dxCall: String, nowMs: Long): Boolean` (records when true) and `clear()`.

- [ ] **Step 1: Write the failing core tests**

Create `core/src/test/java/net/ft8vc/core/DupeLogGuardTest.kt`:

```kotlin
package net.ft8vc.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DupeLogGuardTest {

    @Test
    fun suppressesRepeatWithinWindowAllowsAfter() {
        val g = DupeLogGuard(windowMs = 600_000L)
        assertTrue(g.shouldLog("K1ABC", 0L))
        assertFalse(g.shouldLog("K1ABC", 599_999L))
        assertTrue(g.shouldLog("K1ABC", 600_000L + 599_999L)) // window measured from last LOG
    }

    @Test
    fun differentCallsAreIndependent()  {
        val g = DupeLogGuard(windowMs = 600_000L)
        assertTrue(g.shouldLog("K1ABC", 0L))
        assertTrue(g.shouldLog("K2DEF", 1L))
    }

    @Test
    fun compoundFormsShareOneWindow() {
        val g = DupeLogGuard(windowMs = 600_000L)
        assertTrue(g.shouldLog("K1ABC", 0L))
        assertFalse(g.shouldLog("K1ABC/P", 1_000L))
        assertFalse(g.shouldLog("<PJ4/K1ABC>", 2_000L))
    }
}
```

- [ ] **Step 2: Run to verify failure, then implement**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.DupeLogGuardTest"` — Expected: FAIL (unresolved reference).

Create `core/src/main/java/net/ft8vc/core/DupeLogGuard.kt`:

```kotlin
package net.ft8vc.core

/**
 * Suppresses duplicate log rows when the same station completes again within
 * [windowMs] — the lost-RR73 retry case: we re-confirm on air but must not
 * write a second log entry. Keyed by [CallsignMatcher.base] so compound and
 * hashed retry forms share one window.
 */
class DupeLogGuard(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private val lastLoggedAt = HashMap<String, Long>()

    /** True when a completion for [dxCall] at [nowMs] should be logged; records it if so. */
    fun shouldLog(dxCall: String, nowMs: Long): Boolean {
        val key = CallsignMatcher.base(dxCall)
        val last = lastLoggedAt[key]
        if (last != null && nowMs - last < windowMs) return false
        lastLoggedAt[key] = nowMs
        return true
    }

    fun clear() = lastLoggedAt.clear()

    companion object {
        /** 10 minutes — long enough for any retry chain, short enough for real re-works. */
        const val DEFAULT_WINDOW_MS = 600_000L
    }
}
```

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.DupeLogGuardTest"` — Expected: PASS.

- [ ] **Step 3: Wire into the controller**

In `app/src/main/java/net/ft8vc/app/controllers/QsoSessionController.kt`: delete the `private var lastLoggedKey: String? = null` field (line 127); add `import net.ft8vc.core.DupeLogGuard` and a field next to `abandonedPartners`:

```kotlin
    private val dupeLogGuard = DupeLogGuard()
```

Replace `handleQsoComplete`:

```kotlin
    private suspend fun handleQsoComplete() {
        val snapshot = qso?.snapshot(clock()) ?: return
        if (!dupeLogGuard.shouldLog(snapshot.dxCall, clock())) {
            notifyFn("Re-confirmed ${snapshot.dxCall} — already logged", SnackbarEvent.Tag.TRANSIENT)
            return
        }
        onQsoComplete(snapshot)
        notifyFn("QSO complete with ${snapshot.dxCall} — logged", SnackbarEvent.Tag.QSO_COMPLETE)
    }
```

- [ ] **Step 4: Write the failing controller test**

Append to `QsoSessionControllerTest.kt`, plus a helper next to `decodeRowCq` (mirror its construction — only the listed named args, others default):

```kotlin
    private fun decodeRowDirected(message: String): net.ft8vc.app.DecodeRow =
        net.ft8vc.app.DecodeRow(
            id = clockMs.get(),
            timeUtc = "000000",
            snr = -10,
            dtSeconds = 0f,
            freqHz = 1000,
            message = message,
            isCq = false,
        )

    @Test
    fun duplicateCompletionWithinWindow_logsOnce() = runTest {
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC R-15", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC 73", -8)), TxSlotParity.ODD)
        assertEquals(1, completedSnapshots.size)

        // Lost-final-message retry: resume and complete again inside the window.
        controller.resumeFromDecode(decodeRowDirected("W0DEV K1ABC R-15"))
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC 73", -8)), TxSlotParity.ODD)
        assertEquals(1, completedSnapshots.size)
        assertTrue(notifications.any { it.first.contains("already logged") })

        // Outside the window it logs again.
        clockMs.addAndGet(11 * 60_000L)
        controller.resumeFromDecode(decodeRowDirected("W0DEV K1ABC R-15"))
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC 73", -8)), TxSlotParity.ODD)
        assertEquals(2, completedSnapshots.size)
    }
```

If `DecodeRow`'s constructor requires other parameters without defaults, mirror exactly what `decodeRowCq` passes and add `isCq = false`.

- [ ] **Step 5: Run app suite**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.QsoSessionControllerTest"`
Expected: PASS (the new test drives completion entirely through the decode path — no TX timing involved).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/net/ft8vc/core/DupeLogGuard.kt core/src/test/java/net/ft8vc/core/DupeLogGuardTest.kt app/src/main/java/net/ft8vc/app/controllers/QsoSessionController.kt app/src/test/java/net/ft8vc/app/controllers/QsoSessionControllerTest.kt
git commit -m "feat(app): 10-minute duplicate-log guard on QSO completion"
```

---

### Task 7: send_rr73 settings plumbing end to end

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/StationSettings.kt` (field), `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt` (read + setter + key), `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt` (slice field + toSlice), `app/src/main/java/net/ft8vc/app/OperateUiState.kt` (field), `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (combine mapping ~line 213, init collect ~line 297, setter ~line 420), `app/src/main/java/net/ft8vc/app/controllers/QsoSessionController.kt` (`@Volatile` + setter + `newQsoMachine`), `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt` (AutoToggleRow ~line 257)
- Test: `app/src/test/java/net/ft8vc/app/controllers/QsoSessionControllerTest.kt`

**Interfaces:**
- Consumes: `QsoMachine(..., initiatorRr73)` (Task 4).
- Produces: `QsoSessionController.setSendRr73(enabled: Boolean)`; `StationSettings.sendRr73: Boolean = true`; `OperateUiState.sendRr73: Boolean = true`; `OperateViewModel.setSendRr73(enabled: Boolean)`.

Mirror the `earlyDecodeEnabled` wiring at every site. Exact edits:

- [ ] **Step 1: Write the failing controller tests**

Append to `QsoSessionControllerTest.kt`:

```kotlin
    @Test
    fun sendRr73On_nextTxMessageIsRr73() = runTest {
        controller.setSendRr73(true)
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC R-15", -8)), TxSlotParity.ODD)
        assertEquals("K1ABC W0DEV RR73", controller.slice.value.nextTxMessage)
    }

    @Test
    fun sendRr73Off_nextTxMessageIsRrr() = runTest {
        controller.setSendRr73(false)
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC R-15", -8)), TxSlotParity.ODD)
        assertEquals("K1ABC W0DEV RRR", controller.slice.value.nextTxMessage)
    }
```

Also add `controller.setSendRr73(true)` to `rebuildController()` next to the other setters (mirrors the settings default). Then run `grep -n "RRR" app/src/test/java/net/ft8vc/app/controllers/*.kt` — any existing test asserting an `RRR` next-message must either call `controller.setSendRr73(false)` first or update its expectation to `RR73`; choose whichever preserves the test's original intent (a v1.0-parity test sets the flag false).

- [ ] **Step 2: Run to verify compile failure**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.QsoSessionControllerTest"`
Expected: FAIL — `setSendRr73` unresolved.

- [ ] **Step 3: Controller wiring**

In `QsoSessionController.kt`, next to the other `@Volatile` settings fields:

```kotlin
    @Volatile private var sendRr73: Boolean = true
```

Next to the other setters:

```kotlin
    fun setSendRr73(enabled: Boolean) { sendRr73 = enabled }
```

Update `newQsoMachine()`:

```kotlin
    private fun newQsoMachine(): QsoMachine =
        QsoMachine(myCall, myGrid, effectiveCqModifier(), initiatorRr73 = sendRr73)
```

Run the two new tests — Expected: PASS.

- [ ] **Step 4: Settings chain**

`StationSettings.kt` — add after `earlyDecodeEnabled`:

```kotlin
    val sendRr73: Boolean = true,
```

`SettingsRepository.kt` — in the `settings` map block:

```kotlin
            sendRr73 = prefs[Keys.SEND_RR73] ?: true,
```

setter next to `setEarlyDecodeEnabled`:

```kotlin
    suspend fun setSendRr73(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.SEND_RR73] = enabled }
    }
```

key in `Keys`:

```kotlin
        val SEND_RR73 = booleanPreferencesKey("send_rr73")
```

`SettingsBridge.kt` — `SettingsSlice` field `val sendRr73: Boolean = true,` and `toSlice()` line `sendRr73 = sendRr73,`.

`OperateUiState.kt` — after `earlyDecodeEnabled`:

```kotlin
    val sendRr73: Boolean = true,
```

`OperateViewModel.kt` — in the `combine` mapping after `earlyDecodeEnabled = settings.earlyDecodeEnabled,`:

```kotlin
                sendRr73 = settings.sendRr73,
```

in the init settings collect after `qsoSession.setMaxUnansweredTxCycles(...)`:

```kotlin
                qsoSession.setSendRr73(s.sendRr73)
```

setter next to `setEarlyDecodeEnabled`:

```kotlin
    fun setSendRr73(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setSendRr73(enabled) }
    }
```

`SettingsScreen.kt` — after the "Auto answer CQ" `AutoToggleRow`:

```kotlin
                AutoToggleRow(
                    title = "Send RR73 (log on send)",
                    subtitle = "OFF sends RRR and waits for 73 (v1.0 behavior)",
                    checked = state.sendRr73,
                    onCheckedChange = vm::setSendRr73,
                    enabled = state.txEnabled,
                )
```

- [ ] **Step 5: Build and full app suite**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS / BUILD SUCCESSFUL. If `SettingsBridgeTest` asserts an exhaustive slice mapping, add `sendRr73` to it mirroring how `earlyDecodeEnabled` is asserted.

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat(app): Send RR73 (log on send) setting, default ON"
```

---

### Task 8: Auto-resume CQ

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/controllers/QsoSessionController.kt` (`@Volatile` + setter + pending flag + `maybeAutoResumeCq` + 3 call sites + `stopQsoInternal` clear)
- Modify: the same settings-chain files as Task 7 (`StationSettings`, `SettingsRepository`, `SettingsBridge`, `OperateUiState`, `OperateViewModel`, `SettingsScreen`)
- Test: `app/src/test/java/net/ft8vc/app/controllers/QsoSessionControllerTest.kt`

**Interfaces:**
- Consumes: `handleQsoComplete` (Task 6 form), `ActivationProfile.isValidParkRefList`, `StationProfileValidator`.
- Produces: `QsoSessionController.setAutoCqResumeEnabled(enabled: Boolean)`; `StationSettings.autoCqResumeEnabled: Boolean = false`; `OperateUiState.autoCqResumeEnabled: Boolean = false`; `OperateViewModel.setAutoCqResumeEnabled(enabled: Boolean)`; DataStore key `auto_cq_resume`.

- [ ] **Step 1: Write the failing controller tests**

Append to `QsoSessionControllerTest.kt` (also add `controller.setAutoCqResumeEnabled(false)` to `rebuildController()`):

```kotlin
    @Test
    fun autoResumeCq_restartsAfterCompletion() = runTest {
        controller.setAutoCqResumeEnabled(true)
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC R-15", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC 73", -8)), TxSlotParity.ODD)
        assertEquals(1, completedSnapshots.size)
        assertTrue(controller.slice.value.qsoActive)
        assertEquals("Calling CQ…", controller.slice.value.qsoState)
        assertTrue(notifications.any { it.first.contains("resuming CQ") })
    }

    @Test
    fun autoResumeCq_disabled_staysStopped() = runTest {
        controller.setAutoCqResumeEnabled(false)
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC R-15", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC 73", -8)), TxSlotParity.ODD)
        assertEquals(1, completedSnapshots.size)
        assertFalse(controller.slice.value.qsoActive)
    }

    @Test
    fun autoResumeCq_notAfterManualAbandon() = runTest {
        controller.setAutoCqResumeEnabled(true)
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -8)), TxSlotParity.ODD)
        controller.abandonQso()
        assertFalse(controller.slice.value.qsoActive)
        assertNull(controller.slice.value.qsoState)
    }

    @Test
    fun autoResumeCq_notWhenTxDisabledAtFireTime() = runTest {
        controller.setAutoCqResumeEnabled(true)
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -8)), TxSlotParity.ODD)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC R-15", -8)), TxSlotParity.ODD)
        controller.setTxEnabled(false)
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC 73", -8)), TxSlotParity.ODD)
        assertEquals(1, completedSnapshots.size)
        assertFalse(controller.slice.value.qsoActive)
    }
```

If the eager `UnconfinedTestDispatcher` needs a nudge for the relaunch coroutine, add `testScheduler.advanceUntilIdle()` before the post-completion assertions — but try without it first; the controller scope's dispatcher executes launches inline.

- [ ] **Step 2: Run to verify compile failure**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.QsoSessionControllerTest"`
Expected: FAIL — `setAutoCqResumeEnabled` unresolved.

- [ ] **Step 3: Controller implementation**

In `QsoSessionController.kt` add imports `kotlinx.coroutines.cancelAndJoin`, `net.ft8vc.core.StationProfileValidator` (already imported) — verify. Add fields next to the other `@Volatile` settings:

```kotlin
    @Volatile private var autoCqResumeEnabled: Boolean = false
```

```kotlin
    fun setAutoCqResumeEnabled(enabled: Boolean) { autoCqResumeEnabled = enabled }
```

Add next to `qsoLoopJob`:

```kotlin
    /** Set when a completed/abandoned QSO should auto-restart CQ; cleared by manual stop. */
    private var pendingAutoCqResume: Boolean = false
```

Add the relaunch entry point (near `handleQsoComplete`):

```kotlin
    /**
     * Queue an automatic CQ restart after a completed or partner-abandoned QSO.
     * The pending flag is re-checked on the dispatcher right before the restart,
     * so a manual Stop/Abandon queued in between always wins. Gates mirror
     * [startCq] but fail silently — this is a background action.
     */
    private fun maybeAutoResumeCq(snackbar: String) {
        if (!autoCqResumeEnabled) return
        pendingAutoCqResume = true
        val finishedJob = qsoLoopJob
        scope.launch(qsoDispatcher) {
            finishedJob?.cancelAndJoin()
            if (!pendingAutoCqResume) return@launch
            pendingAutoCqResume = false
            if (!isOperating || !txEnabled) return@launch
            if (!StationProfileValidator.isValidCall(myCall) ||
                !StationProfileValidator.isValidGrid(myGrid)
            ) return@launch
            if (potaModeEnabled && !ActivationProfile.isValidParkRefList(potaParkRef)) return@launch
            notifyFn(snackbar, SnackbarEvent.Tag.TRANSIENT)
            val machine = newQsoMachine()
            machine.startCq()
            startQsoLoop(machine, hearingSlotParity = null)
        }
    }
```

Call sites — in `afterTransmit`, change the completion branch:

```kotlin
        if (qso?.state == QsoState.Complete) {
            handleQsoComplete()
            maybeAutoResumeCq("QSO logged — resuming CQ")
            return true
        }
```

In `onDecodeBatch`, change the completion line:

```kotlin
                if (qso?.state == QsoState.Complete) {
                    handleQsoComplete()
                    maybeAutoResumeCq("QSO logged — resuming CQ")
                }
```

In `abandonForNoReply`, after the existing `notifyFn(...)` call:

```kotlin
        if (dx != null) maybeAutoResumeCq("Resuming CQ")
```

In `stopQsoInternal`, first line:

```kotlin
        pendingAutoCqResume = false
```

(This clears a queued resume on manual Stop/Abandon; the relaunch coroutine consumes the flag before calling `startQsoLoop`, whose internal `stopQsoInternal` therefore cannot cancel its own restart.)

- [ ] **Step 4: Run controller tests**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.QsoSessionControllerTest"`
Expected: PASS.

- [ ] **Step 5: Settings chain + UI**

Mirror Task 7's Step 4 exactly with these values — `StationSettings.kt`: `val autoCqResumeEnabled: Boolean = false,`; `SettingsRepository.kt`: read `autoCqResumeEnabled = prefs[Keys.AUTO_CQ_RESUME] ?: false,`, setter `setAutoCqResumeEnabled`, key `val AUTO_CQ_RESUME = booleanPreferencesKey("auto_cq_resume")`; `SettingsSlice` field + `toSlice` line; `OperateUiState.kt`: `val autoCqResumeEnabled: Boolean = false,`; `OperateViewModel.kt`: combine line `autoCqResumeEnabled = settings.autoCqResumeEnabled,`, collect line `qsoSession.setAutoCqResumeEnabled(s.autoCqResumeEnabled)`, setter:

```kotlin
    fun setAutoCqResumeEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoCqResumeEnabled(enabled) }
    }
```

`SettingsScreen.kt` — after the Task 7 RR73 row:

```kotlin
                AutoToggleRow(
                    title = "Resume CQ after QSO",
                    subtitle = "Keep calling CQ after each logged or abandoned QSO",
                    checked = state.autoCqResumeEnabled,
                    onCheckedChange = vm::setAutoCqResumeEnabled,
                    enabled = state.txEnabled,
                )
```

- [ ] **Step 6: Build and full app suite**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src
git commit -m "feat(app): auto-resume CQ after logged or abandoned QSOs, default OFF"
```

---

### Task 9: Full verification sweep

**Files:** none — verification only.

- [ ] **Step 1: All unit suites + build**

Run: `./gradlew :core:testDebugUnitTest :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS / BUILD SUCCESSFUL, zero failures.

- [ ] **Step 2: Constraint check**

Run: `git diff --stat BASE..HEAD` where BASE is the commit recorded before Task 1 was dispatched (in `.superpowers/sdd/progress.md`), and confirm zero files under `audio/`, `rig/`, `ft8-native/`.

- [ ] **Step 3: Spec conformance read-through**

Re-read `docs/superpowers/specs/2026-07-01-qso-sequencing-upgrades-design.md` section by section; confirm each requirement maps to landed code (RR73 flag + toggle, dupe guard, three-kind CQ acceptance + report-field semantics, auto-resume trigger/non-trigger matrix, CallsignMatcher consumers).

- [ ] **Step 4: Field verification (promotion gates, operator action)**

On the FT-891 + Digirig: (i) activation run with auto-resume ON across several QSOs; (ii) a Tx1-skip hunter worked end to end; (iii) a lost-RR73 retry re-confirming without a duplicate log row; (iv) one QSO with RR73 OFF confirming v1.0 sequencing.
