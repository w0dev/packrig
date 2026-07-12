# Rig Card List Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the rig dropdown on the Rigs tab with a radio-button card list (None / rig cards with Edit and × / dashed + Add Rig) per the owner's mockup, adding rig deselection.

**Architecture:** Deselection plumbing widens `selectRigProfile` to `String?` through repo → ViewModel → section callback (null selection already exists downstream as `RigController.State.NoModel`). A pure `RigCardSummary` object builds card subtitles from preset + overrides. A new `RigCardList` composable replaces `MyRigsBlock`; `RigProfileList.selectionAfterDelete` now falls back to None.

**Tech Stack:** Kotlin, Jetpack Compose Material 3 (`OutlinedCard`, `RadioButton`, dashed border via `drawBehind` + `PathEffect.dashPathEffect`), JUnit4.

**Spec:** `docs/superpowers/specs/2026-07-12-rig-card-list-design.md`

## Global Constraints

- Nothing below the rig list changes: dial frequency, mode row, DATA-U button, CAT status, USB diagnostics, editor dialog, delete confirm dialog.
- No persistence-format changes; 5-rig cap (`RigProfileList.MAX`) unchanged.
- Do NOT commit `app/src/main/java/net/ft8vc/app/ui/nav/Ft8NavHost.kt` (unrelated pre-existing uncommitted insets fix) — stage files explicitly, never `git add -A`.
- Enabled gating for the list stays `!state.catBusy && !state.isTransmitting`.

---

