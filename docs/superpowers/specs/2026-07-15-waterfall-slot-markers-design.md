# Waterfall Slot-Boundary Markers — Design

**Date:** 2026-07-15
**Status:** Approved
**Scope:** Display-only addition to the spectrum waterfall (WSJT-X-style period marks).

## Goal

Draw a horizontal marker across the waterfall at every 15-second FT8 slot
boundary, annotated with the slot-start time (UTC `HH:MM:SS`), so the operator
can see where each RX/TX period begins — like WSJT-X's period lines.

## Approach

Track marker rows in the waterfall model and render them as a Compose overlay
(the same layer that draws the red TX marker). Rejected alternatives: baking
markers into the bitmap pixels (no readable text in a 320-row buffer, pollutes
spectrum data) and deriving positions from wall time in the UI (rows advance
per column received, not per second, so time-derived positions drift).

## Data flow

`DecodeController.onFrames` already has an injected `clock()` (the same clock
`SlotCollector` uses). The spectrum sink gains a timestamp:

- `DecodeController.spectrumSink: (FloatArray, Long) -> Unit`
- `spectrum.process(pcm) { column -> spectrumSink(column, clock()) }`
- `Waterfall.addColumn(column: FloatArray, epochMillisUtc: Long)`

Using the decode clock keeps markers aligned with where decode slots actually
begin.

## New unit: `SlotMarkerTracker`

Pure Kotlin class in `app/src/main/java/net/packrig/app/ui/SlotMarkerTracker.kt`
— no Android imports, fully JVM-testable (same seam rationale as
`Waterfall.estimateFloor`: `Waterfall` itself constructs a Bitmap and cannot be
instantiated in unit tests).

- Constructor: `SlotMarkerTracker(history: Int)` — rows in the scrolling buffer.
- `data class SlotMark(val row: Int, val slotStartEpochMillis: Long)`
- `onColumn(epochMillisUtc: Long)` — shifts existing markers up one row
  (`row - 1`), drops markers with `row < 0`, and when
  `SlotTiming.slotStart(epochMillisUtc)` differs from the previous column's
  slot start, records a marker at the bottom row (`history - 1`) carrying the
  **new slot's start time**. The first column ever seen records no marker.
- `markers(): List<SlotMark>` — current marks, immutable snapshot.
- `clear()` — empties marks and forgets the previous slot.

`Waterfall` owns one tracker, calls `onColumn` inside `addColumn` under the
existing `lock`, exposes `slotMarkers(): List<SlotMark>` (locked copy), and
clears it in `clear()`.

## Rendering (`WaterfallPanel`)

After `drawImage`, before the TX marker, for each mark:

- Horizontal line: full width, 1 dp, `Color.White.copy(alpha = 0.55f)`,
  at `y = (row + 0.5f) / history * size.height`.
- Label: slot-start time formatted `HH:MM:SS` in UTC, drawn at the left edge
  (4 dp padding) just below the line, small monospace (matches the screen's
  existing monospace labels), same translucent white.
- Marks whose label would be clipped by the bottom edge still draw the line;
  the label draws above the line instead of below in the bottom ~16 dp.

Formatting uses `java.time` (`Instant` → `ZoneOffset.UTC`), fine at minSdk 28.
The formatter and `TextMeasurer` live in the composable (`rememberTextMeasurer`),
not in the draw loop's hot path allocations beyond what Compose text requires.

## Edge cases

- `Waterfall.clear()` (dial change, capture restart) also clears the tracker.
- Capture stopped → no columns → markers freeze with the frozen image. Correct.
- At ~10 columns/s the 320-row history spans ~2 slots → typically 2–3 marks
  visible; no cap needed.

## Testing

TDD on `SlotMarkerTracker` (`app/src/test/java/net/packrig/app/ui/SlotMarkerTrackerTest.kt`):

1. Crossing a slot boundary records a mark at the bottom row with the new
   slot's start time.
2. Non-boundary columns shift existing marks up by one row and add nothing.
3. Marks scroll off the top and are dropped.
4. First column ever seen records no mark.
5. `clear()` empties marks; the next column after `clear()` records no mark.

Rendering verified by eye on the phone (debug install).

## Behavior parity

Display-only. The decode path change is a pass-through timestamp parameter on
the spectrum sink; RX/TX/CAT/QSO logic untouched.
