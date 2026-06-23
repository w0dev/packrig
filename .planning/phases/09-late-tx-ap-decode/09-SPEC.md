# Phase 9: Late-Start FT8 TX + A-Priori (AP) Decoding — Specification

**Created:** 2026-06-22
**Ambiguity score:** 0.16 (gate: ≤ 0.20)
**Requirements:** 8 locked
**Reference implementation:** POTACAT v1.8.14 — https://github.com/Waffleslop/POTACAT (used as a behavior reference; this is not a POTACAT port)

## Goal

FT8VC gains two decode/TX capabilities that materially expand operating
capability without changing the v1.0 RX/TX/CAT/QSO behavior contract on the
reference FT-891 + Digirig rig:

1. **Late-start TX** — the operator can tap Answer/Resume up to **7.000 s**
   into a 15 s slot and the next TX still goes out on that slot, with the
   on-air waveform truncated from the front (full FEC-encoded message is
   always synthesized; only leading symbols are skipped before the speaker)
   and the tail landing on the normal 15 s boundary.
2. **A-priori (AP) decoding** — every slot's normal decode pass is followed by
   an AP pass over candidates the standard decoder rejected, hypothesizing
   the operator's own callsign and (once a QSO is in progress) the partner's
   callsign, recovering weak/late replies that still validate the 14-bit CRC.

Both ship **enabled by default** with Settings toggles to disable.

## Background

**Late-start TX, today.** The TX path is:
[OperateViewModel](app/src/main/java/net/ft8vc/app/OperateViewModel.kt) →
[TxOrchestrator.transmit()](app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt:243)
→ `Ft8Native.encode(message, txFreqHz, 12000)` (returns a `ShortArray` of
12 kHz PCM samples for the full 15 s waveform — see
[ft8_jni.cpp:257-277](ft8-native/src/main/cpp/ft8_jni.cpp)) →
[UsbAudioPlayback.playBlocking(samples12k)](audio/src/main/java/net/ft8vc/audio/UsbAudioPlayback.kt:47).
Playback today blocks on the full sample array; PTT is held for the entire
duration. There is no symbol-clock-aware truncation, and TX scheduling
(`transmitNextSlot`) only fires at the next slot boundary — a late tap
inside the current slot is silently dropped.

**AP decoding, today.** The decode pass lives in
[ft8_jni.cpp:199](ft8-native/src/main/cpp/ft8_jni.cpp) and calls
`ftx_decode_candidate(wf, cand, kLdpcIterations, &message, &status)` from
the pinned kgoba ft8_lib commit `9fec6ca39886edbf96f4f5e71edc76da5074e871`
(via CMake FetchContent in
[ft8-native/src/main/cpp/CMakeLists.txt](ft8-native/src/main/cpp/CMakeLists.txt)).
**The pinned kgoba ft8_lib has NO a-priori / hint API** — `grep -n
"ap_\|a_priori\|hint"` over `decode.{c,h}` and `ldpc.{c,h}` returns zero
matches. Candidates that fail standard LDPC iteration are discarded; there
is no second pass and no operator-specific hint injection.

**Why now.** POTACAT v1.8.14 introduced both features upstream and operators
already running POTACAT have provided field evidence that (a) late-tap-then-
recover is a routine ergonomic in POTA/contest operating and (b) AP recovers
real QSOs at the edge of decode threshold without fabricating contacts
(CRC-14 is the safety property). FT8VC's v1.x code-health milestone is
complete (Phases 0–7), the RX/TX paths are now controller-isolated, and the
TxOrchestrator's 4-layer PTT defense + watchdog (Phase 5) is the right
foundation to add a TX-truncation feature without re-opening the PTT-safety
risk surface.

**Out of scope here.** A separate, earlier phase is handling decode-list
legibility (own-TX rows, column header, worked-before coloring). This phase
adds **only** the `AP` badge for AP-recovered decodes; styling of existing
rows is owned there.

## Requirements

