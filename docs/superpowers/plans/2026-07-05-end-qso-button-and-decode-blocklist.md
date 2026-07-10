# End-QSO button + long-press decode blocklist — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the confusing Stop-QSO/Abandon button pair with a single "End QSO" button, and move station blocking to a long-press gesture on decode rows with a session-scoped blocklist managed from Settings.

**Architecture:** `AbandonedPartners` splits its single blocked set into two — `userBlocked` (manual long-press; hides rows + shown in manager) and `autoSuppressed` (no-reply timeout; excludes from auto-answer only, invisible). Auto-answer/CQ exclusion uses the union; row-hiding and the manager read only `userBlocked`. A shared `AbandonedPartners` instance is created in `OperateViewModel` and injected into `QsoSessionController`; row filtering happens in the `DecodeListPanel` composable where view-mode filtering already lives.

**Tech Stack:** Kotlin, Jetpack Compose, Coroutines, JUnit4. Modules: `core` (pure logic), `app` (UI + ViewModel + controllers).

## Global Constraints

- Kotlin official style, 4-space indent, no wildcard imports, one top-level public type per file.
- No new top-level dependencies.
- Behavior parity: RX/TX/CAT/QSO behavior on the reference FT-891 + Digirig must stay byte-equivalent. The only intended behavior deltas are UX-facing (button consolidation, long-press block) plus the internal blocklist split.
- Blocklist stays **session-scoped** (in-memory, cleared on app restart) — do not persist it.
- TX stays gated behind license acknowledgment; do not touch that gate.
- `core` module must not gain Android dependencies.

---

### Task 1: `CallBaseName` — shared base-call normalization

Extract the base-call normalization (strip `/portable` and `-suffix`, uppercase) into a pure core helper so both `AbandonedPartners` and the decode-row block filter use identical logic.

**Files:**
- Create: `core/src/main/java/net/ft8vc/core/CallBaseName.kt`
- Create: `core/src/test/java/net/ft8vc/core/CallBaseNameTest.kt`
- Modify: `core/src/main/java/net/ft8vc/core/AbandonedPartners.kt` (delegate its private `baseCall` to the new helper)

**Interfaces:**
- Produces: `object CallBaseName { fun of(callsign: String): String? }` — returns the uppercased base call (everything before the first `/` or `-`), or `null` if the input is blank.

- [ ] **Step 1: Write the failing test**

```kotlin
package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallBaseNameTest {

    @Test fun stripsPortableSuffix() {
        assertEquals("K1ABC", CallBaseName.of("K1ABC/P"))
    }

    @Test fun stripsDashSuffix() {
        assertEquals("K1ABC", CallBaseName.of("K1ABC-9"))
    }

    @Test fun uppercasesAndTrims() {
        assertEquals("N0XYZ", CallBaseName.of("  n0xyz  "))
    }

    @Test fun blankReturnsNull() {
        assertNull(CallBaseName.of("   "))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.CallBaseNameTest"`
Expected: FAIL — `CallBaseName` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

Create `core/src/main/java/net/ft8vc/core/CallBaseName.kt`:

```kotlin
package net.ft8vc.core

/** Normalizes a callsign to its base form: strips `/portable` and `-suffix`, uppercases. */
object CallBaseName {
    fun of(callsign: String): String? {
        val trimmed = callsign.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.substringBefore('/').substringBefore('-').uppercase()
    }
}
```

- [ ] **Step 4: Refactor `AbandonedPartners.baseCall` to delegate**

In `core/src/main/java/net/ft8vc/core/AbandonedPartners.kt`, replace the private `baseCall` body so it reuses the helper (keeps behavior identical):

```kotlin
    private fun baseCall(callsign: String): String? = CallBaseName.of(callsign)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.CallBaseNameTest" --tests "net.ft8vc.core.AbandonedPartnersTest"`
