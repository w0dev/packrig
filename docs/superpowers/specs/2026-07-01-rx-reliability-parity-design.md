# RX Reliability Parity — Design

**Date:** 2026-07-01
**Status:** Approved (brainstorming complete)
**Phase:** 3 of 5 in the Field Readiness milestone (phase 1: POTA activator
logging `a1a8d3e..e7544c3`; phase 2: QSO sequencing upgrades
`8e7cd47..2785fdd`)

## Problem

Three RX-side gaps cost decodes and diagnosis time in the field:

1. `nativeDecode` calls `hashtable_init()` on every decode pass
   (`ft8-native/src/main/cpp/ft8_jni.cpp`), wiping the callsign hash
   table each slot. Hashed nonstandard-call forms (`<PJ4/K1ABC>`) can
   only resolve if the full call was heard in the SAME slot — WSJT-X
   keeps the table for the whole session. Portable/DX stations display
   as `<...>` far more often than in WSJT-X or FT8CN.
2. The decode band stops at 3000 Hz (`cfg.f_max = 3000`). On busy
   weekends stations sit at 3000–4000 Hz; WSJT-X decodes them, we
   silently don't. The waterfall display and TX-tone ceiling share the
   3000 Hz cap.
3. FT8 decoding degrades hard past ~2 s of clock error and dies beyond
   that, but the app gives no indication that the phone clock is the
   problem — the field symptom is just "no decodes".

## Requirements

- Hashed callsigns resolve across slots for the life of the process;
  entries not re-heard within ~10 minutes age out (WSJT-X-equivalent
  practical behavior). The table can never hard-hang the decoder.
- Decode, waterfall display, and TX-tone placement all extend to
  4000 Hz.
- A status chip warns the operator when the estimated clock offset
  (median decode DT vs nominal) exceeds ±1.0 s, with the signed value.
- All three changes are unconditional (no settings). RX-only: TX/CAT
  code paths untouched; no changes outside `ft8-native/`, `audio/`
  (SpectrumProcessor constant), `core/`, and `app/` display/plumbing.

## Design

### 1. Callsign hash table: persist + evict

- Remove the `hashtable_init()` call from `nativeDecode`. The global
  table (guarded by the existing `g_decodeMutex`) now persists for the
  process lifetime.
- Port upstream ft8_lib's `hashtable_cleanup(uint8_t max_age)` from
  `demo/decode_ft8.c` verbatim (age counter in bits 24–31 of the
  stored hash; entries older than `max_age` are freed; survivors'
  age increments). Call it once at the START of every `nativeDecode`
  with `kHashMaxAgeSlots = 40` (≈10 minutes at 15 s/slot). Our port
  already masks the age bits (`hash & 0x3FFFFF`) in add/lookup, so the
  scheme composes with the existing code.
- Defensive probe cap in `hashtable_add`: bound the linear-probe loop
  to `CALLSIGN_HASHTABLE_SIZE` iterations; on exhaustion, skip the add
  (degrade gracefully — never spin). Upstream lacks this guard; a full
  table would loop forever.
- New JNI hook `nativeClearCallsignTable()` (exposed on `Ft8Native` /
  `Ft8DecoderApi` as `clearCallsignTable()`): resets the table. Needed
  for test isolation — encode and decode share the global table, so
  the cross-slot decode test must clear state seeded by test encodes.
  Production does not call it.

### 2. Passband to 4000 Hz

- `ft8_jni.cpp`: `cfg.f_max` 3000 → 4000; `kMaxCandidates` 140 → 180
  (headroom proportional to the wider band so high-band signals are
  not crowded out of the candidate list).
- `SpectrumProcessor` default `maxFreqHz` 3000 → 4000. The waterfall
  bin count, axis labels, tap/drag-to-tone mapping, and TX marker all
  derive from this one value — no other display changes.
