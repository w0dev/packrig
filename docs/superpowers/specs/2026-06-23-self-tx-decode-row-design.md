# Self-TX Decode Row: Emit at TX Start + Match Time Format

**Date:** 2026-06-23
**Status:** Approved design
**Scope:** Two small, behavior-preserving UI fixes to the synthetic "self-TX" row that appears in the decode list to mark where the operator is in a QSO chain.

## Problem

When we transmit, a synthetic row is injected into the decode list so the operator can see their own outgoing message in the chain. Two things are wrong relative to WSJT-X and to the rest of the decode list:

1. **Timing.** The row appears at the *end* of the transmit (after audio playback completes), not at the *beginning*. WSJT-X shows the line when transmission begins.
2. **Time format.** The row's timestamp renders as `HH:mm:ss` (e.g. `04:15:30`), while every RX decode renders as `HHmmss` (e.g. `041530`). The self-TX row visually mismatches the rest of the list.

## Current implementation

- **Emission:** `TxOrchestrator.runTxBody()` emits `_txLog` only **after** `playback.playBlocking(...)` returns, and only **`if (result)`** (a fully successful, non-halted transmit).
  - `app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt:373`
- **Row construction:** `OperateViewModel` collects `txOrchestrator.txLog` and builds a `DecodeRow`. The time is formatted via `Instant.ofEpochMilli(ev.utcMillis).atZone(UTC).toLocalTime().withNano(0).toString()`, which yields `"HH:mm:ss"`.
  - `app/src/main/java/net/ft8vc/app/OperateViewModel.kt:322`
- **RX time format (the target):** `SimpleDateFormat("HHmmss", Locale.US)` with `timeZone = UTC`.
  - `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt:125`

## Design

### Change 1 — Emit the self-TX row at the start of transmit

Move the `_txLog.tryEmit(TxLogEvent(...))` call from after playback (`TxOrchestrator.kt:373`, success-only) to the **commitment point** in `runTxBody()` — immediately where the orchestrator sets `isTransmitting = true` / `txStatus = "TX: $message"` (`TxOrchestrator.kt:334`).

- The `if (result)` guard is dropped: the row reflects the *start* of transmission, not its outcome. If the operator halts mid-transmit after the row appears, the row stays (accepted behavior, matches WSJT-X).
- `utcMillis` is captured at this commitment moment (≈ slot start), so the row's displayed time reflects when TX began — consistent with how RX decodes are stamped at slot start.
- **Placement relative to the fail-closed RF re-check:** the emit goes *after* the `RX_ONLY` / `EMERGENCY_HALT` re-check early-returns (`TxOrchestrator.kt:321-333`). If TX is blocked before any PTT/audio goes out (Digirig disconnected, safety halt), **no row is emitted** — nothing was transmitted, so nothing appears. The row appears only once we actually commit to keying PTT.

The post-playback emit block is removed entirely; the trailing `_slice.update { ... txStatus = "Sent"/"halted" }` status update is unaffected and stays where it is.

### Change 2 — Match the RX time format

In `OperateViewModel`, replace the `toLocalTime()...toString()` formatting with the same `HHmmss` UTC format RX decodes use, stamped from `ev.utcMillis`. The synthetic row's `timeUtc` becomes e.g. `"041530"` instead of `"04:15:30"`.

Implementation note: format from `ev.utcMillis` in UTC. Either a `DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC)` applied to the `Instant`, or reuse of the same `HHmmss` UTC convention as `DecodeController`. No shared helper is required for two call sites, but the format string and zone must match RX exactly.

## Data flow (unchanged except emit timing)

`runTxBody` (commit-to-key) → `_txLog.tryEmit(TxLogEvent{utcMillis, freqHz, message})` → `OperateViewModel` collector → builds `DecodeRow(source = Tx, timeUtc = HHmmss(utcMillis), ...)` → `decodeController.appendSyntheticRow(row)` → decode list.

`TxLogEvent`'s shape is unchanged. `DecodeRow`'s shape is unchanged. Only (a) *when* the event fires and (b) the `timeUtc` string format change.

## Testing

- `TxOrchestratorTest.txLog_emits_on_successful_transmit` continues to pass (a successful transmit still emits with the correct `freqHz` / `message`). Its intent broadens from "emits on success" to "emits at TX start"; rename accordingly.
- **Add:** a case asserting the row IS emitted even when playback returns halted/false (emit happens before the playback outcome is known).
- **Add:** a case asserting NO event is emitted when the pre-key RF re-check blocks the transmit (`RX_ONLY` or `EMERGENCY_HALT` set before `runTxBody` commits).
- **Time format:** assert the synthetic `DecodeRow.timeUtc` matches `HHmmss` (6 digits, no colons). Covered where the row is constructed/collected, or via a focused unit check on the formatting.

## Out of scope

- No change to RX decode formatting, row layout/rendering, or `DecodeRow` fields.
- No change to PTT/CAT/safety semantics — the fail-closed re-check and watchdog/timeout layers are untouched; only the `_txLog` emit point moves.
- No change to behavior on the reference FT-891 + Digirig path beyond the two stated UI deltas.
