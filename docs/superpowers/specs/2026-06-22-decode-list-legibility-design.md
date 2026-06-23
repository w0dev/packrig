# Decode List Legibility — Design

**Date:** 2026-06-22
**Status:** Approved, ready for implementation planning
**Scope:** v1.x code-health milestone, UX layer only (no RF-path changes)

## Background

Audit against FT8CN (N0BOY) and POTACAT (Waffleslop) v1.8.14 surfaced three legibility gaps in our operate-tab decode list ([`DecodeListPanel.kt`](../../../app/src/main/java/net/ft8vc/app/ui/operate/DecodeListPanel.kt)) versus what FT8 operators expect from WSJT-X / JTDX / FT8CN:

1. Our own transmissions never appear in the decode stream, so mid-QSO it's impossible to see where *our* "K1ABC W9XYZ -12" sat relative to the reply that came back 15 s later.
2. The decode row's five columns (UTC, SNR, distance, audio Hz, message) have no header strip, so a new operator can't tell what `-14` and `1234` mean without external context.
3. We have no "worked before" visual cue. An operator can't tell a band-new station from one already in the log without flipping to the log screen.

POTACAT's late-start TX and a-priori (AP) decoding are **out of scope** — they touch the RX/TX path the milestone is stabilising and are tracked as a separate follow-on phase.

## Goals

- Show outgoing transmissions inline in the decode list, anchored to the slot they were sent in.
- Make the row format self-documenting via a column header strip.
- Color-code each row by worked-before status (this band / other band / never) without overloading existing semantic colors.
- Zero change to RX/TX/CAT behavior. Pure additive UI.

## Non-goals

