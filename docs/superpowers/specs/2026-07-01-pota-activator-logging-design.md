# POTA Activator Logging Correctness — Design

**Date:** 2026-07-01
**Status:** Approved (brainstorming complete)
**Phase:** 1 of 5 in the Field Readiness milestone

## Milestone context

Field Readiness makes FT8VC trustworthy for POTA hunting and activating, at
behavioral parity with WSJT-X / FT8CN where it matters in the field. Five
sub-projects, each independently shippable and field-verified on the
FT-891 + Digirig reference rig:

1. **Activator logging correctness** (this spec)
2. QSO sequencing upgrades — initiator RR73 with log-on-send, auto-resume
   CQ toggle, base-call matching in `QsoMachine.fromDx`
3. RX reliability parity — persist the JNI callsign hash table across
   slots, raise decode `f_max` above 3000 Hz, median-DT clock-health chip
4. Hunter POTA visibility — retain the CQ modifier through parsing,
   badge/filter `CQ POTA` rows
5. PSK Reporter RX upload

## Problem

The park reference is applied at **export time from current settings**
(`AdifNormalizer.normalizeRecord` + `AdifExportContext`), not stored per
QSO. Every contact in the logbook gets stamped with whatever park is in
Settings when the export runs. A multi-park weekend, or home QSOs mixed
with activation QSOs, produces incorrect POTA uploads. There is also no
way to produce the POTA upload unit (one file per park per UTC day), no
two-fer support, and no way to fix a wrong park after the fact.

## Requirements

- Each QSO records, at completion time, the park ref(s) it was made from
  (or none). Later settings changes never alter logged QSOs.
- Multi-park (two-fer) activations are supported: a QSO can carry
  multiple park refs.
- Export produces one ADIF file per activation `(park, UTC day)`,
  directly uploadable to pota.app.
- Park assignments on already-logged QSOs can be fixed in bulk from the
  Log screen.
- RX/TX/CAT behavior is untouched: this phase lives in core, data, and
  app-UI layers only.

## Design

### 1. Data model & capture

- `QsoEntity` and `QsoContact` gain `potaParkRefs: String?` — normalized
  comma-separated refs (e.g. `US-3315,US-0891`). `null` means "not a
  POTA QSO". One Room migration adds the column; existing rows are
  `null` (recoverable via bulk fix).
- `ActivationProfile` (core) owns the format:
  - `parseParkRefs(raw: String): List<String>` — split on commas,
    normalize each via the existing `normalizeParkRef`, drop blanks.
  - `formatParkRefs(refs: List<String>): String?` — normalized CSV,
    `null` when empty.
  - List-aware validation: valid iff non-empty and every ref matches
    the existing `PARK_REF` regex.
- Settings and the Operate park chip accept multiple comma-separated
  refs through the same validator. The DataStore key keeps storing a
  single string (now possibly a CSV); no settings migration needed.
- Capture point: `OperateViewModel.onQsoComplete` snapshots the current
  `potaModeEnabled` + park refs onto the `QsoContact` before it is
  written to Room. POTA mode off → `null`.
- `QsoSessionController.startCq`'s existing POTA guard validates every
  ref in the list.

### 2. Export & the activation picker

- An **activation** is a derived grouping, computed in memory from the
  contacts flow: `(parkRef, UTC day)` over contacts whose park set
  contains that park. A two-fer QSO appears in each of its parks'
  activations. Home QSOs (`null` parks) never appear.
- Log screen gains a POTA export entry listing detected activations
  (park, UTC date, QSO count). Tapping one generates a single ADIF
  containing exactly that group's QSOs, each record stamped
  `MY_SIG=POTA`, `MY_SIG_INFO=<that one park>`, then hands it to the
  Android share sheet named `CALL@PARK-YYYYMMDD.adi`.
- Per-record stamping replaces global stamping:
  - `AdifNormalizer.normalizeRecord` reads park data from the contact
    (plus an optional "stamp as this single park" parameter used by
    activation export).
  - `AdifExportContext.potaEnabled` / `potaParkRef` and the
    "POTA mode on but park reference missing" export exception in
    `LogViewModel` / `AdifAutoBackup` are deleted.
- Full-log export and `AdifAutoBackup` keep their single-file shape and
  emit each record's own park set as a CSV in `MY_SIG_INFO` (community
  practice for multi-park records), so backups preserve park data
  losslessly. Records without parks emit no `MY_SIG`/`MY_SIG_INFO`.

### 3. Bulk park fix

- Log screen gains a selection mode: long-press a QSO to enter it,
  plus a "select this UTC day" shortcut.
- One bulk action — **Set parks…** — opens the same park-ref input and
  **replaces** the park set on all selected QSOs. Empty input clears
  parks (QSOs become home QSOs).
- Applied via a single DAO update; the usual ADIF auto-backup runs
  afterward. No general-purpose QSO editor.

### 4. Errors & validation

- One validation path (`ActivationProfile`) at all three entry points:
  Settings, Operate chip, bulk fix. Invalid refs are rejected inline;
  storage only ever sees normalized values.
- `AdifValidator` remains fail-closed on every export path.
- Activation export cannot target an empty group (the list is derived
  from the data).

## Testing

- Unit (core/data): park-list parse/format round-trips; per-record
  stamping incl. no-park records; activation grouping — UTC-day
  boundaries, two-fer duplication across files, home-QSO exclusion;
  single-park stamping in activation export vs CSV in full export.
- Room migration test for the new column.
- DAO test for the bulk park update.
- Behavior parity: no changes to RX/TX/CAT/QSO sequencing code paths.

## Field verification

Log a real park session on the reference rig, export the activation,
and get a clean accept from the pota.app uploader. For a two-fer,
verify both files contain the same QSOs with the correct single park
each.

## Out of scope

- Hunter-side `SIG_INFO` (the park you hunted) — phase 4 territory.
- General QSO field editing.
- Date-range or non-POTA filtered exports (falls out naturally later).
- Auto re-CQ, RR73, and all sequencing changes — phase 2.