### Task 1: selectionAfterDelete falls back to None (TDD)

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/RigProfileList.kt:33-45`
- Test: `app/src/test/java/net/ft8vc/app/settings/RigProfileListTest.kt:42-48`

**Interfaces:**
- Produces: `RigProfileList.selectionAfterDelete(remaining, deletedId, currentSelection): String?` — returns `currentSelection` only if it survives the delete, else null. `SettingsRepository.deleteRigProfile` already handles a null result (removes the key), so no repo change.

- [ ] **Step 1: Rewrite the test to the new contract**

Replace `selectionFallsBackToFirstRemainingThenNull` in `RigProfileListTest.kt` with:

```kotlin
    @Test
    fun selectionClearsWhenSelectedRigDeleted() {
        val remaining = RigProfileList.delete(four, "1")
        // Deleting the selected rig deselects (None) — owner decision 2026-07-12.
        assertNull(RigProfileList.selectionAfterDelete(remaining, deletedId = "1", currentSelection = "1"))
        // Deleting an unrelated rig keeps the current selection.
        assertEquals("3", RigProfileList.selectionAfterDelete(remaining, deletedId = "9", currentSelection = "3"))
        // Nothing selected stays nothing.
        assertNull(RigProfileList.selectionAfterDelete(remaining, deletedId = "1", currentSelection = null))
        assertNull(RigProfileList.selectionAfterDelete(emptyList(), deletedId = "1", currentSelection = "1"))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.RigProfileListTest"`
Expected: FAIL — first assert gets `"2"` instead of null

- [ ] **Step 3: Implement the new fallback**

Replace `selectionAfterDelete` (and its KDoc) in `RigProfileList.kt`:

```kotlin
    /** New selection after a delete: keep current if it survives, else None (null). */
    fun selectionAfterDelete(
        remaining: List<RigProfile>,
        deletedId: String,
        currentSelection: String?,
    ): String? =
        currentSelection?.takeIf { sel ->
            sel != deletedId && remaining.any { it.id == sel }
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.RigProfileListTest"`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/RigProfileList.kt \
        app/src/test/java/net/ft8vc/app/settings/RigProfileListTest.kt
git commit -m "feat(app): deleting the selected rig now deselects (None)"
```

### Task 2: Nullable selectRigProfile (repo + ViewModel)

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt:150-156`
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt:660-662`

**Interfaces:**
- Produces: `SettingsRepository.selectRigProfile(id: String?)` and `OperateViewModel.selectRigProfile(id: String?)` — null clears the selection. Task 3's UI passes null for the None card; the existing `vm::selectRigProfile` binding in `SettingsScreen` adapts without change (contravariant parameter).

- [ ] **Step 1: Widen the repo setter**

Replace `selectRigProfile` in `SettingsRepository.kt`:

```kotlin
    /** Select a saved rig by id, or clear the selection (None) with null. */
    suspend fun selectRigProfile(id: String?) {
        appContext.settingsDataStore.edit { prefs ->
            if (id == null) {
                prefs.remove(Keys.SELECTED_RIG_PROFILE)
            } else if (RigProfileJson.decode(prefs[Keys.RIG_PROFILES]).any { it.id == id }) {
                prefs[Keys.SELECTED_RIG_PROFILE] = id
            }
        }
    }
```

- [ ] **Step 2: Widen the ViewModel passthrough**

In `OperateViewModel.kt`:

```kotlin
    fun selectRigProfile(id: String?) {
        viewModelScope.launch { settingsRepo.selectRigProfile(id) }
    }
```

- [ ] **Step 3: Compile and run the suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt \
        app/src/main/java/net/ft8vc/app/OperateViewModel.kt
git commit -m "feat(app): selectRigProfile(null) clears the rig selection"
```

### Task 3: RigCardSummary subtitle helper (TDD)

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/settings/RigCardSummary.kt`
- Test: `app/src/test/java/net/ft8vc/app/settings/RigCardSummaryTest.kt`

**Interfaces:**
- Consumes: `RigRegistry.byId`, `RigDescriptor.displayName/protocolFactory/defaultBaud/defaultPtt`, `RigProfile.baud/pttMethod`, `PttMethod`.
- Produces: `RigCardSummary.subtitle(profile: RigProfile): String` — e.g. `"Yaesu FT-891 — CAT @ 38400, auto PTT"`. Task 4 renders it on each rig card.

- [ ] **Step 1: Write the failing test**

```kotlin
package net.ft8vc.app.settings

import net.ft8vc.rig.PttMethod
import net.ft8vc.rig.RigProfile
import net.ft8vc.rig.RigRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class RigCardSummaryTest {

    private fun p(presetId: String, baud: Int? = null, ptt: PttMethod? = null) =
        RigProfile(id = "x", name = "Rig", presetId = presetId, baud = baud, pttMethod = ptt)

    @Test
    fun namedPresetWithDefaults() {
        assertEquals("Yaesu FT-891 — CAT @ 38400, auto PTT", RigCardSummary.subtitle(p("ft891")))
    }

    @Test
    fun overridesWinOverPresetDefaults() {
        assertEquals(
            "Yaesu FT-891 — CAT @ 4800, CAT PTT",
            RigCardSummary.subtitle(p("ft891", baud = 4800, ptt = PttMethod.CAT)),
        )
    }

    @Test
    fun pttOnlyGenericHasNoCat() {
        assertEquals(
            "Serial PTT only (RTS), no CAT (generic) — no CAT, RTS PTT",
            RigCardSummary.subtitle(p(RigRegistry.GENERIC_RTS)),
        )
    }

    @Test
    fun unknownPresetIsDefensive() {
        assertEquals("Unknown preset", RigCardSummary.subtitle(p("not-a-preset")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.RigCardSummaryTest"`
Expected: compilation FAILURE — `Unresolved reference: RigCardSummary`

- [ ] **Step 3: Write the implementation**

```kotlin
package net.ft8vc.app.settings

import net.ft8vc.rig.PttMethod
import net.ft8vc.rig.RigProfile
import net.ft8vc.rig.RigRegistry

/** Pure text rules for rig card subtitles: "<model> — <CAT part>, <PTT part>". */
object RigCardSummary {

    fun subtitle(profile: RigProfile): String {
        val descriptor = RigRegistry.byId(profile.presetId) ?: return "Unknown preset"
        val cat = if (descriptor.protocolFactory != null) {
            "CAT @ ${profile.baud ?: descriptor.defaultBaud}"
        } else {
            "no CAT"
        }
        val ptt = when (profile.pttMethod ?: descriptor.defaultPtt) {
            PttMethod.AUTO -> "auto PTT"
            PttMethod.RTS -> "RTS PTT"
            PttMethod.CAT -> "CAT PTT"
        }
        return "${descriptor.displayName} — $cat, $ptt"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.RigCardSummaryTest"`
Expected: BUILD SUCCESSFUL, 4 tests pass

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/RigCardSummary.kt \
        app/src/test/java/net/ft8vc/app/settings/RigCardSummaryTest.kt
git commit -m "feat(app): RigCardSummary subtitle helper for rig cards"
```

### Task 4: RigCardList composable + rewire RadioSettingsSection

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/settings/RigCardList.kt`
- Modify: `app/src/main/java/net/ft8vc/app/settings/RadioSettingsSection.kt` (replace `MyRigsBlock`, widen `onSelectRigProfile` to `(String?) -> Unit`, drop dropdown imports)

**Interfaces:**
- Consumes: `RigCardSummary.subtitle` (Task 3), `RigProfileList.MAX`, nullable `vm::selectRigProfile` (Task 2).
- Produces: `RigCardList(profiles, selectedId, enabled, onSelect: (String?) -> Unit, onAdd, onEdit, onDelete)` public in `net.ft8vc.app.settings`. `RadioSettingsSection`'s signature changes only in `onSelectRigProfile: (String?) -> Unit`; the `SettingsScreen` call site (`vm::selectRigProfile`) compiles unchanged.

- [ ] **Step 1: Create RigCardList.kt**

```kotlin
package net.ft8vc.app.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.ft8vc.rig.RigProfile

/**
 * Radio-button card list for rig selection (spec
 * 2026-07-12-rig-card-list-design): a None card, one card per saved rig
 * with Edit/delete, and a dashed + Add Rig button. Replaces the dropdown.
 */
@Composable
fun RigCardList(
    profiles: List<RigProfile>,
    selectedId: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
    onAdd: () -> Unit,
    onEdit: (RigProfile) -> Unit,
    onDelete: (RigProfile) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RigCard(
            title = "None",
            subtitle = "No radio connected",
            selected = selectedId == null,
            enabled = enabled,
            onClick = { onSelect(null) },
        )
        profiles.forEach { profile ->
            RigCard(
                title = profile.name,
                subtitle = RigCardSummary.subtitle(profile),
                selected = profile.id == selectedId,
                enabled = enabled,
                onClick = { onSelect(profile.id) },
                trailing = {
                    TextButton(onClick = { onEdit(profile) }, enabled = enabled) { Text("Edit") }
                    IconButton(onClick = { onDelete(profile) }, enabled = enabled) {
                        Icon(Icons.Filled.Close, contentDescription = "Delete ${profile.name}")
                    }
                },
            )
        }
        val atCap = profiles.size >= RigProfileList.MAX
        AddRigButton(onClick = onAdd, enabled = enabled && !atCap)
        if (atCap) {
            Text(
                "Maximum of ${RigProfileList.MAX} rigs.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RigCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            CardDefaults.outlinedCardBorder(enabled)
        },
        colors = if (selected) {
            CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            CardDefaults.outlinedCardColors()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailing?.invoke(this)
        }
    }
}

@Composable
private fun AddRigButton(onClick: () -> Unit, enabled: Boolean) {
    val outline = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f)),
                    ),
                )
            },
    ) {
        Text("+ Add Rig")
    }
}
```

(If `OutlinedCard(onClick = …)` needs `@OptIn(ExperimentalMaterial3Api::class)` on this BOM, add it to `RigCard` — the compiler will say.)

- [ ] **Step 2: Rewire RadioSettingsSection**

In `RadioSettingsSection.kt`:
- Change the parameter to `onSelectRigProfile: (String?) -> Unit`.
- Replace the `MyRigsBlock(...)` call with:

```kotlin
        RigCardList(
            profiles = state.rigProfiles,
            selectedId = state.selectedRigProfileId,
            enabled = !state.catBusy && !state.isTransmitting,
            onSelect = onSelectRigProfile,
            onAdd = { editorTarget = null; editorOpen = true },
            onEdit = { editorTarget = it; editorOpen = true },
            onDelete = { deleteTarget = it },
        )
```

- Delete the whole `MyRigsBlock` composable.
- Remove now-unused imports (`DropdownMenuItem`, `ExposedDropdownMenuBox`, `ExposedDropdownMenuDefaults`, `OutlinedTextField`; keep `ExperimentalMaterial3Api` only if still referenced).
- Everything else (empty-list hint, `catReady` block, diagnostics, dialogs) unchanged.

- [ ] **Step 3: Compile and run the suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no new warnings, all tests pass

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/RigCardList.kt \
        app/src/main/java/net/ft8vc/app/settings/RadioSettingsSection.kt
git commit -m "feat(app): rig card list replaces dropdown (None card, Edit/x, dashed Add Rig)"
```

### Task 5: Verification and docs

**Files:**
- Modify: `docs/superpowers/plans/2026-07-12-rig-card-list.md` (check off steps)

**Interfaces:** none.

- [ ] **Step 1: Full build sanity**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Confirm only intended files changed**

Run: `git status --short`
Expected: only `Ft8NavHost.kt` pending (the pre-existing unrelated insets fix).

- [ ] **Step 3: Commit plan checkboxes**

```bash
git add docs/superpowers/plans/2026-07-12-rig-card-list.md
git commit -m "docs: check off rig card list plan"
```

**Device smoke check (pending, owner):** cards render on the Rigs tab; None deselects (CAT/PTT drop to no-op); Edit/× per card; selected highlight; dashed + Add Rig opens the editor; cap behavior at 5 rigs; deleting the selected rig lands on None.
