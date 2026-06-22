---
phase: 00-foundations
plan: 01
subsystem: promotion-gate
tags: [foundations, promotion-checklist, pr-template, rf-safety]
status: complete
requires: []
provides:
  - promotion-checklist
  - pr-template
affects:
  - .planning/
  - .github/
tech_stack:
  added: []
  patterns:
    - committed-checklist-as-gate
key_files:
  created:
    - .planning/promotion-checklist.md
    - .github/PULL_REQUEST_TEMPLATE.md
  modified: []
decisions:
  - Field-Session Gate uses 10 explicit checklist items in fixed order, matching ROADMAP Phase 0 Success Criterion 1 verbatim.
  - Negative-grep literals (e.g. `_state.update`, `Thread.sleep`, `synchronized(qsoLock)`) are deliberately excluded from the checklist body so future grep-gates in other plans are not poisoned by this widely-referenced file.
  - PR template uses a relative link `../.planning/promotion-checklist.md` so it resolves correctly from the GitHub PR URL.
metrics:
  duration: ~10m
  tasks_completed: 2
  files_created: 2
  files_modified: 0
  commits: 2
  completed_date: 2026-06-22
requirements_satisfied:
  - FOUND-01
  - FOUND-02
  - TEST-07
  - TEST-08
---

# Phase 0 Plan 01: Promotion Checklist + PR Template Summary

Authored the milestone's promotion-gate artifacts — a committed field-session
checklist and a PR template carrying the sign-off checkbox — so every subsequent
phase (1-7) must pass a visible, mandatory bar before promoting `unstable → main`.

## What Shipped

### Files Created (2)

| Path                                  | Purpose                                                          | Lines |
| ------------------------------------- | ---------------------------------------------------------------- | ----- |
| `.planning/promotion-checklist.md`    | FOUND-01 authoritative gate; 10-item field-session matrix         | 98    |
| `.github/PULL_REQUEST_TEMPLATE.md`    | FOUND-02 PR template wiring the sign-off + RF-irrelevant skip     | 24    |

### Files Modified (0)

No production code was modified. `git diff --stat f94193b..HEAD` shows only the
two files above, totaling 122 insertions.

## Commits (per-task atomic)

| Commit  | Type | Message                                                       |
| ------- | ---- | ------------------------------------------------------------- |
| d58bec8 | docs | add v1.x promotion checklist with field-session gate (Task 1) |
| d6cf102 | docs | add PR template wiring promotion-checklist sign-off (Task 2)  |

## Acceptance Criteria Status

All criteria from the plan `<verification>` and `<success_criteria>` blocks PASS.

### Plan `<verification>` block

- [x] `test -f .planning/promotion-checklist.md && test -f .github/PULL_REQUEST_TEMPLATE.md` exits 0.
- [x] `grep -q 'Promotion checklist signed off' .github/PULL_REQUEST_TEMPLATE.md` exits 0.
- [x] All 10 field-session checklist items present in `.planning/promotion-checklist.md` (15 total `- [ ]` items across all gates; the 10 Field-Session items are the canonical set).
- [x] No production-code changes — `git diff --stat f94193b..HEAD` touches only the two declared files.

### Plan `<success_criteria>` block

- [x] Both files exist at their canonical paths.
- [x] "Promotion checklist signed off" line is grep-discoverable verbatim.
- [x] Every checklist item from ROADMAP Phase 0 Success Criterion 1 is present (cold boot, CAT read, 5 decodes/3 slots, PTT CAT-mode, PTT RTS-mode, dummy-load QSO cycle, mid-RX disconnect snackbar, relaunch no PTT-stuck).
- [x] TEST-07 (rapid restart x10 in 5 s) and TEST-08 (cable wiggle detach→reattach within 1 slot) are explicit checklist items with their requirement IDs cited.
- [x] PARITY-01/02/03 are referenced in the checklist body.

### Per-task `<verify>` automated checks

**Task 1:**
- [x] File exists and contains ≥10 `- [ ]` items (found 15).
- [x] Contains 'Rapid restart test', 'Cable wiggle test', 'TEST-07', 'TEST-08'.
- [x] Contains 'PARITY-01', 'PARITY-02', 'PARITY-03'.
- [x] Contains all four section headings: Field-Session Gate, Automated Gate, Recompose-Count Gate, RF-Irrelevant Skip Clause.
- [x] Negative-grep guard: contains 0 occurrences of `_state.update`, `Thread.sleep`, `synchronized(qsoLock)`.

