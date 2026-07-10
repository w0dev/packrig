# Gate Auto-Resume CQ on CQ-Origin Sessions

**Date:** 2026-07-05
**Status:** Approved — ready for planning
**Milestone:** v1.x Code Health (behavior refinement, no feature surface expansion)

## Problem

The `autoCqResumeEnabled` setting — surfaced in Settings as **"Resume CQ after QSO"** — currently fires on *every* QSO end regardless of how the session started. `maybeAutoResumeCq()` is called unconditionally from both completion paths:

- `afterTransmit()` on `QsoState.Complete` (`QsoSessionController.kt:417`)
- `abandonForNoReply()` when a partner ghosts and the no-reply limit trips (`QsoSessionController.kt:455`)

Neither path records how the session was entered. So if the operator answers someone else's CQ (search-and-pounce) — via `answerCq()`, `resumeFromDecode`, answer-when-called, or auto-answer-CQ — the app starts calling CQ on the operator's TX offset when that QSO ends. The operator never intended to run.

The word "Resume" implies returning to something you were doing. If you S&P'd into a QSO, there is nothing to resume — starting CQ is a mode switch, not a resumption. The honest behavior is: **auto-resume CQ only when the session originated from the operator calling CQ.**

## Approved Decisions

1. **Gate both completion paths.** The CQ-origin gate suppresses auto-resume on clean completion *and* on partner-ghost no-reply abandon. Consistent everywhere.
2. **Resume-from-decode is non-CQ origin.** Re-entering a specific station's QSO is S&P-like; on completion it does not auto-start CQ.
3. **No new setting.** The existing `autoCqResumeEnabled` toggle is unchanged. This is an honest-semantics behavior refinement, not a new knob.

## Design

### Core mechanism

Add a single boolean field to `QsoSessionController`:

```kotlin
/** True when the active session originated from the operator calling CQ (run mode).
 *  Gates auto-resume CQ so S&P sessions don't leave the operator running. */
private var runOriginatedFromCq: Boolean = false
```

Every session is created through exactly one chokepoint: `startQsoLoop()` (`QsoSessionController.kt:344`). It already calls `stopQsoInternal()` as its first line, which resets the flag to `false`. Thread the origin in as a new parameter and assign it immediately after:

```kotlin
private suspend fun startQsoLoop(
    machine: QsoMachine,
    hearingSlotParity: TxSlotParity?,
    cqOrigin: Boolean,
) {
    stopQsoInternal()          // resets runOriginatedFromCq to false
    runOriginatedFromCq = cqOrigin
    qso = machine
    ...
}
```

`startQsoLoop` is the single source of truth for the flag. No other code sets it except `stopQsoInternal()` (reset to `false` on any stop/abandon/restart).

### Origin per call site

All six `startQsoLoop` call sites pass `cqOrigin` explicitly:

| Call site | Location | `cqOrigin` | Rationale |
|---|---|---|---|
| `startCq()` | `:202` | `true` | Operator initiated CQ — run mode |
| auto-resume restart in `maybeAutoResumeCq()` | `:485` | `true` | Resumed session is itself run mode; carries the flag forward |
| `answerCq()` | `:220` | `false` | Answering someone's CQ — S&P |
| `resumeFromOpportunity()` | `:517` | `false` | Covers manual resume-from-decode and answer-when-called |
| `tryAutoAnswerCq()` | `:548` | `false` | Auto-answering a CQ — S&P |

### The gate

Add one early return at the top of `maybeAutoResumeCq()` (`:469`), alongside the existing enabled check:

```kotlin
private fun maybeAutoResumeCq(snackbar: String, finishedJob: Job? = null) {
    if (!autoCqResumeEnabled) return
    if (!runOriginatedFromCq) return   // new: only run-mode sessions auto-resume
    pendingAutoCqResume = true
    ...
}
```

The flag is read synchronously (before the restart coroutine launches), so it reflects the **current** session's origin. Because both `afterTransmit()` and `abandonForNoReply()` route through this single function, the gate covers both paths with one check (decision 1).

When the gate passes and a fresh CQ is started (`:483-485`), that restart passes `cqOrigin = true`, so `startQsoLoop` re-sets the flag `true` and a running station keeps running contact after contact (existing behavior preserved).

### Threading

`runOriginatedFromCq` is a plain `var`, written only in `startQsoLoop()` / `stopQsoInternal()` and read only in `maybeAutoResumeCq()`. All of these execute on `qsoDispatcher` (the entry helpers `launch(qsoDispatcher)`; `afterTransmit`/`abandonForNoReply` run inside the `qsoLoopJob` coroutine on the same dispatcher). This matches the existing single-threaded treatment of `pendingAutoCqResume` — no `@Volatile`, no synchronization needed.

### Settings copy

Update the toggle subtitle at `SettingsScreen.kt:259` so the UI matches the semantics:

- Before: `"Keep calling CQ after each logged or abandoned QSO"`
- After: `"After a QSO you started by calling CQ, keep calling CQ"`

Title (`"Resume CQ after QSO"`), key, default (`false`), and `enabled = state.txEnabled` are unchanged.

## Side Effects (intended)

With both auto-answer-CQ and auto-resume-CQ enabled, auto-answered QSOs (`tryAutoAnswerCq`, `cqOrigin = false`) no longer hijack the operator into calling CQ. The app stays in auto-answer/S&P mode — more coherent than the current behavior.

## Out of Scope

- No changes to `QsoMachine`, slot timing, RX/TX/CAT, or the reference-rig audio path.
- No DataStore / settings schema change (no new key, no migration).
- No change to manual Stop/Abandon behavior.

## Testing (TDD)

`QsoSessionControllerTest` already exercises auto-resume with a fake clock/dispatcher. Add/adjust:

- **`autoResumeCq_notAfterAnsweringCq`** — `answerCq` → drive to `Complete` → assert no CQ restart.
- **`autoResumeCq_notAfterAnsweredCqGhosts`** — `answerCq` → no-reply limit → abandon → assert no CQ restart (guards the `abandonForNoReply` path per decision 1).
- **`autoResumeCq_notAfterResumeFromDecode`** — resume-from-decode session → `Complete` → assert no CQ restart (decision 2).
- **`autoResumeCq_restartsAfterCompletion`** (existing) — `startCq` origin → still restarts.
- **`autoResumeCq_carriesForwardAcrossRestart`** (new) — `startCq` → complete → auto-resumed CQ → complete → resumes *again* (flag persists through restart).
- Existing cases (`_disabled_staysStopped`, `_notAfterManualAbandon`, `_notWhenTxDisabledAtFireTime`) must remain green.

## Verification

- Full `QsoSessionControllerTest` green.
- Broader app unit-test suite green (no regressions in `TxOrchestrator*`, `TxCaptureControl`, integration tests that stub `resumeAfterTx`).
- Field spot-check on FT-891 + Digirig before promotion: (a) call CQ, work a station, confirm CQ resumes; (b) answer a station's CQ, confirm the app does **not** start calling CQ after logging.
