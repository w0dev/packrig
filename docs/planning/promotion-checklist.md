# Promotion Checklist — FT8VC v1.x Code Health

This is the **authoritative promotion-gate checklist** for the FT8VC v1.x Code Health
milestone. Every PR merging from `unstable` to `main` must sign off the relevant
sections of this file in the PR body. The PR template
([.github/PULL_REQUEST_TEMPLATE.md](../../.github/PULL_REQUEST_TEMPLATE.md)) carries the
"Promotion checklist signed off" checkbox that links here.

> **Reference rig:** Yaesu FT-891 + Digirig over USB on the Android device named in
> `docs/planning/PROJECT.md`. All field-session items below must be exercised on that
> rig — emulator runs do not count.

## When to Run This Checklist

- **Every PR merging `unstable` → `main`** must sign off the Field-Session Gate, the
  Automated Gate, and (for refactor phases) the Recompose-Count Gate.
- **Every phase-end commit body** must cite the checklist result — at a minimum the
  golden-trace and behavior-parity replay outcomes plus the field-session date.
- A "low-risk" phase **still runs the full gate.** PITFALLS Pitfall 12 (promotion-gate
  rot) is explicit that the gate's failure mode is "I keyed once on dummy load, looked
  fine, promoted" — the only sanctioned bypass is the RF-Irrelevant Skip Clause below
  with a single-line justification.

## Field-Session Gate (required for every unstable → main promotion)

Run the entire list end-to-end on the reference FT-891 + Digirig in one session.
Commit the log/screenshots under `docs/planning/field-sessions/<date>/` (see Evidence
Storage). Tick each item only after the in-field observation; do not pre-tick.

- [ ] App boots cold on the reference FT-891 + Digirig device and claims the Digirig automatically.
- [ ] CAT reads dial frequency within 2 s of opening Operate.
- [ ] At least 5 decodes received across 3 consecutive RX slots.
- [ ] PTT keys (CAT mode `TX1;`/`TX0;`) and releases cleanly — verified on rig TX indicator.
- [ ] PTT keys (RTS mode via CP2102) and releases cleanly.
- [ ] One complete CQ → answer → 73 cycle into a dummy load with auto-seq enabled.
- [ ] Unplug Digirig mid-RX; "Digirig disconnected" snackbar appears within 5 s; PTT confirmed not keyed after detach.
- [ ] Kill app via swipe and relaunch; no PTT-stuck on resume; license-ack still gating TX.
- [ ] **Rapid restart test (TEST-07):** Start operating then Stop operating 10 times in 5 seconds; no orphan decoder thread, no stuck PTT after the final Stop.
- [ ] **Cable wiggle test (TEST-08):** Detach Digirig USB cable then reattach within 1 slot (≤15 s); RX recovers within 1 slot of reattach; AudioRecord chain re-initialises without app relaunch.

## Automated Gate (required for every PR)

Run from the repo root before requesting review. Record the indicated artifact in the
PR body so reviewers can audit which commit and which baseline file were exercised.

- [ ] Golden-trace test (`./gradlew :app:test --tests "*GoldenTraceTest"`) passes — record commit SHA.
- [ ] Behavior-parity replay passes against the committed baseline under `docs/planning/field-sessions/baseline-<date>/` — record baseline filename.
- [ ] All module unit tests pass (`./gradlew test`).

## Recompose-Count Gate (required for every refactor phase)

Every refactor phase (Phases 1–7 of the v1.x milestone) must record the Operate-tab
recomposition count over one full slot cycle and confirm it does not exceed the
Phase 0 baseline captured per FOUND-08. The Phase 0 baseline lives under
`docs/planning/field-sessions/baseline-<date>/recompose-baseline.md` per the
recompose-baseline scheme established in Plan 00-05.

- [ ] Operate-tab recomposition count over one full slot cycle measured for this PR.
- [ ] Δ vs Phase 0 baseline ≤ 0 (no increase). Any increase requires written
      justification in the PR body referencing the specific Compose-stability cost
      that drove it (see PITFALLS Pitfall 6).

## RF-Irrelevant Skip Clause

A phase may skip the Field-Session Gate **only** if it CANNOT possibly affect RF, CAT,
USB, audio, or threading. The PR author must include a single-line justification on
the PR explaining why no RF surface is touched.

- **Examples of legitimately skippable changes:** docstring-only PR, `docs/`
  markdown-only PR, repository-config-only PR (e.g., `.github/` workflows that do not
  ship into the APK).
- **Examples NOT skippable:** anything under `app/`, `audio/`, `core/`, `rig/`,
  `ft8-native/`, `data/` — even if "the change looks small." PITFALLS Pitfall 12 is
  unambiguous that low-stakes refactors are the highest-risk surface for gate rot.

## Evidence Storage

Field-session evidence (log, screenshots, decode CSV, PTT trace, etc.) is committed
under `docs/planning/field-sessions/<date>/`, with one subdirectory per session named
`YYYY-MM-DD-<phase>-<description>/` per PARITY-02. The PR body MUST link the relevant
subdirectory path so reviewers can audit the artefact without leaving GitHub.

Recompose baselines and behavior-parity baselines live alongside the dated session
subdirectories — they are not separate trees.

## Cross-Cutting Requirement References

These three requirements are the non-negotiable bar that every phase clears via the
gates above. They are listed here so the checklist itself is self-contained for the
reviewer reading only this file.

- **PARITY-01** — The behavior-parity replay (FOUND-07) passes against the recorded
  baseline; RX/TX/CAT/QSO behavior is byte-equivalent to v1.0 on the reference rig.
- **PARITY-02** — The full promotion checklist (this file) is signed off in the PR
  with field-session evidence committed under `docs/planning/field-sessions/<date>/`.
- **PARITY-03** — No phase introduces user-visible behavior changes outside those
  explicitly enumerated in `docs/planning/REQUIREMENTS.md`; any new UX surface inlines
  via snackbars, chips, or existing Settings rows.
