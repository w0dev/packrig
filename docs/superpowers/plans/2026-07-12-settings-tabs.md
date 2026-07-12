# Settings Tabs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganize the Settings screen into four Material 3 tabs — General, Rigs, Display, Integrations — and rename "My call"/"My grid" to "Callsign"/"Grid".

**Architecture:** A `SettingsTab` enum defines tab order and titles (unit-testable, no Compose). `SettingsScreen` keeps its Scaffold/TopAppBar and adds a `TabRow` under the app bar; a `rememberSaveable` index selects one of four private tab composables, each a scrollable column of the existing `SettingsSection` blocks moved verbatim. The Integrations tab label carries the existing QRZ warning icon when `state.qrz.warning` is set.

**Tech Stack:** Kotlin, Jetpack Compose Material 3 (`TabRow`/`Tab`), JUnit4 for the JVM unit test.

**Spec:** `docs/superpowers/specs/2026-07-12-settings-tabs-design.md`

## Global Constraints

- No behavior, persistence-key, or ViewModel-wiring changes; sections move verbatim except the two label renames.
- No new dependencies; no bottom-nav changes.
- Do NOT commit `app/src/main/java/net/ft8vc/app/ui/nav/Ft8NavHost.kt` — it has an unrelated uncommitted insets fix that must stay out of these commits (stage files explicitly, never `git add -A`).
- Label renames: "My call" → "Callsign", "My grid" → "Grid" (labels only; placeholders, validators, error copy unchanged).

---

### Task 1: SettingsTab enum (TDD)

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/settings/SettingsTab.kt`
- Test: `app/src/test/java/net/ft8vc/app/settings/SettingsTabTest.kt`

**Interfaces:**
- Produces: `enum class SettingsTab(val title: String)` in package `net.ft8vc.app.settings`, entries in order `GENERAL("General"), RIGS("Rigs"), DISPLAY("Display"), INTEGRATIONS("Integrations")`. Task 2 renders `SettingsTab.entries` in declaration order.

- [ ] **Step 1: Write the failing test**

```kotlin
package net.ft8vc.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTabTest {

    @Test
    fun tabs_areInSpecOrderWithSpecTitles() {
        assertEquals(
            listOf("General", "Rigs", "Display", "Integrations"),
            SettingsTab.entries.map { it.title },
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.SettingsTabTest"`
Expected: compilation FAILURE — `Unresolved reference: SettingsTab`

- [ ] **Step 3: Write minimal implementation**

```kotlin
package net.ft8vc.app.settings

/**
 * Tabs inside the Settings screen. Declaration order is display order.
 */
enum class SettingsTab(val title: String) {
    GENERAL("General"),
    RIGS("Rigs"),
    DISPLAY("Display"),
    INTEGRATIONS("Integrations"),
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.SettingsTabTest"`
Expected: BUILD SUCCESSFUL, 1 test passed

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/SettingsTab.kt \
        app/src/test/java/net/ft8vc/app/settings/SettingsTabTest.kt
git commit -m "feat(app): SettingsTab enum for Settings screen tabs"
```

### Task 2: Tabbed SettingsScreen

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `SettingsTab` from Task 1.
- Produces: `SettingsScreen(vm: OperateViewModel)` public signature unchanged; everything else in the file stays private.

- [ ] **Step 1: Restructure the screen body**

In `SettingsScreen.kt`, replace the single scrollable `Column` inside the Scaffold with a tab row plus a per-tab content switch. The section contents move verbatim into four new private composables; only the two Station labels change.

New imports needed (keep existing ones):

```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
```

New screen body:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: OperateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Snackbar events surface via the app-level host in Ft8vcApp.
    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                SettingsTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(tab.title)
                                if (tab == SettingsTab.INTEGRATIONS && state.qrz.warning) {
                                    Icon(
                                        Icons.Filled.Warning,
                                        contentDescription = "QRZ upload not connected",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        },
                    )
                }
            }
            when (SettingsTab.entries[selectedTab]) {
                SettingsTab.GENERAL -> GeneralSettingsTab(vm, state)
                SettingsTab.RIGS -> RigsSettingsTab(vm, state)
                SettingsTab.DISPLAY -> DisplaySettingsTab(vm, state)
                SettingsTab.INTEGRATIONS -> IntegrationsSettingsTab(vm, state)
            }
        }
    }
}
```

Each tab composable wraps its sections in the same scroll container the old screen used (scroll state is per-tab because each call site has its own `rememberScrollState`):

```kotlin
@Composable
private fun SettingsTabColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        content()
    }
}
```

`GeneralSettingsTab(vm, state)` = `SettingsTabColumn` containing the existing sections, moved verbatim, in order: **Station** (with `label = { Text("Callsign") }` replacing "My call" and `label = { Text("Grid") }` replacing "My grid" — placeholders, `isError`, and supporting text untouched), **Audio**, **TX**, **Auto TX** (including Blocklist and Auto behaviors subheaders), **POTA**, **Clock alignment**, **About**.

`RigsSettingsTab(vm, state)` = `SettingsTabColumn` containing the existing **Radio** section verbatim (which already includes USB diagnostics).

`DisplaySettingsTab(vm, state)` = `SettingsTabColumn` containing the existing **Display** section verbatim (dark mode + decode colors).

`IntegrationsSettingsTab(vm, state)` = `SettingsTabColumn` containing the existing **QRZ Logbook** section verbatim, including its `titleBadge` warning icon.

All four take `(vm: OperateViewModel, state: OperateUiState)`. `GeneralSettingsTab` needs `@OptIn(ExperimentalMaterial3Api::class)` (its sections use `ExposedDropdownMenuBox`). Helper composables (`SettingsSection`, `DevicePicker`, `AnswerPolicyPicker`, `AutoToggleRow`, `MaxUnansweredTxPicker`) and helper functions stay unchanged.

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL, no warnings introduced

- [ ] **Step 3: Run the full app unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt
git commit -m "feat(app): Settings screen tabs — General, Rigs, Display, Integrations"
```

### Task 3: Verification and docs

**Files:**
- Modify: `docs/superpowers/plans/2026-07-12-settings-tabs.md` (check off steps)

**Interfaces:** none.

- [ ] **Step 1: Full build sanity**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verify the unrelated Ft8NavHost.kt change is still uncommitted and untouched**

Run: `git status --short && git diff --stat`
Expected: only `app/src/main/java/net/ft8vc/app/ui/nav/Ft8NavHost.kt` modified (the pre-existing insets fix), nothing else pending.

- [ ] **Step 3: Commit plan checkboxes**

```bash
git add docs/superpowers/plans/2026-07-12-settings-tabs.md
git commit -m "docs: check off settings tabs plan"
```

**Device smoke check (pending, owner):** all four tabs render; every section reachable; Callsign/Grid labels renamed; QRZ warning icon appears on the Integrations tab when uploads are enabled without a working key.
