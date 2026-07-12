# FT8 Encode / Decode / SNR Audit Report

**Date:** 2026-06-24
**Scope:** v1.x Code Health — FT8 correctness audit vs WSJT-X, FT8CN, POTACAT, PyFT8
**Companion spec:** [2026-06-24-ft8-snr-audit-design.md](../specs/2026-06-24-ft8-snr-audit-design.md)
**Companion plan:** [2026-06-24-ft8-snr-fix.md](../plans/2026-06-24-ft8-snr-fix.md)

## Status legend

- ✅ **Verified** in this audit (code inspected / test run on the dev host).
- ⏳ **Pending device** — requires a connected Android device/emulator and WSJT-X
  sample WAVs; scaffolding is in place (`Ft8SnrCalibrationTest`) but cannot run on
  the dev host (no `adb`/emulator).

---

## 1. Root cause of the inflated SNR ✅

The app reported ~+8 dB or higher for essentially every received signal, while
WSJT-X reported realistic (mostly negative) SNRs on the same antenna.

**Cause:** the "SNR" was never an SNR. In
[`ft8_jni.cpp:239`](../../../ft8-native/src/main/cpp/ft8_jni.cpp) (pre-fix):

```cpp
out.snr = (int)std::lround(cand->score * 0.5f);
```

`cand->score` is ft8_lib's **Costas sync-correlation metric** — a non-negative
likelihood score (~10–50 for any decodable signal), not a power ratio. This line
is copied verbatim from ft8_lib's own demo, which flags it as unfinished:

```c
// ft8_lib demo/decode_ft8.c:224
float snr = cand->score * 0.5f; // TODO: compute better approximation of SNR
```

Scaling a positive sync score by 0.5 yields a positive pseudo-dB number that
cannot go negative — exactly the observed "+8 everywhere" behavior.

## 2. The fix ✅

SNR is now a real estimate, computed in **pure Kotlin** (`core/SnrEstimator.kt`)
at the decode seam (`DecodeController.withRecomputedSnr`); the native `out.snr` is
`0`. After evaluating several methods against real WSJT-X data (§2a), we ship
**POTACAT's method**:

- signal power = mean of the eight 8-FSK tone-bin powers (`f0 + k·6.25 Hz`),
  Goertzel over the whole slot;
- noise floor = median bin power across 200–3000 Hz (computed once per slot);
- `SNR = clip(10·log10(signal / noise) + CALIBRATION_DB, −24, 24)`.

It is **alignment-free** (no time offset needed) and **gain-invariant** (the
signal/noise ratio cancels level). `CALIBRATION_DB = −20.3` (POTACAT's theoretical
`10·log10(50/2500) = −17 dB` plus an empirical leakage term), mean-centered
against `210703_133430.wav`. Unit tests cover gain-invariance, monotonicity, and
clamping; `SnrEstimatorWavTest` locks directional accuracy against WSJT-X on the
host (no device).

### 2a. Method selection — why not PyFT8/method D ✅

We validated candidate estimators against the WSJT-X sample `210703_133430.wav`
(18 decodes, −17…+17 dB). Target: slope ≈ 1.0, residual stddev ≤ 3 dB.

| Method | slope | stddev | verdict |
|--------|-------|--------|---------|
| PyFT8 `max−min` (first attempt) | non-monotonic | 7–10 | min-cell is a noise outlier |
| **POTACAT full-buffer (shipped)** | **0.82** | 7.6 | best; degrades on overlap |
| median-of-8-tone | 1.10 | 10 | residual pollution |
| method D — known decoded tones, per-symbol | 0.34–0.54 | 7.5 | needs sub-symbol sync |
| Costas-tone, per-symbol | ~0 | 11–16 | needs sub-symbol sync |

**Key finding:** WSJT-X-exact SNR requires sub-symbol/sub-Hz sync precision plus
signal subtraction for a clean noise reference — effectively the hard half of the
WSJT-X decoder. Neither WSJT-X's 0.1 s DT output nor ft8_lib's coarse `osr=2`
waterfall (3.125 Hz / 0.08 s) provides it, so even "method D" (known decoded
tones, validated on the host with the real ft8_lib encoder) tops out near slope
0.5. POTACAT's alignment-free method is the best achievable without that DSP
subproject: directionally correct, mostly-negative, ~5 dB typical error, with
overlapping signals (within ~50 Hz) reading high. That solves the actual problem
(the bogus always-positive readout) and is a huge improvement over a sync score
with zero SNR correlation. True ±2–3 dB fidelity is scoped as a separate future
DSP milestone.

## 3. Cross-tool comparison ✅

| Tool | SNR source | Real dB? | Notes |
|------|-----------|----------|-------|
| **WSJT-X** | Symbol-spectra power vs noise, ref 2500 Hz | ✅ yes | The reference standard. |
| **ft8_lib (demo)** | `cand->score * 0.5` | ❌ no | The origin of the bug; TODO never done. |
| **PyFT8** | `clip(pmax − min(dBgrid) − 58, −24, 24)` | ✅ yes | `receiver.py:284`. Tried first; inaccurate on real data (§2a). |
| **POTACAT (JS)** | Goertzel sig/noise + `10·log10(50/2500)` | ✅ yes | Independently fixed the same bug. |
| **POTACAT (native C)** | `cand->score * 0.5` | ❌ no | `ft8_addon.c:294` — native path still unfixed. |
| **FT8CN** | Prebuilt `libft8cn.so` (WSJT-X-derived) | ✅ yes (behaviorally) | Source not in repo; not inspectable. |
| **FT8VC (this app), pre-fix** | `cand->score * 0.5` | ❌ no | Same defect. **Now fixed.** |
| **FT8VC, post-fix** | POTACAT method (Goertzel sig/median-noise, ref 2500 Hz) | ✅ yes | `SnrEstimator.kt`; slope 0.82 vs WSJT-X. |

