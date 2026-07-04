# Radio Settings (CAT Baud Rate) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the CAT baud rate user-configurable (4800/9600/19200/38400, default 38400) with live rebind, in a Radio settings section extracted from the monolithic `SettingsScreen.kt`.

**Architecture:** The baud persists in DataStore (`SettingsRepository`), flows through `SettingsBridge.slice` into `OperateViewModel`, which mirrors it onto a new `@Volatile RigController.catBaud` and triggers `rig.rebind()` + `prepareRig()` when the value changes while a Digirig is bound and TX is idle. `DigirigRigBackend` already accepts a `catBaud` constructor parameter — today it is never passed; `bindIfPermitted()` starts passing it. UI-wise, the entire "Rig (FT-891 CAT)" section body moves from `SettingsScreen.kt` into a new `RadioSettingsSection.kt` which gains a baud dropdown.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), DataStore Preferences, JUnit4 + MockK + Turbine for unit tests. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-07-04-radio-settings-design.md`

## Global Constraints

- Branch: `radio-settings` (already created off `readiness`).
- Behavior parity: default baud is `38_400` = `DigirigRigBackend.DEFAULT_CAT_BAUD`; an untouched install must behave byte-identically to v1.0. `DigirigRigBackend` itself must NOT change.
- Baud options are exactly `4800, 9600, 19200, 38400` (FT-891 menu 05-06 choices). Unknown values coerce to 38400.
- All serial close/open runs on `rigSession.catDispatcher`, never on main.
- No rebind while `isTransmitting`.
- The baud picker must be visible/enabled even when `catReady == false` (its purpose is recovering a broken CAT link). Disabled only while `catBusy || isTransmitting`.
- No visual reordering of the Settings tab; section title stays "Rig (FT-891 CAT)".
- Kotlin official style, 4-space indent, no wildcard imports, one top-level public type per file.
- Run unit tests with `./gradlew :app:testDebugUnitTest :rig:testDebugUnitTest` (JVM only — no device needed).

---

### Task 1: Persist catBaud (StationSettings, SettingsRepository, SettingsSlice)

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/StationSettings.kt`
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt`
- Modify: `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt`
- Test: `app/src/test/java/net/ft8vc/app/settings/SettingsRepositoryCatBaudTest.kt` (create)

**Interfaces:**
- Consumes: `net.ft8vc.rig.DigirigRigBackend.DEFAULT_CAT_BAUD` (existing `const val = 38_400`; the app module already depends on `:rig`).
- Produces (later tasks rely on these exact names):
  - `StationSettings.catBaud: Int` (default `DigirigRigBackend.DEFAULT_CAT_BAUD`)
  - `SettingsSlice.catBaud: Int` (same default)
  - `suspend fun SettingsRepository.setCatBaud(baud: Int)`
  - `SettingsRepository.CAT_BAUD_OPTIONS: List<Int>` = `[4800, 9600, 19200, 38400]`
  - `SettingsRepository.coerceCatBaud(baud: Int): Int` (companion, pure)

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/net/ft8vc/app/settings/SettingsRepositoryCatBaudTest.kt` (pattern mirrors `SettingsRepositoryEarlyDecodeTest.kt`):

```kotlin
package net.ft8vc.app.settings

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.ft8vc.app.controllers.SettingsBridge
import net.ft8vc.rig.DigirigRigBackend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * catBaud defaults to the rig-module default (38400, behavior parity with v1.0),
 * coerces unknown values, and propagates through the SettingsBridge slice.
 * Spec: docs/superpowers/specs/2026-07-04-radio-settings-design.md
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryCatBaudTest {

    private lateinit var bridgeScope: CoroutineScope

    @Before fun setUp() {
        bridgeScope = CoroutineScope(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        bridgeScope.cancel()
    }

    private fun makeRepo(initial: StationSettings): Pair<SettingsRepository, MutableStateFlow<StationSettings>> {
        val flow = MutableStateFlow(initial)
        val repo = mockk<SettingsRepository> { every { settings } returns flow }
        return Pair(repo, flow)
    }

    @Test
    fun catBaudDefaultsToRigDefault() {
        assertEquals(DigirigRigBackend.DEFAULT_CAT_BAUD, StationSettings().catBaud)
        assertEquals(38_400, StationSettings().catBaud)
    }

    @Test
    fun coerceCatBaudPassesKnownOptions() {
        SettingsRepository.CAT_BAUD_OPTIONS.forEach { baud ->
            assertEquals(baud, SettingsRepository.coerceCatBaud(baud))
        }
    }

    @Test
    fun coerceCatBaudFallsBackToDefaultForUnknown() {
        assertEquals(38_400, SettingsRepository.coerceCatBaud(0))
        assertEquals(38_400, SettingsRepository.coerceCatBaud(-1))
        assertEquals(38_400, SettingsRepository.coerceCatBaud(115_200))
    }

    @Test
    fun catBaudOptionsMatchFt891Menu() {
        assertEquals(listOf(4800, 9600, 19200, 38400), SettingsRepository.CAT_BAUD_OPTIONS)
    }

    @Test
    fun sliceCarriesCatBaud_default() = runTest {
        val (repo, _) = makeRepo(initial = StationSettings())
        val bridge = SettingsBridge(repo, bridgeScope)

        bridge.slice.test {
            assertEquals(38_400, awaitItem().catBaud)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sliceCarriesCatBaud_roundTrip() = runTest {
        val (repo, _) = makeRepo(initial = StationSettings(catBaud = 4800))
        val bridge = SettingsBridge(repo, bridgeScope)

        bridge.slice.test {
            assertEquals(4800, awaitItem().catBaud)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.SettingsRepositoryCatBaudTest"`
