# Early-Decode (Streaming / Partial-Slot Decode) — Design

**Date:** 2026-06-22
**Status:** Design approved, ready for implementation plan
**Reference implementation:** [POTACAT v1.8.14](https://github.com/Waffleslop/POTACAT) (behavior reference; not a port)
**Companion prerequisite phase:** [Late-Start FT8 TX](2026-06-22-late-start-ft8-tx-design.md) — must ship and be field-verified before this work begins

## Why

Today FT8VC decodes against the full 15 s sample buffer after the slot ends, so a partner's CQ surfaces ~0.5–1.0 s into the *next* slot. Late-start TX (the companion phase) lets the operator answer up to 7.0 s into the slot, which is a real improvement — but it only helps if the operator *saw* the CQ in time. In a busy hunt scenario the read+decide window is still tight: roughly `[t = slot+0.5 s → t = slot+7.0 s] ≈ 6.5 s`. Often the partner who CQ'd at slot start has already been answered by someone else before our late-TX reply goes out.

Early-decode runs a second standard FT8 decode pass at slot-relative `t = 12.000 s` over the in-progress sample buffer, surfacing CRC-validated decodes ~2.5–3.0 s before the slot ends. Combined with late-TX, the read+decide window grows from ~6.5 s to ~9.5 s, and — because the QSO state machine consumes early decodes too — auto-answer can arm before the slot ends and fire on the very first symbol-clock-aligned boundary of the next slot.

The bedrock safety property does not change: an early decode is propagated only after **CRC-14 validates**, exactly like a full-slot decode. There is no "early decodes are flakier" failure mode — either the LDPC iteration converged and the CRC validated, or the candidate is discarded. This is the same property that makes POTACAT v1.8.14's early decode safe upstream.

## Goal

DecodeController fires one early decode pass per slot at slot-relative `t = 12.000 s` (snapshotting whatever PCM SlotCollector has buffered so far), then runs the existing full-slot pass at the boundary unchanged. Each unique decode reaches the UI list and the `_decodesOut` SharedFlow exactly once across the two passes — the dedup contract is load-bearing. The feature ships behind a Settings toggle defaulted ON. With the toggle OFF, the v1.0 single-pass timing returns byte-for-byte (PARITY-01 escape hatch).

The v1.0 RX/TX/CAT/QSO contract on the reference FT-891 + Digirig rig is preserved when the toggle is OFF, and preserved-modulo-the-additive-tolerance (early decodes appear in `_decodesOut` earlier, but never duplicate a key) when the toggle is ON.

## Locked decisions

| # | Decision | Choice |
|---|---|---|
| 1 | QSO-machine consumption | **Feed the QSO machine too.** CRC-14 is the safety property and is unchanged. Auto-answer can fire on an early-decoded CQ. Matches WSJT-X / FT8CN auto-seq + late-TX ergonomics. |
| 2 | Dedup contract | **One row, update-in-place.** Stable cross-pass `DecodeRow.id` derived from `(slotStart, freqBin, message)`. Per-slot `seenKeys` set tracks already-emitted decodes. Full-slot pass updates SNR/dT/freq in place for already-seen keys and **omits them from the emitted `DecodeBatch`**. |
| 3 | Trigger timing | **Single fixed trigger at slot-relative `t = 12.000 s`.** Deterministic, ~80 % of samples present, ~2.5–3.0 s of headroom for the operator's read window. Progressive multi-pass and operator-configurable offsets are explicit follow-ons. |
| 4 | Default state | **ON by default with Settings toggle to disable.** Matches the late-TX precedent and gives a reversible escape hatch for slow phones / long POTA sessions. |
| 5 | UI treatment | **Zero visual delta.** Early and full rows render pixel-identically. Compose snapshot test pins the contract; no chip, no opacity tweak, no debug marker. |
| 6 | AP interaction | **Standard decode only on the early pass.** AP (Phase 9 / late-TX companion) runs on the full-slot pass exactly as before. Combining early + AP is a follow-on if field evidence justifies the CPU cost. |
| 7 | Skip-if-too-few-samples | **Skip the early pass when the snapshot has < 80 % of the expected 12 s buffer** (< 115 200 samples at 12 kHz). Protects against early-pass firing immediately after capture starts / audio hot-swap. |
| 8 | CPU instrumentation | **Measure decode-pass wall-clock duration into `DecodeSlice` for tests and diagnostics — no console logging** (preserves CLAUDE.md "no console logging in production code" convention). |

## Architecture

Two files change in the core RX path; one accessor lands in `core/`; one Settings row is added. Everything else is unchanged.

### `core/src/main/java/net/ft8vc/core/SlotCollector.kt`

- New accessor: `fun snapshot(): ShortArray?`. Returns `buffer.copyOf(count)` when `count > 0`, otherwise `null`. Pure, no clock, no threading.
- The defensive copy is the contract: mutating the returned array must not affect a subsequent `onSlot` invocation.
- `add()` / `onSlot` / `reset()` semantics unchanged.

### `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt`

- New private scheduler — call it `EarlyDecodeScheduler` — that owns one `Job?` per in-progress slot. On each slot transition (detected via the existing `slotCollector.add(...) { samples, slotStart -> ... }` callback or a derived current-slot tracker), it cancels any still-pending early-pass job and schedules a new one via `scope.launch(decodeDispatcher) { delay(slotStart + 12_000ms - clock()) ; ... }`.
- On wake: read `settingsBridge.slice.value.earlyDecodeEnabled` (no-op if `false`), call `slotCollector.snapshot()`, skip if `null` or fewer than `EARLY_MIN_SAMPLES = SAMPLE_RATE_HZ * 12 * 0.80 = 115_200` samples, otherwise call `decodeSlot(snap, slotStart, source = DecodeRowSource.EARLY)`.
- `decodeSlot(...)` grows a `source: DecodeRowSource` parameter (defaults to `FULL` for the existing call site so the existing slot-boundary path is unchanged).
- Inside `decodeSlot`, a per-slot `seenKeys: MutableMap<Long, HashSet<Long>>` (slotStart → set of DecodeRow.id) is consulted before each row is inserted. New keys → insert + add to emitted `DecodeBatch`. Already-seen keys on a `FULL` pass → update in place (final SNR/dT/freq), **omit from the emitted `DecodeBatch`**. Already-seen keys on an `EARLY` pass → no-op (shouldn't happen given the single early trigger per slot, but the guard is cheap).
- `seenKeys` is evicted when the corresponding slot ages out of the `MAX_DECODE_ROWS` window (or via a simple `if (size > 4) removeOldest()` heuristic — exact bound is a plan-phase decision).
- `decodeSlot` wraps the `decoder.decode(...)` call in `measureTimeMillis { ... }`; updates `DecodeSlice.lastDecodePassDurationMs` and `lastDecodePassSource`.

### `app/src/main/java/net/ft8vc/app/DecodeRow.kt`

- `DecodeRow.id` derivation changes from `slotStartEpochMs * 1000L + indexInSlot` to a stable hash of `(slotStartEpochMs, round(freqHz / FREQ_BIN_HZ).toLong(), messageText.trim())` where `FREQ_BIN_HZ = 6.25` (one FT8 tone bin).
- A new `source: DecodeRowSource` field is added (enum `EARLY | FULL`), defaulted to `FULL` for source-compatibility with existing constructors. **Rendering must not branch on it** (R5 invariant — Compose snapshot test pins the contract).
- The exact hash function is a plan-phase decision (`Objects.hash`-based or `Long`-packing) but must be deterministic and avoid collisions within the per-slot decode set (≤ ~50 decodes/slot in practice).

### `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt` + Settings screen

- One new DataStore key: `early_decode_enabled`, default `true`.
- `SettingsBridge.slice` exposes it as `earlyDecodeEnabled: Boolean`.
- One new row under the existing "Advanced decoding / TX" group (introduced by the late-TX phase): **"Early decode (CQs ~3s sooner)"** with subtitle "Runs an extra decode pass partway through each slot."

### Files that explicitly do NOT change

- `QsoSessionController` — already collects `_decodesOut`; early decodes flow through the same `DecodeBatch` channel, deduped at source. Auto-answer logic is unchanged.
- `TxOrchestrator`, `RigSession`, `UsbAudioCapture`, `UsbAudioPlayback`, native lib — not touched. JNI already accepts arbitrary-length PCM and builds the waterfall from it, so no native changes are required.
- `OperateScreen`, `DecodeListPanel` — zero visual delta. The new `DecodeRow.source` field exists but does not affect rendering.

## Data flow & timing

Concrete walk-through for one slot with the toggle ON, a CQ landing at slot-relative t≈3 s, and the operator armed for auto-answer:

1. **Slot starts at wall-clock `slotStart`.** Audio capture is feeding `DecodeController.onFrames(frames)` continuously. SlotCollector buffers samples. EarlyDecodeScheduler schedules a one-shot at `slotStart + 12 000 ms` on `decodeDispatcher`.

2. **Partner CQ arrives at `slot+3 s`.** Symbols accumulate in SlotCollector's buffer along with everything else on the band.

3. **`slot + 12.000 s` — EARLY pass.** Scheduler wakes. Reads `settingsBridge.slice.value.earlyDecodeEnabled = true`. Calls `slotCollector.snapshot()` → `ShortArray` of ~144 000 samples (12 s × 12 kHz). Sample count ≥ 115 200 → proceed. Launches `decodeSlot(snap, slotStart, source = EARLY)` on the existing `decodeDispatcher`.

4. **JNI runs.** `Ft8DecoderApi.decode(snap, 12000)` → `ftx_find_candidates` + `ftx_decode_candidate` build a waterfall and run LDPC + CRC. Returns N CRC-validated decodes, including the partner's CQ.

5. **Dedup + emit (EARLY).** For each result, compute `stableId = hash(slotStart, round(freqHz/6.25), messageText)`. None are in `seenKeys[slotStart]` (first pass of this slot) → insert all into `DecodeSlice.decodes` (prepended, capped at `MAX_DECODE_ROWS`), add all stableIds to `seenKeys[slotStart]`, build `DecodeBatch` containing all N decodes, `emit` on `_decodesOut`. `DecodeSlice.lastDecodePassDurationMs` updates; `lastDecodePassSource = EARLY`.

6. **`slot + ~12.5 s` — UI shows the CQ; QSO machine arms.** DecodeListPanel re-renders the new row (Compose `key = { it.id }` keeps stability). `QsoSessionController` collecting `_decodesOut` matches the CQ against auto-answer criteria and arms a reply.

7. **`slot + 15.0 s` — slot boundary, FULL pass.** SlotCollector's existing `onSlot` callback fires with the full 180 000-sample buffer. `decodeSlot(samples, slotStart, source = FULL)` runs.

8. **Dedup on FULL.** For each result, look up `stableId` in `seenKeys[slotStart]`. The partner's CQ key is already present → update the existing row in place (final SNR, final dT, final freq), do **not** insert a new row, do **not** add to the emitted `DecodeBatch`. Any *new* decodes the EARLY pass missed (weaker signals that needed the full 15 s buffer) get inserted and added to the batch normally.

9. **`slot + ~15.5 s` — emit (FULL).** `DecodeBatch` emitted on `_decodesOut` contains only the *newly discovered* decodes. The QSO machine never sees a duplicate of the early-armed CQ.

10. **`slot + 15.0 s` — TxOrchestrator (late-TX path).** Triggered by the auto-answer armed at step 6. With late-TX shipped, the reply goes out symbol-clock-aligned to the next slot boundary using the existing Phase-9 path.

### Timing invariants the spec asserts

| Invariant | Why |
|---|---|
| Early pass fires at `slotStart + 12.000 s ± 100 ms` | Deterministic operator-facing read window; tolerance covers dispatcher latency |
| Early pass is skipped when snapshot has < 115 200 samples | Guards against capture start / hot-swap edge cases where 12 s isn't yet buffered |
| A slot-boundary transition cancels a still-pending early job for the now-old slot | Prevents stale early passes from running on the wrong slot's data |
| Each unique decode is emitted on `_decodesOut` exactly once across both passes | Dedup contract; auto-answer never fires twice; load-bearing for parity |
| `DecodeRow.id` is stable across passes for the same `(slotStart, freqBin, message)` | Compose `key = { it.id }` keeps list items stable; in-place update doesn't re-insert |
| Full-pass update on an already-seen key preserves row position and id | UI does not flicker / re-order at slot boundary |
| For toggle OFF, no early job is scheduled or fires | PARITY-01 escape hatch |

## Error handling & failure modes

| Scenario | Behavior | Why |
|---|---|---|
| Toggle OFF | Scheduler is a no-op for all subsequent slots; full-slot path runs as v1.0 | PARITY-01 escape hatch |
| Snapshot returns `null` (no samples yet) | Skip silently | Audio just started |
| Snapshot has < 115 200 samples | Skip silently | Capture started mid-slot / hot-swap |
| EARLY pass still running at next slot boundary | Single-thread `decodeDispatcher` serializes: FULL pass queues behind EARLY pass. Both run; dedup contract still holds. | Acceptable on reference rig; observable via R7 duration instrumentation |
| EARLY pass throws | Existing `try { decoder.decode(...) } catch (t: Throwable) { failureCount++; decodeFailureRecent = true }` block fires — same path as FULL pass failures today | Existing reliability path (Phase 6 RELY-04) |
| Decode dispatcher pile-up over many slots | `failureCount` and `lastDecodePassDurationMs` surface it; no automatic mitigation in this phase | Single-threaded serialization is the safety property; explicit skip-if-busy is a follow-on |
| `seenKeys` map grows unbounded | Plan-phase bounds it (cap at 4 most-recent slots, or evict when the slot ages out of `MAX_DECODE_ROWS`) | Bounded memory; aged slots are gone from the UI anyway |
| Same key seen twice on EARLY pass (shouldn't happen with single trigger) | Guard no-ops the duplicate | Defense in depth |
| CRC-14 fails on EARLY candidate | `ftx_decode_candidate` discards it — same as FULL pass | Safety property unchanged |
| QSO machine receives an EARLY-decoded CQ and arms a reply, but FULL pass produces no matching key | Auto-answer still fires on next slot boundary (already armed). EARLY decode was CRC-valid; absence on FULL doesn't invalidate it. | CRC-14 is authoritative |
| Settings toggle flips OFF mid-slot with an EARLY pass already pending | Scheduler reads the slice value on wake; if flipped before wake, the pass no-ops. If flipped after wake but before dispatch, plan-phase decides whether to honor (likely yes — the pass already started). | Mostly cosmetic; rare |

### Things that explicitly do NOT change

- The 4 PTT-defense layers from Phase 5 (`try-finally`, `AutoCloseable`/`use`, `withTimeoutOrNull(SLOT_DURATION_MS + 500)`, 250 ms watchdog) — early decodes are RX-side; they reach TX only via the unchanged `_decodesOut` → `QsoSessionController` → `TxOrchestrator.transmit()` path
- The `AppRfState { READY, RX_ONLY, EMERGENCY_HALT }` state machine
- The license-acknowledgment gate (`AppRfState.READY` precondition for `transmit()`)
- `RigSession.keyPtt()` / `releasePtt()` ordering
- Native lib load / version check
- DecodeListPanel rendering — no branch on `DecodeRow.source`
- SlotCollector's existing `add` / `onSlot` / `reset` contract — only an additive `snapshot()` accessor

## Testing

### Unit tests (JVM, no Android)

**`SlotCollectorSnapshotTest`** in `core/src/test/`:

- `snapshot()` returns `null` before any samples are added
- After `add(144 000 samples of a fixed PCM, now)`, `snapshot()` returns an array of length 144 000 equal to the input
- Mutating the returned `ShortArray` does not affect a subsequent `onSlot` invocation
- Regression: existing `SlotCollectorTest` suite passes unchanged

**`DecodeRowKeyTest`** in `app/src/test/`:

- Two `Ft8DecodeResult`s with identical message text and freqHz within one 6.25 Hz bin (e.g. 1499.8 and 1500.4) produce equal `DecodeRow.id`
- Different message text → distinct id
- Different freqBin (e.g. 1497.0 vs 1506.0) → distinct id
- Same `slotStart`, same `(freqBin, message)` across two passes → equal id (the cross-pass-stability contract)

**`EarlyDecodeSchedulerTest`** using `FakeClock` and a `TestDispatcher`:

- With toggle ON, early pass fires exactly once per slot at `slotStart + 12.000 s ± 100 ms`
- With snapshot < 115 200 samples (simulated late capture start), early pass is skipped
- Slot-boundary transition cancels a still-pending early job for the now-old slot
- With toggle OFF, no early pass fires across 10 simulated slots
- Toggle flipped OFF between schedule and wake → pass no-ops

### Controller integration tests (JVM, with fakes)

**`DecodeControllerEarlyDedupTest`** uses an `Ft8DecoderFake` returning two overlapping result sets (one for the EARLY pass, a superset for the FULL pass), drives `decodeSlot` directly, and asserts:

- After both passes, `DecodeSlice.decodes` row count equals the *union* of unique decodes (not the sum)
- A test subscriber to `_decodesOut` records each unique key at most once across both passes
- An EARLY-only decode that the FULL pass also returns: row position and id unchanged, SNR/dT/freq updated
- A FULL-only decode (early pass missed it): newly inserted, emitted in the FULL batch normally
- An EARLY decode the FULL pass doesn't return: stays in the list with its EARLY-pass values
- `DecodeSlice.lastDecodePassDurationMs` and `lastDecodePassSource` reflect the most recent pass

**`DecodeListPanelEarlyParityTest`** (Compose snapshot):

- Render one row with `source = EARLY` and one row with `source = FULL`, both with identical SNR/dT/freq/message — assert zero pixel delta between snapshots
- Render a `source = FULL` row — assert zero pixel delta against the pre-Phase-10 v1.0 row baseline

**`EarlyDecodeLateTxIntegrationTest`** drives the combined Phase-9 + Phase-10 flow with `Ft8DecoderFake`, `FakeRigBackend`, `FakeUsbAudioPlayback`, and a `FakeClock`:

- Canned CQ in the partial-slot PCM
- At `slotStart + 12.000 s`, the EARLY pass discovers the CQ and emits a `DecodeBatch`
- `QsoSessionController` arms the auto-answer reply
- At the slot boundary, `TxOrchestrator.transmit(reply)` is invoked with `t_in_slot ∈ [0.0 s, 7.0 s]` (Phase 9 R2 path)
- PTT keys within 50 ms; symbol-clock-aligned waveform plays; tail lands on the v1.0 endpoint
- All four PTT-safety layers from Phase 5 still fire when this combined path is exercised under fault-injection (`TxOrchestratorPttSafetyEarlyDecodeTest` re-runs each fault scenario with the EARLY-armed trigger)

### Behavior-parity gate (CI)

Golden-trace replay (Phase 0 FOUND-06):

- Run with `early_decode_enabled = false`: must produce byte-identical state-transition output to the **post-Phase-9 baseline** (`.planning/field-sessions/late-tx-<date>/`). This is the **PARITY-01 escape hatch is real** assertion for this phase.
- Run with `early_decode_enabled = true`: must also pass, under the additive tolerance — EARLY decodes appear in `_decodesOut` earlier than the FULL pass would have produced them, but **never** duplicate a key. The 100-slot duplicate-check subscriber asserts zero key collisions across the trace.

### Field verification (promotion-to-`main` gate)

Recorded session on the reference FT-891 + Digirig under `.planning/field-sessions/early-decode-<date>/`:

- **Mandatory:** At least one QSO completed where the auto-answer was triggered by an EARLY-pass decode (operator confirms by inspecting trace timestamps — auto-answer-armed timestamp falls between `slotStart + 12.0 s` and `slotStart + 15.0 s`)
- **Mandatory:** Toggle OFF for one full QSO cycle, confirming v1.0 single-pass timing end-to-end
- **Mandatory:** Late-TX still works under combined load (toggle ON for both early-decode and late-TX, at least one QSO completed via the hunt-and-pounce loop)
- **Recompose-baseline (Phase 0 FOUND-08):** Operate-tab recomposition count over one full slot cycle with EARLY-then-FULL update-in-place does not exceed the baseline by more than 5 %
- **CPU sanity:** `DecodeSlice.lastDecodePassDurationMs` values from the session log are reviewed; sustained values above a sanity ceiling (plan-phase to set the number) are flagged but do not block promotion on the reference rig

## Constraints

- **Pin stays pinned.** ft8_lib remains at kgoba `9fec6ca39886edbf96f4f5e71edc76da5074e871` via FetchContent. The JNI already accepts arbitrary-length PCM and builds the waterfall from it; no native changes required.
- **CRC-14 is non-negotiable.** EARLY decodes propagate to the QSO machine only after CRC-14 validates — the same property as the FULL pass. No new code path around CRC.
- **Single-thread decode dispatcher unchanged.** EARLY and FULL passes share the existing `decodeDispatcher`. Serial execution prevents JNI re-entry.
- **PTT-safety primacy.** QSO-machine consumption of EARLY decodes flows through the unchanged `_decodesOut` → `QsoSessionController` → `TxOrchestrator.transmit()` chain. The late-TX symbol-clock-aligned path (companion phase R2) is the only TX entry for hunt-and-pounce.
- **No new top-level screen / tab / dependency** (PARITY-03 / CLAUDE.md milestone rule). The new Settings row joins the existing "Advanced decoding / TX" group from the late-TX phase. No new build tooling, no new library dep.
- **No console logging.** R7 instrumentation is a `DecodeSlice` field only — no `Log.i` / `Log.d` / `println` added to DecodeController.
- **License gate preserved.** EARLY decodes that arm an auto-answer still flow through the `AppRfState.READY` precondition before any TX is keyed.
- **minSdk 28, NDK r29, JVM 17** unchanged.
- **Prerequisites.** [Late-Start FT8 TX](2026-06-22-late-start-ft8-tx-design.md) must be shipped and field-verified on the reference rig before this phase begins. Phase 0 FOUND-07 (5-min real-rig session) must be captured and committed — without it, the golden-trace parity gate has no ground truth.

## Boundaries

### In scope

- `SlotCollector.snapshot()` accessor (pure, read-only)
- `EarlyDecodeScheduler` coroutine inside `DecodeController` (one-shot per slot at `slotStart + 12.000 s`)
- Stable cross-pass `DecodeRow.id` derivation + per-slot `seenKeys` set
- `DecodeBatch` emission contract on `_decodesOut`: each unique decode emitted exactly once across both passes
- Pixel-identical row rendering for EARLY and FULL sources (Compose snapshot test)
- Settings row `early_decode_enabled` (default `true`) under "Advanced decoding / TX"
- Decode-pass duration instrumentation in `DecodeSlice` (no console logging)
- Combined-feature integration test for the Phase-9 + Phase-10 hunt-and-pounce loop
- Golden-trace parity test with toggle OFF and ON
- On-air field session on the reference FT-891 + Digirig under `.planning/field-sessions/early-decode-<date>/`

### Out of scope

- **Progressive / multi-pass early decode** (passes at t=10 s + 12 s + 14 s) — single trigger only this phase; progressive is a follow-on if field evidence justifies the CPU cost
- **Operator-configurable early-trigger offset** — fixed at `t = 12.000 s` for this phase; settings slider can be added later if requested
- **AP-pass interaction in the early window** — early pass runs the standard decode only. AP runs on the FULL pass only (companion-phase contract unchanged). Combining EARLY + AP is a follow-on
- **Decode-list legibility** (own-TX rows, worked-before coloring, column header) — owned by the in-flight `readiness` branch work; this phase introduces zero visual deltas
- **ft8_lib bump** — pinned commit unchanged
- **Adaptive early-pass skipping when decoder dispatcher is busy** — the single-threaded `decodeDispatcher` will naturally serialize; if pile-up is observed in the field, a follow-on phase can add explicit skip-if-busy logic
- **UI for the duration instrumentation** — value lives in `DecodeSlice` for diagnostics + tests; not surfaced to the operator
- **In-window UI affordances for the EARLY → FULL transition** — explicitly transparent (R5 contract: zero pixel delta)

## Acceptance criteria

- [ ] `SlotCollector.snapshot()` returns `null` before any samples added; returns a defensive copy after 144 000 samples; mutating the returned array does not affect a subsequent `onSlot` invocation
- [ ] Existing `SlotCollectorTest` suite passes unchanged (regression guard)
- [ ] EarlyDecodeScheduler fires exactly once per slot at `slotStart + 12.000 s ± 100 ms`
- [ ] EarlyDecodeScheduler skips the pass when the snapshot has < 115 200 samples (80 % of the expected 12 s buffer)
- [ ] A slot-boundary crossing cancels a still-pending early-pass coroutine for the now-old slot
- [ ] Two `Ft8DecodeResult`s with the same message and freqHz within one 6.25 Hz bin produce equal `DecodeRow.id`; different messages or different bins produce distinct ids
- [ ] After both EARLY and FULL passes over the same canned PCM, `DecodeSlice.decodes` row count equals the union of unique decodes (not the sum)
- [ ] Each unique decode key appears at most once in `_decodesOut` emissions across the two passes (verified by a 100-slot test subscriber)
- [ ] FULL pass over a key already inserted by EARLY updates SNR / dT / freqHz in place; `id` and list position unchanged
- [ ] `DecodeListPanel` Compose snapshot of an EARLY row vs a FULL row with identical metadata shows zero pixel delta
- [ ] `DecodeListPanel` Compose snapshot of a FULL row vs the pre-Phase-10 v1.0 baseline shows zero pixel delta
- [ ] `Settings → Advanced decoding / TX → "Early decode (CQs ~3s sooner)"` defaults to ON on a fresh install
- [ ] Toggling `early_decode_enabled = false` reverts decode timing to v1.0 single-pass behavior (no EARLY pass for any slot until toggled back)
- [ ] `DecodeSlice.lastDecodePassDurationMs` updates after each decode pass; on a synthetic 100-result slot the value stays below 4 000 ms on the CI emulator
- [ ] No `Log.i` / `Log.d` / `println` added to DecodeController (CLAUDE.md "no console logging" convention)
- [ ] `EarlyDecodeLateTxIntegrationTest` passes: EARLY pass at `t = 12.000 s` arms an auto-answer; `TxOrchestrator` is invoked with `t_in_slot ∈ [0.0 s, 7.0 s]` on the next slot boundary; PTT keys within 50 ms
- [ ] All four Phase-5 PTT-safety scenarios still pass when the TX call is triggered by an EARLY-pass-armed auto-answer (`TxOrchestratorPttSafetyEarlyDecodeTest`)
- [ ] Golden-trace replay against the post-Phase-9 baseline passes with zero diff when `early_decode_enabled = false`
- [ ] Golden-trace replay passes with `early_decode_enabled = true` under the additive-tolerance contract (zero key collisions over 100 simulated slots)
- [ ] Operate-tab recomposition count over one full slot cycle with EARLY-then-FULL update-in-place does not exceed the Phase 0 FOUND-08 baseline by more than 5 %
- [ ] On-air session under `.planning/field-sessions/early-decode-<date>/` completes at least one QSO where the operator confirms the auto-answer fired on an EARLY-pass decode, plus one toggle-OFF QSO confirming v1.0 timing, plus one combined-feature QSO confirming the hunt-and-pounce loop end-to-end

## Open questions for the implementation plan

These do not block the design but should be resolved during `writing-plans`:

1. **`DecodeRow.id` hash function.** `Objects.hash`-based 32→64-bit fold, custom `Long`-packing (e.g. `slotStart shl 32 or (freqBin.toInt() shl 16) or messageHash`), or a `kotlin.math` `siphash`-style mix. Plan-phase picks one with a collision check over a representative decode corpus.
2. **`seenKeys` eviction strategy.** Cap at 4 most-recent slots? Evict when the slot ages out of `MAX_DECODE_ROWS`? Concrete LRU? Bound the memory; the exact bound is a plan-phase decision.
3. **Scheduler coroutine ownership + cancellation.** Plain `Job?` field updated under the same lock the slot-transition detection uses, or a `MutableStateFlow<Long>` that drives a `collectLatest { ... delay ... }`? Both work; the second is more idiomatic and gives free cancellation on new slot transitions.
4. **Settings flip mid-pending-early.** If the toggle goes OFF after the early job was scheduled but before it dispatches, do we honor it (re-check the slice in the scheduled coroutine) or let the pass complete? Almost certainly re-check (simple `if (!enabled) return` at wake), but document the chosen behavior.
5. **R7 rolling stats.** Bare `lastDecodePassDurationMs: Long` is enough for tests; an optional `recentDecodePassDurationsMs: ImmutableList<Long>` (cap N=8) gives field-session sanity-check material at trivial cost. Decide whether to add it now.
6. **Combined-feature CI test naming.** `EarlyDecodeLateTxIntegrationTest` lives where? Likely in `app/src/test/` next to the existing controller integration tests, but confirm with the late-TX phase's plan once it ships.

## Migration note

This design supersedes the GSD-format spec at [.planning/phases/10-early-decode/10-SPEC.md](../../../.planning/phases/10-early-decode/10-SPEC.md). The two are content-equivalent — same nine requirements, same locked decisions, same acceptance criteria — but this file is the source of truth going forward. The GSD spec should be marked superseded or removed before implementation begins, matching the precedent set by the [late-start-ft8-tx-design](2026-06-22-late-start-ft8-tx-design.md) → `.planning/phases/09-late-tx-ap-decode/09-SPEC.md` migration.
