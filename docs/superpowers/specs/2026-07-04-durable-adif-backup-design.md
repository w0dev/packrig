# Durable ADIF Backup: Documents Mirror + ADIF Import

**Date:** 2026-07-04
**Status:** Approved
**Motivation:** On 2026-07-04 the field phone's logbook lost ~7 QSOs: instrumented
test runs uninstalled the app (wiping the Room DB and the app-private ADIF
backup), and Android restore-at-install silently brought back a stale 3-QSO
snapshot. The existing "backup" (`ft8vc-logbook.adi` in
`getExternalFilesDir`) dies with the app on uninstall, and the app has no way
to restore from any ADIF file. This feature closes both gaps.

## Goals

1. Every logbook backup also lands in a location that **survives uninstall**
   and is visible to the user (`Documents/ft8vc/`).
2. The user can **restore** a logbook from any ADIF file via a file picker.
3. Behavior parity: the existing app-private atomic backup, its triggers
   (after every QSO commit, daily timer, "Backup now" button), and the
   "Last backup" status text are unchanged.

## Non-goals

- No cloud sync, no user-configurable backup location (a SAF folder grant
  dies on uninstall, defeating the purpose).
- No timestamped snapshot rotation. One mirror file, refreshed per backup.
- No Operate-screen UI. Import lives in Settings → Logbook.

## Design

### 1. Durable mirror — `DocumentsAdifMirror` (app module)

`AdifAutoBackup.backupNow()` keeps its current flow (export ADIF from Room →
atomic tmp+rename in app-private external dir → update `lastAdifBackupAtMs`),
then additionally calls `DocumentsAdifMirror.write(context, adif)`:

- Writes the same ADIF text to `Documents/ft8vc/ft8vc-logbook.adi` via
  `MediaStore.Files` insert with `RELATIVE_PATH = "Documents/ft8vc"`.
- If a MediaStore entry with that display name in that path is **owned by
  this install**, update it in place (query + truncating
  `openOutputStream`). If not found, insert a new entry. If an
  unowned same-name file exists, MediaStore auto-uniquifies (e.g.
  `ft8vc-logbook (1).adi`) — the stale copy is intentionally left behind as
  history.
- **API guard:** `RELATIVE_PATH` requires API 29. On API 28 the mirror is a
  silent no-op (app-private backup still happens). Reference phone is API 36.
- **Failure isolation:** any mirror exception is caught and logged
  (`Log.w`); it never fails the private backup and never surfaces as an
  error snackbar. The mirror is best-effort by design.

### 2. ADIF import — `AdifReader` (data module, pure Kotlin)

New `data/adif/AdifReader.kt`:

- Parses ADIF text: optional header (everything before `<eoh>`), then
  records of `<FIELD:len[:type]>value` fields terminated by `<eor>`.
  Tag names case-insensitive; unknown fields ignored; whitespace/newlines
  between fields tolerated.
- Maps to `QsoContact` using the same fields `AdifWriter` emits:
  `qso_date` + `time_on` → `utcMillis` (UTC, second precision; minute
  precision tolerated), `call` → `dxCall`, `gridsquare` → `dxGrid`,
  `rst_sent`/`rst_rcvd` (integer SNR reports), `freq` (MHz) → `freqHz`,
  `band`, `mode`, `comment` → `notes`, `my_sig_info`/`pota_ref` →
  `potaParkRefs`, `station_callsign`/`operator` → `myCall`,
  `my_gridsquare` → `myGrid`.
- Records missing `call` or a parseable date+time are skipped and counted;
  a file with zero valid records is an error. `myCall`/`myGrid` fall back
  to values supplied by the caller (current Settings profile).
- Returns `AdifReadResult(contacts: List<QsoContact>, skipped: Int)`.

### 3. Merge — `Logbook.importContacts()` (data module)

New method on the `Logbook` interface + `RoomLogbook`:

`suspend fun importContacts(incoming: List<QsoContact>): ImportResult`

- Duplicate rule: an incoming record is a duplicate iff an existing contact
  has the same `dxCall` (case-insensitive), same `band` (null matches null),
  and `|utcMillis − existing.utcMillis| < 90_000` ms. The window tolerates
  minute-precision files from other loggers.
- Non-duplicates are inserted; `ImportResult(imported: Int, duplicates: Int,
  skipped: Int)` feeds the snackbar. Logbooks are small (hundreds of rows);
  the comparison runs in memory against the current contact list.

### 4. UI — Settings → Logbook

One new button under "Backup now": **"Import ADIF…"**

- Launches `ActivityResultContracts.OpenDocument` (MIME `*/*`; `.adi` has no
  reliable MIME type). No storage permissions needed at any API level.
- On result: read the URI's text, `AdifReader.read(...)` with the current
  station profile as fallback, `logbook.importContacts(...)`, then snackbar
  "Imported N QSOs (M duplicates skipped)" or the failure reason. A
  successful import triggers `AdifAutoBackup` so the mirror reflects the
  merged log.

## Error handling

| Failure | Behavior |
|---|---|
| Mirror write fails (MediaStore) | `Log.w`, private backup unaffected, no snackbar |
| API 28 device | Mirror skipped silently |
| Unreadable/garbage import file | Snackbar with reason, log unchanged |
| Import file with some bad records | Good records import; skipped count reported |
| Import fails mid-insert | Room transaction in `importContacts` — all-or-nothing |

## Testing

- **JVM unit tests (data module):** `AdifReader` — writer→reader round-trip
  (export N contacts, parse back, field-level equality); lowercase tags;
  minute-precision times; missing optional fields; skipped-record counting;
  garbage input. Merge — duplicate window boundaries (89 s dup / 91 s not),
  case-insensitive call match, null band, re-import of own export is a
  full no-op.
- **On-device manual verification (installDebug):** mirror file appears in
  Documents/ft8vc after "Backup now"; survives uninstall/reinstall; import
  restores it; double-import shows all-duplicates.
- Instrumented tests may run on the field phone (owner OK, 2026-07-04),
  but `connectedAndroidTest` uninstalls the app and wipes the logbook —
  pull a logbook copy over adb first (`run-as` the Room DB, or the
  app-private ADIF), and restore/import it afterwards.