Expected: compilation FAILURE — `catBaud`, `CAT_BAUD_OPTIONS`, `coerceCatBaud` unresolved.

- [ ] **Step 3: Implement the data layer**

In `app/src/main/java/net/ft8vc/app/settings/StationSettings.kt`, add the import and field:

```kotlin
import net.ft8vc.rig.DigirigRigBackend
```

Add to the `StationSettings` data class, after `pttPreference`:

```kotlin
    /** CAT serial baud — must match FT-891 menu 05-06 (CAT RATE). Default = v1.0 behavior. */
    val catBaud: Int = DigirigRigBackend.DEFAULT_CAT_BAUD,
```

In `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt`:

1. Add import `net.ft8vc.rig.DigirigRigBackend`.
2. In the `settings` flow mapping, after the `pttPreference` line:

```kotlin
            catBaud = coerceCatBaud(prefs[Keys.CAT_BAUD] ?: DigirigRigBackend.DEFAULT_CAT_BAUD),
```

3. Add the setter after `setPttPreference`:

```kotlin
    suspend fun setCatBaud(baud: Int) {
        appContext.settingsDataStore.edit { it[Keys.CAT_BAUD] = coerceCatBaud(baud) }
    }
```

4. Add to `Keys`:

```kotlin
        val CAT_BAUD = intPreferencesKey("cat_baud")
```

5. Extend the companion object:

```kotlin
    companion object {
        const val INPUT_GAIN_MIN = 0.1f

        /** FT-891 menu 05-06 (CAT RATE) choices — the only valid CAT bauds. */
        val CAT_BAUD_OPTIONS = listOf(4800, 9600, 19200, 38400)

        /** Unknown values fall back to the rig-module default (38400). */
        fun coerceCatBaud(baud: Int): Int =
            if (baud in CAT_BAUD_OPTIONS) baud else DigirigRigBackend.DEFAULT_CAT_BAUD
    }
```

In `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt`:

1. Add import `net.ft8vc.rig.DigirigRigBackend`.
2. Add to `SettingsSlice`, after `pttPreference`:

```kotlin
    val catBaud: Int = DigirigRigBackend.DEFAULT_CAT_BAUD,
```

3. Add to `StationSettings.toSlice()`, after `pttPreference = pttPreference,`:

```kotlin
        catBaud = catBaud,
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.SettingsRepositoryCatBaudTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/StationSettings.kt \
        app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt \
        app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt \
        app/src/test/java/net/ft8vc/app/settings/SettingsRepositoryCatBaudTest.kt
git commit -m "feat(app): persist CAT baud setting (default 38400, FT-891 menu 05-06 options)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: RigController carries catBaud into the backend

**Files:**
- Modify: `rig/src/main/java/net/ft8vc/rig/RigController.kt`
- Test: `rig/src/test/java/net/ft8vc/rig/RigControllerCatBaudTest.kt` (create)

**Interfaces:**
- Consumes: `DigirigRigBackend.DEFAULT_CAT_BAUD` and the existing (currently unused) constructor parameter `DigirigRigBackend(usbManager, device, catBaud)`.
- Produces: `RigController.catBaud: Int` — `@Volatile var`, default `DigirigRigBackend.DEFAULT_CAT_BAUD`. Task 3 sets it from the ViewModel; every `bindIfPermitted()` (and therefore `rebind()`) reads it.

- [ ] **Step 1: Write the failing test**

Create `rig/src/test/java/net/ft8vc/rig/RigControllerCatBaudTest.kt` (MockK is already a rig test dependency; `RigController`'s constructor only stores `context.applicationContext` — no USB calls until a method runs, so a relaxed mock is safe):

```kotlin
package net.ft8vc.rig

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * catBaud must default to DigirigRigBackend.DEFAULT_CAT_BAUD so an untouched
 * install binds at 38400 exactly as v1.0 did (behavior parity), and must be
 * assignable so a persisted setting applies to the next bind/rebind.
 */
