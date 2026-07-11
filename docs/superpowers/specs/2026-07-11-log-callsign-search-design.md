# Log Call Sign Search — Design

**Date:** 2026-07-11
**Branch:** `log-call-search` (off `unstable`)
**Status:** Approved

## Purpose

Let the operator find QSOs in the Log tab by call sign. As the log grows past a
screenful, answering "have I worked K7ABC?" or "when did I last work this
station?" currently requires scrolling. A live search filter answers it in a
few keystrokes.

## Scope

- Filter the Log tab's contact list by DX call sign. Display-only.
- No changes to export, POTA, delete, or logging behavior.

## Design

### Filtering (ViewModel)

`LogViewModel` owns the query and the filtered list:

- `private val _searchQuery = MutableStateFlow("")` with a public
  `searchQuery: StateFlow<String>` and `fun setSearchQuery(q: String)`.
- `filteredContacts: StateFlow<List<QsoContact>>` derived via
  `combine(contacts, searchQuery)`.
- The match itself is a pure, unit-testable function (top-level or small
  helper object in `app`): case-insensitive substring match of the trimmed
  query against `QsoContact.dxCall`. Blank query returns the full list.
- `LogScreen` renders `filteredContacts` instead of `contacts`. The raw
  `contacts` flow remains the source for export/POTA/clear counts.

Keeping the query in the ViewModel (not `remember`) means it survives tab
switches and the filter logic is testable without UI tests.

### UI (LogScreen)

- **Entry point:** a search `IconButton` (magnifier icon) in the non-selection
  `TopAppBar` actions, before the existing POTA/Share/Delete icons. Enabled
  only when the log is non-empty.
- **Active search:** tapping the icon replaces the title area with a compact
  single-line `TextField`: autofocused, uppercase-as-you-type (matches the
  parks-dialog convention), placeholder "Call sign". A trailing X clears the
  query and collapses back to the normal title.
- **Counts:** while a non-blank query is active, the title context shows
  matches vs total, e.g. `Log (12/347)`.
- **Selection mode:** unchanged and takes over the top bar as today. An
  active query keeps filtering the rows underneath; the search field itself is
  hidden while selection is active and returns when selection ends.
- **Empty results:** when a query matches nothing, show
  `No QSOs match "<query>"` in place of the list (distinct from the
  empty-log onboarding copy, which only shows when the log itself is empty).

### Action semantics (display-only filter)

- **Export ADIF**, **POTA activations**, **Clear log**: always operate on the
  full log, regardless of any active query. The Clear-log dialog already
  states the full count, which keeps this honest.
- **Long-press selection**: operates on visible (filtered) rows. The "Day"
  expansion may select QSOs currently hidden by the filter; acceptable because
  delete/set-parks confirmations state exact counts.

## Error handling

None beyond the empty-results state. The filter cannot fail; invalid
characters simply match nothing.

## Testing

Unit tests for the pure filter function:

- blank / whitespace-only query returns all contacts
- case-insensitive match (`k7` matches `K7ABC`)
- substring match anywhere in the call (`7AB` matches `K7ABC`)
- no matches returns empty list
- query is trimmed before matching

Existing LogScreen flows (export, clear, selection, parks) are untouched;
no changes to `data/` or `core/`.
