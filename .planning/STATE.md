---
gsd_state_version: 1.0
milestone: v1.0
milestone_name: milestone
current_phase: 0
current_phase_name: Foundations
status: executing
last_updated: "2026-06-22T12:57:03.333Z"
progress:
  total_phases: 8
  completed_phases: 0
  total_plans: 5
  completed_plans: 3
  percent: 0
---

# State: FT8VC — v1.x Code Health Milestone

**Last Updated:** 2026-06-22 after all 5 Phase 0 plans landed inline (Plans 01, 02, 03 via worktree subagents; Plans 04, 05 via direct orchestrator implementation with human-verify checkpoints skipped-with-justification per operator)

## Project Reference

**Core Value:** The rig still keys, decodes still arrive, and QSOs still complete on a real FT-891 + Digirig in the field — every change in this milestone must preserve that.

**Current Focus:** Phase 0 — Foundations

**Milestone:** v1.x Code Health (rolling on `unstable`; promotion to stable `main` happens only when the milestone is verified end-to-end on the reference FT-891 + Digirig rig).

## Current Position

**Phase:** 0 (Foundations) — INFRASTRUCTURE COMPLETE (human-verify items deferred)
**Plans done:** 5 of 5 infrastructure-wise
  - **00-01** promotion checklist + PR template (autonomous; merged via worktree)
  - **00-02** test classpath wiring (Turbine 1.2.1 + MockK 1.14.7 + kotlinx-collections-immutable 0.4.0 catalog-only; autonomous; merged via worktree)
  - **00-03** three fakes (FakeRigBackend, FakeUsbAudio{Capture,Playback}, Ft8DecoderFake) + 24/24 self-tests (autonomous; merged via worktree)
  - **00-04** golden-trace harness + CI workflow + recording-format spec + `baseline-PENDING/` placeholder (3 autonomous tasks via direct orchestrator implementation; human-verify Task 4 skipped — see "Deferred Human-Verify Items" below)
  - **00-05** recompose-baseline methodology + `recompose-baseline-PENDING/` placeholder (2 autonomous tasks via direct orchestrator implementation; human-verify Task 3 skipped — see "Deferred Human-Verify Items" below)
**Status:** Phase 0 infrastructure ready; Phase 1 can start. The two deferred human-verify items are tracked debt.

**Phase 0 progress:** [██████████] 100% of plans landed (infrastructure)
**Milestone progress:** [█░░░░░░░] Phase 0 complete (infrastructure-only); 1/8 phases ready

## Deferred Human-Verify Items (Tracked Debt)

These two captures were skipped at operator direction with the rationale "ship infrastructure now; capture real-rig artifacts later." The PENDING placeholders make the gaps visible on disk so Phase 1+ promotion gates cannot be honestly checked until they resolve.

| Item | Owner | Blocks | Path to satisfy |
|---|---|---|---|
| **FOUND-07** — 5-min decode/TX session on reference FT-891 + Digirig | Operator | Phase 1+ promotion `Behavior-Parity Gate` checkbox | Capture per `.planning/field-sessions/RECORDING-FORMAT.md`; commit `baseline-<YYYY-MM-DD>/trace.jsonl` + `README.md`; delete `baseline-PENDING/` |
| **FOUND-08** — Operate-tab recomposition baseline (Android Studio Layout Inspector, 3 runs, median) | Operator | Phase 1+ promotion `Recompose-Count Gate` checkbox | Capture per `.planning/field-sessions/recompose-baseline-PENDING/METHODOLOGY.md`; commit `recompose-baseline-<YYYY-MM-DD>/baseline-number.txt` + `runs/run-{1,2,3}.txt` + `README.md`; delete `recompose-baseline-PENDING/` |

Neither blocks **starting** Phase 1 — they block **promoting** any refactor phase from `unstable` to `main`. Resolving them as a single combined session (one rig boot, capture both) is the natural workflow.

## Performance Metrics

| Metric | Value |
|--------|-------|
| Total phases | 8 (Phase 0 through Phase 7) |
| Total v1 requirements | 56 (8 FOUND + 9 REFACTOR + 10 SAFETY + 7 RELY + 6 UX + 5 HYG + 8 TEST + 3 PARITY) |
| Requirements mapped to phases | 53 (PARITY-01/02/03 are cross-cutting, applied at every phase boundary) |
| Phases completed | 1/8 — Phase 0 infrastructure landed (2 human-verify captures deferred as tracked debt) |
| Plans completed | 5 (Phase 0: 00-01, 00-02, 00-03, 00-04 infrastructure, 00-05 infrastructure) |
| Phase 0 (Foundations) requirements | 11 (FOUND-01..08 + TEST-06..08) |
| Phase 5 (TxOrchestrator + RF Safety) requirements | 17 (largest phase; partial defense is not deliverable per PARITY-03) |

## Accumulated Context

### Key Decisions (locked at roadmap creation)