Expected: PASS (existing `AbandonedPartnersTest` still green — behavior unchanged).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/net/ft8vc/core/CallBaseName.kt core/src/test/java/net/ft8vc/core/CallBaseNameTest.kt core/src/main/java/net/ft8vc/core/AbandonedPartners.kt
git commit -m "refactor(core): extract CallBaseName base-call normalization"
```

---

### Task 2: `QsoMessages.senderCall` — public sender extraction

Expose sender-callsign extraction as a public core helper so the decode-row filter and long-press handler can identify a row's sender without re-implementing the parse. `DecodeController` delegates its private copy to it (DRY).

**Files:**
- Modify: `core/src/main/java/net/ft8vc/core/QsoMessages.kt` (add `senderCall`)
- Create: `core/src/test/java/net/ft8vc/core/QsoMessagesSenderCallTest.kt`
- Modify: `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt:404` (delegate `senderCallFromMessage`)

**Interfaces:**
- Produces: `QsoMessages.senderCall(message: String): String?` — the transmitting station's callsign for CQ/GridReply/Report/RReport/Roger/RogerBye/Bye messages; `null` for `Other`.

- [ ] **Step 1: Write the failing test**

```kotlin
package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QsoMessagesSenderCallTest {

    @Test fun cqSenderIsTheCaller() {
        assertEquals("K1ABC", QsoMessages.senderCall("CQ K1ABC FN42"))
    }

    @Test fun directedGridReplySenderIsSecondToken() {
        assertEquals("K1ABC", QsoMessages.senderCall("W0DEV K1ABC FN42"))
    }

    @Test fun reportSenderIsSecondToken() {
        assertEquals("K1ABC", QsoMessages.senderCall("W0DEV K1ABC -15"))
    }

    @Test fun unparseableReturnsNull() {
        assertNull(QsoMessages.senderCall("TNX 73 GL"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.QsoMessagesSenderCallTest"`
Expected: FAIL — `senderCall` unresolved reference.

- [ ] **Step 3: Add the helper to `QsoMessages`**

In `core/src/main/java/net/ft8vc/core/QsoMessages.kt`, inside `object QsoMessages`, add:

```kotlin
    /** The transmitting station's callsign for a parsed message; null when the message has no sender. */
    fun senderCall(message: String): String? =
        when (val rx = parse(message)) {
            is QsoRx.Cq -> rx.call
            is QsoRx.GridReply -> rx.sender
            is QsoRx.Report -> rx.sender
            is QsoRx.RReport -> rx.sender
            is QsoRx.Roger -> rx.sender
            is QsoRx.RogerBye -> rx.sender
            is QsoRx.Bye -> rx.sender
            QsoRx.Other -> null
        }
```

- [ ] **Step 4: Delegate `DecodeController.senderCallFromMessage`**

In `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt`, replace the private helper body (currently the `when (val rx = QsoMessages.parse(message))` block at ~line 404) with:

```kotlin
    /** Extract the sender callsign from a parsed FT8 message; null when message has no sender. */
    private fun senderCallFromMessage(message: String): String? = QsoMessages.senderCall(message)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.QsoMessagesSenderCallTest" && ./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.DecodeController*"`
Expected: PASS — new test green, existing DecodeController tests still green (behavior unchanged).

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/net/ft8vc/core/QsoMessages.kt core/src/test/java/net/ft8vc/core/QsoMessagesSenderCallTest.kt app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt
git commit -m "refactor(core): public QsoMessages.senderCall; DecodeController delegates"
```

---

### Task 3: `AbandonedPartners` two-set split + `QsoSessionController` wiring

Split the blocklist into `userBlocked` and `autoSuppressed`, wire the no-reply timeout to `autoSuppressed`, add manual block/unblock entry points, publish the user blocklist in the slice, and remove the manual `abandonQso()` path.

**Files:**
- Modify: `core/src/main/java/net/ft8vc/core/AbandonedPartners.kt`
- Modify: `core/src/test/java/net/ft8vc/core/AbandonedPartnersTest.kt`
- Modify: `app/src/main/java/net/ft8vc/app/controllers/QsoSessionController.kt`
- Modify: `app/src/test/java/net/ft8vc/app/controllers/QsoSessionControllerTest.kt`

**Interfaces:**
- Consumes: `CallBaseName.of` (Task 1).
- Produces (`AbandonedPartners`):
  - `fun blockUser(callsign: String)` — add to `userBlocked`.
  - `fun suppressAuto(callsign: String)` — add to `autoSuppressed`.
  - `fun isUserBlocked(callsign: String): Boolean` — membership in `userBlocked`.
  - `fun allowResume(callsign: String)` — remove from **both** sets.
  - `fun snapshot(): Set<String>` — **union** of both sets (auto-answer/CQ exclusion).
  - `fun userBlockedSnapshot(): Set<String>` — `userBlocked` only.
  - `fun clear()` — clear **both** sets.
- Produces (`QsoSessionController`):
  - constructor param `abandonedPartners: AbandonedPartners = AbandonedPartners()`.
  - `fun blockStation(callsign: String)` — user-block + publish + "Blocked X" snackbar.
  - `fun unblockStation(callsign: String)` — allowResume + publish.
  - `QsoSlice.userBlockedCalls: List<String>` (sorted) — for the manager.
  - `abandonQso()` **removed**.

- [ ] **Step 1: Rewrite `AbandonedPartnersTest` for the two-set API**

Replace the contents of `core/src/test/java/net/ft8vc/core/AbandonedPartnersTest.kt` with:

```kotlin
package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbandonedPartnersTest {

    @Test fun userBlockMarksUserBlockedAndBaseCall() {
        val p = AbandonedPartners()
        p.blockUser("K1ABC/P")
        assertTrue(p.isUserBlocked("K1ABC"))
        assertTrue(p.isUserBlocked("K1ABC/P"))
        assertFalse(p.isUserBlocked("N0XYZ"))
        assertEquals(setOf("K1ABC"), p.userBlockedSnapshot())
    }

    @Test fun autoSuppressDoesNotUserBlock() {
        val p = AbandonedPartners()
        p.suppressAuto("K1ABC")
        assertFalse(p.isUserBlocked("K1ABC"))
        assertTrue(p.userBlockedSnapshot().isEmpty())
    }

    @Test fun snapshotIsUnionOfBothSets() {
        val p = AbandonedPartners()
        p.blockUser("K1ABC")
        p.suppressAuto("N0XYZ")
        assertEquals(setOf("K1ABC", "N0XYZ"), p.snapshot())
    }

    @Test fun allowResumeClearsBothSets() {
        val p = AbandonedPartners()
        p.blockUser("K1ABC")
        p.suppressAuto("K1ABC")
        p.allowResume("K1ABC")
        assertFalse(p.isUserBlocked("K1ABC"))
        assertTrue(p.snapshot().isEmpty())
    }

    @Test fun clearRemovesAllFromBothSets() {
        val p = AbandonedPartners()
        p.blockUser("K1ABC")
        p.suppressAuto("N0XYZ")
        p.clear()
        assertTrue(p.snapshot().isEmpty())
        assertTrue(p.userBlockedSnapshot().isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.AbandonedPartnersTest"`
Expected: FAIL — `blockUser`, `suppressAuto`, `isUserBlocked`, `userBlockedSnapshot` unresolved.

- [ ] **Step 3: Rewrite `AbandonedPartners` with two sets**

Replace `core/src/main/java/net/ft8vc/core/AbandonedPartners.kt` with:

```kotlin
package net.ft8vc.core

/**
 * In-session station suppression, split by intent:
 *  - [userBlocked]: stations the operator explicitly blocked (long-press). Hidden
 *    from the decode list and shown in the Settings blocklist manager.
 *  - [autoSuppressed]: stations dropped by the no-reply timeout. Excluded from
 *    auto-answer/CQ selection only; never hidden, never shown in the manager.
 * Auto-answer/CQ exclusion uses the union ([snapshot]).
 */
class AbandonedPartners {

    private val userBlocked = LinkedHashSet<String>()
    private val autoSuppressed = LinkedHashSet<String>()

    /** Explicit operator block (long-press). */
    fun blockUser(callsign: String) {
        CallBaseName.of(callsign)?.let { userBlocked.add(it) }
    }

    /** Transient no-reply suppression (auto-answer exclusion only). */
    fun suppressAuto(callsign: String) {
        CallBaseName.of(callsign)?.let { autoSuppressed.add(it) }
    }

    fun isUserBlocked(callsign: String): Boolean {
        val base = CallBaseName.of(callsign) ?: return false
        return userBlocked.contains(base)
    }

    /** Manual "engage this station now" override (tap-to-resume / manager unblock). */
    fun allowResume(callsign: String) {
        val base = CallBaseName.of(callsign) ?: return
        userBlocked.remove(base)
        autoSuppressed.remove(base)
    }

    fun clear() {
        userBlocked.clear()
        autoSuppressed.clear()
    }

    /** Union of both sets: everything excluded from auto-answer/CQ selection. */
    fun snapshot(): Set<String> = LinkedHashSet(userBlocked).apply { addAll(autoSuppressed) }

    /** User-blocked stations only (row hiding + Settings manager). */
    fun userBlockedSnapshot(): Set<String> = userBlocked.toSet()
}
```

- [ ] **Step 4: Wire `QsoSessionController` to the new API**

In `app/src/main/java/net/ft8vc/app/controllers/QsoSessionController.kt`:

a) Add a constructor parameter (place it right after `slotClockIntervalMs`, before the closing `)`):

```kotlin
    private val slotClockIntervalMs: Long = 250L,
    private val abandonedPartners: AbandonedPartners = AbandonedPartners(),
) : AutoCloseable {
```

b) Delete the internal field at line 124 (`private val abandonedPartners = AbandonedPartners()`).

c) Replace `clearAbandonedPartners()` (currently lines 285-288) with a version that also republishes:

```kotlin
    fun clearAbandonedPartners() {
        abandonedPartners.clear()
        publishBlocklist()
        notifyFn("Cleared blocklist", SnackbarEvent.Tag.TRANSIENT)
    }

    /** Explicit operator block from a long-press on a decode row. */
    fun blockStation(callsign: String) {
        abandonedPartners.blockUser(callsign)
        publishBlocklist()
        notifyFn("Blocked $callsign", SnackbarEvent.Tag.TRANSIENT)
    }

    /** Remove a station from the user blocklist (manager unblock). */
    fun unblockStation(callsign: String) {
        abandonedPartners.allowResume(callsign)
        publishBlocklist()
    }

    private fun publishBlocklist() {
        _slice.update { it.copy(userBlockedCalls = abandonedPartners.userBlockedSnapshot().sorted()) }
    }
```

d) Remove `abandonQso()` entirely (currently lines 244-251).

e) In the tap-to-resume path `resumeFromDecode` (the `abandonedPartners.allowResume(opp.dxCall)` call, ~line 234), add a `publishBlocklist()` right after it so the manager reflects the unblock:

```kotlin
        abandonedPartners.allowResume(opp.dxCall)
        publishBlocklist()
```

f) In `abandonForNoReply()` (~line 448), change the block call from `abandon` to `suppressAuto`:

```kotlin
        if (dx != null) abandonedPartners.suppressAuto(dx)
```

g) Leave the three `abandonedPartners.snapshot()` call sites (lines ~301, ~530, ~542) **unchanged** — they now consume the union.

