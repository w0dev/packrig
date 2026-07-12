# Log Call Sign Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Live, display-only call sign filter on the Log tab: type a fragment, see only matching QSOs, with match/total counts in the top bar.

**Architecture:** A pure filter function (`filterByCall`) does the matching and carries the unit tests. `LogViewModel` owns the query in a `MutableStateFlow` and derives `filteredContacts` via `combine`, so the query survives tab switches. `LogScreen` renders `filteredContacts` and swaps the `TopAppBar` title for a search `TextField` when search is active. Export/POTA/Clear keep reading the unfiltered `contacts` flow.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Kotlin Coroutines StateFlow, JUnit4.

**Spec:** `docs/superpowers/specs/2026-07-11-log-callsign-search-design.md`

## Global Constraints

- Branch: `log-call-search` (off `unstable`); working directory `/Users/bsmirks/git/ft8vc`.
- No changes to `data/` or `core/` modules; no new dependencies.
- Display-only filter: Export ADIF, POTA activations, and Clear log always operate on the full log.
- Matching: case-insensitive substring against `QsoContact.dxCall`; query trimmed; blank query = full list.
- Search input uppercases as the user types (matches parks-dialog convention).
- Kotlin official style, 4-space indent, no wildcard imports.

---

### Task 1: Pure filter function `filterByCall`

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/ui/log/LogSearch.kt`
- Test: `app/src/test/java/net/ft8vc/app/ui/log/LogSearchTest.kt`

**Interfaces:**
- Consumes: `net.ft8vc.data.model.QsoContact` (existing data class; relevant field `dxCall: String`).
- Produces: `fun filterByCall(contacts: List<QsoContact>, query: String): List<QsoContact>` — top-level function in package `net.ft8vc.app.ui.log`. Task 2 calls it from `LogViewModel`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/ft8vc/app/ui/log/LogSearchTest.kt`:

```kotlin
package net.ft8vc.app.ui.log

import net.ft8vc.data.model.QsoContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSearchTest {

    private fun contact(id: Long, dxCall: String): QsoContact =
        QsoContact(
            id = id,
            utcMillis = 1_700_000_000_000L + id,
            myCall = "W0DEV",
            myGrid = "EN34",
            dxCall = dxCall,
            dxGrid = null,
            rstSent = -10,
            rstRcvd = -12,
            freqHz = 14_074_000L,
            band = "20m",
        )

    private val log = listOf(
        contact(1, "K7ABC"),
        contact(2, "JA1XYZ"),
        contact(3, "W7AB"),
    )

    @Test
    fun blankQueryReturnsAllContacts() {
        assertEquals(log, filterByCall(log, ""))
        assertEquals(log, filterByCall(log, "   "))
    }

    @Test
    fun matchIsCaseInsensitive() {
        assertEquals(listOf(log[0]), filterByCall(log, "k7"))
    }

    @Test
    fun matchesSubstringAnywhereInCall() {
        assertEquals(listOf(log[0], log[2]), filterByCall(log, "7AB"))
    }

    @Test
    fun noMatchesReturnsEmptyList() {
        assertTrue(filterByCall(log, "VK9").isEmpty())
    }

    @Test
    fun queryIsTrimmedBeforeMatching() {
        assertEquals(listOf(log[1]), filterByCall(log, "  ja1 "))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.ui.log.LogSearchTest"`
Expected: FAIL — Kotlin compilation error, `unresolved reference: filterByCall` (a compile failure of the test source set is the failing state here).

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/net/ft8vc/app/ui/log/LogSearch.kt`:

```kotlin
package net.ft8vc.app.ui.log

import net.ft8vc.data.model.QsoContact

/**
 * Case-insensitive substring filter of [contacts] by DX call sign.
 * The query is trimmed first; a blank query returns the full list.
 */
