# Phase 10: Early-Decode (Streaming / Partial-Slot Decode) — Specification

**Created:** 2026-06-22
**Ambiguity score:** 0.14 (gate: ≤ 0.20)
**Requirements:** 9 locked
**Reference phase:** Phase 9 (`09-late-tx-ap-decode/09-SPEC.md`) — late-start TX symbol-clock math and the hunt-and-pounce scenario this phase pairs with.

## Goal

FT8VC runs a second standard FT8 decode pass at slot-relative t=12.000s
over an in-progress sample buffer, surfacing CRC-validated decodes
~2.5–3.0 s before the slot ends. Combined with Phase 9 late-start TX,
this restores the WSJT-X / POTACAT hunt-and-pounce ergonomic on the
reference FT-891 + Digirig rig without changing the v1.0 RX/TX/CAT/QSO
behavior contract when the feature is toggled OFF.

## Background

**Decode timing today.** The RX pipeline is:
[UsbAudioCapture](audio/src/main/java/net/ft8vc/audio/UsbAudioCapture.kt) →
[DecodeController.onFrames](app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt:117)
→ [SlotCollector.add](core/src/main/java/net/ft8vc/core/SlotCollector.kt:27)
→ on wall-clock slot-boundary crossing, `onSlot(samples, slotStart)` is
invoked once, which launches `DecodeController.decodeSlot(...)` on the
single-thread `decodeDispatcher`. There is **one decode pass per slot**,
fired at `t ≈ slotStart + 15.0s + audio-callback-jitter`, with decoded
rows reaching the UI at `t ≈ slotStart + 15.5–16.0s`. SlotCollector
itself has no read-only view of the in-progress buffer.

**JNI confirms partial buffers are acceptable.**
[ft8_jni.cpp](ft8-native/src/main/cpp/ft8_jni.cpp)'s
`ftx_find_candidates` + `ftx_decode_candidate` operate on a waterfall
built from whatever PCM is handed to the JNI. At 12.000s of samples (80%
of the slot), Costas-array detection still works for most signals; LDPC
iteration + CRC-14 either converge or reject. There is no fabricated-
decode failure mode — the safety property is identical to the full-slot
pass.

