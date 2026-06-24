# FT8 Audit + WSJT-X-Fidelity SNR — Design

**Date:** 2026-06-24
**Status:** Approved (design); pending implementation plan
**Milestone:** v1.x Code Health
**Author:** brainstormed with Claude Code

## Problem

The app reports SNR of roughly **+8 dB or higher for nearly every received
transmission**, while WSJT-X and other tools report realistic, mostly-negative
SNR (−XX dB) for the same band conditions on a known-poor antenna (rain
gutters). The app's number is wrong, not the antenna.

### Root cause (confirmed)

In [`ft8-native/src/main/cpp/ft8_jni.cpp:239`](../../../ft8-native/src/main/cpp/ft8_jni.cpp):

```cpp
out.snr = (int)std::lround(cand->score * 0.5f);
```

This is copied verbatim from ft8_lib's demo, `decode_ft8.c:224`:

```c
float snr = cand->score * 0.5f; // TODO: compute better approximation of SNR
```

`cand->score` is the **Costas sync-correlation metric** — a non-negative
likelihood score (~10–50 for any decodable signal), not a power ratio. Scaling
it by 0.5 yields the persistent +8…+25 values observed. It has no relationship
to decibels and cannot go negative the way real SNR does.

WSJT-X computes a genuine SNR in dB referenced to a **2500 Hz noise
bandwidth**. ft8_lib never implemented this (the TODO was never completed), so
any project riding ft8_lib's demo decode path inherits the same wrong number
unless it replaced the line. **The app is reporting a sync score wearing an SNR
label.**

## Decisions

### Engine: keep ft8_lib

ft8_lib remains the encode/decode engine. It is mature, widely deployed, and its
LDPC belief-propagation decoder works (QSOs complete on the reference rig). A
ground-up rewrite of encode/decode was explicitly considered and rejected for
this milestone:

- **Encode is a closed spec** (CRC-14 → LDPC(174,91) → Gray → Costas → tones).
  It is deterministic and already correct; a rewrite carries only regression
  risk, no quality upside.
- **Decode is the riskiest thing to rewrite.** WSJT-X's sensitivity comes from
  years of weak-signal tuning (a-posteriori probabilities, OSD / ordered-
  statistics decoding, multi-pass subtraction). ft8_lib uses plain LDPC BP
  without full OSD. A from-scratch or PyFT8-style decoder that does not
  reproduce that machinery would **decode fewer weak signals**, directly
  harming the core value ("decodes still arrive"). That is the opposite of
  "rock solid."

The defect (SNR) lives entirely in the **measurement layer bolted on after
decode**, which is the layer this work owns and rebuilds.

### SNR method: clean re-demod at WSJT-X fidelity

Implement WSJT-X's SNR method, using **PyFT8 (G1OJS) as the readable spec** for
the same algorithm WSJT-X expresses in Fortran. After ft8_lib confirms a decode
(so the exact 79-symbol tone sequence is known):

1. Downsample that candidate's signal to complex baseband centered on its
   frequency.
2. Measure per-symbol **signal power** at the correct tone bins and **noise
   power** from the off-tone content.
3. Compute `10 · log10(signal / noise)`.
4. Subtract the fixed **2500 Hz-reference constant** (the ~26–27 dB bandwidth
   offset; exact value verified against WSJT-X master source during
   implementation, not assumed here).

This measures signal power from a clean baseband — the way WSJT-X does — rather
than from ft8_lib's coarse 6.25 Hz waterfall bins. It is the maximal-fidelity
SNR available without touching the decoder.

`cand->score` remains available internally for candidate ranking; only the value
**reported as SNR** changes.

## Scope

### In scope

1. **SNR estimator** — new native routine (above), replacing the `score * 0.5`
   line at `ft8_jni.cpp:239`.
2. **Audit report** covering encode, decode parity, decode sensitivity, and SNR
   (see Deliverables).
3. **Test harness** decoding the WSJT-X sample WAVs as the regression lock and
   parity check.

### Out of scope (YAGNI)

- Rewriting ft8_lib encode or decode.
- Adding OSD / multi-pass subtraction to the decoder.
- Matching WSJT-X SNR to 0.1 dB (target is operator-equivalent, ~1–2 dB).
- UI redesign — the SNR column already renders negative numbers fine; only the
  value changes.
- Per-decode SNR variance modeling, multi-signal AGC.

## Deliverables

### 1. Code change

- New SNR routine in the native layer; `ft8_jni.cpp` calls it instead of
  `cand->score * 0.5f`.
- Calibration constant verified against WSJT-X master source and pinned by test.

### 2. Audit report (document)

A written comparison of the app's path against WSJT-X, FT8CN, POTACAT, and
PyFT8, on four axes:

- **Encode** — prove the app's channel symbols are byte-identical to a
  WSJT-X-generated reference message. Pass/fail.
- **Decode parity** — decode the WSJT-X sample WAVs in-app; confirm message
  text, frequency, and dt match WSJT-X's published decodes.
- **Decode sensitivity gap** — count decodes per WAV vs WSJT-X's published
  count. Surfaces any weak-signal shortfall (the no-OSD risk) **as data**. Any
  remediation is a separate, future decision — not bundled into this work.
- **SNR** — document the defect, the WSJT-X method, and measured agreement
  against the sample WAVs' published SNRs.

### 3. Test harness

- A JVM/native test that decodes each WSJT-X sample WAV and asserts:
  - correct decoded messages,
  - SNR within ~1–2 dB of WSJT-X's published value.
- Doubles as the decode-parity check and the regression lock for the
  calibration constant.
- No rig required.

## Validation

- **Ground truth:** the standard WSJT-X 15-second FT8 sample WAVs, which ship
  with known messages and published SNRs. Decoded via the test harness; no rig
  needed.
- **Field confirmation (manual, not automated):** Yaesu FT-891 + Digirig over
  USB-C OTG — confirm decodes still arrive, QSOs still complete, and reported
  SNR now tracks reality. This is the milestone's promotion bar.

## Risks

- **ft8_lib `mag` / baseband scaling.** The waterfall `mag` values are
  log-domain and clamped (`WF_ELEM_MAG_INT = 2*(mag+120)`). The re-demod path
  works from the complex signal rather than `mag`, sidestepping this, but the
  exact baseband normalization must be verified so the dB reference is correct.
  Detail, not a blocker.
- **Calibration constant.** The 2500 Hz reference offset must be taken from
  WSJT-X master source, not assumed. Pinned by the sample-WAV regression test.
- **Sensitivity gap is a finding, not a fix.** If ft8_lib decodes materially
  fewer weak signals than WSJT-X, that is reported here and scoped separately;
  this work does not attempt to close it.

## Behavior parity statement

RX/TX/CAT/QSO behavior is unchanged. The only runtime change is the value shown
in the SNR column (now often negative and realistic). Receive-only default and
TX license gating are untouched.

## References

- WSJT-X master: https://sourceforge.net/p/wsjt/wsjtx/ci/master/tree/
- FT8CN: https://github.com/N0BOY/FT8CN
- POTACAT: https://github.com/Waffleslop/POTACAT
- PyFT8 (G1OJS): https://github.com/G1OJS/PyFT8
- ft8_lib (pinned commit `9fec6ca`): kgoba/ft8_lib
