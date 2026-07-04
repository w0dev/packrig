# Log tab: delete individual QSO entries

**Date:** 2026-07-03
**Status:** Approved (autonomous session — minimal design following existing selection-mode UX)

## Problem

The Log tab can only delete the entire log ("Clear log" trash can). There is no way
to remove a single bad entry — e.g. the duplicate log entries observed in the
2026-07-03 field report.

## Design

Reuse the existing multi-select mode (long-press to select, tap to toggle, "Day"
to expand to whole UTC days) that already powers "Set parks":

- When one or more rows are selected, the selection top bar gains a **Delete**
  icon button next to the existing Set-parks and Cancel actions.
- Tapping Delete shows a confirmation `AlertDialog`: "Delete N QSOs?" with
  Cancel/Delete buttons. Deletion is permanent (no undo), matching the existing
  Clear-log confirmation pattern.
- Confirming deletes exactly the selected rows and exits selection mode.
- After deletion, the ADIF auto-backup is rescheduled
  (`AdifAutoBackup.scheduleBackupAfterQso`) so the single rolling backup file
  reflects the post-deletion log rather than resurrecting deleted entries.
- The existing whole-log "Clear log" action is unchanged.

## Components

| Layer | Change |
|-------|--------|
| `data/db/QsoDao.kt` | `@Query("DELETE FROM qso_contacts WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<Long>)` |
| `data/Logbook.kt` | `suspend fun delete(ids: List<Long>)` on the interface; RoomLogbook delegates to the DAO |
| `app/LogViewModel.kt` | `fun deleteContacts(ids: List<Long>)` — launches delete + auto-backup on `viewModelScope` |
| `app/ui/log/LogScreen.kt` | Delete icon in selection-mode top bar + confirm dialog; clears `selectedIds` on confirm |

## Error handling

Room delete on missing ids is a no-op — safe if the flow emits between selection
and confirm. No new failure modes; deletion is a simple keyed SQL delete.

## Testing

- Instrumented `QsoDaoTest`: insert three rows, delete two by id, assert the
  third survives (mirrors the existing `bulkUpdateAndClearParkRefs` pattern).
- Compile verification of app + data modules; existing unit test suites must pass.
- Field bar: verified on-device before promotion, per milestone rules.