**Phase 9 sets the late-TX cutoff at 7.000s.** With v1.0 decode timing,
the operator-facing read+decide window is `[t ≈ slot+0.5s → t = slot+7.0s]
≈ 6.5s`. With this phase's early decode pushing CQs to the screen at
`t ≈ slot-2.5s` (i.e. previous slot's `t=12.5s`), the window grows to
`≈ 9.5s` — the WSJT-X / POTACAT hunt-and-pounce ergonomic on a phone.

**Why now.** Phase 9 (late-TX + AP decoding) must ship and field-verify
first; early-decode without late-TX is much less useful (the human still
cannot answer in the current slot). Phase 7's controller-isolated RX
pipeline (DecodeController owns `decodeDispatcher`, SlotCollector, the
`_decodesOut` SharedFlow) is the right foundation: the early-decode
scheduler is a self-contained addition inside DecodeController, no new
top-level seams.

**Out of scope here.** Decode-list legibility (own-TX rows, worked-before
coloring, column header) is owned by the in-flight `readiness` branch
work and lands separately. This phase introduces zero visual deltas to
DecodeListPanel rows.

## Requirements

1. **R1 — SlotCollector partial-snapshot API**: SlotCollector gains a
   pure, read-only `snapshot()` accessor returning the in-progress
   buffer as a defensive copy. No threading, no scheduling, no clock
   coupling.
   - Current: `SlotCollector` exposes only `add(frames, now, onSlot)`
     and `reset()`. The in-progress buffer is private; callers cannot
     decode it before the slot boundary fires.
   - Target: `fun snapshot(): ShortArray?` returns
     `buffer.copyOf(count)` when `count > 0`, otherwise `null`.
     `add()` / `onSlot` semantics and `reset()` unchanged.
     Modifying the returned array must not affect subsequent `add()` /
     `onSlot` output (defensive copy contract).
   - Acceptance: `SlotCollectorSnapshotTest` asserts (i) `snapshot()`
     returns `null` before any samples added, (ii) after adding 144000
     samples of a fixed PCM, `snapshot()` returns an array of length
     144000 equal to the input, (iii) mutating the returned array does
     not affect a subsequent `onSlot` invocation, (iv) regression: the
     existing `SlotCollectorTest` suite passes unchanged.

2. **R2 — EarlyDecodeScheduler inside DecodeController**: A one-shot
   coroutine per slot fires at wall-clock `slotStart + 12.000 s ± 100
   ms`, snapshots the SlotCollector buffer, and launches a decode pass
   on the existing `decodeDispatcher` with `source = EARLY`.
   - Current: DecodeController launches `decodeSlot(...)` only inside
     the `slotCollector.add(...) { samples, slotStart -> ... }`
     callback, i.e. only at the boundary.
   - Target: DecodeController owns a private scheduler coroutine that,
     on each new slot transition (detected via the existing
     `add()`-driven callback or a derived current-slot tracker),
     schedules a one-shot using `delay(slotStart + 12_000ms - clock())`.
     On wake: call `slotCollector.snapshot()`. If null or below 80% of
     `expectedSlotSamples = SAMPLE_RATE_HZ * 12` (i.e. < 115200
     samples), skip silently. Else launch
     `scope.launch(decodeDispatcher) { decodeSlot(snap, slotStart,
     source = EARLY) }`. A new slot transition cancels any pending
     early-pass coroutine. The scheduler is a no-op when the Settings
     toggle (R6) is OFF.
   - Acceptance: `EarlyDecodeSchedulerTest` (using a `FakeClock` and a
     `TestDispatcher`) asserts (i) early pass fires exactly once at
     `slotStart + 12.000s ± 100ms`, (ii) early pass is skipped when
     the snapshot has < 115200 samples (simulated late capture start),
     (iii) a slot-boundary crossing cancels a pending early pass for
     the now-old slot, (iv) with the toggle OFF, no early pass fires
     across 10 simulated slots.

3. **R3 — Stable cross-pass DecodeRow key**: `DecodeRow.id` is derived
   from `(slotStartEpochMs, freqBin, messageText)` so the same logical
   decode produced by the EARLY pass and the FULL pass collides on one
   key.
   - Current: `DecodeRow.id = slotStartEpochMs * 1000L + indexInSlot`
     (DecodeController.kt:196). `indexInSlot` depends on candidate
     ordering, which can differ between passes.
   - Target: `DecodeRow.id` is a stable 64-bit hash of
     `(slotStartEpochMs, round(freqHz / FREQ_BIN_HZ).toLong(),
     messageText.trim())` where `FREQ_BIN_HZ = 6.25` (one FT8 tone
     bin). The exact hash function is a plan-phase decision but must
     be deterministic and collision-resistant across the per-slot
     decode set (≤ ~50 decodes per slot in practice). `key = { it.id }`
     in DecodeListPanel continues to provide Compose item stability
     across the two passes.
   - Acceptance: `DecodeRowKeyTest` constructs two `Ft8DecodeResult`s
     for the same message + freq with slightly different reported
     freqHz (e.g. 1499.8 Hz and 1500.4 Hz, both within the 6.25 Hz
     bin) and asserts they produce equal `DecodeRow.id` values.
     A negative test on different messages or different bins asserts
     distinct ids.

4. **R4 — Dedup contract and DecodeBatch emission**: Within a single
   slot, the EARLY and FULL passes share a per-slot dedup set. Each
   unique decode is inserted exactly once in `DecodeSlice.decodes` and
   emitted exactly once in `DecodeBatch` on `_decodesOut`.
   - Current: `decodeSlot(...)` always prepends all results into
     `_slice.decodes` and always emits the full `DecodeBatch`. There
     is no per-slot dedup state.
   - Target: DecodeController maintains a `seenKeys:
     MutableMap<Long, HashSet<Long>>` (slotStart → set of
     DecodeRow.id), evicted when the slot ages out of the
     `MAX_DECODE_ROWS` window. EARLY pass: each result whose key is
     not in the set → insert row + add to emitted DecodeBatch. FULL
     pass: for each result, if key not in set → insert row + add to
     batch; if key was already inserted by EARLY → update row in
     place (final SNR, dT, freqHz) and **omit from the emitted
     DecodeBatch**. Result: `QsoSessionController` collecting
     `_decodesOut` sees each unique decode exactly once, regardless
     of which pass discovered it first.
   - Acceptance: `DecodeControllerEarlyDedupTest` feeds canned PCM
     into the controller (or invokes `decodeSlot` directly with two
     overlapping result sets), asserts (i) total rows in
     `DecodeSlice.decodes` after both passes equals the union, not
     the sum, (ii) `_decodesOut` emits at most one batch entry per
     unique key across both passes, (iii) the in-place update on the
     FULL pass replaces SNR/dT/freqHz fields but preserves `id` and
     existing list position (no re-insertion), (iv) a downstream test
     subscriber to `_decodesOut` records exactly N unique decodes
     where the input has N unique CRC-validated messages.

5. **R5 — Zero UI delta between EARLY and FULL rows**: DecodeListPanel
   renders rows produced by the EARLY pass pixel-identically to FULL
   rows. No badge, no opacity tweak, no debug marker.
   - Current: DecodeListPanel renders rows from `DecodeSlice.decodes`
     without any source indication (the EARLY/FULL distinction does
     not exist yet).
   - Target: `DecodeRow` may carry a `source: DecodeRowSource` field
     for telemetry / dedup, but DecodeListPanel rendering must not
     branch on it. Visual contract: an EARLY-only row from `t=12.5s`
     and a FULL row from `t=15.5s` (with identical SNR/dT/freq/text)
     produce byte-identical Compose snapshots.
   - Acceptance: `DecodeListPanelEarlyParityTest` (Compose snapshot
     test) renders one row with `source = EARLY` and one with
     `source = FULL`, both with identical metadata; asserts zero
     pixel delta between them. A second snapshot diff against the
     existing v1.0 baseline asserts zero pixel delta against the
     pre-Phase 10 row rendering for `source = FULL`.

6. **R6 — Settings toggle (ON by default)**: Early-decode ships
   enabled by default with a Settings toggle to disable. Toggle state
   persists via DataStore.
   - Current: No such toggle exists.
   - Target: SettingsRepository gains key `early_decode_enabled`
     (default `true`). Settings screen gains a row under "Advanced
     decoding / TX" (the same group Phase 9 introduces): **"Early
     decode (CQs ~3s sooner)"** with subtitle "Runs an extra decode
     pass partway through each slot." When `false`,
     EarlyDecodeScheduler is a no-op for all subsequent slots; behavior
     reverts to v1.0 single-pass per slot.
   - Acceptance: `SettingsRepositoryEarlyDecodeTest` asserts the key
     defaults to `true` on a fresh install and round-trips through
     DataStore. `EarlyDecodeSchedulerTest` (cross-references R2-iv)
     asserts the scheduler is a no-op when the SettingsBridge slice
     reports `earlyDecodeEnabled = false`.

7. **R7 — Decode-pass duration instrumentation**: DecodeController
   measures each decode pass's wall-clock duration and surfaces it
   into the DecodeSlice for diagnostics and tests. No console logging
   — the project convention (CLAUDE.md "No console logging in
   production code") is preserved; duration is a slice field only.
   - Current: Decode-pass duration is not measured anywhere.
   - Target: `DecodeSlice` gains `lastDecodePassDurationMs: Long` and
     `lastDecodePassSource: DecodeRowSource?`. `decodeSlot(...)`
     wraps the `decoder.decode(...)` call in a `measureTimeMillis {
     ... }` and updates the slice. Optional rolling stats
     (`recentDecodePassDurationsMs: ImmutableList<Long>` capped at
     the last N=8 passes) is a plan-phase decision. No `Log.i` /
     `println` calls and no new logging dependency.
   - Acceptance: `DecodeControllerDurationTest` runs a synthetic
     decode pass via `Ft8DecoderFake` (with a 50 ms canned delay),
     asserts `DecodeSlice.lastDecodePassDurationMs >= 40 && <= 500`
     after the pass completes, and asserts `lastDecodePassSource`
     matches the pass that ran. A second assertion verifies that on
     a synthetic 100-result slot, the duration stays below a sanity
     ceiling (`< 4000ms` on the CI emulator) — failure here flags a
     decoder-perf regression rather than a logic bug. A negative
     test (or grep gate in CI, plan-phase choice) asserts no
     `Log.i` / `Log.d` / `println` was added to DecodeController.

8. **R8 — Combined-feature hunt-and-pounce verified**: With Phase 9
   late-TX and this phase's early-decode both ON, an integration
   test demonstrates the full hunt-and-pounce loop end to end. The
   on-air field session is documented as a separate acceptance check.
   - Current: No combined-feature test exists (Phase 9 has not
     shipped yet).
   - Target: `EarlyDecodeLateTxIntegrationTest` (using
     `Ft8DecoderFake`, `TxOrchestrator` with the Phase 9 late-TX
     path, fake clock, and a CQ canned in the partial-slot PCM)
     drives the controllers through one slot:
     (a) at `slotStart + 12.000s` the EARLY pass discovers the CQ
     and emits a DecodeBatch;
     (b) `QsoSessionController` arms an auto-answer reply;
     (c) at the slot boundary, `TxOrchestrator.transmit(reply)` is
     invoked with `t_in_slot ∈ [0.0s, 7.0s]` (per Phase 9 R2);
     (d) the late-TX symbol-clock-aligned waveform is requested;
     (e) PTT keys within 50 ms.
     The full timing budget is documented in SPEC.md (this file) as
     part of the Background section.
   - Acceptance: The integration test passes in CI. On-air session
     on the reference FT-891 + Digirig completes at least one QSO
     where the operator confirms (via field log timestamps) that the
     auto-answer fired on an EARLY-pass decode (not the FULL pass);
     recorded under `.planning/field-sessions/early-decode-<date>/`
     and committed before promotion to `main`.

9. **R9 — Behavior-parity preservation on toggle OFF**: With
   `early_decode_enabled = false`, RX/TX/CAT/QSO behavior on the
   reference rig is byte-equivalent to the post-Phase-9 baseline.
   With it ON, golden-trace replay tolerates EARLY-emitted decodes
   as additive (same contract as Phase 9 R8).
   - Current: Phase 0 PARITY-01 / FOUND-07 golden-trace baseline
     established; Phase 9 R8 extends the tolerance contract to
     AP-recovered decodes. This phase extends it again to EARLY
     decodes.
   - Target: With `early_decode_enabled = false`, FOUND-06
     golden-trace replay against the post-Phase-9 baseline
     (`.planning/field-sessions/late-tx-ap-<date>/`) passes with
     zero diff. With it ON, replay passes with the additive
     tolerance: EARLY decodes that the FULL pass would also have
     produced appear earlier in the emitted decode stream but at
     most once per unique key.
   - Acceptance: CI runs the golden-trace test with
     `early_decode_enabled = false` against the post-Phase-9
     baseline; passes with identical state-transition output. A
     second CI run with the toggle `true` asserts the test still
     passes (EARLY decodes are additive, not substitutive). A
     third assertion confirms that `_decodesOut` never emits a
     duplicate (same key) across the two passes — the dedup
     contract (R4) is load-bearing for parity.

## Boundaries

**In scope:**

- `SlotCollector.snapshot()` API (R1) — pure, read-only buffer accessor.
- `EarlyDecodeScheduler` coroutine inside `DecodeController` (R2) —
  one-shot per slot at `t = slotStart + 12.000s`.
- Stable cross-pass `DecodeRow.id` derivation (R3) and per-slot dedup
  set in `DecodeController` (R4).
- `DecodeBatch` emission contract on `_decodesOut`: each unique decode
  emitted exactly once across both passes (R4).
- Pixel-identical row rendering for EARLY and FULL sources (R5).
- New Settings row `early_decode_enabled` (default `true`) under
  "Advanced decoding / TX" (R6).
- Decode-pass duration instrumentation in `DecodeSlice` + log line
  per pass (R7).
- Integration test for the combined Phase 9 + Phase 10 hunt-and-pounce
  loop (R8).
- Golden-trace parity test with toggle OFF and ON (R9).
- On-air field session on the reference FT-891 + Digirig under
  `.planning/field-sessions/early-decode-<date>/`.

**Out of scope:**

- Progressive / multi-pass early decode (passes at t=10s + 12s + 14s)
  — single trigger only this phase; progressive is a follow-on if
  field evidence justifies the CPU cost.
- Operator-configurable early-trigger offset — fixed at t=12.000s for
  this phase; settings slider can be added later if requested.
- AP-pass interaction in the early window — early pass runs the
  **standard** decode only. AP runs only on the FULL pass, exactly per
  Phase 9 R5. Combining EARLY + AP is a follow-on phase.
- Decode-list legibility (own-TX rows, worked-before coloring, column
  header) — owned by the in-flight `readiness` branch work; this
  phase introduces zero visual deltas.
- ft8_lib bump — pinned commit
  `9fec6ca39886edbf96f4f5e71edc76da5074e871` unchanged.
- Adaptive early-pass skipping when decoder dispatcher is busy — the
  single-threaded `decodeDispatcher` will naturally serialize; if the
  FULL pass of slot N is still running when the EARLY pass of slot N+1
  is scheduled, the latter queues. Acceptable given the rarity on the
  reference device class. If field evidence shows pile-up on slow
  phones, a follow-on phase can add explicit skip-if-busy logic.
- UI for the duration instrumentation — value lives in `DecodeSlice`
  for diagnostics + tests; not surfaced to the operator.

## Constraints

- **Pin stays pinned.** ft8_lib remains at kgoba
  `9fec6ca39886edbf96f4f5e71edc76da5074e871`. The JNI already accepts
  arbitrary-length PCM and builds the waterfall from it; no native
  changes required for this phase.
- **CRC-14 is load-bearing.** EARLY decodes propagate to the QSO
  machine only after CRC-14 validates, exactly like FULL decodes.
  This is the bedrock safety property — no fabricated contacts.
- **Single-thread decode dispatcher unchanged.** EARLY and FULL passes
  share the existing `decodeDispatcher`. Serial execution prevents
  the JNI from being re-entered.
- **PTT-safety primacy.** R2 + R8 cannot weaken `TxOrchestrator`'s
  Phase 5 4-layer PTT defense. QSO-machine consumption of EARLY
  decodes flows through the same `_decodesOut` →
  `QsoSessionController` → `TxOrchestrator.transmit()` path. The
  late-TX symbol-clock-aligned path (Phase 9 R2) is the only TX entry
  for hunt-and-pounce.
- **No new top-level screen / tab / dependency** (CLAUDE.md milestone
  rule). The new Settings row sits inside the existing "Advanced
  decoding / TX" group that Phase 9 introduces. No new build tooling.
- **License gate preserved.** EARLY decodes that arm an auto-answer
  still flow through the `AppRfState.READY` precondition before any
  TX is keyed.
- **minSdk 28, NDK r29, JVM 17** unchanged.
- **Prerequisites.** Phase 9 (late-start TX + AP decoding) must be
  shipped and field-verified on the reference rig before this phase
  begins. Phase 0 FOUND-07 (5-min real-rig session) must be captured
  and committed — without it, the golden-trace parity gate has no
  ground truth.
- **Promotion gate.** FOUND-07 (behavior-parity replay) and FOUND-08
  (recompose baseline) gates from Phase 0 apply unchanged. Promotion
  to `main` requires the on-air field session under
  `.planning/field-sessions/early-decode-<date>/` to be committed
  and signed off in the PR.

## Acceptance Criteria

- [ ] `SlotCollector.snapshot()` returns `null` before any samples
      added; returns a defensive copy after 144000 samples; mutating
      the returned array does not affect a subsequent `onSlot`
      invocation (R1).
- [ ] Existing `SlotCollectorTest` suite passes unchanged (R1
      regression guard).
- [ ] EarlyDecodeScheduler fires exactly once per slot at
      `slotStart + 12.000s ± 100ms` (R2).
- [ ] EarlyDecodeScheduler skips the pass when the snapshot has
      < 115200 samples (80% of the expected 12s buffer) (R2).
- [ ] A slot-boundary crossing cancels a still-pending early-pass
      coroutine for the now-old slot (R2).
- [ ] Two `Ft8DecodeResult`s with the same message and freqHz
      within one 6.25 Hz bin produce equal `DecodeRow.id` values;
      different messages or different bins produce distinct ids (R3).
- [ ] After both EARLY and FULL passes over the same canned PCM,
      `DecodeSlice.decodes` row count equals the union of unique
      decodes (not the sum), and each unique key appears at most
      once in `_decodesOut` emissions (R4).
- [ ] FULL pass over a key already inserted by EARLY updates SNR /
      dT / freqHz in place; `id` and list position unchanged (R4).
- [ ] `DecodeListPanel` Compose snapshot of an EARLY row vs a FULL
      row with identical metadata shows zero pixel delta (R5).
- [ ] `DecodeListPanel` Compose snapshot of a FULL row vs the
      pre-Phase-10 v1.0 baseline shows zero pixel delta (R5).
- [ ] `Settings → Advanced decoding / TX → "Early decode (CQs ~3s
      sooner)"` defaults to ON on a fresh install (R6).
- [ ] Toggling `early_decode_enabled = false` reverts decode timing
      to v1.0 single-pass behavior (no EARLY pass for any slot until
      toggled back) (R6).
- [ ] `DecodeSlice.lastDecodePassDurationMs` updates after each
      decode pass; on a synthetic 100-result slot the value is below
      4000 ms on the CI emulator (R7).
- [ ] No `Log.i` / `Log.d` / `println` is added to DecodeController
      (R7 — preserves CLAUDE.md "no console logging" convention).
- [ ] `EarlyDecodeLateTxIntegrationTest` passes: EARLY pass at
      `t=12.000s` arms an auto-answer; `TxOrchestrator` is invoked
      with `t_in_slot ∈ [0.0s, 7.0s]` on the next slot boundary;
      PTT keys within 50 ms (R8).
- [ ] On-air field session on the reference FT-891 + Digirig
      completes at least one QSO where the operator confirms (via
      field log timestamps) that the auto-answer fired on an
      EARLY-pass decode; recorded under
      `.planning/field-sessions/early-decode-<date>/` (R8).
- [ ] Golden-trace replay (FOUND-06) against the post-Phase-9
      baseline passes with zero diff when
      `early_decode_enabled = false` (R9).
- [ ] Golden-trace replay passes with `early_decode_enabled = true`
      under the additive-tolerance contract: EARLY decodes appear
      earlier than the FULL pass would have produced them, but never
      duplicate a key (R9).
- [ ] `_decodesOut` never emits a duplicate (same key) across the
      EARLY + FULL passes of a single slot — verified by a CI test
      subscriber over 100 simulated slots (R9, supports R4).
- [ ] Recompose count for the Operate tab over one full slot cycle
      with EARLY-then-FULL update-in-place does not exceed the
      Phase 0 FOUND-08 baseline by more than 5%.
- [ ] PTT-safety scenarios from Phase 5 (`TxOrchestratorTest`) still
      pass when the TX call is triggered by an EARLY-pass-armed
      auto-answer (combined with Phase 9 R2 path).

## Edge Coverage

**Coverage:** 9/9 applicable edges resolved · 0 unresolved

| Category                 | Requirement | Status      | Resolution / Reason |
|--------------------------|-------------|-------------|---------------------|
| Boundary (timing)        | R2          | ✅ covered  | AC: early pass fires at `slotStart + 12.000s ± 100ms`; slot-boundary crossing cancels pending pass |
| Boundary (sample count)  | R2          | ✅ covered  | AC: skip pass when snapshot < 115200 samples (audio just started, hot-swap) |
| State (dedup)            | R3, R4      | ✅ covered  | AC: stable cross-pass key derivation; per-slot `seenKeys` set; in-place update on FULL collision |
| Concurrency (dispatcher) | R2, R4      | ✅ covered  | Single-thread `decodeDispatcher` serializes EARLY + FULL; no JNI re-entry |
| Adversarial (CRC)        | R4, R9      | ✅ covered  | EARLY decodes propagate only after CRC-14 validates — same property as FULL pass |
| State (toggle OFF)       | R6, R9      | ✅ covered  | AC: golden-trace passes with zero diff when toggle OFF; v1.0 single-pass path restored |
| Compatibility (parity)   | R9          | ✅ covered  | AC: golden-trace passes with toggle ON under additive tolerance; no duplicate keys ever emitted |
| UI (zero delta)          | R5          | ✅ covered  | AC: Compose snapshot diff EARLY vs FULL vs v1.0 baseline — zero pixel delta |
| Integration (late-TX)    | R8          | ✅ covered  | AC: combined-feature integration test + on-air field session under `.planning/field-sessions/early-decode-<date>/` |

## Prohibitions (must-NOT)

**Coverage:** 5/5 applicable prohibitions resolved · 0 unresolved

| Prohibition (must-NOT statement) | Requirement | Status              | Verification / Reason |
|----------------------------------|-------------|---------------------|------------------------|
| MUST NOT relax or bypass CRC-14 validation on the EARLY pass | R4, R9 | resolved / test | EARLY pass calls the same `Ft8DecoderApi.decode(...)` as FULL — no new code path around CRC. Verified by `DecodeControllerEarlyDedupTest` and golden-trace parity (R9 AC). |
| MUST NOT emit the same decode key twice on `_decodesOut` within one slot | R4 | resolved / test | Per-slot `seenKeys` set + the "FULL omits already-emitted keys" rule. Verified by the 100-slot duplicate-check subscriber (R9 AC). |
| MUST NOT weaken the Phase 5 4-layer PTT defense when TX is armed by an EARLY decode | R8 | resolved / test | EARLY-armed TX flows through the unchanged `TxOrchestrator.transmit()` path. `TxOrchestratorTest` PTT-safety scenarios re-run with an EARLY-pass-armed trigger. |
| MUST NOT introduce a visual delta in DecodeListPanel rows for EARLY vs FULL sources | R5 | resolved / test | Compose snapshot test asserts zero pixel delta between EARLY and FULL rows and against the v1.0 baseline. |
| MUST NOT introduce a new top-level screen, tab, or dependency (PARITY-03 / CLAUDE.md) | R6 | resolved / judgment | Settings row joins the existing "Advanced decoding / TX" group from Phase 9. No new build tooling, no new library dep. |

## Ambiguity Report

| Dimension          | Score | Min  | Status | Notes                                                                          |
|--------------------|-------|------|--------|--------------------------------------------------------------------------------|
| Goal Clarity       | 0.90  | 0.75 | ✓      | One feature, concrete numeric thresholds (12.000s trigger, 6.25 Hz bin, 80% snapshot floor) |
| Boundary Clarity   | 0.85  | 0.70 | ✓      | Progressive multi-pass, operator-configurable offset, EARLY+AP combination all explicitly out |
| Constraint Clarity | 0.70  | 0.65 | ✓      | Phase 9 prerequisite stated; CRC-14 + PTT-safety primacy carried forward       |
| Acceptance Criteria| 0.80  | 0.70 | ✓      | 21 pass/fail criteria across 9 requirements                                    |
| **Ambiguity**      | 0.14  | ≤0.20| ✓      |                                                                                |

## Interview Log

| Round | Perspective       | Question summary                                                              | Decision locked                                                                                                                                                          |
|-------|-------------------|-------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 0     | Codebase scout    | Does the JNI accept a partial sample buffer?                                  | Yes. `ftx_find_candidates` + `ftx_decode_candidate` build the waterfall from whatever PCM is handed in; no native changes required. CRC-14 still gates every decode.     |
| 0     | Codebase scout    | Where does SlotCollector live and is there any partial-buffer accessor today? | `core/SlotCollector.kt` — pure logic, no Android deps, no partial-buffer accessor. Adding `snapshot()` keeps it pure and unit-testable.                                  |
| 0     | Codebase scout    | What is the current Phase 9 state? Does it still bundle AP?                   | Phase 9 dir `09-late-tx-ap-decode/` still bundles late-TX + AP. Rename has not happened. This phase numbers as 10.                                                       |
| 1     | User              | UI-only vs feed QSO machine vs UI-now-QSO-later?                              | **Feed QSO machine too** — CRC-14 is the safety property and is unchanged. Parity with WSJT-X / FT8CN auto-seq + Phase 9 late-TX delivers the hunt-and-pounce ergonomic. |
| 1     | User              | Dedup contract: one row update-in-place vs two rows vs replace-on-confirm?    | **One row, update-in-place** — stable cross-pass key in `DecodeRow.id`; per-slot `seenKeys` set; FULL pass updates in place and omits already-emitted keys from `_decodesOut`. |
| 2     | User              | Trigger: single fixed t=12s vs configurable vs progressive vs adaptive?       | **Single fixed t=12.000s** — POTACAT precedent; deterministic; configurable + progressive are explicit follow-ons.                                                       |
| 2     | User              | Default state: ON-with-toggle vs OFF-opt-in vs ON-no-toggle?                  | **ON by default with toggle** — matches Phase 9 precedent and gives a reversible escape hatch for slow phones / long POTA sessions.                                      |
| 3     | User              | EARLY/FULL rows: no marker vs pending indicator vs permanent chip?            | **No marker, identical rendering** — CRC-validated is CRC-validated; the row at `t=12.5s` is the same row whether or not FULL later confirms it.                          |
| 3     | Boundary Keeper   | UI parity: explicit boundary statement vs dedicated requirement with test?    | **Dedicated requirement R5** — Compose snapshot test pins zero pixel delta as a CI guardrail against future drift.                                                       |
| 4     | Failure Analyst   | What does a broken version look like?                                         | (a) Duplicate decode emitted on `_decodesOut` → auto-answer fires twice → dedup contract R4 + R9 AC. (b) EARLY pass triggers TX on a corrupted decode → CRC-14 is the property; same as FULL pass. (c) Decode pile-up on slow phones → single-thread dispatcher serializes; R7 instrumentation surfaces it. |
| 4     | Failure Analyst   | Rollback path?                                                                | R6 toggle OFF → EarlyDecodeScheduler is a no-op for all subsequent slots; R9 golden-trace passes with zero diff against post-Phase-9 baseline.                            |

---

*Phase: 10-early-decode*
*Spec created: 2026-06-22*
*Next step: /gsd-discuss-phase 10 — implementation decisions (DecodeRow.id hash function, scheduler coroutine scope + cancellation strategy, SettingsBridge slice wiring, log channel for R7).*
