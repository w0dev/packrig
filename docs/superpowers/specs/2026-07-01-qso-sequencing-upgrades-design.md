# QSO Sequencing Upgrades — Design

**Date:** 2026-07-01
**Status:** Approved (brainstorming complete)
**Phase:** 2 of 5 in the Field Readiness milestone (phase 1: POTA activator
logging, shipped as commits `a1a8d3e..e7544c3`)

## Problem

Four sequencing behaviors cost an activator QSOs or time:

1. As initiator the machine sends `RRR` and only logs when the partner's
   `73` decodes. A lost `73` (very common with weak hunters) means the QSO
   is abandoned and **never logged**, even though the hunter logged it.
   It also costs one extra 15-second cycle per QSO.
2. When a QSO ends, the TX loop stops. The operator must tap "CQ POTA"
   again after every contact.
3. During an active CQ, `QsoMachine.handleCqReplies` only accepts grid
   replies. Hunters who skip the grid message (Tx1-skip, common in POTA
   pileups) are ignored while we CQ over them. Likewise, a partner who
   lost our final message and retries their R-report gets ignored.
4. `QsoMachine.fromDx` uses exact string equality. A partner alternating
   between `K1ABC` and a compound/hashed form (`PJ4/K1ABC`, `<PJ4/K1ABC>`)
   stalls the sequencer mid-QSO.

## Requirements

- Initiator can send `RR73` and treat the QSO as complete-and-loggable at
  the moment it transmits (WSJT-X parity). Settings toggle, default ON;
  OFF reproduces v1.0 (`RRR` + wait for 73) byte-for-byte.
- A lost-RR73 retry (partner re-sends R-report) is re-confirmed on air
  but does not create a duplicate log row within a 10-minute window.
- Optional auto-resume: after a logged QSO or a partner-vanished abandon,
  the CQ loop restarts automatically. Default OFF. Never fires after an
  unanswered bare-CQ timeout or a manual Stop/Abandon.
- An active CQ accepts directed grid replies, plain reports, and
  R-reports, honoring the configured answer policy across all kinds.
- Directed-message matching tolerates compound (`/P`, `/QRP`) and hashed
  (`<...>`) callsign forms via base-call comparison.
- No changes outside core and app layers; audio/rig/native untouched.

## Design

### 1. RR73 with log-on-send

- `QsoMachine` constructor gains `initiatorRr73: Boolean = false`,
  snapshotted per QSO (mid-QSO settings flips do not affect an exchange
  in flight).
- Flag ON: `txMessage()` for `SendingRoger` emits
  `QsoMessages.rr73(dx, myCall)`; `markTransmitted()` promotes
  `SendingRoger → Complete` (mirroring today's `SendingSeventyThree`
  handling). Flag OFF: unchanged v1.0 behavior.
- No new states: `QsoTxStep`, `QsoFormLogic`, the operate TX selector,
  and `QsoResume`'s state mapping are untouched.
  `resumeInitiatorAfterRReport → SendingRoger` inherits RR73 for free.
- Receiving `Bye`/`RogerBye` while still in `SendingRoger` (flag ON,
  before our first RR73 TX) still advances to `Complete` as today.
- Plumbing: DataStore key `send_rr73` (default **true**) →
  `StationSettings.sendRr73` → `SettingsBridge` → `QsoSessionController`
  `@Volatile sendRr73` → `newQsoMachine()`. Settings row under
  "Operating (auto TX)": title "Send RR73 (log on send)", subtitle
  "OFF sends RRR and waits for 73 (v1.0 behavior)".

### 2. Duplicate-log guard

- `QsoSessionController.handleQsoComplete` replaces `lastLoggedKey` with
  a per-callsign recency map (`dxCall → completedAtEpochMs`).
- A `Complete` for the same `dxCall` within
  `DUPE_LOG_WINDOW_MS = 600_000` (10 min) skips `onQsoComplete` (no log
  row, no backup) and notifies "Re-confirmed <dx> — already logged".
  Outside the window it logs normally.
- The on-air re-confirmation itself (answer-when-called resume or
  tail-repair, below) is never suppressed — only the duplicate row.

### 3. Active-CQ reply acceptance