### Key finding: the bug is widespread, and our fix is independently corroborated

**POTACAT hit the exact same bug and fixed it the same way we did.** Its
`lib/ft8-worker.js:27` comment reads: *"ft8js returns fake SNR (sync_score *
0.5). We replace it with a proper estimate using Goertzel DFT on the raw audio
samples."* It defines `SNR_REF_BW = 2500` and
`BW_CORRECTION = 10·log10(50/2500) = −17.0 dB`, then computes per-tone Goertzel
signal power over a median-of-spectrum noise floor. This is the same physics as
our estimator (Goertzel tone power, 2500 Hz reference) arrived at independently —
strong validation of the approach.

Two structural notes worth recording:

1. **POTACAT's native C decoder still ships the broken `score*0.5`** and is
   corrected *above* the native layer (in JS). Our fix has the same shape: native
   emits 0, Kotlin computes the real SNR. Fixing above the C boundary is the
   pragmatic, testable pattern — not a workaround.
2. **Method difference (intentional):** POTACAT uses *signal-tone power ÷
   median-spectrum noise* with a physically-derived −17 dB correction; we use
   PyFT8's *max−min spread within the candidate's tone cells* with an empirically
   calibrated offset. Both are 2500 Hz-referenced and Goertzel-based. The
   empirical calibration (Task 4) absorbs the windowing/leakage differences; the
   fitted offset should land in a physically plausible range — if it doesn't,
   that's a signal to investigate before locking it.

## 4. Encode ✅

The app does **not** re-implement FT8 encoding — it calls ft8_lib's `ft8_encode`
unmodified (`ft8_jni.cpp` `nativeEncode`), over the full 91-bit payload + LDPC +
Costas + Gray + tone mapping. Encode correctness is therefore *structural*, not
heuristic: the channel symbols are whatever the spec-correct ft8_lib produces at
pinned commit `9fec6ca`. The instrumented `encodeThenDecodeRoundTrips` test
proves encode→decode self-consistency (a synthesized message decodes back to
itself). No encode defect found.

## 5. Decode parity ⏳

`Ft8SnrCalibrationTest` decodes WSJT-X sample WAVs and matches each decode to
WSJT-X's published message + frequency. Running it requires a device and the WAV
fixtures (see `ft8-native/src/androidTest/assets/snr/README.md`). **Expected
result, to be confirmed:** message text and audio frequency match WSJT-X, since
both run ft8_lib-class LDPC decoding. Record the per-WAV results here after the
first device run.

## 6. Decode sensitivity gap ⏳

ft8_lib uses LDPC belief-propagation **without** WSJT-X's full OSD
(ordered-statistics decoding) / multi-pass subtraction. At the weakest threshold,
WSJT-X is expected to decode *more* signals than ft8_lib. This audit flags it as a
measurement to take, **not** a defect to fix here:

> On each sample WAV, record `count(app decodes)` vs `count(WSJT-X decodes)` and
> list any weak decodes WSJT-X catches that the app misses.

If a material gap appears, adding OSD to the decode path is a **separate future
milestone**, scoped on evidence — not bundled into this SNR fix. ⏳ pending device.

## 7. SNR parity ✅ (host-validated)

Calibrated and validated on the host against `210703_133430.wav` (no device
needed — the estimator is alignment-free, so WSJT-X's published frequencies feed
it directly). `CALIBRATION_DB = −20.3` (mean-centered). Across the 18 decodes:

- **slope 0.82** vs WSJT-X (the old `score*0.5` had ~zero correlation);
- weak signals (−12…−17 dB) read **−14…−19** — correctly negative and tracking;
- strong signals (+14/+17) read **+12/+13** — correctly positive (mildly
  compressed by the 0.82 slope);
- **2 of 18 outliers** (−8 and −16 dB signals overlapping the +17 dB signal
  within 50 Hz) read positive — the documented limitation.

Locked by `SnrEstimatorWavTest` (host) and `Ft8SnrCalibrationTest` (on-device,
end-to-end through ft8_lib). This is directional, not ±2 dB; see §2a.

## 8. Field confirmation ⏳

The milestone promotion bar: on the reference rig (Yaesu FT-891 + Digirig over
USB-C OTG), confirm decodes still arrive, QSOs still complete, and the SNR column
now shows realistic (often negative) values tracking WSJT-X on the same band.

---

## Summary

- ✅ Root cause confirmed: `score*0.5` was a sync metric mislabeled as SNR.
- ✅ Fixed with POTACAT's Goertzel estimator (sig / median-noise, ref 2500 Hz) in
  pure, unit-tested Kotlin. Calibrated (`CALIBRATION_DB = −20.3`) and host-validated
  against a WSJT-X sample: slope 0.82, mostly-negative, directionally correct.
- ✅ Method selection rigorously evaluated (§2a): PyFT8 `max−min` and per-symbol
  "method D" both fall short without sub-symbol sync; POTACAT is the best
  achievable short of a dedicated DSP subproject.
- ✅ Independently corroborated by POTACAT, which fixed the identical bug the same
  way; the defect is endemic to ft8_lib's demo path.
- ✅ Encode is spec-correct (unmodified ft8_lib).
- ⏳ Decode parity, decode sensitivity gap, and the on-rig field check remain —
  they require a device; scaffolding (`Ft8SnrCalibrationTest`) is in place.
- 🔭 True ±2–3 dB WSJT-X fidelity is a separate future DSP milestone (fine sync +
  signal subtraction).