h) Add the field to `QsoSlice` (data class at ~line 689):

```kotlin
    val utcClock: String = "00:00:00",
    val userBlockedCalls: List<String> = emptyList(),
)
```

- [ ] **Step 5: Update `QsoSessionControllerTest` for the new model**

In `app/src/test/java/net/ft8vc/app/controllers/QsoSessionControllerTest.kt`:

Replace the `abandonQso_blocksLaterAutoResume` test (lines 228-242) with a block-based version:

```kotlin
    @Test
    fun blockStation_suppressesLaterAutoResume() = runTest {
        controller.onDecodeBatch(
            listOf(QsoDecode("W0DEV K1ABC FN42", -10)),
            slotParity = TxSlotParity.EVEN,
        )
        assertEquals("K1ABC", controller.slice.value.qsoDx)
        controller.stopQso()
        controller.blockStation("K1ABC")
        assertEquals(listOf("K1ABC"), controller.slice.value.userBlockedCalls)
        // Same caller must NOT auto-resume while user-blocked.
        controller.onDecodeBatch(
            listOf(QsoDecode("W0DEV K1ABC FN42", -10)),
            slotParity = TxSlotParity.EVEN,
        )
        assertFalse(controller.slice.value.qsoActive)
    }
```

Replace the `autoResumeCq_notAfterManualAbandon` test (lines 333-341) — rename and use `stopQso()`:

```kotlin
    @Test
    fun autoResumeCq_notAfterManualStop() = runTest {
        controller.setAutoCqResumeEnabled(true)
        controller.startCq()
        controller.onDecodeBatch(listOf(QsoDecode("W0DEV K1ABC FN42", -8)), TxSlotParity.ODD)
        controller.stopQso()
        assertFalse(controller.slice.value.qsoActive)
        assertNull(controller.slice.value.qsoState)
    }
```

Add a test that no-reply suppression does NOT surface as a user block. Append inside the class:

```kotlin
    @Test
    fun noReplyTimeout_suppressesAuto_withoutUserBlock() = runTest {
        controller.setMaxUnansweredTxCycles(1)
        controller.setAutoCqResumeEnabled(false)
        controller.onDecodeBatch(
            listOf(QsoDecode("W0DEV K1ABC FN42", -10)),
            slotParity = TxSlotParity.EVEN,
        )
        assertEquals("K1ABC", controller.slice.value.qsoDx)
        // Drive TX cycles until the no-reply limit trips and the QSO is torn down.
        repeat(4) { advanceTimeBy(10_000L) }
        assertFalse(controller.slice.value.qsoActive)
        // No-reply must not add the station to the user blocklist.
        assertTrue(controller.slice.value.userBlockedCalls.isEmpty())
    }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.AbandonedPartnersTest" && ./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.QsoSessionControllerTest"`
