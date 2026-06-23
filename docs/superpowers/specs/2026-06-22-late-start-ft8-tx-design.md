# Late-Start FT8 TX — Design

**Date:** 2026-06-22
**Status:** Design approved, ready for implementation plan
**Reference implementation:** [POTACAT v1.8.14](https://github.com/Waffleslop/POTACAT) (behavior reference; not a port)
**Companion follow-up phase:** Early-decode / streaming partial-slot decode (tracked separately — must ship after this feature is field-verified)

## Why

Today, FT8VC decodes against the full 15 s sample buffer after the slot ends, so a partner's CQ surfaces ~0.5–1.0 s into the *next* slot. By the time the operator reads it and taps Answer, several seconds have elapsed. Under the v1.0 TX path, that Answer queues to the *next-next* TX slot — 30 s after the CQ ended, by which point the CQer has usually already worked someone else.

Late-start TX lets the Answer go out in the *current* slot by truncating the on-air waveform from the front: skip the leading symbols that have already passed, key PTT, play the remaining symbols on the original symbol clock so the tail lands on the normal 15 s boundary. The full FEC-encoded message is always synthesized; only the on-air audio is clipped. The receiver re-syncs on the middle and end Costas arrays.

This restores the hunt-and-pounce ergonomic on the FT-891 + Digirig reference rig without touching the decode path. Pairing late-TX with early-decode (a future phase) closes the loop end-to-end, but late-TX delivers value standalone — the cases where the operator sees the CQ in time but can't reply in time are common today.

## Goal

The operator can tap Answer (or Manual TX, or trigger auto-answer) up to **7.000 s** into a 15 s slot and the next transmission goes out on that slot, with the tail landing on the normal 15 s boundary. Past 7.000 s, behavior is unchanged from v1.0 (silent queue to next slot). The feature ships behind a Settings toggle defaulted ON.

The v1.0 RX/TX/CAT/QSO contract on the reference FT-891 + Digirig rig is preserved byte-for-byte when the toggle is OFF (PARITY-01 escape hatch) and for any tap with `t_in_slot < 1.34 s` when the toggle is ON (the v1.0 code path is reused unchanged for sub-symbol-period lateness).

## Locked decisions

| # | Decision | Choice |
|---|---|---|
| 1 | Scope | Late-Start TX only. A-priori (AP) decoding deferred. Early-decode tracked as separate follow-up. |
| 2 | Late-TX cutoff | 7.0 s (derived from symbol math; see [Symbol-clock math](#symbol-clock-math)) |
| 3 | Trigger paths | Answer/Resume, Manual TX, and auto-answer — all funnel through existing `TxOrchestrator.transmit()` |
| 4 | Past-cutoff behavior | Silent queue to next slot — same as v1.0, no snackbar |
| 5 | In-window UI | Fully transparent — no countdown, no chip, no Compose changes |
| 6 | Settings toggle | "Late-start TX (up to 7s into slot)" — default ON, persisted via DataStore, escape hatch |
| 7 | Floor (sub-symbol lateness) | `t_in_slot < 1.34 s` → unchanged v1.0 path runs. Late-TX path only activates when `offsetSymbols ≥ 1`. |

## Symbol-clock math

FT8 framing in this codebase (verified by reading `ft8_jni.cpp:267-278`):

- Sample rate: `12_000 Hz`
- Symbol period: `FT8_SYMBOL_PERIOD = 0.160 s`
- Samples per symbol: `nSpsym = round(12000 × 0.160) = 1920`
- Symbols per transmission: `FT8_NN = 79` (Costas at 0–6, 36–42, 72–78; data interleaved)
- Waveform duration: `79 × 1920 / 12000 = 12.640 s`
- Slot duration: `FT8_SLOT_TIME = 15.000 s` → `15 × 12000 = 180_000` samples
- **Silence padding (each side, computed in `nativeEncode`):** `(180000 - 79 × 1920) / 2 = 14160 samples = 1.180 s`
- **Waveform starts at:** `slot_start + 1.180 s`
- **Waveform ends at:** `slot_start + 1.180 + 12.640 = slot_start + 13.820 s`

The **7.0 s cutoff** is derived from this framing:

```
offsetSymbols(t=7.0) = ceil((7.000 - 1.180) / 0.160)
                    = ceil(36.375)
                    = 37
waitMs(t=7.0)       = round((1.180 + 37 × 0.160 - 7.000) × 1000)
                    = round(0.100 × 1000)
                    = 100 ms
```

At `t_in_slot = 7.0 s`, we've truncated up through symbol 36 (the first symbol of the middle Costas array at symbols 36–42). Symbols 37–42 of the middle Costas survive, and the **end Costas array (symbols 72–78) survives entirely intact** — giving the receiver two anchors for late re-sync. This is conservatively more robust than POTACAT v1.8.14's framing (which uses 0.5 s leading silence and truncates further into the middle Costas at its 7.0 s cutoff); on-air verification on the FT-891 + Digirig reference rig locks in the empirical evidence (see [Field verification](#field-verification)).

The **1.34 s floor** is one symbol period past waveform start (`1.180 + 0.160 = 1.340`). Any tap before then computes `offsetSymbols = 0` and routes through the unchanged v1.0 TX path. The v1.0 path already includes the 1.180 s of pre-waveform silence inside its 15 s buffer, so a tap at e.g. `t = 0.5 s` simply means the v1.0 path plays silence for the first 0.680 s of remaining slot and then emits the full waveform — identical to today.

## Architecture

Three files change. Everything else is unchanged.

### `ft8-native/src/main/cpp/ft8_jni.cpp`

- `synth_gfsk` grows an `offset_symbols` parameter. When `> 0`, it skips emitting PCM for the first `offset_symbols` symbols. When `0`, an early-return takes the byte-identical v1.0 path.
- The `encode` JNI entry point grows the same parameter.
- `ftx_message_encode` and `ft8_encode` continue to run over all 79 symbols regardless of offset — **FEC is always complete**. Only the audio synthesis is truncated.

### `ft8-native/src/main/java/net/ft8vc/ft8native/Ft8Native.kt`

- `Ft8Native.encode(message, freqHz, sampleRate, offsetSymbols: Int = 0)` — defaulted parameter so all existing call sites compile unchanged.
- Returns a `ShortArray` of `(79 - offsetSymbols) × samplesPerSymbol` 12 kHz samples.

### `app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt`

- New private helper:
  ```kotlin
  internal fun computeLateTxPlan(
      tInSlotMs: Long,
      toggleEnabled: Boolean
  ): LateTxPlan

  sealed interface LateTxPlan {
      object Normal : LateTxPlan       // route through unchanged v1.0 path
      object Deferred : LateTxPlan     // queue to next slot (existing path)
      data class Late(
          val offsetSymbols: Int,
          val waitMs: Long
      ) : LateTxPlan
  }
  ```
- `transmit()` calls this helper at entry, branches on the result.
- The existing 4-layer PTT defense wraps the entire flow unchanged — late-TX runs *inside* the safety envelope, never parallel to it.

### `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt` + Settings screen

- One new DataStore key: `late_start_tx_enabled`, default `true`.
- `SettingsBridge.slice` exposes it; `TxOrchestrator` reads it through the slice it already collects.
- One new row under an existing Settings group: "Late-start TX (up to 7s into slot)".

### Files that explicitly do NOT change

- `QsoSessionController` — already calls `TxOrchestrator.transmit()`; late-TX is invisible to it.
- `DecodeController`, `RigSession`, `SlotCollector`, `UsbAudioCapture`, `UsbAudioPlayback` — RX path and audio I/O unchanged.
- `OperateScreen`, `DecodeListPanel`, the Answer/Resume button — fully transparent UI means zero Compose changes.
- `Ft8Native.version()` / native-lib load handshake from Phase 5 — unchanged.

## Data flow & timing

Concrete walk-through for the operator tapping Answer at `t_in_slot = 3.200 s` with the toggle ON:

1. **Tap → `TxOrchestrator.transmit(message)`.** Same entry point as v1.0. Existing license-gate / `AppRfState.READY` / `transmitInProgress` guards run unchanged. Capture `tapTsMs` from monotonic clock.

2. **Compute the plan.** `computeLateTxPlan(tapTsMs - slotStartMs = 3200, toggleEnabled = true)`:
   - `offsetSymbols = ceil((3.200 - 1.180) / 0.160) = ceil(12.625) = 13` — round **up** to land on the next safe symbol boundary (never emit a fractional-symbol head).
   - `waitMs = round((1.180 + 13 × 0.160 - 3.200) × 1000) = round(0.060 × 1000) = 60 ms`.
   - Returns `LateTxPlan.Late(offsetSymbols = 13, waitMs = 60)`.

3. **Encode (on encode dispatcher).** `Ft8Native.encode(message, txFreqHz, 12000, offsetSymbols = 13)` → JNI runs full `ftx_message_encode` + `ft8_encode` over all 79 symbols (FEC intact), then `synth_gfsk` emits PCM for symbols `[13, 79)` = 66 symbols × 1920 samples/symbol = **126,720 samples** (≈ 10.56 s of 12 kHz audio). No silence padding — the v1.0 padding existed because the v1.0 path was always called at slot boundary; late-TX is called mid-slot so the operator wants audio *now*.

4. **Snapshot-to-key drift check (race window mitigation).** Re-read the monotonic clock. If `(nowMs - tapTsMs) > 80 ms` (half a symbol period), abort late-TX and fall through to `transmitNextSlot`. Bounds the worst-case drift between plan and key.

5. **Symbol-clock wait.** `delay(waitMs)` on the encode dispatcher. ~20 ms here. Bounded above by `< 1 symbol period (160 ms)`, so the watchdog (250 ms) is never at risk.

6. **PTT key.** Existing `RigSession.keyPtt()` via the same call as v1.0.

7. **Play.** `playback.playBlocking(pcm, outputId)` — blocks for ~10.56 s. Watchdog timer is 250 ms (per-key); the existing `withTimeoutOrNull(SLOT_DURATION_MS + 500 = 15_500)` still bounds the whole operation. Tail lands at slot-relative `1.180 + 13 × 0.160 + 10.560 = 13.820 s`, **identical to a normal-start TX waveform endpoint**.

8. **PTT release.** Existing `try-finally` + `AutoCloseable` paths fire unchanged. 4-layer defense intact.

### Timing invariants the spec asserts

| Invariant | Why |
|---|---|
| `offsetSymbols` is a whole number of symbols (no fractional-symbol head) | Receiver Costas re-sync depends on whole-symbol alignment |
| `waitMs ∈ [0, 160)` | Always less than one symbol period, always well under the 250 ms watchdog |
| Tail end of waveform is identical to v1.0 for any `offsetSymbols` | We only trim from the front; the tail never extends past the v1.0 endpoint |
| For `t_in_slot < 1.34 s`, `computeLateTxPlan` returns `Normal` | Floor decision; v1.0 path runs unmodified |
| For `t_in_slot > 7.0 s`, `computeLateTxPlan` returns `Deferred` | Cutoff decision; v1.0 `transmitNextSlot` queueing runs |
| Snapshot-to-key drift > 80 ms aborts and defers | Belt-and-suspenders for clock surprises (NTP jumps, GC pauses) |

## Error handling & failure modes

| Scenario | Behavior | Why |
|---|---|---|
| Toggle OFF | `computeLateTxPlan` returns `Normal` → v1.0 path runs unchanged | PARITY-01 escape hatch |
| `t_in_slot > 7.0 s` | `Deferred` → existing `transmitNextSlot` queueing (silent, v1.0 behavior) | Locked decision |
| `t_in_slot < 1.34 s` | `Normal` → v1.0 path runs unchanged | Floor decision |
| Snapshot-to-key drift > 80 ms | Abort late-TX, fall through to `transmitNextSlot` | Race-window mitigation |
| Encode fails / returns empty `ShortArray` | Existing TxOrchestrator failure path: surface existing snackbar, release PTT (never keyed yet), no retry | Same as v1.0 |
| Playback throws mid-stream | Existing 4-layer PTT defense fires unchanged | Phase 5 invariant preserved |
| Watchdog (250 ms) fires | Forces PTT release, surfaces existing "TX safety halt" snackbar + chip | Phase 5 invariant preserved |
| `withTimeoutOrNull(SLOT_DURATION_MS + 500)` trips | Existing path: cancel + release PTT | Phase 5 invariant preserved (truncated waveform finishes earlier than v1.0, so this is **less** likely to trip, never more) |
| USB detach during late-TX | Existing `AppRfState.EMERGENCY_HALT` transition fires; PTT released idempotently; license re-check on reconnect | Phase 5 SAFETY-02 invariant preserved |
| App killed mid-late-TX | Existing `onCleared()` of TxOrchestrator forces PTT release | Phase 5 invariant preserved |
| Synthetic invalid plan (e.g. `offsetSymbols ≥ 79`) | Log + fall through to `transmitNextSlot`. No PTT key, no user-facing error. | Defense-in-depth; shouldn't happen given cutoff math |

### Things that explicitly do NOT change

- The 4 PTT-defense layers from Phase 5: `try-finally`, `AutoCloseable`/`use`, `withTimeoutOrNull(SLOT_DURATION_MS + 500)`, 250 ms watchdog
- The `AppRfState { READY, RX_ONLY, EMERGENCY_HALT }` state machine
- The license-acknowledgment gate (`AppRfState.READY` precondition for `transmit()`)
- `RigSession.keyPtt()` / `releasePtt()` — same calls, same order, same finally-block placement
- Native lib load/version check (Phase 5 SAFETY-04)

## Testing

### Unit tests (JVM, no Android)

**`Ft8NativeLateTxTest`** in `ft8-native/src/test/`:

- `encode(msg, 1500.0f, 12000, 0)` is sample-identical to the pre-parameter v1.0 path — regression guard for the early-return branch
- `encode(msg, 1500.0f, 12000, 17)` returns `(79 - 17) × samplesPerSymbol` samples that equal the tail of the `offsetSymbols=0` output within ±1 LSB per sample
- `encode(msg, 1500.0f, 12000, 79)` returns an empty `ShortArray` (degenerate but defined)
- FEC bits in the synthesized waveform are unchanged across all `offsetSymbols` values: decode the truncated output back to symbols and confirm symbols `[offsetSymbols, 79)` match the full encode

**`LateTxPlanTest`** in `app/src/test/` (pure logic, no fakes needed):

- Floor: `t_in_slot ∈ [0.000, 0.660)` → `Normal` (parameterized over a sweep)
- Late window: `t_in_slot ∈ [0.660, 7.000]` → `Late(offsetSymbols ∈ [1, 41], waitMs ∈ [0, 160))`
- Cutoff: `t_in_slot ∈ (7.000, 15.000)` → `Deferred`
- Toggle OFF: every input → `Normal` regardless
- Invariant property test: for any returned `Late(n, w)`, `n ∈ [1, 37]`, `w ∈ [0, 160)`, and `1.180 + n × 0.160 - t_in_slot - (w/1000) ∈ [0, 0.160)` within float epsilon

### Controller integration tests (JVM, with fakes)

**`TxOrchestratorLateTxTest`** extends existing `TxOrchestratorTest`, uses `FakeRigBackend` + `FakeUsbAudioPlayback`:

- `t_in_slot = 6.500 s, toggle ON`: PTT keyed within 50 ms of expected symbol-boundary moment; `FakeUsbAudioPlayback` receives a `ShortArray` of expected length; PTT released within 250 ms of waveform end
- `t_in_slot = 7.500 s, toggle ON`: TX defers to next slot, no PTT key in current slot
- `t_in_slot = 3.000 s, toggle OFF`: routes through v1.0 path, full-length `ShortArray`, normal-start timing
- `t_in_slot = 0.400 s, toggle ON`: floor → v1.0 path, full-length `ShortArray`
- Snapshot-to-key drift simulated > 80 ms (via FakeClock advance between snapshot and key): aborts, defers to next slot
- Plan rejected (synthetic `offsetSymbols = 80`): logs, defers, no PTT key

**`TxOrchestratorPttSafetyLateTxTest`** — re-runs each of the 4 PTT-safety scenarios from the existing `TxOrchestratorTest` *with a late-TX call* injected, asserting each layer still fires:

- Layer (a) try-finally: `playback.playBlocking` throws synchronously → PTT released in finally
- Layer (b) AutoCloseable: TX session `close()` runs even on cancellation
- Layer (c) `withTimeoutOrNull(SLOT_DURATION_MS + 500)`: timeout fires → cancel + release
- Layer (d) 250 ms watchdog: stuck PTT → forced release + safety-halt snackbar
- USB detach mid-late-TX → `AppRfState.EMERGENCY_HALT`, idempotent release

### Behavior-parity gate (CI)

Golden-trace replay (Phase 0 FOUND-06):

- Run with `late_start_tx_enabled = false`: must produce byte-identical state-transition output to the Phase 0–7 baseline. This is the **PARITY-01 escape hatch is real** assertion.
- Run with `late_start_tx_enabled = true`: must also pass. The baseline trace contains no late-TX events (captured pre-feature), but every tap in the baseline runs through `LateTxPlan.Normal` (sub-floor) or `Deferred` (post-cutoff) and must produce identical output.

### Field verification (promotion-to-`main` gate)

Recorded session on the reference FT-891 + Digirig under `.planning/field-sessions/late-tx-<date>/`:

- **Mandatory:** At least one QSO completed using late-TX, where the recorded trace shows `PTT keyed at slot-relative time t > 3.0 s` (measurable from trace timestamps, not operator self-report) **AND** the partner's next decode confirms our message was received and decoded
- **Mandatory:** At least one tap at `t > 7.0 s` confirming the silent-queue-to-next-slot path fires correctly
- **Mandatory:** At least one normal-timing tap (`t < 1.34 s`) during the session confirming the v1.0 path still runs under toggle ON
- **Mandatory:** Toggle OFF for one full QSO cycle, confirming v1.0 behavior end-to-end on the reference rig
- **Recompose-baseline (Phase 0 FOUND-08):** Operate-tab recomposition count over one full slot cycle does **not** exceed the Phase 0 baseline at all (no relaxation — the feature has zero new Compose surface)

## Constraints

- **Pin stays pinned.** ft8_lib remains at kgoba `9fec6ca39886edbf96f4f5e71edc76da5074e871` via FetchContent. No upstream-commit churn in this phase.
- **PTT-safety primacy.** Late-TX runs inside the existing 4-layer defense; the timeout's upper bound remains the full slot duration plus 500 ms. The truncated waveform finishes earlier — that is fine; the watchdog still forces release if anything is stuck.
- **License gate preserved.** Late-TX is a TX feature; the existing `AppRfState.READY` precondition continues to block TX before license acknowledgment. The Settings toggle is visible regardless but has no effect until license is acknowledged.
- **No new top-level screen / tab** (PARITY-03). One new Settings row under an existing group; no new Compose surfaces.
- **No new top-level dependencies** (CLAUDE.md milestone rule).
- **minSdk 28, NDK r29, JVM 17** unchanged.

## Boundaries

### In scope

- JNI: `synth_gfsk` + `encode` accept an `offset_symbols` parameter; v1.0 early-return branch when `0`
- Kotlin: `LateTxPlan` + `computeLateTxPlan` in `TxOrchestrator`; symbol-clock wait; snapshot-to-key drift abort
- Settings: one DataStore key, one Settings row
- Tests: unit (JNI + plan), integration (TxOrchestrator + PTT-safety re-run), golden-trace parity (toggle ON and OFF), on-air field session

### Out of scope

- **A-priori (AP) decoding** — deferred to its own phase. Late-TX is self-contained and ships first.
- **Early-decode / streaming partial-slot decode** — tracked as separate follow-up phase. Must ship after late-TX is field-verified.
- **Decode-list legibility** (own-TX rows, column header, worked-before coloring) — owned by a separate earlier phase.
- **Late-start CQ** — initial CQ from a cold partner-relationship is operationally counterproductive (receiver needs leading Costas for a cold lock). Late-TX only applies to Answer/Resume, Manual TX, and auto-answer flows.
- **Operator-tunable cutoff** — 7.0 s is locked to the symbol math (end Costas survival). If field evidence later supports a different threshold, that's a follow-on tweak.
- **TX truncation by fractional symbols** — sample alignment is always to a whole symbol boundary so receiver re-sync holds.
- **In-window UI affordances** (countdown, chip) — explicitly transparent.
- **Past-cutoff snackbar** — explicitly silent.
- **Bumping the pinned kgoba ft8_lib commit** — pin stays pinned.

## Acceptance criteria

- [ ] `Ft8Native.encode(msg, 1500.0f, 12000, 0)` is sample-identical to the pre-parameter v1.0 path
- [ ] `Ft8Native.encode(msg, 1500.0f, 12000, 13)` returns `(79 - 13) × 1920` samples matching the tail of the v1.0 (silence-padded) output's waveform region within ±1 LSB per sample
- [ ] FEC bits in synthesized waveform are unchanged across all `offsetSymbols` values
- [ ] `computeLateTxPlan` returns `Normal` for `t_in_slot < 1.34 s` (parameterized sweep)
- [ ] `computeLateTxPlan` returns `Late(n, w)` with `n ∈ [1, 37]`, `w ∈ [0, 160)` for `t_in_slot ∈ [1.34, 7.0] s`
- [ ] `computeLateTxPlan` returns `Deferred` for `t_in_slot > 7.0 s`
- [ ] `computeLateTxPlan` returns `Normal` for every input when toggle is OFF
- [ ] `TxOrchestrator` at `t_in_slot = 6.5 s` keys PTT within 50 ms of expected symbol-boundary moment and the truncated waveform tail lands within 50 ms of `slot_start + 13.820 s`
- [ ] `TxOrchestrator` at `t_in_slot = 7.5 s` defers to next slot (no PTT in current slot)
- [ ] Snapshot-to-key drift > 80 ms aborts late-TX and defers to next slot
- [ ] All 4 PTT-safety layers from Phase 5 still fire under the late-TX path (re-run scenarios in `TxOrchestratorPttSafetyLateTxTest`)
- [ ] Settings → "Late-start TX (up to 7s into slot)" defaults to ON; toggling OFF reverts to v1.0 "queue to next slot" behavior for taps after slot start
- [ ] Golden-trace replay passes against Phase 0 baseline with toggle forced OFF (byte-identical state transitions)
- [ ] Golden-trace replay passes with toggle forced ON (no late-TX in baseline, but `Normal` and `Deferred` paths must produce identical output)
- [ ] On-air session on the reference FT-891 + Digirig completes at least one QSO using late-TX (trace shows PTT keyed at `t > 3.0 s`) with partner-confirmed decode
- [ ] On-air session demonstrates the silent-queue-past-cutoff path (tap at `t > 7.0 s`) and the floor path (tap at `t < 1.34 s`) and the toggle-OFF path each at least once
- [ ] Operate-tab recomposition count over one full slot cycle does not exceed the Phase 0 FOUND-08 baseline at all
- [ ] Session recorded under `.planning/field-sessions/late-tx-<date>/` and referenced in the PR

## Open questions for the implementation plan

These do not block the design but should be resolved during `writing-plans`:

1. **`computeLateTxPlan` rounding direction.** The walk-through used `ceil` for `offsetSymbols` (round up to next safe boundary). Confirm in the plan whether `ceil` or `floor` is the right call for the boundary case `t_in_slot = exactly k × 0.160 + 0.500`. Both work; `ceil` is slightly more conservative (never emits a partial symbol from a barely-late tap).
2. **Where the slot-start clock lives.** `TxOrchestrator` needs `slotStartMs` to compute `t_in_slot`. It already collects from `QsoSessionController.slice` which carries `utcClock` / `secondsToNextSlot` / `slotIndex`. Confirm in the plan that the existing slice surface is enough or whether a new field is needed.
3. **Pre-parameter `encode` JNI symbol.** Add the parameter with a Kotlin default (`offsetSymbols: Int = 0`), or keep a separate no-arg JNI export and route through it for the v1.0 path? Default-arg is cleaner Kotlin; separate export is safer if we want absolute proof the v1.0 binary path is unreached when offset is 0.
4. **Test fixture for FEC-bit assertion.** `Ft8NativeLateTxTest` needs a known-good message + truncated-decode round-trip. Decide whether to use an existing golden-trace fixture or generate inline.

## Migration note

This design supersedes the GSD-format spec previously written at `.planning/phases/09-late-tx-ap-decode/09-SPEC.md`, which combined Late-TX with A-priori decoding. That spec should be removed (or marked superseded) before implementation begins.
