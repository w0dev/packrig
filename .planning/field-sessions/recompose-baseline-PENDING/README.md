# recompose-baseline-PENDING — placeholder until FOUND-08 capture lands

This directory is committed only so the recompose-baseline tree is visible in `.planning/field-sessions/` before any refactor phase begins. The actual baseline directory MUST be named `recompose-baseline-<YYYY-MM-DD>/` (UTC date of capture) — it replaces this placeholder.

A `gate="blocking"` human-verify checkpoint in Phase 0 Plan 05 gates Phase 1 on the real baseline number being captured per the methodology in [`./METHODOLOGY.md`](./METHODOLOGY.md). Until the real directory is committed and `./baseline-number.txt` contains a valid integer, the Phase 1 (and every subsequent refactor phase) PR template **Recompose-Count Gate** checkbox cannot be honestly checked.

The promotion checklist's [Recompose-Count Gate section](../../promotion-checklist.md) references this baseline directly. Any refactor phase commit body must cite the baseline number from this directory; any post-refactor delta exceeding it (by more than ε, suggested ε = 0) fails the gate and blocks promotion from `unstable` to `main`.

To capture the baseline: follow [METHODOLOGY.md](./METHODOLOGY.md) end-to-end, then delete this `recompose-baseline-PENDING/` directory once the dated `recompose-baseline-<YYYY-MM-DD>/` is committed.
