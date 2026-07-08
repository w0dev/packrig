# Waterfall Occupancy, WSJT-X Style — Design

**Date:** 2026-07-07
**Branch:** `multi-rig`
**Status:** Approved (design), pending implementation plan

## Problem

Two issues with the Spectrum-tab waterfall, reported from the field:

1. **Callsign labels never appear**, even when the "Labels" checkbox is on. Root
   cause: [`SpectrumMarkers.forLatestSlot()`](../../../app/src/main/java/net/ft8vc/app/ui/spectrum/SpectrumMarkers.kt)
   groups decodes by `id / 1000`, assuming `DecodeRow.id` is encoded as
   `slotStart * 1000 + index`. That encoding no longer exists —
   [`DecodeRowKey.stableId()`](../../../app/src/main/java/net/ft8vc/app/controllers/DecodeRowKey.kt)
   produces a 64-bit hash. So `maxOf { it.id / 1000 }` selects one arbitrary hash
   bucket and the marker set collapses to ~1 random decode; unless that decode is a
   `CQ`, no label renders. The stale comment at `DecodeController.kt:62` and the
   fabricated ids in `SpectrumMarkersTest.kt:12` (both still using the old `*1000`
   form) let the unit tests pass green while production is broken.

2. **The occupancy cue is too weak.** The current design overlays a thin 50 Hz
   amber TX band plus CQ-only ticks/labels. The band is subtle and, because of the
   bug above, the labels are effectively absent — so it is hard to see where signals
   are and whether a chosen TX offset would land on top of someone.

This reverses the direction of the earlier
[`2026-07-05-spectrum-decode-markers-design.md`](2026-07-05-spectrum-decode-markers-design.md),
which added the on-waterfall label/marker overlay now being removed.

## Goal

Make the waterfall a clean **occupancy view** that is as readable as WSJT-X, with a
**bold, unambiguous TX marker** so the operator can see a clear frequency and avoid
stepping on another station. Callsigns stay in the decode list, not on the
waterfall. No expansion of the feature surface (code-health milestone).

**Core-value guard:** RX/TX/CAT/QSO behavior on the reference FT-891 + Digirig is
unchanged. This touches only the Spectrum-tab rendering and a dead persisted
setting; the audio, decode, and CAT paths are untouched.

## Decisions (confirmed with operator)

- **Direction:** WSJT-X-style — waterfall = occupancy; decode list = callsigns. No
  callsign labels on the waterfall.
- **Rx marker:** none. FT8VC RX is wideband; there is no per-frequency Rx concept.
  The meaningful marker is the operator's TX offset.
- **Dead `spectrumMarkersEnabled` setting:** remove it fully (DataStore key, repo,
  `StationSettings`, `SettingsBridge`, UI state, VM setter, tests).
- **Clash tinting:** drop it (WSJT-X has no auto-clash warning — the operator reads
  occupancy against the bold TX marker).

## Changes

### 1. Remove the label/marker overlay (fixes the bug by deletion)

- **`WaterfallPanel.kt`** — drop the `markers` and `showMarkers` parameters, delete
  the CQ tick + callsign-label loop (lines ~102–127) and the `SpectrumMarkers`
  import/usage.
- **`SpectrumScreen.kt`** — remove the `markers` computation, the `remember` over
  `SpectrumMarkers.forLatestSlot`, and the **"Labels" checkbox** row; stop passing
  `markers`/`showMarkers`.
- **Delete `SpectrumMarkers.kt`** and **`SpectrumMarkersTest.kt`** — nothing else
  references them once labels and clash tinting are gone.

### 2. Bold TX marker (WSJT-X red goalpost)

Replace the low-alpha amber band with a prominent **red** TX marker in
`WaterfallPanel`:

- A solid red vertical line at the exact TX tone (`txFreqHz`), ~2–3 dp wide.
- A red "goalpost" bracket spanning the FT8 signal width (`txFreqHz` ..
  `txFreqHz + FT8_SIGNAL_WIDTH_HZ`, i.e. ~50 Hz): a light red fill (single fixed
  color, no clash branch) plus short red caps at top/bottom so the occupied span is
  obvious against the traces.
- Colors sourced from the existing theme (`Ft8Red`); drop `Ft8Amber` usage here.

Tap/drag-to-set-TX behavior is unchanged.

### 3. Occupancy clarity (palette + contrast)

Tune [`Waterfall`](../../../app/src/main/java/net/ft8vc/app/ui/Waterfall.kt) so noise
stays dark and individual signals separate cleanly (the reason WSJT-X reads well). In
the reported screenshot the mid-band saturates to yellow/pink, hiding individual
signals.

- Reshape `colorFor()` and/or the default `floorOffsetDb` / `rangeDb` so more of the
  low end maps to dark blue/black and signals occupy the bright range. Starting
  hypothesis: raise `floorOffsetDb` (from `4f`) and/or narrow `rangeDb` (from `45f`)
  for more contrast; final values set by on-device verification.
- Keep the adaptive noise-floor estimator as-is.
- **No new UI controls** (no gain/zero sliders) — feature surface stays flat.

### 4. Remove the dead `spectrumMarkersEnabled` setting

Delete every reference enumerated below:

- `SettingsRepository.kt` — `Keys.SPECTRUM_MARKERS_ENABLED`, the read in the settings
  flow, and `setSpectrumMarkersEnabled()`.
- `StationSettings.kt` — the field.
- `SettingsBridge.kt` — both references (slice field + mapping).
- `OperateUiState.kt` — the field.
- `OperateViewModel.kt` — the mapping (line ~217) and `setSpectrumMarkersEnabled()`
  (lines ~572–573).
- `SettingsRepositorySpectrumMarkersTest.kt` — delete.

### 5. Minor cleanup

- Fix the stale comment at `DecodeController.kt:62` that still describes ids as
  `slotStart * 1000 + indexInSlot`; they are `DecodeRowKey.stableId` hashes.

## Testing

- **Unit:** remove `SpectrumMarkersTest` and `SettingsRepositorySpectrumMarkersTest`
  with the code they cover. Confirm the full `:app` unit suite compiles and passes
  after the setting removal (no dangling references).
- **Visual/manual:** the TX marker and palette are visual. Verify on a real
  FT-891 + Digirig screenshot: (a) the red TX marker and its 50 Hz span are obvious
  against band traces, (b) individual signals are distinguishable from noise
  (occupancy as clear as WSJT-X), (c) tap/drag still repositions TX.

## Out of scope

- Any on-waterfall callsign display (explicitly removed).
- Gain/zero/palette-picker UI controls.
- A separate 1D "current spectrum" strip like WSJT-X's Wide Graph.
- Rx-frequency marker (no such concept in FT8VC).
- Changes to decode, audio, or CAT paths.