1. **R1 — Late-TX symbol-clock truncation in JNI**: The native encode path
   accepts an `offset_symbols` parameter and synthesizes only symbols
   `[offset_symbols, FT8_NN)` of the waveform, while still running the full
   `ftx_message_encode` + `ft8_encode` over all 79 symbols (so FEC is
   complete on every transmission).
   - Current: `Ft8Native.encode(message, freqHz, sampleRate)` and
     `ft8_jni.cpp:synth_gfsk` always synthesize all `FT8_NN` (79) symbols
     starting at sample 0.
   - Target: `Ft8Native.encode(message, freqHz, sampleRate, offsetSymbols=0)`
     returns a `ShortArray` of `(FT8_NN - offsetSymbols) * samplesPerSymbol`
     12 kHz samples. The first sample corresponds to the start of symbol
     `offsetSymbols` (no fractional-symbol head). When `offsetSymbols == 0`,
     output is byte-identical to the v1.0 path (regression guard).
   - Acceptance: Unit test `Ft8NativeLateTxTest` calls `encode(msg, 1500.0,
     12000, 0)` and `encode(msg, 1500.0, 12000, 30)` for a fixed
     deterministic message; asserts `result.size == fullLen - 30 *
     samplesPerSymbol` and that `result` equals the tail of the
     `offsetSymbols=0` output within ±1 LSB per sample.

2. **R2 — Late-TX scheduling envelope**: TxOrchestrator accepts a transmit
   request up to **7.000 s** into the current slot, computes the symbol
   offset that lands the tail on the normal 15 s boundary, calls
   `Ft8Native.encode(..., offsetSymbols)`, keys PTT immediately, and plays
   the truncated waveform.
   - Current: `TxOrchestrator.transmit()` only fires at the next slot
     boundary; a late tap is queued to the next slot (loses the current
     QSO turn).
   - Target: When the operator triggers TX at slot-relative time
     `t_in_slot ∈ [0.000, 7.000] s`, TxOrchestrator (a) computes
     `offsetSymbols = floor(t_in_slot / FT8_SYMBOL_PERIOD)` where
     `FT8_SYMBOL_PERIOD = 0.160 s`, (b) waits `(t_in_slot mod
     FT8_SYMBOL_PERIOD)` so the first emitted sample aligns with a symbol
     boundary, (c) keys PTT, (d) plays the truncated waveform, (e)
     releases PTT on completion (existing 4-layer defense applies
     unchanged). For `t_in_slot > 7.000 s`, behavior is unchanged from v1.0
     (queue to next slot).
   - Acceptance: `TxOrchestratorLateTxTest` injects a `FakeClock` advancing
     `t_in_slot` to 6.500 s, calls `transmit(msg)`; asserts (i) PTT keyed
     within 50 ms, (ii) playback called with a sample count corresponding
     to `floor((15.0 - 6.500) / 0.160)` symbols ± 1, (iii) PTT released
     within 250 ms of waveform end. A second test at `t_in_slot = 7.500 s`
     asserts the request is deferred to the next slot (no PTT key in
     current slot).

3. **R3 — Late-TX UI affordance**: The Answer/Resume button surfaces a
   "Late TX" countdown while the late-start window is open in the current
   slot.
   - Current: The Answer/Resume button has a single label; pressing it
     after a slot has started does nothing visible until the next slot
     boundary.
   - Target: Between slot-relative `t=0.000s` and `t=7.000s`, the button
     subtitle reads "Late TX: Xs left" where `X = ceil(7.0 - t_in_slot)`
     and the button retains its primary color. From `t=7.001s` to slot end,
     the subtitle reverts to v1.0 behavior (no "Late TX" text).
   - Acceptance: `OperateScreenLateTxIndicatorTest` (Compose test) advances
     a fake slot clock to 3.0 s, asserts the Answer button subtitle
     contains "Late TX: 4s left"; advances to 7.5 s, asserts the subtitle
     no longer contains "Late TX".

4. **R4 — In-tree AP decode pass in JNI**: An in-tree patch on top of
   pinned kgoba ft8_lib adds an `ftx_decode_candidate_ap(...)` entry point
   that retries a rejected candidate with a hint payload clamping the
   operator's own callsign bits (and, when supplied, the partner's
   callsign bits) before LDPC iteration. The 14-bit CRC must validate on
   the recovered message, exactly as the standard path.
   - Current: `ftx_decode_candidate` accepts no hint; rejected candidates
     are discarded.
   - Target: A patch file at `ft8-native/src/main/cpp/ft8lib-ap.patch` is
     applied automatically after FetchContent populates `ft8lib-src/`
     (Phase 9 plan-phase chooses the apply mechanism — patch step in
     CMakeLists.txt is the expected approach). The patch adds
     `ftx_decode_candidate_ap(wf, cand, max_iters, hint_payload,
     hint_mask, &message, &status)` where `hint_mask` is a bitmask marking
     which payload bits are clamped. CRC-14 validation in the patched path
     is bit-identical to the standard path (the patch MUST NOT relax CRC).
   - Acceptance: `Ft8NativeApDecodeTest` feeds a known-weak candidate
     (synthesized at SNR below the standard threshold, with own-callsign
     hint applied) and asserts the AP path returns the original message
     and `status.crc_ok == true`. A negative test feeds random noise with
     the same hint and asserts no decode is returned (CRC rejects).

