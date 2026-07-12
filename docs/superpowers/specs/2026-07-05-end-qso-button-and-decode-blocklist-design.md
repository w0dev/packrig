# Single End-QSO button + long-press decode blocklist

**Date:** 2026-07-05
**Status:** Approved (design)
**Milestone:** v1.x Code Health

## Problem

The Operate screen shows two similarly-weighted buttons during an active QSO —
**Stop QSO** and **Abandon** — whose labels don't reveal the only thing that
actually differs between them. Operators reasonably read both as "make the
current QSO stop now."

The real difference today: `stopQso()` ends the exchange and leaves the partner
re-engageable; `abandonQso()` does the same **and** adds the DX call to an
in-session blocklist (`AbandonedPartners`) that suppresses auto-resume and
excludes the station from auto-answer/CQ selection. That "will the app chase
this partner again?" nuance can't be conveyed through two equal buttons.

## Goal

Collapse the two buttons into one honest **End QSO** action, and move
station-blocking to an explicit per-station gesture — long-press a decode row —
with a session-scoped blocklist you can review and edit from Settings.

Non-goals: persisting the blocklist across app restarts (stays session-scoped,
matching current design); any main-screen layout / space-reclamation change
(explicitly dropped); changing RX/TX/CAT behavior on the reference rig.

## Behavior

### Controls row

- During an active QSO, the Stop QSO / Abandon pair collapses to a single
  **End QSO** button that calls today's `stopQso()` — a clean stop with **no**
  auto-block of the partner.
- The manual `abandonQso()` QSO-ending path is removed.
- Consequence: ending a QSO no longer blocks the partner. To end *and* block,
  the operator ends the QSO, then long-presses that station's decode. This is
  the rarer case and the extra tap buys a clearer separation of concerns.

### Long-press a decode to block

- Long-press a decode row → derive the sender via the existing
  `senderCallFromMessage(row.message)` helper → add its base-call to the
  **user blocklist** → show a "Blocked X" snackbar.
- Rows whose sender base-call is in the user blocklist are **filtered out** of
  the Operate decode list (hidden), applied in the decode pipeline where the
  sender is already extracted.
- Tap-to-answer / tap-to-resume on a station continues to override the block
  for that station (existing `allowResume`), which naturally unblocks it.

### Auto no-reply timeout

- The no-reply timeout keeps its loop-prevention behavior: after a timeout the
  app must not immediately re-answer the same silent/weak station.
- But timed-out stations must **not** be hidden from the decode list and must
  **not** appear in the blocklist manager — they were never intentionally
  blocked by the operator. Intent and lifetime differ from a user block.

### Blocklist manager (Settings)

- Expand the existing "Clear abandoned-station blocklist" button in Settings
  ("Operating (auto TX)" section) into a small list:
  - Each user-blocked callsign with an individual **unblock** action.
  - A **Clear all** action (current behavior).
- Log-like, session-scoped, kept off the main Operate screen so it doesn't
  claim main-screen real estate.

## Design

### `AbandonedPartners` — two sets

Split the single blocked set into two:

- `userBlocked` — populated only by the manual long-press block. Drives row
  hiding and is the set surfaced in the manager.
- `autoSuppressed` — populated by the no-reply timeout
  (`abandonForNoReply`). Excludes stations from auto-answer/CQ selection only;
  never hides rows, never shown in the manager.

Rules:

- Auto-answer / CQ selection exclusion uses the **union** of both sets.
- Row-hiding and the manager read `userBlocked` only.
- `allowResume(call)` removes the call from **both** sets (tap-to-resume is an
  explicit "engage this station now" override).
- Session `clear()` clears both.
- Base-call normalization (`baseCall`) is unchanged: strip `/` portable and `-`
  suffixes, uppercase.

New/changed surface (names indicative, follow existing style):

- `abandon(call)` → split into `blockUser(call)` (manual) and
  `suppressAuto(call)` (no-reply). Keep a clear method per intent.
- `isExcludedFromAuto(call)` → union membership (replaces today's
  `isAbandoned` at the auto-answer call sites).
- `isUserBlocked(call)` → `userBlocked` membership (row hiding + manager).
- `userBlockedSnapshot(): Set<String>` → for the manager list.
- `unblock(call)` / `allowResume(call)` → remove from both sets.

### Wiring

- `QsoSessionController`
  - `abandonQso()` (manual) removed; the End QSO button uses `stopQso()`.
  - `abandonForNoReply()` calls `suppressAuto(dx)` instead of `abandon(dx)`.
  - New `blockStation(call)` entry point for the long-press → `blockUser(call)`
    + notify "Blocked X".
  - New `unblockStation(call)` for the manager → `unblock(call)`.
  - Auto-answer / CQ exclusion sites switch to `isExcludedFromAuto` / union.
  - `clearAbandonedPartners()` unchanged (clears both).
- `OperateViewModel` — thin pass-throughs: `blockStation`, `unblockStation`,
  expose `userBlocked` snapshot in UI state for the Settings manager.
- Decode pipeline (`DecodeController`) — filter out rows whose sender base-call
  `isUserBlocked`, alongside existing sender extraction.
- `OperateControls` — during active QSO render one **End QSO** button (wired to
  `stopQso`) instead of the Stop QSO / Abandon pair.
- `DecodeListPanel` — add a long-press handler on decode rows that calls
  `blockStation` with the row's sender.
- `SettingsScreen` — replace the single clear button with the blocklist
  manager list (per-callsign unblock + clear all), reading the `userBlocked`
  snapshot.

## Testing

- `AbandonedPartners` unit tests: manual vs auto membership stays separate;
  union used for auto-exclusion; `isUserBlocked` reflects only manual blocks;
  `unblock`/`allowResume` remove from both sets; `clear` empties both;
  base-call normalization for block and lookup.
- `QsoSessionController` test: no-reply timeout populates only `autoSuppressed`
  (station remains visible / absent from manager); `blockStation` populates
  `userBlocked`.
- Decode-filter test: a decode whose sender is user-blocked is hidden; a decode
  whose sender is only auto-suppressed is still shown.
- Regression: existing QSO-stop behavior (`stopQso`) unchanged; End QSO button
  stops cleanly without blocking the partner.

## Behavior parity

RX/TX/CAT paths on the reference FT-891 + Digirig are untouched. The only
behavioral change is UX-facing (button consolidation, block gesture) plus the
internal split of an already-session-scoped blocklist. Auto-answer/CQ selection
continues to exclude both user-blocked and no-reply-suppressed stations, so
field auto-sequencing behavior is preserved.
