# DT Self-Calibration — Manual Clock Alignment

**Date:** 2026-07-05
**Branch:** `multi-rig`
**Status:** Design approved, pending spec review

## Problem

FT8's 15-second slot grid is aligned to UTC. FT8VC derives slot timing from the
raw device wall clock (`System.currentTimeMillis()`). When that clock is off vs.
true UTC — common on a phone that has been off-grid for a long field session —
decode reliability degrades: as DT walks past the decoder's ~±3 s sync window,
decodes fade and skew toward one edge of the DT column.

The app already *measures* this error (`ClockOffsetEstimator` →
`slice.clockOffsetSeconds`, surfaced as the amber/red offset chip in
`OperateStatusBar`). It does not *correct* it. This feature lets the operator
apply the measured offset to the slot grid, using signals already on the band as
the time reference — no internet (NTP) and no location (GPS) dependency, keeping
the app fully offline-agnostic.

## Non-Goals

- No NTP. No GPS. No new permissions (the app currently requests zero network
  and zero location permissions; that stays true).
- No automatic/continuous correction. Application is operator-triggered only.
- No persistence across process restart.

## Decisions (resolved during brainstorming)

1. **Apply mode: Manual "Align now."** The operator triggers the correction; the
   app never shifts the grid on its own. Simplest, predictable, keeps the
   operator in control, and matches the field-reliability ethos.
2. **Trigger surfaces: both, conditional chip + on-demand Settings action.** The
   existing `OperateStatusBar` offset chip becomes tappable and stays conditional
   (only shown when `|residual| ≥ WARN_S`, i.e. 1.0 s, as today). A Settings row
   is the always-available entry point and hosts Reset.
3. **Persistence: in-memory only.** The applied offset lives for the ViewModel's
   lifetime — it survives capture stop/start and device changes within a session,
   but a fresh app launch starts at zero. This avoids silently double-correcting
   if the OS clock got re-synced (NITZ/NTP) while the app was closed, and matches
   the app's "QSO state lost on exit by design" philosophy. The operator
   re-aligns with one tap once decodes flow again (~a few slots).

## Approach

**Approach A — Shared corrected-clock seam (chosen).**

All slot timing already flows through three injectable `clock: () -> Long`
seams that today default to `System.currentTimeMillis()`:

- `DecodeController` — RX slot collection + early-decode trigger
- `TxOrchestrator` — TX keying and slot-wait
- `QsoSessionController` — slot-parity decisions and slot-wait

A single correction holder feeds all three, so applying an offset shifts RX slot
collection, early-decode timing, TX keying, and slot parity **coherently and
simultaneously**. This is the crux: correcting RX alone would misalign TX and
break QSO completion — the one behavior this milestone must never break.

Rejected alternatives:

- **B — Offset at each `SlotTiming` call site.** Scattered across ~15 call sites;
  one missed site = incoherent RX/TX timing. Rejected.
- **C — Correct RX only.** Breaks QSO timing coherence. Rejected outright.

## Components & Data Flow

### New: `core/ClockCorrection.kt`

Pure logic, no Android, unit-testable. Cumulative-residual model.

```kotlin
package net.ft8vc.core

/**
 * Holds an operator-applied correction between the raw device clock and FT8
 * band time. `now()` is the corrected epoch-ms that all slot timing reads.
 *
 * The correction is applied as a *residual*: each apply adds whatever error
 * remains on top of the current offset, so repeated applies converge toward
 * zero measured DT bias. In-memory only; a fresh process starts at zero.
 */
class ClockCorrection(private val rawClock: () -> Long = { System.currentTimeMillis() }) {
    @Volatile private var offsetMs = 0L
    fun now(): Long = rawClock() - offsetMs
    val appliedOffsetMs: Long get() = offsetMs
    /** Apply the remaining residual (seconds, signed) on top of the current correction. */
    fun applyResidualSeconds(residualSeconds: Float) { offsetMs += Math.round(residualSeconds * 1000f) }
    fun reset() { offsetMs = 0L }
}
```

Thread safety: `now()` is read on the audio-capture thread; `applyResidualSeconds`
/ `reset` are called on Main. `@Volatile Long` provides the needed visibility and
atomicity.

