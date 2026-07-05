# Decode slot tint — design

**Date:** 2026-07-05
**Branch:** multi-rig
**Status:** Approved, ready to plan

## Problem

In the Operate-tab decode list, decodes from consecutive 15-second UTC slots run
together visually. There is no cue for where one slot's decodes end and the next
begins, so it is harder than it should be to read the list slot-by-slot.

## Goal

Make it easy to tell one slot's decodes from the next, without adding chrome,
vertical space, or a new setting, and without competing with the existing
semantic decode colors.

## Approach

Tint the **UTC (first) column cell** with a subtle neutral grey on one slot
parity, leaving the other parity dark as it is today. This produces a stable
zebra keyed to slot rhythm and visually echoes the Even/Odd TX toggle the
operator already thinks in.

Rows in the same slot share the tint (grouped); the next slot flips parity and
so flips the tint (separated).

### Why parity, and why the time cell only

- `DecodeRow` already carries `slotParity: TxSlotParity` (`EVEN`/`ODD`), so no
  model change is needed.
- Tinting only the UTC cell keeps the change minimal: no layout change, no new
  vertical space, and the SNR/DIST/CC/Hz/MSG cells are untouched.
- A neutral grey (not a category color) guarantees it never competes with the
  semantic category fills (OWN_TX / PARTNER / MY_CALL) or the dimmed-OTHER
  treatment. It reads as "slot timing," not "signal category."

### Rejected alternatives

- **Horizontal separator line between slots** (driven off `timeUtc` change):
  strongest boundary but adds a thin line every few rows on a busy band and
  consumes scarce main-screen vertical space.
- **Whole-row zebra by parity:** stronger grouping but collides with the
  existing category row-fills and OTHER dimming.
- **Settings toggle:** expands the settings surface; the milestone constraint is
  no new feature surface. The tint is subtle enough to leave always-on.

## Behavior

- **EVEN** slot rows: UTC cell gets a neutral grey background.
- **ODD** slot rows: UTC cell unchanged (dark, as today).
- (EVEN vs ODD as the tinted parity is arbitrary but fixed; chosen EVEN.)
- Always on. No preference, no toggle.
- Applies to every row regardless of category or filter state — including
  own-TX synthesized rows and category-filled rows, where the subtle grey layers
  over the existing fill without obscuring it.
- The `UTC` **header** cell is not tinted, so column alignment is unaffected.

## Visual detail

- Color: `MaterialTheme.colorScheme.onSurface` at a very low alpha
  (~0.06–0.08) so it holds up in both light and dark themes.
- Shape: small rounded background behind the UTC text with minimal horizontal
  padding, sitting inside the existing row so it does not change row height
  (`heightIn(min = 40.dp)`).

## Implementation

- Add one pure, testable helper alongside the row rendering, e.g.
  `slotTintAlpha(parity: TxSlotParity): Float` (returns the tint alpha for EVEN,
  `0f`/none for ODD) — or a nullable `Color` variant. Keeps the parity→tint rule
  in one place.
- In `DecodeRowItem` (`app/src/main/java/net/ft8vc/app/ui/operate/DecodeListPanel.kt`),
  wrap the existing UTC `Text` with a `background(...)` derived from the helper
  and `row.slotParity`.
- No change to `DecodeRow`, `MonitorDecodeFilter`, the header row, or layout.

## Testing

- **Unit:** the helper — EVEN → tint present, ODD → none.
- **Behavior parity:** display-only change; RX/TX/CAT/QSO paths untouched and
  remain byte-equivalent on the reference rig.
- **Field:** eyeball on Yaesu FT-891 + Digirig to confirm the tint is
  subtle-but-legible and does not muddy the category colors in the field.

## Constraints honored

- No new feature surface, no new setting, no new top-level dependency.
- No main-screen real estate claimed (no added rows/height).
- Behavior parity preserved (display-only).
