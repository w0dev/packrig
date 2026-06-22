# Field-Session Evidence — FT8VC v1.x

## Purpose

This directory holds the field-session logs and behavior-parity baselines required by `PARITY-02` and FOUND-07 in the v1.x code-health milestone. Every promotion from `unstable` to `main` requires evidence committed here per the [promotion checklist](../promotion-checklist.md). The behavior-parity baseline captured before any controller extraction begins is the reference recording every refactor commit must replay against via the golden-trace harness (`app/src/test/java/net/ft8vc/app/foundations/golden/`).

## Directory Layout

| Directory | Purpose | Contents |
|---|---|---|
| `baseline-<YYYY-MM-DD>/` | FOUND-07 behavior-parity baseline (only one promoted baseline at a time; replaces `baseline-PENDING/`) | `trace.jsonl`, `README.md`, optionally `screenshots/`, `raw-logcat.txt` |
| `<YYYY-MM-DD>-phase-<N>-<description>/` | Per-phase promotion evidence (PARITY-02) | `trace.jsonl`, `README.md`, screenshots/logs as needed |
| `recompose-baseline-<YYYY-MM-DD>/` | Plan 05 output — Operate-tab recomposition baseline (FOUND-08) | `baseline-number.txt`, `README.md`, `runs/run-{1,2,3}.txt`, optionally `screenshots/` |
| `baseline-PENDING/` | Placeholder until FOUND-07 capture lands; deleted after the real dated directory is committed | Just a README flagging the gap |
| `recompose-baseline-PENDING/` | Placeholder until FOUND-08 capture lands; deleted after the real dated directory is committed | Methodology + README |

## Required Files per Directory

Every field-session directory must include:

- `README.md` — operator notes: device model, Android version, app version code + git commit SHA, Digirig firmware, dial frequency at capture time, observed anomalies.
- `trace.jsonl` — the recording in the JSONL schema defined by [`RECORDING-FORMAT.md`](./RECORDING-FORMAT.md).
- Optional `screenshots/` — UI captures for visual evidence.
- Optional `raw-logcat.txt` — the unprocessed logcat used to derive the JSONL.

The captured `trace.jsonl` MUST parse cleanly through `GoldenTrace.loadJsonl` (`app/src/test/java/net/ft8vc/app/foundations/golden/GoldenTrace.kt`) — if it does not, the recording is unusable for the parity gate and must be re-captured.

## Promotion Gate Reference

See [../promotion-checklist.md](../promotion-checklist.md) for the field-session evidence requirements every promotion PR must satisfy. The checklist's `Behavior-Parity Gate` section directly references `baseline-<YYYY-MM-DD>/trace.jsonl`; the `Recompose-Count Gate` section references `recompose-baseline-<YYYY-MM-DD>/baseline-number.txt`.
