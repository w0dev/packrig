# Spectrum decode markers + honest TX picker — Design

**Date:** 2026-07-05
**Branch:** `multi-rig` (kept here per owner)
**Milestone:** v1.x Code Health — this is a Spectrum-screen UX delta (allowed; must not crowd main-screen real estate). Operate main screen is untouched.

## Problem

The Spectrum waterfall renders raw spectral energy only. On a real band the FT8
signals (each ~50 Hz wide, packed across ~0–3000 Hz) smear into one bright
"blob" with noise fuzz at the edges. Nothing links the decoded messages the app
already produces to their position on the waterfall, so the operator cannot:

1. See **where** a given CQ is coming from (which audio Hz), and
2. Tell whether their **TX tone is landing on top of** another station's signal.

The decode data needed to fix both already exists in UI state and is currently
unused by the Spectrum screen.

## Key discovery (scope reduction)

`OperateUiState.decodes: ImmutableList<DecodeRow>` is already delivered to the
Spectrum screen's ViewModel. Each `DecodeRow` already carries:

- `freqHz: Int` — the decoder's audio frequency for that signal
- `isCq: Boolean`
- `message: String` (sender callsign parsable via existing `QsoMessages.parse`)
- `id: Long` = `slotStartEpochMs * 1000 + indexInSlot` (so `id / 1000` groups a slot)

**Therefore no `core` / `QsoDecode` / `DecodeController` change is required.** All
new behavior lives in the `app` UI layer plus one persisted settings flag. This
is the primary risk reduction versus the original pitch.

## Scope

In scope (all on the Spectrum screen):

1. **Decode markers** on the waterfall — CQ callers only, toggle-gated.
2. **Honest TX marker** — ~50 Hz-wide band instead of a 1px line, with a
   gentle collision tint.
3. **Live Hz readout** while tapping/dragging the TX tone.
4. **Persisted toggle** ("Labels") controlling #1, default ON.

Explicitly OUT (YAGNI / anti-clutter):

- No zoom / pinch into a frequency band.
- No labels for non-CQ decodes (their frequencies still feed collision detection).
- No scrolling labels — markers reflect the latest slot and refresh in place.
- No per-decode SNR / dt shown on the waterfall.
- No change to the Operate main screen, decode list, or QSO machine.
- No blocking of TX — the collision tint informs only.

## Design

### Data derivation (no new pipeline)

Add a pure helper, `SpectrumMarkers`, that derives the current-slot marker set
from the state the Spectrum screen already collects:

```
data class SpectrumMarker(val freqHz: Int, val callsign: String, val isCq: Boolean)

object SpectrumMarkers {
    /** Rows belonging to the most recent slot (max id/1000), stripped to markers. */
    fun forLatestSlot(decodes: List<DecodeRow>): List<SpectrumMarker>
}
```

- Group `decodes` by `id / 1000`; take the max (latest) slot only. Empty when no
  decodes yet.
- `callsign` parsed from `message` (reuse the same sender-parse logic as
  `DecodeController.senderCallFromMessage`; a small shared `QsoMessages`-based
  helper, not a copy). For CQ rows this is the caller.
- Returned list is used two ways:
  - **Labels:** filter `isCq == true`.
  - **Collision:** all markers (CQ and non-CQ) contribute occupied frequencies.

This helper is pure and unit-testable with hand-built `DecodeRow` lists.

### 1. Decode markers on the waterfall (toggle-gated)

Rendered in `WaterfallPanel`'s existing `Canvas`, over the bitmap, only when the
`Labels` toggle is on:

- For each **CQ** marker: a thin, semi-transparent vertical tick at its Hz
  (`x = freqHz / maxFreqHz * width`), full canvas height, low alpha so it does
  not fight the waterfall colors.
- A small monospace callsign label near the **top** of the canvas at that x.
- **Label de-clutter:** sort CQ markers by frequency; when two labels would
  overlap horizontally, alternate them between two stacked rows (top band).
  Beyond what two rows can hold without overlap, drop the lowest-priority extra
  labels (keep the tick). CQ-only density makes this rare in practice.
- Markers reflect the latest slot and are replaced wholesale when the next
  slot's decodes arrive — nothing accumulates or scrolls.

### 2. Honest, collision-aware TX marker

Replace the current 1px amber line in `WaterfallPanel` with a translucent
**band** spanning the real FT8 footprint: `txFreqHz` → `txFreqHz + 50` Hz
(8-FSK × 6.25 Hz ≈ 50 Hz), clamped to the canvas.