5. **R5 — AP pass orchestration + hint sources**: After the standard decode
   pass each slot, the JNI runs an AP pass over standard-rejected
   candidates using two hint sources: (a) **own-call hint** —
   `SettingsRepository.stationCall` (always present when TX is licensed);
   (b) **partner-call hint** — `QsoSessionController.qsoDx` when
   `qsoActive == true`. Decodes recovered via AP are returned with an
   `isAp: Boolean = true` flag through to the UI layer.
   - Current: One decode pass per slot; no hints; no AP flag in
     `Ft8DecodeResult` / `DecodeRow`.
   - Target: `Ft8DecoderApi.decode(...)` gains an optional `hints:
     List<ApHint>` parameter where `ApHint` carries a clamped payload + mask
     for a single callsign (own or DX). The JNI executes the standard pass
     first; for each rejected candidate, runs `ftx_decode_candidate_ap` per
     hint until one validates CRC or all hints are exhausted. Recovered
     decodes carry `isAp = true` in `Ft8DecodeResult`; this flag
     propagates through `DecodeController.DecodeRow` to the UI slice.
   - Acceptance: `DecodeControllerApTest` (using `Ft8DecoderFake` extended
     to mark canned results as `isAp = true`) asserts (i) hints are
     constructed from `SettingsBridge.slice.stationCall` and
     `QsoSessionController.slice.qsoDx`, (ii) `isAp = true` survives the
     `decodesOut` SharedFlow → `DecodeListPanel` slice projection, (iii)
     when `qsoActive = false`, only the own-call hint is supplied.

6. **R6 — `AP` badge in decode list**: AP-recovered decodes display a
   compact `AP` badge in the decode list row, distinct from the dB/Hz
   metadata, without crowding existing columns.
   - Current: `DecodeListPanel` rows show timestamp, dB, dT, Hz, message
     text. No badge surface exists.
   - Target: When `DecodeRow.isAp == true`, an `AP` chip (Material 3
     `AssistChip` or equivalent compact surface) renders inline at the end
     of the row, with an `accessibilityLabel = "A-priori decode"`. Non-AP
     rows render unchanged (zero visual delta vs v1.0).
   - Acceptance: `DecodeListPanelApBadgeTest` (Compose test) renders a row
     with `isAp = true`, asserts an element with text "AP" is present and
     has the accessibility label; renders a row with `isAp = false`,
     asserts no "AP" element is present. Snapshot diff against the v1.0
     baseline for the non-AP row shows zero pixel delta.

7. **R7 — Settings toggles (on by default)**: Both features ship enabled
   by default and are individually disablable via Settings; the toggle
   state is persisted via DataStore (`SettingsRepository`).
   - Current: No such toggles exist.
   - Target: Settings screen gains two new rows under an "Advanced
     decoding / TX" group: (a) "Late-start TX (up to 7s into slot)" —
     default ON; (b) "A-priori decode (recover weak replies)" — default
     ON. Both persist via `SettingsRepository` with keys
     `late_start_tx_enabled` (default `true`) and `ap_decode_enabled`
     (default `true`). Disabling Late-TX makes R2's late path a no-op (the
     v1.0 "queue to next slot" behavior returns). Disabling AP skips the
     AP pass entirely (R5 returns the standard decode list unchanged).
   - Acceptance: `SettingsRepositoryToggleTest` asserts both keys default
     to `true` on a fresh install. `TxOrchestratorLateTxTest` re-runs the
     R2 acceptance with the late-TX toggle set to `false` and asserts the
     request defers to the next slot. `DecodeControllerApTest` re-runs the
     R5 acceptance with the AP toggle set to `false` and asserts the
     decoder receives an empty `hints` list.

8. **R8 — Behavior-parity preservation on toggle OFF**: With both toggles
   set to `false`, RX/TX/CAT/QSO behavior on the reference rig is
   byte-equivalent to v1.0 (Phase 0 PARITY-01 / FOUND-07 golden-trace).
   - Current: PARITY-01 baseline established at Phase 0; subsequent
     refactor phases preserved it.
   - Target: With `late_start_tx_enabled = false` AND `ap_decode_enabled
     = false`, the golden-trace harness (FOUND-06) replay against the
     Phase 0 baseline (`.planning/field-sessions/baseline-<date>/`)
     passes with zero diff.
   - Acceptance: CI runs the golden-trace test (existing) against a
     fixture where both toggles are forced `false`; the test passes with
     identical state-transition output to the Phase 0-7 baseline. A
     second CI run with both toggles `true` asserts the test still passes
     (golden-trace must tolerate AP-recovered decodes as additive — they
     append to the decode list, they do not change the standard decode
     stream).

