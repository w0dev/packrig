# Waterfall Noise-Floor Fix — Design

**Date:** 2026-07-15
**Status:** Approved
**Scope:** Display-only change to the spectrum waterfall renderer.

## Problem

`Waterfall.addColumn()` (`app/src/main/java/net/packrig/app/ui/Waterfall.kt`)
estimates the per-column noise floor as the **15th percentile** of the FFT
column. On the reference rig (FT-891, DATA-U) the RX filter leaves roughly
25–40% of bins near-silent — everything above ~2.9 kHz and below ~200 Hz. The
15th percentile therefore lands inside that silent region and measures the
*filter* floor, not the *band-noise* floor.

Consequences on screen:

- In-band noise sits 30–40 dB "above floor" and renders green/yellow/orange.
- Real FT8 signals saturate into the same orange/red as the noise.
- The red TX marker (`Ft8Red`) and signal traces lose contrast against the
  washed-out background (field report with screenshot, 2026-07-15).

## Change

Estimate the floor as the **median (50th percentile)** of the column instead
of the 15th percentile.

Rationale: with a typical data-mode passband (~62% of displayed bins in-band),
the median lands solidly inside the in-band noise block. If a rig/audio chain
has no filter roll-off (all bins carry noise), the median is still a valid
noise estimate — the fix is not FT-891-specific.

Everything else stays as-is:

- EMA smoothing (α = 0.1)
- `floorOffsetDb` default (24 − 0.6·32 = 4.8 dB)
- `rangeDb` (45 dB)
- The color ramp in `colorFor()`

Expected rendering with a correct floor: noise at/below the offset maps to
black/dark blue; a 0 dB-SNR FT8 signal (~26 dB above per-bin noise) maps to
cyan/green; only genuinely strong signals reach red. The red TX marker pops
against the dark background.

## Testability

`Waterfall` cannot be instantiated in JVM unit tests (it constructs an
`android.graphics.Bitmap`; the app module has no Robolectric). Extract the
floor estimation into a pure **companion function**, e.g.
`Waterfall.estimateFloor(column: FloatArray, n: Int): Float`, called from
`addColumn()`. Companion access does not trigger Bitmap creation.

New unit test `app/src/test/java/net/packrig/app/ui/WaterfallFloorTest.kt`:

- Synthetic column: 40% quiet bins at −100 dB, 60% noise bins at −60 dB →
  estimator must return ≈ −60 dB (the 15th-percentile code returns −100 dB).
- Uniform column (all bins at one level) → returns that level.

TDD: failing test first, then the fix.

## Risk / behavior parity

- Display-only; no RX/TX/CAT or decode-path impact. Decoding uses the native
  path, not this renderer.
- On a very crowded band where signals occupy a large fraction of bins, the
  median can ride up slightly and dim the weakest traces. The EMA smooths this
  across slots; WSJT-X uses a comparable in-band baseline without issue.
- Field sanity check on the reference rig: noise background dark, signal
  traces distinct, TX marker clearly visible.
