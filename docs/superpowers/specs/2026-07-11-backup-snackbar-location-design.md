# Backup Snackbar Location — Design

**Date:** 2026-07-11
**Status:** Approved
**Branch target:** `unstable`

## Goal

When the operator taps "Backup now" in the Log tab's logbook tools menu, the
snackbar should say **where** the backup landed — and only claim the durable
Documents/ft8vc location when that copy actually succeeded.

## Current State

- `AdifAutoBackup.backupNow(...)` writes the ADIF atomically to the
  app-private external dir, then calls `DocumentsAdifMirror.write(...)`
  (best-effort mirror to `Documents/ft8vc/ft8vc-logbook.adi`, API 29+ only)
  and **discards its Boolean result**. Returns `File?` — the private file on
  success, `null` on failure.
- `OperateViewModel.backupAdifNow()` maps that to a snackbar:
  "ADIF backup written" (TRANSIENT) or "ADIF backup failed" (ERROR).
- Consequence: the snackbar says "written" even when the durable mirror
  silently failed (Android 9 device, or stale MediaStore ownership after a
  reinstall) — precisely the case where a user who goes looking in
  Documents/ft8vc finds nothing.

## Design

### `AdifAutoBackup.Outcome`

`backupNow` returns a nested result type instead of `File?`:

```kotlin
data class Outcome(val privateFile: File, val mirrored: Boolean)

suspend fun backupNow(...): Outcome?
```

Inside `backupNow`, capture `val mirrored = DocumentsAdifMirror.write(context, adif)`
(currently called and ignored) and return `Outcome(target, mirrored)`.
`null` on failure, as today.

Other callers (`scheduleBackupAfterQso`, the daily timer, LogViewModel via
`scheduleBackupAfterQso`) ignore the return value and need no changes.

### Message selection (pure, tested)

A pure function on `AdifAutoBackup` picks the success text:

```kotlin
fun backupSnackbarText(mirrored: Boolean): String =
    if (mirrored) "ADIF backup written to Documents/ft8vc"
    else "ADIF backup written (app-private storage only)"
```

### ViewModel wiring

`OperateViewModel.backupAdifNow()` becomes:

- success → `notify(AdifAutoBackup.backupSnackbarText(result.mirrored), TRANSIENT)`
- failure → `notify("ADIF backup failed", ERROR)` (unchanged)

The mirror-failed case stays TRANSIENT (not ERROR): the private backup did
succeed, and on Android 9 the mirror is expected to be unavailable — a
lingering dismissable error every backup would be noise. The message text
carries the caveat.

### Message wording

- Mirror OK: `ADIF backup written to Documents/ft8vc`
- Mirror failed or unavailable: `ADIF backup written (app-private storage only)`
- Failure: `ADIF backup failed` (unchanged)

The directory is named, not the file: after a reinstall MediaStore may
uniquify the display name (`ft8vc-logbook (1).adi`), so the directory is the
stable, truthful part.

## Not Changing

- Backup mechanics, atomic write, mirror logic, auto-backup triggers, the
  Log tab menu UI, the "Last backup: X ago" label.
- No configurable backup directory (considered, declined — SAF picker and
  arbitrary-tree mirroring expand the feature surface this code-health
  milestone holds; may revisit later).

## Testing & Verification

- Unit test `backupSnackbarText` (both branches) — plain JVM test, no
  Android deps.
- Compile + existing app unit suite green.
- Device smoke check: Backup now on the field phone (API 29+) shows
  "ADIF backup written to Documents/ft8vc".