- `QsoMachine.handleCqReplies` delegates to
  `AnswerSelector.selectOpportunity(myCall, myGrid, decodes, policy,
  excludedDx)` — the same selector the idle answer-when-called path uses
  — and accepts three kinds while `CallingCq`:
  - `InitiatorGridReply` → `resumeInitiatorAfterGridReply` semantics
    (today's flow): dx + grid + measured SNR → `SendingReport`.
  - `AnswererReport` (Tx1-skip hunter) → `resumeAnswererAfterReport`
    semantics → `SendingRReport`, continuing through the existing
    answerer states (Roger/RogerBye received → `SendingSeventyThree` →
    73 → `Complete`).
  - `InitiatorRReport` (tail-repair: partner lost our RR73 and retried)
    → `resumeInitiatorAfterRReport` semantics → `SendingRoger` →
    RR73/RRR again per the flag.
  - `AnswererRoger` (stray RRR/RR73 addressed to us while CQing) stays
    ignored — there is no exchange to repair.
- **Report-field semantics** (applies to both active-CQ acceptance and
  the existing idle resume path): `reportRcvd` = the report carried in
  the message payload (`rx.snr`); `reportSent` = our measured SNR of
  their signal (`decode.snr`). Implemented by adding
  `payloadReport: Int?` to `QsoResume.Opportunity` (populated for the
  Report and RReport kinds) and threading it through `QsoResume.apply`
  into `resumeAnswererAfterReport` / `resumeInitiatorAfterRReport`.
  This is a targeted fix of a pre-existing quirk where the idle resume
  path discarded the payload report and echoed our measured SNR into
  both fields — RST_RCVD in the log was wrong for resumed QSOs.
- The answer policy (FIRST / BEST_SNR / FURTHEST) applies uniformly
  across all accepted kinds; the abandoned-partner exclusion set is
  honored as today.
- **Deliberately not toggle-gated**: this is a correction toward
  standard FT8 auto-sequence behavior (WSJT-X accepts these). The
  existing auto-seq switch still governs whether decodes advance the
  machine at all.

### 4. Auto-resume CQ

- DataStore key `auto_cq_resume` (default **false**) →
  `StationSettings.autoCqResumeEnabled` → bridge → controller. Settings
  row under "Operating (auto TX)": title "Resume CQ after QSO", subtitle
  "Keep calling CQ after each logged or abandoned QSO".
- One private entry point `maybeAutoResumeCq()` on the qso dispatcher,
  invoked from: (a) `handleQsoComplete` — both call sites (TX-path
  `afterTransmit` and decode-path advance); (b) `abandonForNoReply` only
  when `dxCall != null` (mid-QSO partner vanish).
- Never invoked from: unanswered bare-CQ timeout (`dxCall == null` —
  the no-reply limit remains the walk-away safety; `cycles = 0` already
  means unlimited), `stopQso()`, or `abandonQso()`. Manual stop clears
  any pending resume; the single-threaded qso dispatcher serializes
  stop vs resume so there is no race.
- Gates checked at fire time: toggle on, `isOperating`, `txEnabled`,
  valid station profile, and the POTA park-list gate when POTA mode is
  on (same checks as `startCq`).
- Restart: fresh machine via `newQsoMachine().startCq()` on
  `defaultTxSlotParity`, after the current loop job has fully wound
  down. Snackbar: "QSO logged — resuming CQ" (or "resuming CQ" after an
  abandon).

### 5. CallsignMatcher (base-call matching)

- New core object `CallsignMatcher`:
  - `base(call: String): String` — strips `<`/`>` hash brackets, takes
    `substringBefore('/')`, uppercases.
  - `matches(token: String, call: String): Boolean` — true when the
    bracket-stripped forms match case-insensitively, or when the bases
    match AND the base contains a digit (so modifiers like `DX`, `POTA`,
    `NA` can never base-match).
- Consumers:
  - `QsoMachine.fromDx` — both the target-vs-myCall and sender-vs-dxCall
    comparisons.
  - `QsoResume.opportunityFromDecode` — the `rx.target == myCall` checks
    (this also widens the decode list's `isToMe` flag, which is correct).
  - `AnswerSelector` — its directed-target checks.
  - `MonitorDecodeFilter.callsignMatches` — refactored to delegate to
    `CallsignMatcher` (single implementation, four call sites).
- `machine.dxCall` remains the first-seen form; TX messages compose with
  the stored form. Matching is flexible; storage is stable.

## Testing

- `QsoMachine`: full-sequence tests both flag modes (CQ → grid → report
  → R-report → RR73-and-complete-on-send vs RRR → 73 → complete);
  Tx1-skip sequence; tail-repair sequence; stray-RogerBye-while-CQing
  ignored; policy application across mixed reply kinds.
- `CallsignMatcher`: exact, `/P` base match, `<PJ4/K1ABC>` bracket
  strip, digitless-base rejection (`DX` vs `DX`), case insensitivity.
- `QsoSessionController`: dupe-guard inside/outside the 10-minute
  window; auto-resume fires after complete and after abandon-with-dx;
  does NOT fire after bare-CQ timeout, manual stop, abandon, toggle
  off, TX disabled, or invalid POTA park; pending resume cleared by
  manual stop.
- Parity: with `send_rr73` OFF and `auto_cq_resume` OFF, existing
  QsoMachine/controller test suites pass unchanged except for the
  intentional active-CQ acceptance widening (section 3).

## Field verification (promotion gates)

On the reference FT-891 + Digirig: (i) a full activation run with
auto-resume ON sustaining CQ across several QSOs; (ii) at least one
Tx1-skip hunter worked end-to-end; (iii) a lost-RR73 retry observed
re-confirming without a duplicate log row; (iv) one QSO with the RR73
toggle OFF confirming v1.0 sequencing end-to-end.

## Out of scope

- Applying `CallsignMatcher` to TX message composition (stored dxCall is
  always used verbatim).
- Compound-callsign support for MY station callsign in generated
  messages (encoder-side, unchanged).
- Contest/Fox-Hound modes; RX-side clock/decode work (phase 3);
  PSK Reporter (phase 5).
