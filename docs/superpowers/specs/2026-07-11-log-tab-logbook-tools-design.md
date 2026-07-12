# Log Tab Logbook Tools — Design

**Date:** 2026-07-11
**Status:** Approved
**Branch target:** `unstable`

## Goal

Move the logbook maintenance controls — **Backup now** and **Import ADIF…** —
from the Settings screen's "Logbook" section into the Log tab, so all logbook
functionality lives in one place. Same functionality, relocated UI only.

## Current State

- `SettingsScreen.kt` has a `SettingsSection("Logbook")` containing:
  - "Last backup: X ago" label derived from `OperateUiState.lastAdifBackupAtMs`
  - **Backup now** button → `OperateViewModel.backupAdifNow()`
  - **Import ADIF…** button → `OpenDocument("*/*")` picker →
    `OperateViewModel.importAdif(uri)`
  - Caption: ADIF auto-exports after every QSO to app-private storage and
    Documents/ft8vc
- The logic lives on **OperateViewModel** and must stay there:
  - `importAdif` invalidates OperateViewModel's `WorkedBeforeCache`
  - Success/failure feedback goes through `notify(...)` →
    app-level snackbar host in `Ft8NavHost`, visible on any tab
- The Log tab (`LogScreen.kt`, backed by `LogViewModel`) top bar already has
  search, POTA export, share ADIF, and clear-log actions, plus contextual
  selection actions.

## Design

### Log tab overflow menu

In the default top-bar state (not searching, no selection), add a three-dot
overflow menu (`Icons.Filled.MoreVert`) after the existing action icons.
Menu contents:

1. **Informational line (non-clickable):** "Last backup: X ago" (same age
   buckets as Settings today: just now / N min / N h / N d / never), with
   supporting text "Auto-exports after every QSO to Documents/ft8vc".
2. **Backup now** → `onBackupNow()`
3. **Import ADIF…** → launches `OpenDocument` with `arrayOf("*/*")`
   (`.adi` has no registered MIME type; the reader filters), then
   `onImportAdif(uri)`.

Both actions are always enabled — import into an empty log is the
restore-after-reinstall path; backup of an empty log matches current
Settings behavior.

### Empty-state import button

When the log is empty, below the existing hint
("Complete a QSO on Operate to populate your log."), show an
**Import ADIF…** `OutlinedButton` that launches the same picker.

### Wiring

`LogScreen` gains three parameters instead of taking `OperateViewModel`:

```kotlin
fun LogScreen(
    vm: LogViewModel = viewModel(),
    lastAdifBackupAtMs: Long? = null,
    onBackupNow: () -> Unit = {},
    onImportAdif: (Uri) -> Unit = {},
)
```

`Ft8NavHost` supplies them from `operateVm` / `operateState`, which it
already holds:

```kotlin
LogScreen(
    vm = logVm,
    lastAdifBackupAtMs = operateState.lastAdifBackupAtMs,
    onBackupNow = operateVm::backupAdifNow,
    onImportAdif = operateVm::importAdif,
)
```

This keeps LogScreen decoupled from OperateViewModel and previewable.

### Settings screen removal

- Delete the whole `SettingsSection("Logbook")` block from
  `SettingsScreen.kt` (the separate "QRZ Logbook" section stays).
- Remove imports that become unused (e.g. `rememberLauncherForActivityResult`,
  `ActivityResultContracts`) only if nothing else in the file uses them.
- Update doc comments that reference "Settings → Logbook":
  - `OperateViewModel.backupAdifNow()` KDoc
  - `OperateViewModel.importAdif()` KDoc
  - `AdifAutoBackup` class KDoc

## Not Changing

- `backupAdifNow` / `importAdif` implementations, snackbar plumbing,
  `AdifAutoBackup`, `AdifReader`, worked-before invalidation — untouched.
- QRZ Logbook section in Settings — stays.
- Existing Log tab actions (search, POTA, share, clear) — unchanged.

## Error Handling

Unchanged: backup and import report success/failure via
`OperateViewModel.notify(...)` snackbars, which display app-wide.

## Testing & Verification

- UI-only relocation; no new logic worth a unit test. The pure pieces
  (age formatting) are trivial string bucketing inherited verbatim.
- Verify: project compiles, existing unit tests pass.
- Device smoke check (unstable build): overflow menu opens, Backup now
  fires the "ADIF backup written" snackbar, Import ADIF opens the picker
  and merges a file, empty-state import button appears when log is empty,
  Settings no longer shows the Logbook section.