Expected: PASS. If `noReplyTimeout_suppressesAuto_withoutUserBlock` timing needs adjustment, tune the `repeat` count so the loop reaches the no-reply limit (max cycles = 1) — the assertion on `userBlockedCalls` being empty is the invariant that must hold.

- [ ] **Step 7: Commit**

```bash
git add core/src/main/java/net/ft8vc/core/AbandonedPartners.kt core/src/test/java/net/ft8vc/core/AbandonedPartnersTest.kt app/src/main/java/net/ft8vc/app/controllers/QsoSessionController.kt app/src/test/java/net/ft8vc/app/controllers/QsoSessionControllerTest.kt
git commit -m "feat(core): split blocklist into user vs auto sets; add block/unblock"
```

---

### Task 4: `OperateViewModel` wiring + `OperateUiState` field

Create the shared `AbandonedPartners`, inject it into `QsoSessionController`, expose `blockStation`/`unblockStation`, drop `abandonQso`, and surface `userBlockedCalls` in `OperateUiState`.

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt`

**Interfaces:**
- Consumes: `QsoSessionController.blockStation/unblockStation/clearAbandonedPartners`, `QsoSlice.userBlockedCalls` (Task 3).
- Produces: `OperateViewModel.blockStation(call: String)`, `OperateViewModel.unblockStation(call: String)`, `OperateUiState.userBlockedCalls: List<String>`.

- [ ] **Step 1: Add the field to `OperateUiState`**

In `app/src/main/java/net/ft8vc/app/OperateUiState.kt`, add to the `OperateUiState` data class (near the other list/collection fields; any position with a default is safe):

```kotlin
    val userBlockedCalls: List<String> = emptyList(),
```

- [ ] **Step 2: Create the shared `AbandonedPartners` and inject it**

In `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`, add a private field before the `qsoSession` declaration (~line 145):

```kotlin
    private val abandonedPartners = net.ft8vc.core.AbandonedPartners()
```

Then pass it into the `QsoSessionController(...)` constructor call (add as the last argument, ~line 152):

```kotlin
        resumeCaptureIfNeeded = ::resumeCaptureIfNeededForQso,
        abandonedPartners = abandonedPartners,
    )
```

- [ ] **Step 3: Map `userBlockedCalls` into the combined state**

In the `combine { ... }` that builds `OperateUiState` (the `qso` source is `qsoSession.slice`), add the mapping (near the other `qso.*` fields):

```kotlin
                userBlockedCalls = qso.userBlockedCalls,
```

- [ ] **Step 4: Replace `abandonQso` with block/unblock pass-throughs**

In `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`, replace the `abandonQso()` function (lines ~720-724) with:

```kotlin
    fun blockStation(call: String) {
        qsoSession.blockStation(call)
    }

    fun unblockStation(call: String) = qsoSession.unblockStation(call)