## Boundaries

**In scope:**

- JNI: an in-tree patch on top of pinned kgoba ft8_lib adding
  `ftx_decode_candidate_ap`; a new `Ft8Native.encode(..., offsetSymbols)`
  signature; AP-pass orchestration in `ft8_jni.cpp` per slot.
- Kotlin: `TxOrchestrator` late-TX gating + symbol-clock alignment;
  `DecodeController` AP-pass plumbing and `isAp` propagation; two new
  Settings toggles in `SettingsRepository`.
- UI: "Late TX: Xs left" Answer-button subtitle; compact `AP` chip in
  `DecodeListPanel` rows; two new Settings rows under "Advanced
  decoding / TX".
- Tests: native AP regression (R4), late-TX JNI regression (R1),
  TxOrchestrator late-TX scheduling (R2), Compose tests for indicator
  (R3) and badge (R6), Settings toggle defaults (R7), golden-trace
  parity with toggles off (R8).
- Field verification: on-air session on the reference FT-891 + Digirig
  exercising both features against a real partner; recorded to
  `.planning/field-sessions/late-tx-ap-<date>/`.

**Out of scope:**

- Decode-list legibility (own-TX rows, column header, worked-before
  coloring) — owned by the earlier separate phase that is designing
  this work first. Touching those rows in this phase would create a
  merge contention; the `AP` chip is the only decode-list visual delta
  this phase introduces.
- Bumping the pinned kgoba ft8_lib commit — decision was an in-tree
  patch so the pin stays auditable. Any subsequent upstream-bump is its
  own phase.
- AP hints for grids/exchanges beyond callsign payload bits — POTACAT
  v1.8.14 hints only on callsigns; matching that surface keeps CRC
  safety the same property and avoids a larger search space.
- Auto-tuning of the 7.0 s late-TX cutoff — fixed at 7.0 s for parity
  with POTACAT; if field evidence later supports a different threshold,
  that's a follow-on tweak.
- TX truncation by fractional symbols — sample alignment is always to a
  whole symbol boundary (R2 (b) wait) so the receiver's middle/end
  Costas re-sync property holds.

## Constraints

- **Pin stays pinned.** ft8_lib remains at kgoba
  `9fec6ca39886edbf96f4f5e71edc76da5074e871` via FetchContent. AP support
  arrives as an in-tree patch file applied after FetchContent (apply
  mechanism chosen in plan-phase). No upstream-commit churn in this
  phase.
- **CRC-14 is load-bearing.** The patched AP path MUST NOT relax or skip
  CRC validation. If CRC fails, the candidate is rejected — no
  fabricated contacts.
- **PTT-safety primacy.** R2 cannot weaken TxOrchestrator's existing
  4-layer PTT defense. Late-TX path must flow through the same
  `transmit()` entry point and respect the existing `try-finally` +
  `AutoCloseable` + `withTimeoutOrNull(SLOT_DURATION_MS + 500)` +
  250 ms watchdog (Phase 5 SAFETY-01..10). The timeout's upper bound
  remains the FULL slot duration plus 500 ms; the truncated waveform
  finishes earlier and that is fine — the watchdog still forces release
  if anything is stuck.
- **License gate preserved.** Late-TX is a TX feature; the existing
  license-acknowledgment gate (`AppRfState.READY` precondition)
  continues to block TX before acknowledgment. The Settings toggle for
  Late-TX is visible regardless, but has no effect until license is
  acknowledged.
- **No new top-level screen / tab.** PARITY-03 holds: the two new
  Settings rows live inline under an existing-style group; the `AP`
  chip and "Late TX" countdown surface inline.
- **No new top-level dependencies** (CLAUDE.md milestone rule). The
  `AP` chip uses an existing Material 3 surface. The patch ships as a
  text file under `ft8-native/src/main/cpp/` and applies with `git
  apply` or CMake `execute_process` — no new build tooling.
