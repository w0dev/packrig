# Decode Color Scheme — WSJT-X-Style Categories, User-Configurable

**Date:** 2026-07-04
**Status:** Approved design, pending implementation plan

## Problem

When the operator answers a CQ and the partner replies, only the operator's own
TX rows stand out in the decode list. Two code paths in
`app/src/main/java/net/ft8vc/app/ui/operate/DecodeListPanel.kt` cause this:

1. Only TX rows get a background fill (`rowBackground = if (isTx)
   Ft8Amber.copy(alpha = 0.14f) else Color.Transparent`). Incoming rows rely on
   text color alone.
2. The amber "to me" text rule is `row.isToMe && !qsoActive` — it turns OFF the
   moment a QSO starts. The partner's reply then falls to the `isPartner` rule,
   which renders in `colorScheme.primary` = `Ft8Green` — the same green as
   every CQ row. Mid-QSO, the most important incoming message is visually
   near-identical to CQ chatter.

Additionally, `DecodeRowItem` and `core .. DecodePrefix` classify "partner"
with different rules (one requires `qsoActive`, one does not), and the
worked-before dimming is a hardcoded alpha that (a) never shipped to field
builds (`WorkedBefore` exists only on `readiness`; field builds run
`unstable`) and (b) silently classifies everything `Never` when CAT is
disconnected (`currentBand == null`).

## Prior art

- **WSJT-X** uses priority-ordered **background fills** (Settings → Colors):
  "My Call in message" = red, CQ = green, TX = yellow, plus new-call /
  worked-before categories. The my-call red outranks CQ green and never turns
  off during a QSO.
- **FT8CN** highlights messages containing the operator's call in a hot color
  regardless of QSO state and tracks the active QSO exchange distinctly.

Common denominator: **"contains my call" is the strongest visual treatment,
stronger than or equal to own-TX, and never disabled mid-QSO.**

## Design

### 1. Category resolution (core module, pure logic)

New enum + resolver in `core/src/main/java/net/ft8vc/core/`, alongside
`DecodePrefix`:

```kotlin
enum class DecodeCategory {
    MY_CALL,               // message directed to my callsign (in or out of QSO)
    PARTNER,               // current qsoDx during an active QSO
    OWN_TX,                // my transmitted rows (DecodeRowSource.Tx)
    CQ_NEW,                // CQ from a never-worked call
    CQ_WORKED_OTHER_BAND,  // CQ from a call worked, but not on this band
    CQ_WORKED_THIS_BAND,   // CQ from a call already worked on this band
    OTHER,                 // everything else
}

object DecodeCategoryResolver {
    fun resolve(
        isTx: Boolean,
        isCq: Boolean,
        isToMe: Boolean,
        workedBefore: WorkedBefore,
        qsoActive: Boolean,
        qsoDx: String?,
        message: String,
    ): DecodeCategory
}
```

Fixed priority, first match wins: `OWN_TX` > `PARTNER` > `CQ_NEW` / `CQ_WORKED_OTHER_BAND` / `CQ_WORKED_THIS_BAND` > `MY_CALL` > `OTHER`.

- `OWN_TX` must be checked first: a transmitted row's message text contains
  both the partner call and my call, so it would otherwise match `PARTNER`.
- `PARTNER` requires `qsoActive && qsoDx != null && message.contains(qsoDx)`
  (the `DecodePrefix` rule — the stricter of the two current variants). It
  outranks `MY_CALL` because partner replies also contain my call, and
  partner rows must keep the `▸` glyph. This also covers the partner's
  messages to *other* stations (e.g., the partner answering a different
  caller), which is exactly when the operator needs to notice.
- `MY_CALL` applies whenever `isToMe`, **including during an active QSO** —
  this is the direct fix for the reported problem. Mid-QSO it captures
  stations other than the partner calling me (tail-enders). If a row is
  ever inconsistently flagged both CQ and to-me (unreachable today — `isDirectedToMe` only matches directed message types), the CQ categories win: a broadcast must never render as "calling you" (pinned by the v1.0 test `cqPrefixWinsOverToMe`).