### Wiring in `OperateViewModel`

- Construct one `ClockCorrection`.
- Pass `clockCorrection::now` as the `clock` argument when constructing
  `DecodeController`, `TxOrchestrator`, and `QsoSessionController`. This is a
  construction-site change; no controller signatures change.

### Apply path

1. `DecodeController` computes the live residual via `ClockOffsetEstimator`
   (`slice.clockOffsetSeconds = median(DT) − NOMINAL_DT_S`).
2. Operator taps chip or Settings "Align now" → VM reads current residual `r` →
   `clockCorrection.applyResidualSeconds(r)`.
3. VM calls a new `DecodeController` hook that resets the estimator window
   (`clockOffset.reset()`) and publishes `clockOffsetSeconds = null`, so the chip
   rebuilds from corrected slots instead of lingering on stale pre-correction DTs.
4. The next capture chunk reads corrected `now()` → the slot grid shifts for RX
   and TX together. Over the next ≤ `WINDOW_SLOTS` (4) slots the residual
   re-derives toward ~0 — built-in confirmation the alignment worked.

`reset()` (Settings) zeroes the applied offset.

## Guardrails & Edge Cases

- **One-time slot flush at alignment.** When `now()` jumps, `SlotCollector`'s
  next `add()` sees a new `slotStart` and flushes the in-progress (misaligned)
  slot early. A single expected blip — and correct, since that partial slot was
  mis-aligned anyway. Covered by a test.
- **Nothing to apply when estimate is null.** The chip only appears at
  `|residual| ≥ WARN_S`, which requires an estimate to exist, so it is only
  tappable when there is a real correction. The Settings action shows "not enough
  decodes yet" and disables Align when `clockOffsetSeconds == null`.
- **TX in flight.** An already-started transmission plays a pre-scheduled
  fixed-length buffer; shifting `now()` does not truncate it. Only the *next*
  slot decision uses corrected time. No mid-TX guard needed.
- **No persistence.** Offset is VM-lifetime only; a fresh process starts at 0.

## UI Surfaces

- **Operate chip** (`OperateStatusBar`): the existing conditional offset chip
  becomes clickable → `onAlignClock()`. No new main-screen real estate; stays
  hidden below 1.0 s.
- **Settings**: a "Clock alignment" row showing the applied correction (e.g.
  `+0.7 s`) and the live residual, with **Align now** (enabled only when a
  residual estimate exists) and **Reset**. Always-available entry point.
- `OperateUiState` gains `appliedClockOffsetMs` for display; the chip gains an
  `onClick`.

## Testing (TDD)

- `ClockCorrectionTest` (core): `now()` math, cumulative apply, reset,
  offset visibility.
- `SlotCollector` clock-jump test: injected clock jumps → in-progress slot
  flushes and re-aligns on the new grid.
- Coherence test: a shared `ClockCorrection` injected into the three seams →
  assert `slotStart` and parity shift identically for RX and TX after
  `applyResidualSeconds`.
- Estimator-reset-on-align: after apply, `clockOffsetSeconds` publishes null then
  rebuilds from corrected slots.
- `ClockOffsetEstimator` is already covered.

## Scope & Behavior Parity

Files touched:

- **New:** `core/src/main/java/net/ft8vc/core/ClockCorrection.kt`
- `app/.../OperateViewModel.kt` — construct + inject the shared corrected clock;
  wire `onAlignClock` / reset.
- `app/.../controllers/DecodeController.kt` — add an estimator-reset hook.
- `app/.../ui/operate/OperateStatusBar.kt` — clickable chip.
- `app/.../OperateUiState.kt` — `appliedClockOffsetMs`, chip onClick.
- Settings screen — clock-alignment row (Align now / Reset).

**Default behavior is byte-identical to today:** the offset starts at 0, so with
no operator tap the slot timing is unchanged. The feature is purely opt-in.

Lands on `unstable` (`net.ft8vc.unstable`); field-verified on the reference
Yaesu FT-891 + Digirig before any promotion — RX decode, TX keying, and a full
QSO must all still complete after an alignment tap.