- **8 phases, not 5-8 standard.** PITFALLS Pitfall 1 mandates one-controller-per-commit; the 5 controller-extraction phases (1-5) cannot be merged. Wave phases (0, 6, 7) are already compressed.
- **Phase 5 carries all RF-safety work as one unit.** Partial 4-layer PTT defense is not deliverable per PARITY-03 / PITFALLS Pitfall 4. The 4 PTT defense layers + watchdog + `AppRfState` enum + USB-disconnect routing + license re-check + native lib version handshake + `combine` flow assembly all land in Phase 5.
- **Phase ordering deviates from a literal PITFALLS Pitfall 1 read.** `QsoSessionController` (Phase 4) extracts before `TxOrchestrator` (Phase 5) so that the `qsoLock`-removal commit stays separable from the TX-extraction commit. `QsoSessionController` temporarily calls back into the residual VM's TX path; Phase 5 rewires it.
- **PARITY-01..03 are cross-cutting**, not assigned to any single phase. They are enforced via the promotion checklist (FOUND-01) at every phase boundary.
- **Granularity = standard.** Hygiene, UX-polish, and ADIF auto-export merged into a single Phase 7 wave. CAT reliability + AudioRecord recovery merged into a single Phase 6 wave. Further compression is unsafe under RF-safety constraints.
- **No new top-level screens.** Hard constraint from PROJECT.md / PARITY-03. Every operator-facing delta surfaces via snackbar, status chip, or existing Settings row.
- **Dedicated single-thread dispatchers for JNI** (decode, encode, CAT, QSO). `Dispatchers.IO` reserved for Room/file. Per PITFALLS Pitfalls 2, 7, 9.

### Open Todos

- [ ] **FOUND-07** — Capture real-rig 5-minute baseline on FT-891 + Digirig. Commit `.planning/field-sessions/baseline-<YYYY-MM-DD>/trace.jsonl` + `README.md`; delete `.planning/field-sessions/baseline-PENDING/`. Blocks Phase 1+ `Behavior-Parity Gate`.
- [ ] **FOUND-08** — Capture Operate-tab recompose baseline (3 runs, median, Layout Inspector). Commit `.planning/field-sessions/recompose-baseline-<YYYY-MM-DD>/baseline-number.txt` + `runs/run-{1,2,3}.txt` + `README.md`; delete `.planning/field-sessions/recompose-baseline-PENDING/`. Blocks Phase 1+ `Recompose-Count Gate`.
- [ ] **Validation backlog** — `./gradlew :app:testDebugUnitTest --tests "*GoldenTraceTest"` was NOT run during the inline implementation (orchestrator shell has no Java). The next CI run (or local `./gradlew :app:testDebugUnitTest` invocation) is the first real validation of `GoldenTrace.kt`, `GoldenTraceReplay.kt`, and the canonical `cq-answer-73.jsonl` fixture. Any compile or assertion failure surfaces there.
- [ ] Run `/gsd-verify-phase 0` (verifier was enabled in config) once the two deferred captures land — or after CI confirms the golden-trace test passes, whichever the operator prefers.
- [ ] Then `/gsd-discuss-phase 1` to begin Phase 1 (Extract SettingsBridge).

### Blockers

- None blocking the **start** of Phase 1.
- Phase 1+ **promotion** to `main` (via `/gsd-ship`) is blocked on FOUND-07 + FOUND-08 captures landing.

### Surprises / Notes

- Phase 0 must complete in full before any subsequent phase can start. PITFALLS Pitfall 10 makes `FakeRigBackend` + golden-trace harness a hard prerequisite, and PARITY-02 makes the promotion checklist + behavior-parity baseline non-skippable.
- Phase 5 (RF Safety + TxOrchestrator) will likely need its own deeper research session during planning — 4-layer PTT defense has subtle cross-thread exception semantics; watchdog timing needs real-rig calibration (per research/SUMMARY.md "Research Flags" section).
- Phase 4 (qsoLock removal) needs close reading of the current synchronization pattern; lock-invariant documentation must be committed BEFORE the lock is deleted, with reviewer sign-off (PITFALLS Pitfall 7).
- Phase 6 may need a focused spike on `AudioDeviceCallback` vendor inconsistencies (Android 9-14) and the specific `usb-serial-for-android` driver timeout API on the reference device.

## Session Continuity

**Last session ended:** 2026-06-22 after Phase 0 infrastructure landed inline on `readiness` (Plans 04 + 05 implemented directly by the orchestrator after subagent spend-limit hit; human-verify captures skipped-with-justification per operator).
**Resume point:** Phase 1 is unblocked. Recommended next: `/gsd-discuss-phase 1` (Extract SettingsBridge — lowest-coupling controller; proves the slice/combine pattern). Before promoting Phase 1+ from `unstable` to `main`, resolve the two PENDING field-session captures (FOUND-07 + FOUND-08) in a single rig session.

**Files of record:**

- `.planning/PROJECT.md` — milestone scope, core value, constraints, key decisions
- `.planning/REQUIREMENTS.md` — 56 v1 requirements with phase traceability
- `.planning/ROADMAP.md` — 8 phases with goals and success criteria
- `.planning/research/SUMMARY.md` — research synthesis
- `.planning/research/STACK.md`, `FEATURES.md`, `ARCHITECTURE.md`, `PITFALLS.md` — research detail
- `.planning/codebase/CONCERNS.md` — the audit driving this milestone (source of truth for in-scope work)

---

*State initialized: 2026-06-21*
