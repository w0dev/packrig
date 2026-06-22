# Plan 00-05 Summary — Recompose-Count Baseline Methodology

**Plan:** 00-05 (Operate-tab Compose recomposition-count baseline)
**Requirements covered:** FOUND-08
**Status:** Methodology + placeholder shipped. **Real baseline number SKIPPED with justification** (see "Skip Justification" below).
**Tasks:** 2/3 (autonomous) — Task 3 (human-verify checkpoint) skipped per operator directive.

## What Shipped

| File | Purpose |
|---|---|
| `.planning/field-sessions/recompose-baseline-PENDING/METHODOLOGY.md` | Documents the lightweight manual capture procedure (Android Studio Layout Inspector + Highlight Recompositions), the output-format spec (`baseline-number.txt` + `runs/run-{1,2,3}.txt` + operator README), and the acceptance criteria (3 runs, median ≤ 200, runs within ±30% of median, RX-only). Explicitly defers the heavier instrumented-test path to Phase 5. |
| `.planning/field-sessions/recompose-baseline-PENDING/README.md` | Placeholder flagging the missing real-rig baseline; cross-references METHODOLOGY.md and the promotion checklist's Recompose-Count Gate. |

## Commits

- `bfa9aef` — `docs(00): plan 05 recompose-baseline methodology + PENDING placeholder (FOUND-08, inline)`

## Skip Justification (Task 3 — human-verify checkpoint)

The blocking human-verify checkpoint for FOUND-08 (capture the actual median OperateScreen recomposition delta on the reference device via Android Studio Layout Inspector) was **skipped at operator direction** — the operator chose to ship infrastructure now and defer the real-rig capture to a later session. The PENDING placeholder makes the gap visible: Phase 1 (and every subsequent refactor phase) cannot honestly check the `Recompose-Count Gate` checkbox on its promotion PR until `.planning/field-sessions/recompose-baseline-<YYYY-MM-DD>/baseline-number.txt` exists with a valid integer.

This is a documented technical-debt item, not a defect:

- The methodology document fully satisfies the "documented" half of FOUND-08.
- The captured baseline number — the second half of FOUND-08 — is owed by the operator before any refactor phase honestly claims the Recompose-Count Gate.
- The operator may resolve this between Phase 0 and Phase 1, or fold it into Phase 5 planning where it logically lives (Phase 5 already touches Compose stability per PITFALLS Pitfall 6).

## Constraint Compliance

- No production source modified — files exclusively under `.planning/field-sessions/`.
- No test source modified.
- No build/Gradle files modified.

## Downstream Impact

- The promotion checklist's `Recompose-Count Gate` section (Plan 01) is wired to reference this baseline directory.
- Every refactor phase (1–7) must capture a post-refactor `OperateScreen` recompose count per the methodology and compare against the baseline number.
- Phase 5 in particular MUST measure the post-refactor count to validate that the `combine + distinctUntilChanged + @Immutable` pattern did not regress the storm.