- `SettingsRepository.setTxToneHz` coercion ceiling 3000 → 4000.
- `SnrEstimator` noise-floor band (200–3000 median) intentionally
  unchanged: the median band noise remains representative, and
  candidates above 3000 measure their tone power at their actual
  frequency against it.
- CPU budget: decode-pass duration is already surfaced as
  `lastDecodePassDurationMs`; the field gate checks it stays well
  inside the slot budget on the reference phone.

### 3. Median-DT clock-health indicator

- New pure core class `ClockOffsetEstimator`:
  - `onSlotDts(dts: List<Float>)` — feed the DT values of one FULL
    decode pass (early-pass results are excluded by the caller to
    avoid double counting).
  - Rolling window: the last 4 slots' DTs, pooled.
  - `offsetSeconds: Float?` — `median(pooled) − NOMINAL_DT_S` when the
    pool has ≥ 4 samples, else null.
  - `NOMINAL_DT_S = 0.5f` (FT8 transmissions nominally begin 0.5 s
    into the slot; our decoder's DT is measured from the slot-aligned
    buffer start). The implementation plan validates this constant
    against the existing calibration WAV before trusting it; if the
    measured nominal differs, the constant is corrected to match.
  - `reset()` — cleared when capture restarts.
  - Thresholds as constants: `WARN_S = 1.0f`, `SEVERE_S = 2.0f`.
- `DecodeController` owns an instance, feeds it in the FULL-pass
  branch of `decodeSlot`, resets it in `reset()`, and exposes
  `clockOffsetSeconds: Float?` on `DecodeSlice`.
- `OperateStatusBar`: chip visible only when `|offset| ≥ WARN_S`;
  amber below `SEVERE_S`, red at/above. Text `Clock +1.4s` (signed,
  one decimal). Tooltip: "Phone clock differs from FT8 band time — fix
  in Android date & time settings". Display only; the slot grid and TX
  timing are untouched.
- Measurable range limitation (documented, accepted): DT can only
  observe offsets within the decoder's sync window (~±3 s) and only
  modulo the 15 s slot grid. A clock off by more than ~3 s produces no
  decodes at all — the chip cannot appear, and "no decodes" remains
  the symptom. The chip targets the common 1–3 s cellular-drift case.

## Testing

- Core: `ClockOffsetEstimatorTest` — window rolling (5th slot evicts
  the 1st), min-sample gating (3 samples → null), median robustness to
  one outlier DT, sign convention (fast clock → positive offset),
  reset behavior.
- Instrumented (`ft8-native` androidTest, device gate):
  - Cross-slot hash resolution: clear table; decode a synthesized slot
    containing `CQ PJ4/K1ABC EM26`; decode a second synthesized slot
    containing a message with only the hashed form; assert the decoded
    text shows `<PJ4/K1ABC>` resolved (full call present).
  - Existing decode + SNR calibration tests re-run green at
    f_max 4000 / 180 candidates.
- Parity: all changes RX-only and unconditional; TX/CAT untouched;
  existing QSO/golden-trace suites unaffected.

## Field verification (promotion gates)

On the reference FT-891 + Digirig: (i) busy-band session with decodes
observed between 3000–4000 Hz, including working one with the TX tone
placed above 3000; (ii) a compound-call station observed resolving
from its hashed form using an earlier slot's information; (iii) disable automatic time and skew
the phone clock +2 s → red chip reads ≈ +2.0 s (sign and magnitude),
then restore automatic time and confirm the chip clears within
~4 slots; (iv) `lastDecodePassDurationMs` stays under 3 s throughout
at 4000 Hz / 180 candidates.

## Out of scope

- Applying the clock offset to the slot grid (RX or TX) — future
  phase if field data shows it's needed.
- Multi-pass/subtraction decoding, AP decoding (accepted structural
  gap vs WSJT-X; documented in the milestone assessment).
- Settings/toggles for any of the three changes.
- Persisting the hash table across process restarts.