- Late-start TX (separate phase).
- A-priori decoding (separate phase).
- DXCC / state / grid coloring (would require shipping a DXCC dataset; out of scope for code-health).
- Mode-aware worked-before (FT8 is the only mode).
- Repeat/cancel gestures on synthesized TX rows (the main controls already own those).
- Per-slot background rhythm (FT8CN's 4-color cycle). Skip — overkill for our compact theme.

## Architecture

### Data model

A new sealed type in `core/`:

```kotlin
sealed interface DecodeRowSource {
    object Rx : DecodeRowSource
    object Tx : DecodeRowSource
}

enum class WorkedBefore { Never, ThisBand, OtherBand }
```

`DecodeRow` (currently in `app/`) gains two fields:

```kotlin
val source: DecodeRowSource = DecodeRowSource.Rx
val workedBefore: WorkedBefore = WorkedBefore.Never
```

Both default to today's behavior so existing call sites compile unchanged.

### TX row synthesis

`TxOrchestrator` already knows when it keys and what message it sent. It gains a new `SharedFlow<TxLogEvent>`:

```kotlin
data class TxLogEvent(val utc: Long, val freqHz: Int, val message: String)
```

`OperateViewModel` collects from that flow and appends a `DecodeRow(source = Tx, snr = 0, distanceKm = null, freqHz = txEvent.freqHz, message = txEvent.message, …)` to the same decode list it already maintains. Single source of truth, same `MAX_DECODE_ROWS` cap (currently 200). The model carries `snr = 0` and `distanceKm = null`; the renderer branches on `source == Tx` to display whitespace in those columns (see Visual spec).

TX rows do **not** feed `QsoMachine.onDecodes(...)` — the machine already knows what it sent; round-tripping our own TX through the decode path would risk double-counting.

### Worked-before lookup

New Room query on the existing QSO DAO:

```kotlin
@Query("SELECT DISTINCT band FROM qso WHERE call = :call")
suspend fun workedBands(call: String): List<String>
```

A `WorkedBeforeCache` in `app/` (or a thin `data/` helper) keeps a `Map<String, Set<String>>` of call → set of bands seen. Lookups are lazy: when a row enters the decode list, look up the call (if not cached, query → cache → return), then classify against the current band:

```kotlin
fun classify(call: String, currentBand: String, worked: Set<String>): WorkedBefore = when {
    currentBand in worked -> WorkedBefore.ThisBand
    worked.isNotEmpty()   -> WorkedBefore.OtherBand
    else                  -> WorkedBefore.Never
}
```

The cache is invalidated for a specific callsign when a new QSO with that call is logged (hook into the existing `RoomLogbook.log(...)` path). Cleared entirely in `OperateViewModel.onCleared()`.

### Component diagram

```
TxOrchestrator ──TxLogEvent──▶ OperateViewModel ──┐
                                                  │
DecodeController ──RX batch──▶ OperateViewModel ──┼─▶ decode rows ─▶ DecodeListPanel
                                                  │       ▲
WorkedBeforeCache ◀─QsoDao.workedBands───────────┘       │
       ▲                                                  │
       └──RoomLogbook.log() invalidate───────────────────┘
```

## Visual spec

### Column header strip

A single 24 dp row above the `LazyColumn`. Monospace `labelSmall`, `onSurfaceVariant`, same horizontal spacing as data rows so columns align:

```
UTC      SNR  DIST   Hz   MSG
```

- Sticky during scroll.
- No tap behavior.
- Hidden in the "empty / waiting for decodes" state — no header-without-rows.

### TX row

- Full-width background tint: `Ft8Amber.copy(alpha = 0.14f)`. Reuses the existing amber hue (already meaning "your attention here") at a softer fill so it reads as a *band* rather than text emphasis. Verified on both light and dark themes during implementation.
- Same column layout as RX rows.
- `SNR` and `DIST` columns render as whitespace (preserves column alignment without a placeholder glyph that would shift the eye).
- `Hz` shows the actual TX audio tone.
- `MSG` shows the outgoing FT8 text in `onSurface` color, weight `Bold`.
- No prefix glyph, no icon. The background tint is the marker.
- Non-clickable. No `Modifier.clickable`.

### Worked-before coloring

Applied to the **message column text only**, layered *under* the existing semantic-color rules. Precedence top-to-bottom (first match wins):

| Rule | Text color |
|---|---|
| CQ (existing) | `Ft8Green` |
| To-me, not in QSO (existing) | `Ft8Amber` |
| Partner (existing) | `colorScheme.primary` |
| Dimmed in-QSO (existing) | `onSurfaceVariant.copy(alpha = 0.5f)` |
| Worked this band | `onSurfaceVariant.copy(alpha = 0.55f)` |
| Worked other band | `colorScheme.tertiary` |
| Never worked | `onSurface` (current default) |

Semantic colors (CQ / to-me / partner) keep winning outright — a CQ from a station you've worked still renders green because you might want it for a multiplier. Worked-before only changes the appearance of the "default" tier.

All colors come from `MaterialTheme.colorScheme`; no hardcoded hex.

## Edge cases & behavior

- **MAX_DECODE_ROWS cap.** TX rows count against the 200-row cap like any other row. A long QSO may push older RX context out — correct: the *current* conversation stays visible.
- **Filter modes.** TX rows are always visible regardless of `DecodeViewMode` (Band / Focus) and the CQ·73 filter. Your own transmissions are never "chatter."
- **Slot-parity ordering.** TX rows sort into the list by UTC like RX rows. If a TX and an RX share the same second boundary (rare; opposite parity), tiebreak by source — RX first, TX second — so the conversation reads "they sent → you replied."
- **Worked-before cache lifecycle.** Built lazily on first reference; cleared in `OperateViewModel.onCleared()`. A new logged QSO invalidates just that callsign's entry, not the whole cache.
- **Empty logbook / no `myCall` set.** `workedBefore` defaults to `Never` for every row; no special UI.
- **Band identity for the lookup.** Derived from current `state.rigFreqHz`, bucketed via the existing band helper used by `QsoEntity.band`. Aligned with whatever string format the logbook already persists. Mode is FT8-only, so no mode key.

## Testing

### Unit (`core/`, no Android deps)

- `DecodeRowSource` defaults to `Rx`; explicit `Tx` round-trips.
- `WorkedBefore` classification given `(call, currentBand, workedBands)` tuples — all three branches.
- `MAX_DECODE_ROWS` eviction with mixed RX/TX rows preserves chronological order.

### Repository (`data/`, in-memory Room)

- `QsoDao.workedBands(call)` returns distinct bands across multiple QSOs.
- Logging a new QSO updates the band set.

### ViewModel (`app/`, coroutines test)

- `TxLogEvent` emitted by `TxOrchestrator` appears as a `DecodeRow` with `source = Tx` and the right `freqHz` / `message`.
- Logging a QSO invalidates the cached `workedBefore` entry for that callsign.

### Compose UI (`app/`)

- `DecodeListPanel` renders the column header when rows exist; not when empty.
- TX row renders with the amber background and bold message text.
- Worked-this-band RX row renders with the dimmed message color.
- Worked-other-band RX row renders with the tertiary message color.
- A CQ from a worked-this-band station still renders `Ft8Green` (semantic precedence).

### Manual on-rig regression

5-QSO session on the FT-891 + Digirig confirming:

- TX rows appear at the slots they were sent in.
- RX/TX/CAT behavior is unchanged from v1.0.
- Decode counts, ADIF export, and QSO completion are unaffected.

This is the milestone's behavior-parity bar.

## File-level change surface

- `core/` — add `DecodeRowSource`, `WorkedBefore`, classification function + tests.
- `data/` — add `QsoDao.workedBands(call)`, repository wrapper.
- `app/` — add `WorkedBeforeCache`; extend `DecodeRow`; collect `TxOrchestrator.txLog` in `OperateViewModel`; populate `workedBefore` when appending RX rows; invalidate cache on log.
- `app/.../rig/TxOrchestrator.kt` — add `SharedFlow<TxLogEvent>`, emit on key.
- `app/.../ui/operate/DecodeListPanel.kt` — sticky column header; TX row background branch; worked-before color branches.

## Open questions for implementation

- Confirm `QsoEntity.band` storage format and how to bucket `state.rigFreqHz` to the same representation. Likely just reuse an existing helper.
- Confirm `Ft8Amber.copy(alpha = 0.14f)` reads as a band (not a smudge) on the light theme — adjust alpha if needed during UI test.

## Out-of-scope follow-ups (tracked separately)

- Late-start FT8 TX (WSJT-X parity).
- A-priori (AP) decoding with `AP` badge on recovered rows.
