# WSJT-X Log Timing — Log at RR73/RRR Receipt (Answerer Side)

**Date:** 2026-07-04
**Status:** Approved

## Problem

WSJT-X logs a QSO the moment the partner confirms with RR73 (or RRR), concurrent
with queuing our courtesy 73. FT8VC's answerer path logs only after our 73
transmission completes — one slot later, and a QSO the partner already confirmed
is silently lost if anything interrupts before the 73 slot (Stop, USB unplug,
TX failure, app exit).

The initiator paths already match WSJT-X:

- **Initiator, RR73 mode (default):** logs when our RR73 TX completes
  (`markTransmitted` → `Complete`).
- **Initiator, RRR mode:** logs on receipt of the partner's 73.

Only the **answerer** path changes.

## Design

### Core — `QsoMachine`

- New `confirmedByPartner: Boolean` (private set), set when `onDecodes`
  advances `SendingRReport → SendingSeventyThree` via `QsoRx.Roger` **or**
  `QsoRx.RogerBye` (RRR and RR73 are treated identically). Reset in `reset()`.
- `snapshot()` returns non-null when `state == Complete` **or**
  (`state == SendingSeventyThree && confirmedByPartner`), so the log record
  (with both reports) is available at the confirmation moment.

### Controller — `QsoSessionController`

- New per-QSO `qsoLogged: Boolean` field. Reset in `startQsoLoop` and
  `stopQsoInternal`. All access stays on `qsoDispatcher` (existing
  single-writer invariant — no new locking).
- In `onDecodeBatch`, after a decode advances the machine: if
  `qso.snapshot()` is now non-null and `!qsoLogged`, call
  `handleQsoComplete()` immediately and set `qsoLogged = true`. The QSO loop
  keeps running and still transmits the courtesy 73 on our next slot.
- The existing snackbar ("QSO complete with X — logged") fires at this early
  log moment.
- In both Complete paths (`afterTransmit` and the decode-path Complete
  branch): skip `handleQsoComplete()` when `qsoLogged` is already set.
  Teardown and auto-CQ-resume are unchanged. This guard (not `DupeLogGuard`)
  prevents the second log, so the normal flow never shows the spurious
  "Re-confirmed — already logged" snackbar.

### Deliberately unchanged

- **Initiator paths** — already WSJT-X-equivalent.
- **Manual control** — `onDecodes` does not advance under `manualControl`;
  logging stays at 73-TX completion. The user owns that flow.
- **`resumeAnswererAfterRoger`** (tapping a stray RRR/RR73 decode to resume) —
  that machine has no report data; `confirmedByPartner` stays false and it
  logs at Complete as today.
- **Lost-73 retry** — partner re-sends RR73 after teardown → resume path →
  `DupeLogGuard` suppresses the duplicate row, same as v1.0.

## Error handling

- Stop/abandon/USB-loss after RR73 receipt but before the 73 slot: the QSO is
  already logged (this is the point of the change). `stopQsoInternal` resets
  `qsoLogged` only as part of tearing down for the *next* QSO.
- Dupe suppression across QSOs is unchanged (`DupeLogGuard`, 10-min window).

## Testing (TDD)

- `QsoMachineTest`:
  - `confirmedByPartner` set on Roger receipt in `SendingRReport`; also on
    RogerBye; not set on unrelated decodes or other states.
  - `snapshot()` non-null in `SendingSeventyThree` once confirmed, with both
    reports populated; still null in `SendingRReport` and earlier.
  - Initiator flows: snapshot behavior unchanged.
  - `reset()` clears the flag.
- `QsoSessionControllerTest`:
  - Answerer flow: log callback fires exactly once, at the decode batch
    carrying RR73 — before the 73 TX.
  - The 73 is still transmitted afterward; loop tears down at Complete with
    no second log and no "Re-confirmed" snackbar.
  - RR73 received then `stopQso()` before the 73 slot: QSO was logged.
  - Manual-control and resume-after-roger flows: logging still at Complete.

## Behavior-parity note

RX/TX/CAT timing is untouched: the 73 still transmits in the same slot as
v1.0. Only the moment the log row is written (and the snackbar) moves earlier.