**Task 2:**
- [x] File exists.
- [x] Contains 'Promotion checklist signed off' verbatim.
- [x] Contains markdown link to `.planning/promotion-checklist.md`.
- [x] Contains 'RF-Irrelevant Skip' heading.
- [x] Contains the three test-evidence bullets (golden-trace, behavior-parity, recompose count).

## Field-Session Items Captured (the 10)

Per plan Task 1 action — exact order, exact wording:

1. App boots cold on the reference FT-891 + Digirig device and claims the Digirig automatically.
2. CAT reads dial frequency within 2 s of opening Operate.
3. At least 5 decodes received across 3 consecutive RX slots.
4. PTT keys (CAT mode `TX1;`/`TX0;`) and releases cleanly — verified on rig TX indicator.
5. PTT keys (RTS mode via CP2102) and releases cleanly.
6. One complete CQ → answer → 73 cycle into a dummy load with auto-seq enabled.
7. Unplug Digirig mid-RX; "Digirig disconnected" snackbar appears within 5 s; PTT confirmed not keyed after detach.
8. Kill app via swipe and relaunch; no PTT-stuck on resume; license-ack still gating TX.
9. **TEST-07 — Rapid restart:** Start operating then Stop operating 10 times in 5 seconds; no orphan decoder thread, no stuck PTT after the final Stop.
10. **TEST-08 — Cable wiggle:** Detach Digirig USB cable then reattach within 1 slot (≤15 s); RX recovers within 1 slot of reattach; AudioRecord chain re-initialises without app relaunch.

The Automated Gate adds 3 more `- [ ]` items and the Recompose-Count Gate adds 2,
for a total of 15 checkboxes in the file. The 10 above are the canonical
Field-Session subset.

## Production-Code Constraint Compliance

Plan execution-rule 5: no modifications under `app/src/main/`, `audio/src/main/`,
`core/src/main/`, `data/src/main/`, `rig/src/main/`, `ft8-native/src/main/`. Diff
confirms the only touched paths are `.planning/promotion-checklist.md` and
`.github/PULL_REQUEST_TEMPLATE.md`. Constraint satisfied.

## Requirement IDs Satisfied

| ID       | How                                                                                          |
| -------- | -------------------------------------------------------------------------------------------- |
| FOUND-01 | `.planning/promotion-checklist.md` committed with the full field-session matrix.             |
| FOUND-02 | `.github/PULL_REQUEST_TEMPLATE.md` adds the sign-off checkbox + RF-irrelevant skip clause.   |
| TEST-07  | "Rapid restart test" item #9 in the Field-Session Gate, explicitly citing TEST-07.           |
| TEST-08  | "Cable wiggle test" item #10 in the Field-Session Gate, explicitly citing TEST-08.           |

## Deviations from Plan

None — plan executed exactly as written.

The only execution-time adjustment was that the worktree was created from a
historical SHA that predated the `.planning/` directory; a `git reset --hard` to
the orchestrator-specified expected base (`f94193b7`) was required at agent
startup. This was a setup-sync, not a plan deviation, and no plan content was
modified.

## Authentication Gates

None encountered. This was a pure-docs plan with no external service calls.

## Known Stubs

None. The checklist is the deliverable in its committed form; it is intentionally
manual (per FOUND-01 / PITFALLS Pitfall 12 — automated enforcement is out of
scope for v1.x and tracked via the recompose-count gate in Plan 00-05 plus the
behavior-parity replay in Plan 00-04, both of which sit downstream).

## Threat Flags

None. This plan creates only markdown files under `.planning/` and `.github/`;
no new network endpoints, auth paths, file access patterns, or schema changes
at trust boundaries are introduced. The threat register in the plan
(T-00-01 through T-00-SC) is satisfied by the checklist body and the
RF-Irrelevant Skip Clause exactly as designed.

## Self-Check: PASSED

Files:
- FOUND: .planning/promotion-checklist.md
- FOUND: .github/PULL_REQUEST_TEMPLATE.md

Commits:
- FOUND: d58bec8 (docs(00): add v1.x promotion checklist with field-session gate)
- FOUND: d6cf102 (docs(00): add PR template wiring promotion-checklist sign-off)