```

Leave `stopQso()` (lines ~709-713) unchanged — the End QSO button uses it.

- [ ] **Step 5: Verify the app module compiles and existing tests pass**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest`
Expected: PASS. Compile failures here mean a missed `abandonQso` reference — grep for it: `grep -rn "abandonQso" app/src/main` should return nothing.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateViewModel.kt app/src/main/java/net/ft8vc/app/OperateUiState.kt
git commit -m "feat(app): share AbandonedPartners; expose block/unblock + userBlockedCalls"
```

---

### Task 5: Single "End QSO" button in `OperateControls`

Collapse the Stop-QSO/Abandon pair into one "End QSO" button wired to `stopQso`.

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/OperateControls.kt`
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt`

**Interfaces:**
- Consumes: `OperateViewModel.stopQso` (unchanged).
- Produces: `OperateControls(..., onEndQso: () -> Unit, ...)` — replaces `onStopQso` + `onAbandonQso`.

- [ ] **Step 1: Replace the two-button block with one End QSO button**

In `app/src/main/java/net/ft8vc/app/ui/operate/OperateControls.kt`:

Change the signature — remove `onStopQso` and `onAbandonQso`, add `onEndQso`:

```kotlin
fun OperateControls(
    state: OperateUiState,
    onToggleOperate: () -> Unit,
    onStartCq: () -> Unit,
    onEndQso: () -> Unit,
    modifier: Modifier = Modifier,
) {
```

Replace the `if (state.qsoActive) { ... }` branch (the two `Button`s, lines ~97-120) with a single button:

```kotlin
            if (state.qsoActive) {
                Button(
                    onClick = onEndQso,
                    modifier = Modifier
                        .weight(1f)
                        .height(Ft8Compact.tapTargetPrimary),
                    contentPadding = Ft8Compact.buttonPadding,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Ft8Red,
                        contentColor = Color.Black,
                    ),
                ) {
                    Text("End QSO", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
            } else {
```

(Leave the `else` Start-CQ branch unchanged.)

- [ ] **Step 2: Update the call site in `OperateScreen`**

In `app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt`, in the `OperateControls(...)` call (lines ~146-160), replace the two handler lines:

```kotlin
                onStopQso = vm::stopQso,
                onAbandonQso = vm::abandonQso,
```

with one:

```kotlin
                onEndQso = vm::stopQso,
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS. `grep -rn "onAbandonQso\|onStopQso" app/src/main` should return nothing.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/operate/OperateControls.kt app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt
git commit -m "feat(ui): single End QSO button replaces Stop QSO / Abandon"
```

---

### Task 6: Decode-row block filter + long-press to block

Hide user-blocked senders from the decode list and add a long-press gesture on rows to block their sender.

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/ui/operate/DecodeBlocklist.kt`
- Create: `app/src/test/java/net/ft8vc/app/ui/operate/DecodeBlocklistTest.kt`
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/DecodeListPanel.kt`
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt`

**Interfaces:**
- Consumes: `QsoMessages.senderCall` (Task 2), `CallBaseName.of` (Task 1), `OperateUiState.userBlockedCalls` (Task 4), `OperateViewModel.blockStation` (Task 4).
- Produces:
  - `object DecodeBlocklist { fun isSenderBlocked(message: String, source: DecodeRowSource, blocked: Collection<String>): Boolean; fun senderToBlock(message: String, source: DecodeRowSource): String? }`
  - `DecodeListPanel(..., userBlockedCalls: List<String>, onBlockSender: (String) -> Unit, ...)`

- [ ] **Step 1: Write the failing test for the pure filter helper**

Create `app/src/test/java/net/ft8vc/app/ui/operate/DecodeBlocklistTest.kt`:

```kotlin
package net.ft8vc.app.ui.operate

import net.ft8vc.core.DecodeRowSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeBlocklistTest {

    @Test fun blockedSenderIsHidden() {
        assertTrue(
            DecodeBlocklist.isSenderBlocked("W0DEV K1ABC FN42", DecodeRowSource.Rx, listOf("K1ABC")),
        )
    }

    @Test fun portableSenderMatchesBaseCallBlock() {
        assertTrue(
            DecodeBlocklist.isSenderBlocked("W0DEV K1ABC/P FN42", DecodeRowSource.Rx, listOf("K1ABC")),
        )
    }

    @Test fun unblockedSenderIsVisible() {
        assertFalse(
            DecodeBlocklist.isSenderBlocked("W0DEV N0XYZ FN42", DecodeRowSource.Rx, listOf("K1ABC")),
        )
    }

    @Test fun ownTxRowIsNeverBlocked() {
        assertFalse(
            DecodeBlocklist.isSenderBlocked("CQ K1ABC FN42", DecodeRowSource.Tx, listOf("K1ABC")),
        )
    }

    @Test fun senderToBlockReturnsBaseCall() {
        assertEquals("K1ABC", DecodeBlocklist.senderToBlock("CQ K1ABC/P FN42", DecodeRowSource.Rx))
    }

    @Test fun senderToBlockNullForTxOrUnparseable() {
        assertNull(DecodeBlocklist.senderToBlock("CQ K1ABC FN42", DecodeRowSource.Tx))
        assertNull(DecodeBlocklist.senderToBlock("TNX 73 GL", DecodeRowSource.Rx))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.ui.operate.DecodeBlocklistTest"`
Expected: FAIL — `DecodeBlocklist` unresolved reference.

- [ ] **Step 3: Implement the helper**

Create `app/src/main/java/net/ft8vc/app/ui/operate/DecodeBlocklist.kt`:

```kotlin
package net.ft8vc.app.ui.operate

import net.ft8vc.core.CallBaseName
import net.ft8vc.core.DecodeRowSource
import net.ft8vc.core.QsoMessages

/** Blocklist visibility rules for decode rows. Our own TX rows are never blocked. */
object DecodeBlocklist {

    /** True when [message]'s sender base-call is in [blocked] (and the row is not our own TX). */
    fun isSenderBlocked(message: String, source: DecodeRowSource, blocked: Collection<String>): Boolean {
        if (source is DecodeRowSource.Tx) return false
        val base = QsoMessages.senderCall(message)?.let { CallBaseName.of(it) } ?: return false
        return base in blocked
    }

    /** Base-call to block from a long-press on this row, or null when there's nothing blockable. */
    fun senderToBlock(message: String, source: DecodeRowSource): String? {
        if (source is DecodeRowSource.Tx) return null
        return QsoMessages.senderCall(message)?.let { CallBaseName.of(it) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.ui.operate.DecodeBlocklistTest"`
Expected: PASS.

- [ ] **Step 5: Apply the filter and long-press in `DecodeListPanel`**

In `app/src/main/java/net/ft8vc/app/ui/operate/DecodeListPanel.kt`:

a) Add the opt-in annotation for `combinedClickable` above the `DecodeRowItem` composable, and the imports at the top of the file:

```kotlin
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
```

b) Add two parameters to `DecodeListPanel`'s signature (after `onResume`):

```kotlin
    onResume: (DecodeRow) -> Unit,
    userBlockedCalls: List<String>,
    onBlockSender: (String) -> Unit,
    modifier: Modifier = Modifier,
```

c) Extend the `visibleDecodes` filter (lines ~67-80) so blocked senders drop out first:

```kotlin
    val visibleDecodes = decodes.filter { row ->
        !DecodeBlocklist.isSenderBlocked(row.message, row.source, userBlockedCalls) &&
        (row.source is DecodeRowSource.Tx ||
            MonitorDecodeFilter.visibleForDisplay(
                message = row.message,
                isCq = row.isCq,
                myCall = myCall,
                freqHz = row.freqHz,
                txToneHz = txToneHz,
                viewMode = decodeViewMode,
                cq73OnlyFilter = cq73OnlyFilter,
                qsoDx = qsoDx,
                qsoActive = qsoActive,
            ))
    }
```

d) In the `items(visibleDecodes, ...)` block (lines ~214-226), compute the long-press action and pass it down:

```kotlin
                    items(visibleDecodes, key = { it.id }) { row ->
                        val blockTarget = DecodeBlocklist.senderToBlock(row.message, row.source)
                        DecodeRowItem(
                            row = row,
                            qsoDx = qsoDx,
                            qsoActive = qsoActive,
                            decodeColors = decodeColors,
                            onClick = when {
                                canAnswer && row.isCq -> ({ onAnswerCq(row) })
                                canResume && row.isToMe -> ({ onResume(row) })
                                else -> null
                            },
                            onLongClick = blockTarget?.let { call -> { onBlockSender(call) } },
                        )
                    }
```

e) Update `DecodeRowItem` to accept `onLongClick` and use `combinedClickable`. Change its signature (line ~279) and the modifier line (~321):

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DecodeRowItem(
    row: DecodeRow,
    qsoDx: String?,
    qsoActive: Boolean,
    decodeColors: DecodeColorScheme,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
```

Replace the `.then(if (onClick != null && !isTx) Modifier.clickable(onClick = onClick) else Modifier)` line with:

```kotlin
            .then(
                if (!isTx && (onClick != null || onLongClick != null)) {
                    Modifier.combinedClickable(
                        onClick = onClick ?: {},
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier
                },
            )
```

- [ ] **Step 6: Wire the new params from `OperateScreen`**

In `app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt`, in the `DecodeListPanel(...)` call (lines ~121-138), add:

```kotlin
                onResume = { row -> gateOnLicense { vm.resumeFromDecode(row) } },
                userBlockedCalls = state.userBlockedCalls,
                onBlockSender = { call -> vm.blockStation(call) },
                modifier = Modifier.fillMaxWidth().weight(1f),
```

- [ ] **Step 7: Verify compile + tests**

Run: `./gradlew :app:compileDebugKotlin && ./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.ui.operate.DecodeBlocklistTest"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/operate/DecodeBlocklist.kt app/src/test/java/net/ft8vc/app/ui/operate/DecodeBlocklistTest.kt app/src/main/java/net/ft8vc/app/ui/operate/DecodeListPanel.kt app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt
git commit -m "feat(ui): hide blocked senders + long-press decode to block"
```

---

### Task 7: Blocklist manager in Settings

Replace the single "Clear abandoned-station blocklist" button with a list of user-blocked callsigns, each with an unblock action, plus a Clear-all action.

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `OperateUiState.userBlockedCalls` (Task 4), `OperateViewModel.unblockStation` + `clearAbandonedPartners` (Tasks 3-4).

- [ ] **Step 1: Replace the clear-only button with the manager**

In `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt`, replace the single `TextButton(onClick = vm::clearAbandonedPartners, ...)` block (lines ~201-207) with a small manager. Use the existing state (`state` is already in scope in this section):

```kotlin
                Text(
                    "Blocklist",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.userBlockedCalls.isEmpty()) {
                    Text(
                        "No blocked stations.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.userBlockedCalls.forEach { call ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(call, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { vm.unblockStation(call) }) {
                                Text("Unblock")
                            }
                        }
                    }
                    TextButton(
                        onClick = vm::clearAbandonedPartners,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Clear all")
                    }
                }