fun filterByCall(contacts: List<QsoContact>, query: String): List<QsoContact> {
    val q = query.trim()
    if (q.isEmpty()) return contacts
    return contacts.filter { it.dxCall.contains(q, ignoreCase = true) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.ui.log.LogSearchTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/log/LogSearch.kt app/src/test/java/net/ft8vc/app/ui/log/LogSearchTest.kt
git commit -m "feat(log): pure call sign filter for log search

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Query state and filtered flow in LogViewModel

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/LogViewModel.kt`

**Interfaces:**
- Consumes: `filterByCall(contacts, query)` from Task 1.
- Produces (Task 3 relies on these exact members of `LogViewModel`):
  - `val searchQuery: StateFlow<String>`
  - `fun setSearchQuery(query: String)`
  - `val filteredContacts: StateFlow<List<QsoContact>>`

There is no unit test for this glue (LogViewModel is an `AndroidViewModel` with a hard-wired `RoomLogbook`; the logic lives in the tested `filterByCall`). Verification is compilation plus the full app test suite.

- [ ] **Step 1: Add the query flow and derived filtered flow**

In `app/src/main/java/net/ft8vc/app/LogViewModel.kt`, add these imports to the existing import block (keep alphabetical order):

```kotlin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import net.ft8vc.app.ui.log.filterByCall
```

Then add below the `_activations`/`activations` declarations:

```kotlin
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Contacts narrowed by the call sign search. Display-only: export/clear use [contacts]. */
    val filteredContacts: StateFlow<List<QsoContact>> =
        combine(_contacts, _searchQuery) { list, query -> filterByCall(list, query) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
```

- [ ] **Step 2: Verify it compiles and existing tests pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (note: `DecodeControllerTest.reset_clearsLevelMeter` is a known pre-existing flake — re-run if it's the only failure).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/LogViewModel.kt
git commit -m "feat(log): search query state and filtered contacts flow

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Search UI in LogScreen

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/ui/log/LogScreen.kt`

**Interfaces:**
- Consumes from Task 2: `vm.searchQuery: StateFlow<String>`, `vm.setSearchQuery(String)`, `vm.filteredContacts: StateFlow<List<QsoContact>>`.
- Produces: UI only; nothing downstream.

Behavior being built (from the spec):
- Magnifier icon in the non-selection top bar (leftmost action), enabled only when the log is non-empty.
- Tapping it swaps the title for an autofocused single-line `TextField` (placeholder "Call sign", uppercase-as-you-type). While the field is open, the other actions (POTA/Share/Delete) are hidden and a single X action clears the query and collapses back; they return on collapse.
- While the query is non-blank, a `matches/total` count (e.g. `12/347`) shows beside the field.
- List renders `filteredContacts`; a non-blank query with zero matches shows `No QSOs match "<query>"`; the empty-log onboarding copy still shows only when the log itself is empty.
- Selection mode is unchanged and takes over the top bar; the filter keeps applying to rows underneath.
- Search re-opens with its query if the user switches tabs and returns while a query was active (state initialized from `vm.searchQuery`).

- [ ] **Step 1: Add imports**

In `app/src/main/java/net/ft8vc/app/ui/log/LogScreen.kt`, add to the import block (alphabetical order within the existing groups):

```kotlin
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
```

- [ ] **Step 2: Collect the new state and add search-active state**

In `LogScreen`, right after `val contacts by vm.contacts.collectAsStateWithLifecycle()`, add:

```kotlin
    val filteredContacts by vm.filteredContacts.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
```

After `val selectionActive = selectedIds.isNotEmpty()`, add:

```kotlin
    var searchActive by remember { mutableStateOf(vm.searchQuery.value.isNotBlank()) }
    val searchFocus = remember { FocusRequester() }
```

- [ ] **Step 3: Rework the TopAppBar title**

Replace the existing `title = { ... }` lambda of the `TopAppBar` with:

```kotlin
                title = {
                    if (selectionActive) {
                        Text("${selectedIds.size} selected")
                    } else if (searchActive) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextField(
                                value = searchQuery,
                                onValueChange = { vm.setSearchQuery(it.uppercase()) },
                                placeholder = { Text("Call sign") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(searchFocus),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                            )
                            if (searchQuery.isNotBlank()) {
                                Text(
                                    "${filteredContacts.size}/${contacts.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        LaunchedEffect(Unit) { searchFocus.requestFocus() }
                    } else {
                        Column {
                            Text("Log (${contacts.size})")
                            Text(
                                "Share exports validated ADIF 3.1",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
```

- [ ] **Step 4: Rework the TopAppBar actions**

In the `actions = { ... }` lambda, the `if (selectionActive) { ... }` branch stays exactly as-is. Replace the `else { ... }` branch with:

```kotlin
                    } else if (searchActive) {
                        IconButton(onClick = {
                            vm.setSearchQuery("")
                            searchActive = false
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close search")
                        }
                    } else {
                        IconButton(
                            onClick = { searchActive = true },
                            enabled = contacts.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = "Search call sign")
                        }
                        IconButton(
                            onClick = { showActivations = true },
                            enabled = activations.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.Park, contentDescription = "POTA activation export")
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        shareAdif(context, "ft8vc_export.adi", vm.exportAdif())
                                    } catch (e: AdifExportException) {
                                        snackbarHostState.showSnackbar(e.message ?: "ADIF export failed")
                                    }
                                }
                            },
                            enabled = contacts.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.Share, contentDescription = "Export ADIF")
                        }
                        IconButton(onClick = { showClearConfirm = true }, enabled = contacts.isNotEmpty()) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear log")
                        }
                    }
```

(The POTA/Share/Delete buttons are unchanged from the current code — only the surrounding branching and the new Search button are new.)

- [ ] **Step 5: Render the filtered list and the no-match state**

In the Scaffold body, replace the `if (contacts.isEmpty()) { ... } else { LazyColumn ... }` block with:

```kotlin
            if (contacts.isEmpty()) {
                Text(
                    "Complete a QSO on Operate to populate your log.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (filteredContacts.isEmpty()) {
                Text(
                    "No QSOs match \"${searchQuery.trim()}\"",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(filteredContacts, key = { it.id }) { contact ->
                        LogRow(
                            contact = contact,
                            selected = contact.id in selectedIds,
                            onTap = {
                                if (selectionActive) {
                                    selectedIds =
                                        if (contact.id in selectedIds) selectedIds - contact.id
                                        else selectedIds + contact.id
                                }
                            },
                            onLongPress = { selectedIds = selectedIds + contact.id },
                        )
                    }
                }
            }
```

(Only the `items(...)` source changed to `filteredContacts` plus the new `else if` branch; `LogRow` and its callbacks are unchanged. `filteredContacts.isEmpty()` can only be reached with a non-blank query because a blank query returns the full, non-empty list.)

- [ ] **Step 6: Verify it compiles and existing tests pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/log/LogScreen.kt
git commit -m "feat(log): call sign search UI in Log top bar

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Assemble and manual smoke check

**Files:** none created or modified.

**Interfaces:** n/a — verification only.

- [ ] **Step 1: Build the debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Manual smoke check (device or emulator, needs a populated log)**

On a build with logged QSOs, verify on the Log tab:

1. Magnifier icon appears left of the POTA icon; disabled when the log is empty.
2. Tapping it opens the field with the keyboard up; typing `k7` uppercases to `K7` and narrows the list live; the `matches/total` count appears.
3. A nonsense query shows `No QSOs match "..."`.
4. X clears the query, restores the full list and normal title/actions.
5. With a filter active: long-press selection works on visible rows; Export ADIF still shares the full log; Clear-log dialog still states the full count.
6. Switch to Operate and back with a query active: the search field returns with the query still applied.

Report results honestly; if a check cannot be run (no device attached), say so rather than claiming it passed.
