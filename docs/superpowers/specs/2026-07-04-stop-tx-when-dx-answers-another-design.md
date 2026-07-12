# Stop TX when the DX we're answering picks another station

**Date:** 2026-07-04
**Status:** Approved for implementation (autonomous session; user directive)

## Problem

When we answer a station's CQ (`Answering` state) and that station replies to a
*different* caller with a signal report (`{other} {dx} {snr}`), the QSO machine
ignores the decode — it only advances on a report addressed to us. The TX loop
keeps calling the DX every cycle until the no-reply limit, transmitting on the
same slot parity as the station they picked and interfering with their QSO.

## Behavior

While in `QsoState.Answering` with auto-sequencing on:

- If a decode parses as `QsoRx.Report` whose **sender matches `dxCall`** and
  whose **target does not match `myCall`**, the DX has chosen another caller.
  Stop transmitting: tear down the QSO session, show a snackbar
  (`"{dx} answered another station — stopped calling"`), and honor the
  auto-CQ-resume setting (same as a no-reply abandon).

Deliberate scope limits:

- **Only `Report` triggers the stop.** `RRR`/`RR73`/`73` from the DX to a third
  party is frequently a re-confirmation tail of their *previous* QSO (partner
  missed the RR73 and re-sent R-report); the DX is still available, so we keep
  calling. A grid reply from the DX to a third party (DX abandons CQing to
  answer someone else's CQ) is rare and out of scope.
- **Only the `Answering` state.** Once the DX has sent *us* a report
  (`SendingRReport` onward) we are the chosen partner; third-party traffic no
  longer means we lost.
- **The DX is NOT added to `AbandonedPartners`.** They didn't ignore us — they
  picked someone first. They will likely CQ again within minutes and
  auto-answer/answer-when-called should still fire.
- **`manualControl` suppresses the auto-stop** (user owns the QSO form).

## Design

1. `QsoMachine.dxAnsweredAnotherStation(decodes: List<QsoDecode>): Boolean` —
   pure query in core. True iff `!manualControl`, `state == Answering`, and any
   decode is a `Report` with `CallsignMatcher.matches(sender, dxCall)` and
   `!CallsignMatcher.matches(target, myCall)`.
2. `QsoSessionController.onDecodeBatch` — in the running/auto-seq branch, when
   `onDecodes` did not advance, check the query; on true, capture `dxCall`,
   `stopQsoInternal()`, notify (TRANSIENT), `maybeAutoResumeCq("Resuming CQ")`.

## Testing

- `QsoMachineTest`: query true for report from DX to other; false when report is
  to us (and `onDecodes` advances instead); false for other senders, other
  payload types (RRR/RR73/73/grid to a third party), other states, and under
  `manualControl`.
- `QsoSessionControllerTest`: answering K1ABC, feed `N0XYZ K1ABC +03` → session
  stops, snackbar mentions K1ABC, machine cleared; K1ABC's next CQ still
  auto-answers (no blocklist entry); auto-CQ-resume fires when enabled.