```

- [ ] **Step 2: Add any missing imports**

Ensure these are imported at the top of `SettingsScreen.kt` (add whichever are absent):

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt
git commit -m "feat(settings): blocklist manager with per-call unblock + clear all"
```

---

### Task 8: Full verification

Confirm the whole feature builds and the suite is green, then run a manual smoke check on the reference rig before promotion.

**Files:** none (verification only).

- [ ] **Step 1: Run the full unit-test suite**

Run: `./gradlew :core:testDebugUnitTest :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Assemble the debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Confirm no stale references remain**

Run: `grep -rn "abandonQso\|onAbandonQso\|isAbandoned\|\.abandon(" app/src core/src --include=*.kt`
Expected: no matches (the old manual-abandon API and the old single-set method are gone).

- [ ] **Step 4: Manual smoke check (reference FT-891 + Digirig)**

Verify on the device build the operator normally runs:
- During an active QSO the controls show a single **End QSO** button; tapping it stops the QSO cleanly and does NOT block the partner (their next CQ still appears and is answerable).
- Long-pressing a decode row shows a "Blocked X" snackbar and removes that station's rows from the list (both Focus and Band views).
- Settings → Operating shows the blocked callsign; Unblock removes it and the station's rows reappear on the next decode; Clear all empties the list.
- A no-reply timeout still stops re-calling that station but does NOT add it to the Settings blocklist.
- RX decodes, TX keying, and CAT freq/mode read behave exactly as before.

- [ ] **Step 5: Commit (if any verification tweaks were needed)**

```bash
git add -A
git commit -m "test: verify End QSO button + decode blocklist end-to-end"
```

---

## Self-Review

**Spec coverage:**
- Single End QSO button (no auto-block) → Task 5 + `stopQso` unchanged. ✓
- Long-press decode → block sender + snackbar → Task 6 + Task 3 `blockStation`. ✓
- Blocked senders hidden from list → Task 6 filter. ✓
- Two-set `AbandonedPartners` (user vs auto; union for auto-exclusion) → Task 3. ✓
- No-reply keeps loop-prevention, stays out of user list/manager → Task 3 (`suppressAuto`) + test `noReplyTimeout_suppressesAuto_withoutUserBlock`. ✓
- Blocklist manager in Settings (unblock + clear all) → Task 7. ✓
- Session-scoped (no persistence) → nothing persists `AbandonedPartners`; created in VM per session. ✓
- Unblock via tap-to-resume override → Task 3 keeps `allowResume` + publishes. ✓

**Placeholder scan:** No TBD/TODO; every code step shows concrete code. ✓

**Type consistency:** `blockUser`/`suppressAuto`/`isUserBlocked`/`allowResume`/`snapshot`/`userBlockedSnapshot`/`clear` (Task 3) are the names used by `QsoSessionController` (Task 3) and never by the UI directly. `blockStation`/`unblockStation` (controller + VM) and `userBlockedCalls` (`QsoSlice` → `OperateUiState`) are consistent across Tasks 3-7. `DecodeBlocklist.isSenderBlocked`/`senderToBlock` (Task 6) match their test and call sites. `onEndQso` replaces `onStopQso`/`onAbandonQso` in both `OperateControls` and `OperateScreen` (Task 5). ✓