class RigControllerCatBaudTest {

    private fun controller(): RigController {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        return RigController(context)
    }

    @Test
    fun catBaudDefaultsToBackendDefault() {
        assertEquals(DigirigRigBackend.DEFAULT_CAT_BAUD, controller().catBaud)
    }

    @Test
    fun catBaudIsAssignable() {
        val rig = controller()
        rig.catBaud = 4800
        assertEquals(4800, rig.catBaud)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.ft8vc.rig.RigControllerCatBaudTest"`
Expected: compilation FAILURE — `catBaud` unresolved on `RigController`.

- [ ] **Step 3: Implement**

In `rig/src/main/java/net/ft8vc/rig/RigController.kt`, add the field next to the existing `useCatPtt` var (after the `fallback` property declaration is also fine — put it right below `private val fallback = NoOpRigBackend()`):

```kotlin
    /**
     * CAT baud for the next bind/rebind — must match FT-891 menu 05-06 (CAT RATE).
     * Owner (OperateViewModel) mirrors the persisted setting here; changing it does
     * NOT reconfigure a live connection — call [rebind] to apply.
     */
    @Volatile
    var catBaud: Int = DigirigRigBackend.DEFAULT_CAT_BAUD
```

In `bindIfPermitted()`, change the construction line:

```kotlin
        val backend = DigirigRigBackend(usbManager, device, catBaud)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :rig:testDebugUnitTest --tests "net.ft8vc.rig.RigControllerCatBaudTest"`
Expected: PASS (2 tests).

Also run the full rig suite to catch regressions: `./gradlew :rig:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add rig/src/main/java/net/ft8vc/rig/RigController.kt \
        rig/src/test/java/net/ft8vc/rig/RigControllerCatBaudTest.kt
git commit -m "feat(rig): RigController passes configurable catBaud into DigirigRigBackend

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: ViewModel plumbing — mirror, live rebind, setter, UI state

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt`

**Interfaces:**
- Consumes: `SettingsSlice.catBaud`, `SettingsRepository.setCatBaud`, `RigController.catBaud`, `rig.rebind()`, `rig.isDigirigReady`, `rigSession.catDispatcher`, private `prepareRig()`.
- Produces (Task 4 relies on): `OperateUiState.catBaud: Int` and `fun OperateViewModel.setCatBaud(baud: Int)`.

There is no JVM test harness for `OperateViewModel` (an `AndroidViewModel`); existing coverage tests controllers instead. The propagation into the slice is covered by Task 1's tests; this task is verified by compilation plus the full-suite run in Task 5, and the rebind path by the field bar in the spec.

- [ ] **Step 1: Add catBaud to OperateUiState**

In `app/src/main/java/net/ft8vc/app/OperateUiState.kt`, add the import:

```kotlin
import net.ft8vc.rig.DigirigRigBackend
```

Add to the `OperateUiState` data class, next to the existing `pttPreference` field:

```kotlin
    /** CAT serial baud from settings — must match FT-891 menu 05-06 (CAT RATE). */
    val catBaud: Int = DigirigRigBackend.DEFAULT_CAT_BAUD,
```

(If `OperateUiState.kt` has no `pttPreference` field co-located — it is set in the ViewModel combine around `OperateViewModel.kt:229` — place `catBaud` beside `catBusy` at `OperateUiState.kt:122` instead. Any position compiles; keep rig-related fields together.)

- [ ] **Step 2: Map it in the state combine**

In `OperateViewModel.kt`, in the big `OperateUiState(...)` construction (the combine block, near line 229 where `pttPreference = settings.pttPreference,` is set), add:

```kotlin
                catBaud = settings.catBaud,
```

- [ ] **Step 3: Mirror + live rebind in the settings collector**

In `OperateViewModel.kt` `init`, inside the existing `settingsBridge.slice.collect { s -> ... }` block (starts near line 291), add at the end of the lambda body (after `qsoSession.setDefaultTxSlotParity(s.txSlotParity)`):

```kotlin
                // CAT baud mirror + live apply (spec 2026-07-04-radio-settings).
                // Handles both a user change and the startup race where the Digirig
                // bound at the default before DataStore emitted a persisted value.
                if (rig.catBaud != s.catBaud) {
                    rig.catBaud = s.catBaud
                    if (rig.isDigirigReady && !state.value.isTransmitting) {
                        viewModelScope.launch(rigSession.catDispatcher) {
                            rig.rebind()
                            withContext(Dispatchers.Main) { prepareRig() }
                        }
                    }
                }
```

Notes for the implementer:
- `withContext(Dispatchers.Main)` and `viewModelScope.launch(rigSession.catDispatcher)` are the exact shape of the existing `onUsbAttached()` (`OperateViewModel.kt:385-395`); `Dispatchers` is already imported.
- `prepareRig()` is private in the same class; its `Ready` branch re-probes CAT (`configurePttFromCatProbe()`) and refreshes `pttReady`/`txStatus`/`catStatus`, which is exactly the feedback the operator needs after a baud change. If `rebind()` failed, `prepareRig()`'s non-Ready branches surface that too — no new error channel.
- Do NOT rebind when `isTransmitting`; the mirror still updates `rig.catBaud`, so the next natural rebind (USB reattach) applies it.

- [ ] **Step 4: Add the setter**

In `OperateViewModel.kt`, next to `setPttPreference` (near line 491):

```kotlin
    fun setCatBaud(baud: Int) {
        viewModelScope.launch { settingsRepo.setCatBaud(baud) }
    }
```

(Persist only — the slice collector above owns the mirror + rebind, so there is exactly one apply path.)

- [ ] **Step 5: Compile and run the app suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateViewModel.kt \
        app/src/main/java/net/ft8vc/app/OperateUiState.kt
git commit -m "feat(app): mirror CAT baud to RigController and rebind live on change

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Extract RadioSettingsSection.kt with the CAT baud picker

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/settings/RadioSettingsSection.kt`
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `OperateUiState` (`catReady`, `catBusy`, `catStatus`, `rigFreqHz`, `rigMode`, `isTransmitting`, `pttPreference`, `catBaud`), `DialFrequencyDropdownField(rigFreqHz: Long?, enabled: Boolean, onSelect: (Long) -> Unit)`, `SettingsRepository.CAT_BAUD_OPTIONS`, `vm.setCatBaud(Int)` / `vm.setRigFrequency(Long)` / `vm.readRig()` / `vm.setRigDataUsb()` / `vm.setPttPreference(PttPreference)` / `vm.usbDiagnostics(): String`.
- Produces: `@Composable fun RadioSettingsSection(state, usbDiagnostics, onSelectDialFrequency, onReadRig, onSetRigDataUsb, onSetCatBaud, onSetPttPreference)` — public, one top-level type per file convention (the private helper composables move along with it).

- [ ] **Step 1: Create RadioSettingsSection.kt**

This is a MOVE of the existing section body (`SettingsScreen.kt:141-178`), `PttPreferencePicker` (`SettingsScreen.kt:517-555`), and `UsbDiagnosticsExpandable` (`SettingsScreen.kt:557-584`) plus one NEW composable (`CatBaudPicker`). Do not restyle the moved code. Full file content:

```kotlin
package net.ft8vc.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.ft8vc.app.OperateUiState
import net.ft8vc.app.ui.DialFrequencyDropdownField
import net.ft8vc.app.ui.theme.Ft8Green

/**
 * Radio (rig + serial link) settings: dial frequency, mode, DATA-U, CAT baud,
 * PTT preference, and USB diagnostics. Extracted from SettingsScreen so the
 * monolithic settings view stops growing (spec 2026-07-04-radio-settings).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioSettingsSection(
    state: OperateUiState,
    usbDiagnostics: String,
    onSelectDialFrequency: (Long) -> Unit,
    onReadRig: () -> Unit,
    onSetRigDataUsb: () -> Unit,
    onSetCatBaud: (Int) -> Unit,
    onSetPttPreference: (PttPreference) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.catReady) {
            DialFrequencyDropdownField(
                rigFreqHz = state.rigFreqHz,
                enabled = !state.catBusy,
                onSelect = onSelectDialFrequency,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Mode: ${state.rigMode ?: "?"}", fontFamily = FontFamily.Monospace)
                TextButton(onClick = onReadRig, enabled = !state.catBusy) {
                    Text("Read rig")
                }
            }
            Button(
                onClick = onSetRigDataUsb,
                enabled = !state.catBusy && state.rigMode != "DATA-U",
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Ft8Green, contentColor = androidx.compose.ui.graphics.Color.Black),
            ) {
                Text("Set DATA-U (FT8 mode)")
            }
            state.catStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Text(
                "CAT unavailable — connect Digirig serial and grant USB permission.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        CatBaudPicker(
            baud = state.catBaud,
            enabled = !state.catBusy && !state.isTransmitting,
            onSelect = onSetCatBaud,
        )
        PttPreferencePicker(
            preference = state.pttPreference,
            onSelect = onSetPttPreference,
        )
        UsbDiagnosticsExpandable(diagnostics = usbDiagnostics)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatBaudPicker(
    baud: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = "$baud baud",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("CAT baud rate") },
            supportingText = { Text("Must match FT-891 menu 05-06 (CAT RATE)") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SettingsRepository.CAT_BAUD_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text("$option baud") },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PttPreferencePicker(
    preference: PttPreference,
    onSelect: (PttPreference) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = preference.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("PTT preference") },
            supportingText = { Text(preference.description) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PttPreference.entries.forEach { pref ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(pref.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                pref.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(pref)
                    },
                )
            }
        }
    }
}

@Composable
private fun UsbDiagnosticsExpandable(diagnostics: String) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (expanded) "Hide USB diagnostics" else "Show USB diagnostics",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(text = if (expanded) "▴" else "▾", style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) {
            Text(
                text = diagnostics,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

Note: the moved code wraps in a `Column(spacedBy(8.dp))` because `SettingsSection`'s own column no longer sees the individual children — the nested column reproduces the same 8dp internal spacing.

- [ ] **Step 2: Slim SettingsScreen.kt**

In `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt`:

1. Replace the entire body of the `SettingsSection("Rig (FT-891 CAT)") { ... }` block (lines 140-179) with:

```kotlin
            SettingsSection("Rig (FT-891 CAT)") {
                RadioSettingsSection(
                    state = state,
                    usbDiagnostics = vm.usbDiagnostics(),
                    onSelectDialFrequency = vm::setRigFrequency,
                    onReadRig = vm::readRig,
                    onSetRigDataUsb = vm::setRigDataUsb,
                    onSetCatBaud = vm::setCatBaud,
                    onSetPttPreference = vm::setPttPreference,
                )
            }
```

2. Delete the now-moved private composables `PttPreferencePicker` (lines 517-555) and `UsbDiagnosticsExpandable` (lines 557-584) from `SettingsScreen.kt`.
3. Remove imports that became unused in `SettingsScreen.kt` — after the deletion, check each of these and drop the ones the file no longer references: `androidx.compose.material3.Button`, `androidx.compose.material3.ButtonDefaults`, `androidx.compose.ui.text.font.FontFamily`, `net.ft8vc.app.ui.DialFrequencyDropdownField`, `net.ft8vc.app.ui.theme.Ft8Green`. (Note: `Button` is still used by the Logbook and About sections — verify before removing; the Kotlin compiler warns on unused imports, so trust the build output.)

- [ ] **Step 3: Compile and run the app suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no unused-import warnings for `SettingsScreen.kt`, all tests PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/RadioSettingsSection.kt \
        app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt
git commit -m "feat(app): extract Radio settings section with CAT baud picker

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Run every JVM test suite and assemble the APK**

Run: `./gradlew :core:test :rig:testDebugUnitTest :audio:testDebugUnitTest :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, zero failures. (If a listed module has no `testDebugUnitTest` task — e.g. `:core` is a plain Kotlin module using `test` — Gradle will say so; use the task name it suggests.)

- [ ] **Step 2: Verify behavior-parity invariants by inspection**

Confirm and note in the final report:
- `git diff readiness -- rig/src/main/java/net/ft8vc/rig/DigirigRigBackend.kt` is empty (backend untouched).
- Default path: fresh install → `StationSettings().catBaud == 38_400` → first bind identical to v1.0.

- [ ] **Step 3: Report field-verification checklist (do not perform — needs the reference rig)**

The branch is NOT promotable until this passes on the FT-891 + Digirig:
1. With rig menu 05-06 at 38400 and app untouched: CAT reads work (parity).
2. Set rig menu 05-06 to 4800 → CAT reads fail → change app setting to 4800 → CAT recovers without replugging USB, PTT probe reports CAT.
3. Restore both to 38400 → confirm recovery again.
4. Kill and relaunch the app with 4800 persisted → first bind talks CAT at 4800 (startup-race path).

- [ ] **Step 4: Commit any stragglers and finish**

```bash
git status --short   # expect clean
```

Then use superpowers:finishing-a-development-branch.
