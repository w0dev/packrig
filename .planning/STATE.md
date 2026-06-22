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

**Last Updated:** 2026-06-22 after Wave 2 of Phase 0 (Plans 01, 02, 03 merged into `readiness`)

## Project Reference

**Core Value:** The rig still keys, decodes still arrive, and QSOs still complete on a real FT-891 + Digirig in the field — every change in this milestone must preserve that.

**Current Focus:** Phase 0 — Foundations

**Milestone:** v1.x Code Health (rolling on `unstable`; promotion to stable `main` happens only when the milestone is verified end-to-end on the reference FT-891 + Digirig rig).

## Current Position

**Phase:** 0 (Foundations) — EXECUTING (partial)
**Plans done:** 3 of 5 (00-01 promotion checklist + PR template, 00-02 test classpath wiring, 00-03 three fakes + 24/24 self-tests)
**Plans pending:** 2 — both require human checkpoints
  - **00-05** — Compose recomposition-count baseline (operator measures in Android Studio Layout Inspector)
  - **00-04** — Golden-trace harness + CI workflow + 5-minute behavior-parity baseline (operator captures on reference FT-891 + Digirig)
**Status:** Autonomous waves complete; awaiting morning to run human-checkpoint plans

**Phase 0 progress:** [██████░░░░] 60% of plans complete
**Milestone progress:** [░░░░░░░░] 0/8 phases complete (Phase 0 still in-flight)

## Performance Metrics

| Metric | Value |
|--------|-------|
| Total phases | 8 (Phase 0 through Phase 7) |
| Total v1 requirements | 56 (8 FOUND + 9 REFACTOR + 10 SAFETY + 7 RELY + 6 UX + 5 HYG + 8 TEST + 3 PARITY) |
| Requirements mapped to phases | 53 (PARITY-01/02/03 are cross-cutting, applied at every phase boundary) |
| Phases completed | 0/8 (Phase 0 in progress — 3/5 plans done) |
| Plans completed | 3 (Phase 0: 00-01, 00-02, 00-03) |
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

- [ ] **Plan 00-05** (Compose recompose-count baseline) — operator measures in Android Studio Layout Inspector, commits a number under `.planning/field-sessions/recompose-baseline-<YYYY-MM-DD>/` (FOUND-08)
- [ ] **Plan 00-04** (golden-trace harness + CI workflow + behavior-parity baseline) — operator captures a 5-minute decode/TX session on the reference FT-891 + Digirig, commits the recording under `.planning/field-sessions/baseline-<YYYY-MM-DD>/` (FOUND-06, FOUND-07, TEST-06)
- [ ] After both checkpoints clear, resume with `/gsd-execute-phase 0` (or `--wave 1` for Plan 05 then `--wave 3` for Plan 04)
- [ ] Then `/gsd-verify-phase 0` (verifier was enabled in config)

### Blockers

- Plans 00-04 and 00-05 require human action (real-rig field session + Android Studio measurement). Cannot proceed until operator handles them.

### Surprises / Notes

- Phase 0 must complete in full before any subsequent phase can start. PITFALLS Pitfall 10 makes `FakeRigBackend` + golden-trace harness a hard prerequisite, and PARITY-02 makes the promotion checklist + behavior-parity baseline non-skippable.
- Phase 5 (RF Safety + TxOrchestrator) will likely need its own deeper research session during planning — 4-layer PTT defense has subtle cross-thread exception semantics; watchdog timing needs real-rig calibration (per research/SUMMARY.md "Research Flags" section).
- Phase 4 (qsoLock removal) needs close reading of the current synchronization pattern; lock-invariant documentation must be committed BEFORE the lock is deleted, with reviewer sign-off (PITFALLS Pitfall 7).
- Phase 6 may need a focused spike on `AudioDeviceCallback` vendor inconsistencies (Android 9-14) and the specific `usb-serial-for-android` driver timeout API on the reference device.

## Session Continuity

**Last session ended:** 2026-06-22 after Wave 2 merge — autonomous Phase 0 work complete on `readiness`
**Resume point:** Operator runs `/gsd-execute-phase 0` in the morning; the workflow will pick up Plans 00-04 and 00-05 (the human-checkpoint plans). Both pause at their `gate="blocking"` checkpoint and resume when the operator commits the requested artifacts under `.planning/field-sessions/`.

**Files of record:**

- `.planning/PROJECT.md` — milestone scope, core value, constraints, key decisions
- `.planning/REQUIREMENTS.md` — 56 v1 requirements with phase traceability
- `.planning/ROADMAP.md` — 8 phases with goals and success criteria
- `.planning/research/SUMMARY.md` — research synthesis
- `.planning/research/STACK.md`, `FEATURES.md`, `ARCHITECTURE.md`, `PITFALLS.md` — research detail
- `.planning/codebase/CONCERNS.md` — the audit driving this milestone (source of truth for in-scope work)

---

*State initialized: 2026-06-21*
