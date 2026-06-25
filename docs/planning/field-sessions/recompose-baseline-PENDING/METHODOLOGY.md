# Operate-Tab Recomposition Baseline Methodology

Pre-refactor capture procedure for FOUND-08.

## Why

`PITFALLS.md` Pitfall 6 names the Compose recomposition storm as the primary risk of splitting one StateFlow into five during Phases 1–5. The single integer baseline captured by this methodology becomes the regression threshold the promotion checklist's **Recompose-Count Gate** compares against on every refactor PR. If a post-refactor Operate-tab recomposition delta exceeds the Phase 0 baseline by more than ε (suggested ε = 0), the gate fails and the PR cannot promote from `unstable` to `main` without written justification.

## What We Measure

- **Target composable:** `OperateScreen` at root composition — `app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt`.
- **Tracked children:** `DecodeListPanel`, `OperateStatusBar`, `OperateControls`, `Waterfall` — the four top-level slot-rate consumers under `OperateScreen`.
- **Duration:** one full slot cycle ≈ 15 seconds wall clock (one RX slot plus the boundary into the next).
- **Metric:** total recomposition count of `OperateScreen` plus each tracked child during the 15 s observation window.
- **Conditions:**
  - App in foreground.
  - Digirig connected (audio device claimed, CAT reachable).
  - Operate tab active.
  - At least one decode arriving during the window.
  - No PTT activity (RX-only — TX cycles add unrelated recompositions and inflate the baseline).

## Lightweight Method (RECOMMENDED for Phase 0)

This is the method Phase 0 commits to. It avoids landing instrumented-test infrastructure under `app/src/androidTest/`, which the codebase does not have today and which would be scope creep.

1. **Install the debug APK on the reference device:**
   ```
   ./gradlew :app:installDebug
   ```

2. **Connect the device to Android Studio via ADB.**

3. **Open Layout Inspector:** `Tools → Layout Inspector` → connect to the `net.ft8vc.debug` process.

4. **Enable recomposition counts in Layout Inspector:**
   - Open Layout Inspector's settings (gear icon).
   - Toggle "Show recomposition counts" / "Highlight Recompositions" ON.
   - Confirm the per-composable count column is visible.

5. **Stage the app:** Open the Operate tab; tap **Start**; wait for the first decode to arrive so the screen is in steady-state.

6. **Snapshot the baseline composition counts** (before the timed window) for each tracked composable:
   - `OperateScreen` (root)
   - `DecodeListPanel`
   - `OperateStatusBar`
   - `OperateControls`
   - `Waterfall`
   Record each as `baseline_count_<composable>`.

7. **Start a 15-second timer at a fresh slot boundary** (UTC :00, :15, :30, or :45 — check the device clock). The exact start instant matters: we want one full RX slot plus a small carry into the next.

8. **After 15 seconds elapse, record the final composition count** for each tracked composable.

9. **Compute `delta = final_count − baseline_count`** for each composable. The `OperateScreen` delta is the headline number; the children's deltas are recorded for diagnostic value.

10. **Repeat the measurement 3 times.** Record the median across the three runs as the committed baseline number. Three runs catches measurement noise (LayoutInspector can stutter on first connect; thermal throttling can jitter timing).

## Optional Heavier Method (DEFER to Phase 5)

An instrumented test under `app/src/androidTest/` using `androidx.compose.ui:ui-test-junit4` and a custom `RecomposeCounter` Composable could automate this capture. Phase 0 intentionally does NOT take that path because:

- The codebase currently has no `app/src/androidTest/` source set.
- Adding it requires emulator config + `androidx.compose.ui:ui-test-junit4` + AGP plumbing — all of which conflict with Phase 0's "no production changes" constraint.
- The manual Layout Inspector path is sufficient for a single integer baseline.

Phase 5 already touches Compose stability (it lands the `combine + distinctUntilChanged + @Immutable` flow assembly per PITFALLS Pitfall 6). The Phase 5 plan may promote this methodology to an instrumented test at that point if the manual approach proves too noisy. That decision belongs to Phase 5 planning, not Phase 0.

## Output Format

The captured baseline directory must follow this layout exactly so the promotion checklist's gate logic can locate the number deterministically:

```
docs/planning/field-sessions/recompose-baseline-<YYYY-MM-DD>/
├── README.md              # Operator notes (see below)
├── baseline-number.txt    # Single integer: median OperateScreen delta
├── runs/
│   ├── run-1.txt          # Raw (baseline, final, delta) per composable from run 1
│   ├── run-2.txt          # Same from run 2
│   └── run-3.txt          # Same from run 3
└── screenshots/           # OPTIONAL — Layout Inspector screenshots
    └── …
```

**README.md fields (required):**

- Device model + Android version (e.g., `Pixel 8 / Android 14`).
- App version code + git commit SHA of the capture build.
- Digirig firmware version.
- Dial frequency at capture time (e.g., `14.074 MHz`).
- UTC capture date (matches the directory name suffix).
- Per-composable median deltas (`OperateScreen`, `DecodeListPanel`, `OperateStatusBar`, `OperateControls`, `Waterfall`).
- Any observed anomalies (Layout Inspector flicker, thermal throttling warning, etc.).

**`baseline-number.txt`:** a single line containing one non-negative integer — the median `OperateScreen` delta across the three runs. Nothing else. No trailing newline beyond the natural one.

**`runs/run-N.txt`:** a small table listing `baseline_count`, `final_count`, and `delta` for each tracked composable. Plain text — operator's call on exact format, but the three composable rows must be unambiguously identifiable for future audit.

## Acceptance Criteria for the Captured Baseline

Three runs completed.

- Median `OperateScreen` delta is a non-negative integer ≤ 200. If the pre-refactor monolith somehow recomposes more than 200 times per slot, the methodology probably mis-measured (Layout Inspector stuck, count overflowed window, etc.); re-run.
- Each run's `OperateScreen` delta is within ±30% of the median. Otherwise the measurement is noisy — wait for the device to settle (no background syncs, screen brightness fixed) and retry all three runs.
- Capture was strictly RX-only — no TX cycle inside the 15-second window.

## How Subsequent Phases Use This Baseline

- Every refactor phase (1–7) re-runs the same methodology after the phase's commits land and records its own `OperateScreen` delta in the PR body.
- The promotion checklist's **Recompose-Count Gate** fails if the post-phase delta exceeds the Phase 0 baseline by more than ε (suggested ε = 0). Any increase requires written justification in the PR body and reviewer sign-off.
- Phase 5 in particular MUST capture a post-refactor recompose count to validate that the `combine + distinctUntilChanged + @Immutable` pattern (PITFALLS Pitfall 6) did not regress the storm.

## Requirement Traceability

- FOUND-08 (REQUIREMENTS.md): Project records a Compose recomposition-count baseline for the Operate tab over one full slot cycle, captured pre-refactor and re-captured at the end of each refactor phase; documented in the promotion checklist.

This methodology document satisfies the "documented" half of FOUND-08; the actual captured baseline number (under `docs/planning/field-sessions/recompose-baseline-<YYYY-MM-DD>/baseline-number.txt`) satisfies the "captured" half.