- **Default fill:** muted amber (`Ft8Amber` at low alpha) with a solid 2 dp
  leading edge at `txFreqHz` (preserves today's precise marker line).
- **Collision tint:** if any marker (CQ *or* non-CQ) has a center within a tight
  window of the TX center — `abs(markerFreq - txFreqHz) <= CLASH_WINDOW_HZ`
  (start at **30 Hz**) — shift the band fill toward a muted red. Gentle, not a
  bright flash; no text, no icon, never blocks TX.
- Rationale for the tight window and soft styling: on a crowded band a loose
  threshold makes the band red almost everywhere (alarm fatigue), destroying the
  signal. `CLASH_WINDOW_HZ` is a named constant so it can be tuned in the field.

The clash test is a pure function over `(txFreqHz, markers)` — unit-testable.

### 3. Live Hz readout

The Spectrum header currently shows a static "Tap or drag to set TX tone" hint.
Replace with a live readout bound to `txFreqHz`, e.g. `TX tone: 1500 Hz`, in
monospace. Because `onFreqChange` updates `txFreqHz` in state on every drag
delta, this updates continuously while dragging with no new drag-tracking state.
Keep a compact affordance so discoverability is preserved (e.g.
`TX tone: 1500 Hz · tap/drag`).

### 4. Persisted "Labels" toggle

- A small `Checkbox` (or `Switch`) + label in the Spectrum header row, next to
  the readout — no new screen real estate beyond the existing row.
- Backed by a new boolean in `SettingsRepository`/DataStore, following the exact
  pattern of existing boolean prefs (e.g. `earlyDecodeEnabled`, `cq73OnlyFilter`):
  flow in state, setter on the ViewModel. **Default ON.**
- New `OperateUiState` field `spectrumMarkersEnabled: Boolean = true`.

## Components touched

| Component | Change |
|-----------|--------|
| `app/.../ui/operate/WaterfallPanel.kt` | Draw CQ tick+label overlay (gated); replace TX line with collision-aware band; accept `markers` + `showMarkers` params |
| `app/.../ui/spectrum/SpectrumScreen.kt` | Derive markers via `SpectrumMarkers`; live Hz readout; Labels checkbox wired to VM |
| `app/.../ui/spectrum/SpectrumMarkers.kt` (new) | Pure derivation + clash test helpers |
| `app/.../OperateUiState.kt` | Add `spectrumMarkersEnabled: Boolean = true` |
| `app/.../settings/SettingsRepository.kt` | Persist `spectrumMarkersEnabled` (existing boolean-pref pattern) |
| `app/.../OperateViewModel.kt` | Flow the pref into state; `setSpectrumMarkersEnabled(...)` setter |

No changes in `core`, `audio`, `rig`, `data`, or `ft8-native`.

## Data flow

```
RX decode (existing) → DecodeController → OperateUiState.decodes (existing)
                                                 │
SpectrumScreen: SpectrumMarkers.forLatestSlot(state.decodes) ──► List<SpectrumMarker>
                                                 │
                          ┌──────────────────────┴───────────────────────┐
             filter isCq (labels, if toggle on)              all markers (collision)
                          │                                              │
                          └──────────────► WaterfallPanel Canvas ◄───────┘
                                     (ticks+labels)     (TX band tint)
```

## Error / edge handling

- **No decodes yet:** `forLatestSlot` returns empty → no ticks, TX band renders
  in default amber. No crash, no special-case UI.
- **Marker off-screen:** frequencies above `maxFreqHz` clamp to the canvas edge
  (same clamp the TX marker already uses).
- **`maxFreqHz <= 0`:** existing guard in `WaterfallPanel` short-circuits drawing.
- **Toggle off:** ticks/labels suppressed; TX band + collision tint still render
  (collision awareness is a TX-safety aid, not a labeling nicety — but see Open
  question below).
- **Stale markers:** bounded by "latest slot only"; replaced each slot, so a
  dead band clears its labels within one slot cycle.

## Testing

Unit tests (JVM, `app` test source — pure Kotlin, no Android):

- `SpectrumMarkers.forLatestSlot`: empty input; single slot; multiple slots
  returns only the max-slot group; CQ vs non-CQ classification; callsign parse
  for CQ / directed messages.
- Clash test: marker inside window → clash; just outside window → no clash;
  no markers → no clash; multiple markers, nearest governs.

Manual / field verification (reference FT-891 + Digirig):

- On a live band, CQ labels appear at plausible positions and match the decode
  list's frequencies.
- Toggle off/on hides/shows labels and persists across app restart.
- Dragging the TX tone updates the Hz readout continuously; band width looks
  like ~50 Hz; tint appears only when parked on a decode and clears when moved
  off. Confirm it is **not** red across the whole band on a busy run.
- Regression: tap/drag still sets TX tone exactly as before; RX/TX/CAT unaffected.

## Open questions for spec review

1. **Collision tint when Labels toggle is OFF** — the design keeps the TX band's
   collision tint active even when labels are hidden (it is a safety aid, not a
   label). Acceptable, or should the toggle gate the tint too?
2. **`CLASH_WINDOW_HZ` starting value** — spec starts at 30 Hz center-to-center.
   Field-tunable; may need adjustment after real-band use.
