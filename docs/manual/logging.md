# Logging and ADIF

## What gets logged, and when

Completed QSOs log automatically — there is no manual "log it" step. The
moment of logging follows the **Send RR73** setting
(Settings → General → Auto TX):
with it on (default), the contact logs when you send RR73; with it off, when
the partner's 73 arrives after your RRR. Each entry records the partner's
call and grid, the signal reports, UTC time, band/dial frequency, mode (FT8),
and — when POTA mode is on — your park reference(s).

The dial frequency comes from CAT when available; with a no-CAT rig profile
it uses the manual dial frequency from Settings → Rigs, so keep that
accurate.

## The Log tab

The tab header shows the total count. Each row shows the partner, date and
time (UTC), band, grid, and RST sent/received. The header also holds the
call sign **search** (magnifier icon) to filter the list, the export and
clear actions, and the **⋮** (Logbook tools) menu with **Backup now** and
**Import ADIF…**.

- **Tap** a row (once selection is active) to add or remove it from the
  selection; **long-press** a row to start selecting.
- With rows selected, the top bar offers **Delete selected** (with
  confirmation) and **Set parks** — apply park reference(s) to the selected
  QSOs, for when you logged before entering the park info. Park lists are
  validated (`US-3315`, comma-separated).
- **Clear log** removes every logged QSO from the device, after a
  confirmation dialog. There is no undo — back up first.

## Getting your log out

Three paths, for different purposes:

### Share export

**Export ADIF** on the Log tab produces a single validated ADIF 3.1 file
(`packrig_export.adi`) and opens the Android share sheet — send it to Drive,
email, a logging app, wherever. When POTA mode is involved, entries carry
`MY_SIG = POTA` and `MY_SIG_INFO = <ref>`; export fails closed (with a clear
error instead of a bad file) if POTA mode is on without a valid park
reference.

### POTA activation export

The **POTA activations** section of the Log tab groups POTA contacts into
**one file per park per UTC day** — exactly the shape pota.app wants. Tap
**Share** on an activation to send that day's file for upload.

### Automatic backup

After every logged QSO, PackRig writes an ADIF backup to two places:
app-private storage and the shared **`Documents/packrig`** folder. The
`Documents/packrig` copy survives app uninstall and is readable by any file
manager. The Log tab's **⋮** (Logbook tools) menu shows the last-backup time
and a **Backup now** entry to force one — for example right before running
Clear log or reinstalling.

## Importing

**Import ADIF…** (in the Log tab's **⋮** Logbook tools menu, or offered
directly when the log is empty) merges an existing ADIF file into the
logbook — use it to restore a backup or carry over history from another
logger. Android's file picker shows all file types because `.adi` has no
registered MIME type; PackRig validates the content on read.

Worked-before coloring in the decode list (see
[Settings reference](settings.md#display)) is driven by the logbook, so an
imported history immediately improves CQ highlighting.