- **minSdk 28, NDK r29, JVM 17** unchanged.
- **Promotion gate.** Behavior-parity replay (FOUND-07) and
  recompose-baseline (FOUND-08) gates from Phase 0 apply unchanged.
  Promotion to `main` requires the on-air field session under
  `.planning/field-sessions/late-tx-ap-<date>/` to be committed
  and signed off in the PR.

## Acceptance Criteria

- [ ] `Ft8Native.encode(msg, 1500.0f, 12000, 0)` is byte-identical to
      the v1.0 path (regression guard for R1).
- [ ] `Ft8Native.encode(msg, 1500.0f, 12000, 30)` returns `(79 - 30)
      * samplesPerSymbol` samples that match the tail of the
      `offsetSymbols=0` output (R1).
- [ ] `TxOrchestrator.transmit()` invoked at `t_in_slot = 6.500 s` keys
      PTT within 50 ms and plays a truncated waveform whose tail lands
      within 50 ms of the next slot boundary (R2).
- [ ] `TxOrchestrator.transmit()` invoked at `t_in_slot = 7.500 s`
      defers to next slot (no PTT in current slot) (R2).
- [ ] Answer button subtitle reads "Late TX: Xs left" for `t_in_slot ∈
      [0.000, 7.000]` and reverts after 7.000 s (R3).
- [ ] `ft8lib-ap.patch` applies cleanly to the FetchContent-populated
      `ft8lib-src/` directory on a fresh build (R4).
- [ ] `ftx_decode_candidate_ap` recovers a known-weak candidate with
      own-call hint and CRC validates (R4).
- [ ] `ftx_decode_candidate_ap` rejects pure noise even with hint
      supplied (CRC rejects) (R4).
- [ ] Decode list rows with `isAp = true` show an "AP" chip with
      accessibility label "A-priori decode" (R6).
- [ ] Decode list rows with `isAp = false` show zero pixel delta vs the
      v1.0 baseline snapshot (R6).
- [ ] Settings → "Advanced decoding / TX" → "Late-start TX (up to 7s
      into slot)" defaults to ON; toggling OFF reverts to v1.0
      "queue to next slot" behavior (R7).
- [ ] Settings → "Advanced decoding / TX" → "A-priori decode (recover
      weak replies)" defaults to ON; toggling OFF skips the AP pass
      entirely (R7).
- [ ] Golden-trace replay (FOUND-06) against Phase 0 baseline passes
      with both toggles forced OFF (R8).
- [ ] Golden-trace replay passes with both toggles ON (AP-recovered
      decodes are additive, not substitutive) (R8).
- [ ] On-air session on the reference FT-891 + Digirig completes at
      least one QSO using Late-TX (operator confirms tap was > 3 s
      into slot) AND at least one decode marked `AP` in the field log;
      recorded under `.planning/field-sessions/late-tx-ap-<date>/`.
- [ ] Recomposition count for the Operate tab over one full slot cycle
      with AP decodes streaming does not exceed the Phase 0 baseline
      (FOUND-08) by more than 5%.
- [ ] All four PTT-safety layers from Phase 5 still trigger correctly
      under the late-TX path (`TxOrchestratorTest` extended to inject
      a late-TX call into each PTT-safety scenario).

## Edge Coverage

**Coverage:** 8/8 applicable edges resolved · 0 unresolved

| Category               | Requirement | Status      | Resolution / Reason |
|------------------------|-------------|-------------|---------------------|
| Boundary (timing)      | R2          | ✅ covered  | AC: `t_in_slot = 7.500 s` defers to next slot; `7.000 s` allowed |
| Boundary (timing)      | R2          | ✅ covered  | AC: symbol-boundary alignment via `(t_in_slot mod 0.160)` wait — no fractional-symbol waveform head |
| Boundary (offset=0)    | R1          | ✅ covered  | AC: `offsetSymbols=0` byte-identical to v1.0 (regression guard) |
| Concurrency (PTT race) | R2          | ✅ covered  | AC: Phase 5 4-layer defense unchanged; `TxOrchestratorTest` extended for late-TX path |
| Adversarial (CRC)      | R4          | ✅ covered  | AC: noise + hint → no decode (CRC rejects); CRC validation NOT relaxed in patch |
| State (no-QSO hint)    | R5          | ✅ covered  | AC: when `qsoActive=false`, only own-call hint supplied (partner hint suppressed) |
| State (toggle OFF)     | R7, R8      | ✅ covered  | AC: golden-trace passes with both toggles OFF; v1.0 paths restored |
| Compatibility (parity) | R8          | ✅ covered  | AC: golden-trace passes with toggles ON (AP additive, not substitutive) |

