# State: FT8VC — v1.x Code Health Milestone

**Last Updated:** 2026-06-21 (initial creation by roadmapper)

## Project Reference

**Core Value:** The rig still keys, decodes still arrive, and QSOs still complete on a real FT-891 + Digirig in the field — every change in this milestone must preserve that.

**Current Focus:** Refactor the monolithic `OperateViewModel` (1,135 LOC) into 5 focused controllers (SettingsBridge, RigSession, DecodeController, QsoSessionController, TxOrchestrator); migrate from Executors + manual threads + `Thread.sleep` to structured coroutines on dedicated dispatchers; close the worst RF-safety, reliability, and hygiene gaps from the 2026-06-21 CONCERNS audit; bring every extracted controller under test. No feature surface expansion.

**Milestone:** v1.x Code Health (rolling on `unstable`; promotion to stable `main` happens only when the milestone is verified end-to-end on the reference FT-891 + Digirig rig).

## Current Position

**Phase:** — (no phase started yet)
**Plan:** — (no plan started yet)
**Status:** Roadmap created; awaiting `/gsd-plan-phase 0` to begin Phase 0 (Foundations).

**Progress:** [░░░░░░░░] 0/8 phases complete

## Performance Metrics

| Metric | Value |
|--------|-------|
| Total phases | 8 (Phase 0 through Phase 7) |
| Total v1 requirements | 56 (8 FOUND + 9 REFACTOR + 10 SAFETY + 7 RELY + 6 UX + 5 HYG + 8 TEST + 3 PARITY) |
| Requirements mapped to phases | 53 (PARITY-01/02/03 are cross-cutting, applied at every phase boundary) |
| Phases completed | 0/8 |
| Plans completed | 0 |
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

- [ ] Run `/gsd-plan-phase 0` to plan Phase 0 (Foundations).

### Blockers

None.

### Surprises / Notes

- Phase 0 must complete in full before any subsequent phase can start. PITFALLS Pitfall 10 makes `FakeRigBackend` + golden-trace harness a hard prerequisite, and PARITY-02 makes the promotion checklist + behavior-parity baseline non-skippable.
- Phase 5 (RF Safety + TxOrchestrator) will likely need its own deeper research session during planning — 4-layer PTT defense has subtle cross-thread exception semantics; watchdog timing needs real-rig calibration (per research/SUMMARY.md "Research Flags" section).
- Phase 4 (qsoLock removal) needs close reading of the current synchronization pattern; lock-invariant documentation must be committed BEFORE the lock is deleted, with reviewer sign-off (PITFALLS Pitfall 7).
- Phase 6 may need a focused spike on `AudioDeviceCallback` vendor inconsistencies (Android 9-14) and the specific `usb-serial-for-android` driver timeout API on the reference device.

## Session Continuity

**Last session ended:** 2026-06-21 (roadmap creation session)
**Resume point:** Begin Phase 0 planning via `/gsd-plan-phase 0`.

**Files of record:**
- `.planning/PROJECT.md` — milestone scope, core value, constraints, key decisions
- `.planning/REQUIREMENTS.md` — 56 v1 requirements with phase traceability
- `.planning/ROADMAP.md` — 8 phases with goals and success criteria
- `.planning/research/SUMMARY.md` — research synthesis
- `.planning/research/STACK.md`, `FEATURES.md`, `ARCHITECTURE.md`, `PITFALLS.md` — research detail
- `.planning/codebase/CONCERNS.md` — the audit driving this milestone (source of truth for in-scope work)

---

*State initialized: 2026-06-21*