- Worked-before categories apply **only to CQ rows**. Today's rendering dims
  any row from a worked call; that conflicts with QSO-chatter dimming and does
  not serve the actual decision ("should I answer this CQ?"). Non-CQ rows from
  worked calls fall to `OTHER`. `workedBefore` stays populated on every
  `DecodeRow` (classification logic is owned by the "worked callsign
  visibility" workstream); rendering consumes it only for CQ rows.
- `DecodePrefix.prefixFor` is reimplemented on top of the resolver so the
  color-blind glyphs (`●` CQ, `→` to-me, `▸` partner) and row colors can never
  disagree. Glyph mapping: `PARTNER` → `▸`, `MY_CALL` → `→`, `CQ_*` → `●`,
  `OWN_TX` and `OTHER` → blank. (Behavior change from today: `→` now also
  shows mid-QSO for to-me rows that are not from the partner.)

### 2. Row treatment (app module)

Treatment *style* is fixed per category; only the *color* is configurable.

| Priority | Category | Default color | Treatment |
|---|---|---|---|
| 1 | `OWN_TX` | `Ft8Amber` `0xFFFFB347` | background fill (0.16 alpha) + bold |
| 2 | `PARTNER` | `Ft8Red` `0xFFE63946` | background fill (0.16 alpha) + bold |
| 3 | `CQ_NEW` | `Ft8Green` `0xFF3DDC97` | text color |
| 4 | `CQ_WORKED_OTHER_BAND` | Cyan `0xFF4CC9F0` | text color |
| 5 | `CQ_WORKED_THIS_BAND` | Gray `0xFF9AA0A6` | text color |
| 6 | `MY_CALL` | `Ft8Red` `0xFFE63946` | background fill (0.16 alpha) + bold |
| 7 | `OTHER` | theme default | unchanged (QSO-active chatter dim stays) |

Notes:

- Background fills are reserved for the three "this concerns *your* QSO"
  categories — matching WSJT-X's core cue while keeping CQ rows as colored
  text so the list stays quiet (FT8CN does the same for most rows). Fill
  alpha is a fixed constant (`0.16f`), not user-configurable.
- `CQ_WORKED_THIS_BAND` replaces the current hardcoded
  `onSurfaceVariant.copy(alpha = 0.55f)` dim with an explicit muted color the
  user can change. The worked-before "dimming" feature is thereby absorbed
  into the scheme, not left as a parallel mechanism.
- The QSO-active dim of unrelated chatter (`0.5f` alpha on `OTHER` rows while
  a QSO is active) is a separate focus feature and is unchanged.
- SNR/DIST/Hz/UTC cells keep `onSurfaceVariant`; only the message text and row
  background carry category color.

### 3. Settings + persistence

- `SettingsRepository` gains six ARGB-int preferences (one per configurable
  category; `OTHER` is not configurable):
  `decode_color_my_call`, `decode_color_partner`, `decode_color_own_tx`,
  `decode_color_cq_new`, `decode_color_cq_worked_other`,
  `decode_color_cq_worked_this`.
- A `DecodeColorScheme` data class (six `Int` ARGB fields + `DEFAULT`
  companion) flows into `OperateUiState` alongside existing settings and is
  passed to `DecodeListPanel`.
- Settings screen gets a collapsible **"Decode colors"** card, collapsed by
  default (pattern: existing collapsible USB diagnostics row). Contents:
  - One row per category: color swatch + category name + one-line description
    (e.g., "My call — messages directed at you").
  - Tapping a row opens a dialog with a **curated 12-swatch palette** (pure
    Compose, no new dependencies):
    `0xFFE63946` red, `0xFFFF6B6B` coral, `0xFFFFB347` amber,
    `0xFFFFD166` yellow, `0xFF3DDC97` green, `0xFF2EC4B6` teal,
    `0xFF4CC9F0` cyan, `0xFF6C9DFF` blue, `0xFF9B8CFF` violet,
    `0xFFE07BE0` magenta, `0xFFFF8FA3` pink, `0xFF9AA0A6` gray.
    All chosen for legibility on the dark theme; no free HSV picker, so users
    cannot pick an unreadable color.
  - A "Reset to defaults" row.

### 4. Testing

- **Core:** `DecodeCategoryResolver` unit tests — priority ordering; the
  reported failure (partner reply mid-QSO resolves `MY_CALL`/`PARTNER`, never
  a CQ style); to-me highlighting active during QSO; worked-before categories
  gated to CQ rows; prefix/category agreement.
- **App:** DataStore round-trip test (write colors → read `DecodeColorScheme`);
  Compose UI test asserting a to-me row carries the background fill during an
  active QSO (the original bug, pinned).

### 5. Sequencing and coordination

- Implementation starts **after** the pending uncommitted autoscroll +
  TX-status changes on `readiness` are committed (they touch
  `DecodeListPanel.kt` / `OperateUiState.kt`).
- The parallel session "Worked callsign visibility in decodes" owns
  worked-before *classification* correctness (logbook lookup, band
  normalization, the CAT-disconnected `currentBand == null` → `Never`
  limitation) and has been asked not to touch decode styling or settings UI.
  This spec consumes whatever `WorkedBefore` value rows carry.
- Lands on `readiness` → `unstable` per the milestone flow. Note this also
  closes the "worked-before dimming never reached field builds" gap at the
  next promotion.

## Known constraints

- With CAT disconnected, `WorkedBefore.classify(null, …)` returns `Never`, so
  all CQ rows render as `CQ_NEW`. Accepted for this design; any improvement
  belongs to the worked-before classification workstream.
- Scope note: this adds a small feature surface (settings section) during a
  code-health milestone, accepted by the user; in exchange it centralizes the
  currently duplicated/inconsistent row-classification logic.

## Out of scope

- User-reorderable category priority (WSJT-X allows it; deferred — most of the
  complexity for little field value).
- Per-category fill-vs-text choice.
- A dedicated Rx-Frequency-style QSO thread pane (conflicts with the
  milestone's screen-real-estate constraint; revisit post-milestone).
- Light-theme-specific palettes (the curated palette is chosen for the dark
  theme; acceptable on light).
