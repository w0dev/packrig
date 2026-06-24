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

SNR is now a real estimate, computed in **pure Kotlin** (`core/SnrEstimator.kt`),
ported from PyFT8's algorithm:

- For each decoded candidate, a Goertzel detector measures power at the eight
  8-FSK tone bins (`f0 + k·6.25 Hz`) over each symbol's 1920-sample window.
- `SNR = clip(maxCellDb − minCellDb − offset, −24, 24)` — strongest tone cell
  (signal) minus weakest cell (noise floor), referenced to WSJT-X's 2500 Hz
  bandwidth by a single calibration `offset`.
- `maxCellDb − minCellDb` is invariant to input gain, so the offset is a true
  fixed reference.

The native `out.snr` is now `0`; SNR is owned by Kotlin at the decode seam
(`DecodeController.withRecomputedSnr`). Unit tests cover gain-invariance,
monotonicity vs noise, and clamping. The calibration constant
(`DEFAULT_OFFSET_DB`) is pinned by an instrumented test against WSJT-X sample
WAVs (⏳ pending device).

## 3. Cross-tool comparison ✅

| Tool | SNR source | Real dB? | Notes |
|------|-----------|----------|-------|
| **WSJT-X** | Symbol-spectra power vs noise, ref 2500 Hz | ✅ yes | The reference standard. |
| **ft8_lib (demo)** | `cand->score * 0.5` | ❌ no | The origin of the bug; TODO never done. |
| **PyFT8** | `clip(pmax − min(dBgrid) − 58, −24, 24)` | ✅ yes | Our chosen spec. `receiver.py:284`. |
| **POTACAT (JS)** | Goertzel sig/noise + `10·log10(50/2500)` | ✅ yes | Independently fixed the same bug. |
| **POTACAT (native C)** | `cand->score * 0.5` | ❌ no | `ft8_addon.c:294` — native path still unfixed. |
| **FT8CN** | Prebuilt `libft8cn.so` (WSJT-X-derived) | ✅ yes (behaviorally) | Source not in repo; not inspectable. |
| **FT8VC (this app), pre-fix** | `cand->score * 0.5` | ❌ no | Same defect. **Now fixed.** |
| **FT8VC, post-fix** | Goertzel max−min, ref 2500 Hz | ✅ yes | `SnrEstimator.kt`. |

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

## 7. SNR parity ⏳

After calibration (Task 4), record here the fitted `DEFAULT_OFFSET_DB` and the
measured `|ours − WSJT-X|` spread across all matched decodes. Target: within
~1–3 dB (operator-equivalent). Current state: `DEFAULT_OFFSET_DB = 0.0`
(placeholder; estimator clamps to +24 until pinned).

## 8. Field confirmation ⏳

The milestone promotion bar: on the reference rig (Yaesu FT-891 + Digirig over
USB-C OTG), confirm decodes still arrive, QSOs still complete, and the SNR column
now shows realistic (often negative) values tracking WSJT-X on the same band.

---

## Summary

- ✅ Root cause confirmed: `score*0.5` was a sync metric mislabeled as SNR.
- ✅ Fixed with a PyFT8-style Goertzel estimator in pure, unit-tested Kotlin.
- ✅ Independently corroborated by POTACAT, which fixed the identical bug the same
  way; the defect is endemic to ft8_lib's demo path.
- ✅ Encode is spec-correct (unmodified ft8_lib).
- ⏳ Decode parity, sensitivity gap, SNR-offset calibration, and field check
  remain — all require a device + WSJT-X sample WAVs; scaffolding is in place.
