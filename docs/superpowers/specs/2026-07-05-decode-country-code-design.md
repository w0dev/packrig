# Decode Country Code Column (Design)

**Date:** 2026-07-05
**Branch:** `multi-rig` (user chose to land it here)
**Status:** Approved (code system, data source, and column placement confirmed with user)

## Problem

Other FT8 apps (WSJT-X, FT8CN) show the DX station's country alongside each decode.
FT8VC decode rows show time / SNR / distance / freq / message, but the operator has to
recognize prefixes by eye to know whether a CQ is DX or local. Distance already exists
(`DecodeDistance`), but only for messages carrying a grid; a country indicator works for
every message with a resolvable callsign.

## Scope

**In:** A two-letter ISO 3166 alpha-2 country code column in the decode list, derived
offline from the sender callsign of each row. Works in both Operate and Monitor decode
views (they share the same row model and panel).

**Out (explicitly rejected during brainstorming):**
- Flag emoji or full country names — user chose two-letter codes.
- DXCC-flavored codes (distinct codes for Asiatic vs European Russia, etc.) — ham
  distinctions collapse to ISO: KL7 → US, UA9 → RU, IS0 → IT.
- Runtime `cty.dat` asset parsing (approach B) and a hand-written table (approach C).
- Country in the QSO log / ADIF export — display-only, decode list only.
- Call→grid caching to extend the *distance* column — noted as a separate future idea.

## Decisions

1. **Code system:** ISO 3166 alpha-2. DXCC entities with no clean ISO equivalent map to
   the administering ISO country where obvious, else null (blank cell). Wrong-but-blank
   beats wrong-but-confident.
2. **Data source:** A committed, generated lookup table in `core/`, produced by a
   dev-time script in `tools/` from the AD1C `cty.dat` file plus an entity-name→ISO map
   maintained in the script. Regeneration is manual and rare; staleness degrades to a
   blank cell.
3. **Placement:** Fixed two-character monospace column between distance and freq,
   styled identically to its neighbors (`labelSmall`, monospace, `onSurfaceVariant`).
   Unknown → ` —`, TX rows → blank, mirroring the distance column's conventions.
4. **Lookup timing:** Once per new decode row, on the decode worker thread, at the same
   point `workedBeforeLookup` runs. Table parse is `lazy` (first decode pays a few ms,
   once).

## Design

### Generator (`tools/gen_country_table.py`, new)

- Input: `cty.dat` (basic variant) fetched from country-files.com (URL documented in
  the script header) or passed as a local path.
- Contains the DXCC-entity-name → ISO alpha-2 map (~340 entries) as script data.
- Collapses cty.dat to ISO granularity, then prunes: a prefix entry is dropped when the
  next-shorter matching prefix already yields the same ISO code; an exact-call override
  is kept only when its ISO differs from what prefix matching would produce.
- Output: `core/src/main/java/net/ft8vc/core/CallsignCountryTable.kt` — a generated
  object holding two compact `String` constants (`PREFIXES`, `EXACT`) in
  `PREFIX=CC;PREFIX=CC;…` form, plus a `MAX_PREFIX_LEN` const. A header comment names
  the generator, the cty.dat version line, and the generation date.

### Core (`core/.../CallsignCountry.kt`, new)

- `object CallsignCountry { fun isoFor(call: String): String? }` — pure Kotlin, no I/O.
- Parses `CallsignCountryTable` into two `HashMap`s inside a `by lazy` initializer.
- Algorithm, in order:
  1. Normalize: trim, uppercase. Hashed/nonstandard tokens (`<...>`) → null.
  2. Strip one trailing portable suffix: `/P`, `/M`, `/QRP`, `/A`, or `/<digit>`.
     `/MM` and `/AM` → null (no DXCC country by rule).
  3. If a `/` remains (e.g. `F/DL1ABC`), the shorter segment is the prefix designator;
     look it up as the call. Ties go to the first segment.
  4. Exact-call map hit → that ISO.
  5. Longest-prefix match: try substrings from `min(length, MAX_PREFIX_LEN)` down to 1
     against the prefix map. First hit wins; no hit → null.

### Row model + controller (`app/.../controllers/DecodeController.kt`)

- `DecodeRow` gains `countryCode: String? = null`.
- At row creation (next to the existing `workedBeforeLookup` call):
  `countryCode = sender?.let { CallsignCountry.isoFor(it) }`.
- No refresh path needed — a call's country never changes mid-session.

### UI (`app/.../ui/operate/DecodeListPanel.kt`)

- New `Text` between the distance and freq columns:
  `if (isTx) "  " else (row.countryCode ?: " —")`, same style as neighbors.
- Header row gains `DecodeHeaderCell("CC")` between the existing `DIST` and `Hz` cells.
- Net main-screen cost: two monospace characters plus one 6dp gap of message width.

### Error handling

- Every failure mode (unparseable call, unknown prefix, hashed call, maritime mobile)
  renders ` —`. No exceptions escape `isoFor`; no new error channels.

### Threading

- Lookup runs on the decode worker thread inside the existing row-building loop. The
  lazy table parse therefore also lands there — never on Main or audio threads.

## Testing

- **Unit (core), table-driven `CallsignCountryTest`:**
  - Plain prefixes: `K1ABC` → US, `JA1XYZ` → JP, `DL1ABC` → DE, `G4ABC` → GB.
  - ISO collapse traps: `KL7AA` → US, `KH6AA` → US, `KG4AB` → US, `RA9AA` → RU,
    `IS0ABC` → IT, `VP8ABC` → FK.
  - Portable: `F/DL1ABC` → FR, `DL1ABC/P` → DE, `W1ABC/MM` → null.
  - Nulls: `<...>` hashed, empty string, `QRZ?`-style junk.
- **Unit (core):** generated-table sanity — parses without error, every value is two
  uppercase ASCII letters, `MAX_PREFIX_LEN` consistent with the longest key.
- **Unit (app):** `DecodeController` populates `countryCode` on new rows (extend the
  existing controller test fixture).
- **Field bar:** none required — display-only; RX/TX/CAT paths untouched.

## Behavior parity statement

No change to decode, QSO sequencing, TX, CAT, or logging paths. The feature adds one
pure-function call per new decode row and one display column. Removing the column
restores the exact v1.x row layout.