## Prohibitions (must-NOT)

**Coverage:** 4/4 applicable prohibitions resolved · 0 unresolved

| Prohibition (must-NOT statement) | Requirement | Status              | Verification / Reason |
|----------------------------------|-------------|---------------------|------------------------|
| MUST NOT relax or bypass CRC-14 validation in the AP path | R4 | resolved / test | `Ft8NativeApDecodeTest` negative case: noise + hint returns no decode |
| MUST NOT weaken any of the 4 PTT-safety layers in the late-TX path | R2 | resolved / test | `TxOrchestratorTest` extended: each PTT-safety scenario re-asserted with a late-TX call |
| MUST NOT skip the on-air field-session evidence before promotion to `main` | (all) | resolved / judgment | PR promotion checklist (FOUND-01) gates on `.planning/field-sessions/late-tx-ap-<date>/` artifact |
| MUST NOT introduce a new top-level screen, tab, or dependency (PARITY-03 / CLAUDE.md) | R3, R6, R7 | resolved / judgment | Code review gate; new surfaces are inline chips/Settings rows only |

## Ambiguity Report

| Dimension          | Score | Min  | Status | Notes                                                                 |
|--------------------|-------|------|--------|----------------------------------------------------------------------|
| Goal Clarity       | 0.90  | 0.75 | ✓      | Two features, both with concrete numeric thresholds (7.000 s, CRC-14) |
| Boundary Clarity   | 0.85  | 0.70 | ✓      | Decode-list legibility, ft8_lib bump, grid/exchange hints all out    |
| Constraint Clarity | 0.70  | 0.65 | ✓      | Pin stays pinned; patch-apply mechanism deferred to plan-phase       |
| Acceptance Criteria| 0.80  | 0.70 | ✓      | 16 pass/fail criteria across 8 requirements                           |
| **Ambiguity**      | 0.16  | ≤0.20| ✓      |                                                                       |

## Interview Log

| Round | Perspective       | Question summary                                                    | Decision locked                                                              |
|-------|-------------------|---------------------------------------------------------------------|------------------------------------------------------------------------------|
| 0     | Codebase scout    | Does kgoba ft8_lib expose any AP/hint API at the pinned commit?     | NO — confirmed via grep over `decode.{c,h}` and `ldpc.{c,h}` (zero matches). AP must be added in-tree. |
| 0     | Codebase scout    | Where is the TX waveform synthesized?                               | `ft8_jni.cpp:synth_gfsk` (lines 113–277) — pure C++ produces the full `ShortArray`. Truncation is cleanest at the JNI symbol-stream layer, not at the Kotlin PCM-slice layer. |
| 0     | Codebase scout    | How is TX invoked today?                                            | `TxOrchestrator.transmit()` → `decoder.encode(...)` → `playback.playBlocking(pcm)`. Late-TX adds an `offsetSymbols` arg to encode and a symbol-clock-aligned wait before key. |
| 1     | User (Phase #)    | What phase number?                                                  | Phase 9 (Phase 8 reserved for decode-list legibility per user's note). Roadmap is from a prior milestone goal — user OK with this as a standalone follow-on phase. |
| 1     | User (Defaults)   | Toggles vs on-by-default?                                           | Both ON by default with Settings toggles to disable (R7). |
| 1     | User (AP source)  | Bump pin vs carry patch?                                            | In-tree patch on top of kgoba — keeps the pin auditable (R4, Constraint #1). |
| 2     | Boundary Keeper   | What MUST stay out?                                                 | Decode-list legibility (own-TX rows, worked-before coloring, column header) — owned by separate earlier phase. ft8_lib bump. Hints beyond callsigns. |
| 3     | Failure Analyst   | What does a broken version look like?                               | (a) Fabricated AP contacts (CRC weakened) → R4 prohibition #1. (b) PTT stuck open under late-TX path → R2 prohibition #2 + AC re-running Phase 5 PTT-safety scenarios. (c) Late waveform tail crossing the next slot boundary → R2 (b) symbol-boundary wait + AC tail-within-50ms. |
| 3     | Failure Analyst   | What if both toggles get flipped off (rollback)?                    | R8: golden-trace passes against Phase 0 baseline with both toggles OFF — both features are additive and reversibly gated. |

---

*Phase: 09-late-tx-ap-decode*
*Spec created: 2026-06-22*
*Next step: /gsd-discuss-phase 9 — implementation decisions (patch-apply mechanism, symbol-clock wait primitive, AP hint payload construction, Settings group placement)*
