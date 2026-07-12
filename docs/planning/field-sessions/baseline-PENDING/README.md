# baseline-PENDING — placeholder until FOUND-07 capture lands

This directory is committed only so the `docs/planning/field-sessions/` tree is visible before any controller extraction begins. The actual baseline directory MUST be named `baseline-<YYYY-MM-DD>/` (UTC date of capture) — it replaces this placeholder.

A `gate="blocking"` human-verify checkpoint in Phase 0 Plan 04 gates Phase 1 on the real-rig 5-minute behavior-parity baseline being captured per [`../RECORDING-FORMAT.md`](../RECORDING-FORMAT.md) on the reference FT-891 + Digirig setup. Until the real directory is committed and contains a `trace.jsonl` that parses cleanly through `GoldenTrace.loadJsonl`, the Phase 1 (and every subsequent refactor phase) PR template **Behavior-Parity Gate** checkbox cannot be honestly checked.

See [../README.md](../README.md) for the full directory schema and [`../RECORDING-FORMAT.md`](../RECORDING-FORMAT.md) for the JSONL recording format + capture procedure.

To capture the baseline: follow `RECORDING-FORMAT.md` end-to-end, then delete this `baseline-PENDING/` directory once the dated `baseline-<YYYY-MM-DD>/` directory is committed.
