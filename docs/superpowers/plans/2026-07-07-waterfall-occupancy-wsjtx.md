# Waterfall Occupancy (WSJT-X Style) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Spectrum-tab waterfall a clean WSJT-X-style occupancy view: remove the broken on-waterfall callsign labels, add a bold red TX goalpost marker, tune palette contrast, and delete the dead `spectrumMarkersEnabled` setting.

**Architecture:** Pure deletion + rendering change confined to the `app` module's Spectrum UI (`WaterfallPanel`, `SpectrumScreen`, `Waterfall`) and the settings plumbing for one dead preference. No decode, audio, or CAT path is touched. The `SpectrumMarkers` object (whose `id / 1000` slot grouping is the root-cause bug) is deleted outright rather than fixed.

**Tech Stack:** Kotlin, Jetpack Compose Canvas, DataStore Preferences, JUnit4 unit tests via Gradle.

**Spec:** `docs/superpowers/specs/2026-07-07-waterfall-occupancy-wsjtx-design.md`

## Global Constraints

- Branch: `multi-rig`; land there (do not touch `main`/`readiness`).
- Behavior parity: RX/TX/CAT/QSO paths byte-equivalent; only Spectrum-tab rendering + dead-setting removal change.
- No new dependencies, no new UI controls (no sliders/pickers).
- Kotlin official style, 4-space indent, no wildcard imports.
- Tap/drag-to-set-TX on the waterfall must keep working exactly as before.
- Unit test command: `./gradlew :app:testDebugUnitTest` (compile check: `./gradlew :app:compileDebugKotlin`).

---

### Task 1: Remove the label overlay, Labels checkbox, and `SpectrumMarkers`

The on-waterfall callsign labels never render because `SpectrumMarkers.forLatestSlot()` groups rows by `id / 1000`, an id encoding that no longer exists (`DecodeRow.id` is a `DecodeRowKey.stableId` 64-bit hash). We remove the feature per the spec, which deletes the bug. This task leaves the TX band as a plain amber band (no clash tint — clash depended on the deleted markers); Task 2 restyles it red.

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/WaterfallPanel.kt`
- Modify: `app/src/main/java/net/ft8vc/app/ui/spectrum/SpectrumScreen.kt`
- Delete: `app/src/main/java/net/ft8vc/app/ui/spectrum/SpectrumMarkers.kt`
- Delete: `app/src/test/java/net/ft8vc/app/ui/spectrum/SpectrumMarkersTest.kt`

**Interfaces:**
- Consumes: existing `WaterfallPanel` call site in `SpectrumScreen` (the only caller — verified by grep).
- Produces: `WaterfallPanel(vm, version, maxFreqHz, txFreqHz, onFreqChange, modifier)` — the `markers: List<SpectrumMarker>` and `showMarkers: Boolean` parameters are gone. Task 2 edits this same composable.

- [ ] **Step 1: Replace `WaterfallPanel.kt` with the marker-free version**

Full new file content (labels loop, `markers`/`showMarkers` params, clash logic, text-measurer, and their imports removed; everything else byte-identical):

```kotlin
package net.ft8vc.app.ui.operate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import net.ft8vc.app.OperateViewModel
import net.ft8vc.app.ui.theme.Ft8Amber

/** FT8 occupied bandwidth: 8-FSK x 6.25 Hz tone spacing. */
private const val FT8_SIGNAL_WIDTH_HZ = 50

@Composable
fun WaterfallPanel(
    vm: OperateViewModel,
    version: Long,
    maxFreqHz: Int,
    txFreqHz: Int,
    onFreqChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun freqForX(x: Float, widthPx: Int): Int =
        if (widthPx <= 0) txFreqHz else (x / widthPx * maxFreqHz).toInt()

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .pointerInput(maxFreqHz) {
                    detectTapGestures { offset -> onFreqChange(freqForX(offset.x, size.width)) }
                }
                .pointerInput(maxFreqHz) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onFreqChange(freqForX(change.position.x, size.width))
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                @Suppress("UNUSED_EXPRESSION") version
                val image = vm.waterfall.snapshot()
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                )
                if (maxFreqHz <= 0) return@Canvas
                val hzToX = { hz: Int -> (hz.toFloat() / maxFreqHz * size.width).coerceIn(0f, size.width) }

                // TX footprint band (txFreq .. txFreq + 50 Hz).
                val bandStart = hzToX(txFreqHz)
                val bandEnd = hzToX(txFreqHz + FT8_SIGNAL_WIDTH_HZ)
                drawRect(
                    color = Ft8Amber.copy(alpha = 0.22f),
                    topLeft = Offset(bandStart, 0f),
                    size = Size((bandEnd - bandStart).coerceAtLeast(1f), size.height),
                )

                // Solid leading edge at the exact TX tone (preserves v1.0 precision marker).
                drawLine(
                    color = Ft8Amber,
                    start = Offset(bandStart, 0f),
                    end = Offset(bandStart, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
        FrequencyAxis(maxFreqHz = maxFreqHz)
    }
}

@Composable
private fun FrequencyAxis(maxFreqHz: Int) {
    val ticks = listOf(0, maxFreqHz / 4, maxFreqHz / 2, maxFreqHz * 3 / 4, maxFreqHz)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        ticks.forEachIndexed { index, hz ->
            Text(
                text = if (index == ticks.lastIndex) "$hz Hz" else "$hz",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 2: Replace `SpectrumScreen.kt` with the checkbox-free version**

Full new file content (markers computation, `remember`, Labels text + checkbox, and their imports removed):

```kotlin
package net.ft8vc.app.ui.spectrum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.ft8vc.app.OperateViewModel
import net.ft8vc.app.ui.DialFrequencySelector
import net.ft8vc.app.ui.operate.TxToneIndicator
import net.ft8vc.app.ui.operate.WaterfallPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpectrumScreen(vm: OperateViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spectrum") },
                actions = {
                    TxToneIndicator(
                        txFreqHz = state.txFreqHz,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialFrequencySelector(
                    rigFreqHz = state.rigFreqHz,
                    enabled = state.catReady && !state.catBusy,
                    onSelect = vm::setRigFrequency,
                )
                Text(
                    "TX ${state.txFreqHz} Hz",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            WaterfallPanel(
                vm = vm,
                version = state.waterfallVersion,
                maxFreqHz = vm.maxAudioFreqHz,
                txFreqHz = state.txFreqHz,
                onFreqChange = vm::setTxFreqHz,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}
```

- [ ] **Step 3: Delete the marker object and its test**

```bash
git rm app/src/main/java/net/ft8vc/app/ui/spectrum/SpectrumMarkers.kt \
       app/src/test/java/net/ft8vc/app/ui/spectrum/SpectrumMarkersTest.kt
```

- [ ] **Step 4: Verify nothing references the deleted symbols, then compile**

```bash
grep -rn "SpectrumMarker" app/src --include="*.kt" | grep -v worktrees
```
Expected: no output.

```bash
./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the app unit suite**

```bash
./gradlew :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, no failures (the deleted `SpectrumMarkersTest` no longer runs; `SettingsRepositorySpectrumMarkersTest` still exists and still passes — it goes away in Task 3).

- [ ] **Step 6: Commit**

```bash
git add -A app/src
git commit -m "fix(spectrum): remove broken on-waterfall callsign labels

SpectrumMarkers grouped decodes by an id/1000 slot encoding that no
longer exists (ids are DecodeRowKey hashes), so the marker set collapsed
to ~1 arbitrary decode and labels never rendered. Per the 2026-07-07
waterfall-occupancy design, callsigns stay in the decode list; the
overlay, Labels checkbox, and clash tinting are removed.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Bold red TX goalpost marker (WSJT-X style)

Replace the subtle amber band with a prominent red marker: solid line at the exact TX tone, light red fill over the 50 Hz footprint, and red goalpost caps at top and bottom edges of the span.

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/WaterfallPanel.kt`

**Interfaces:**
- Consumes: `WaterfallPanel(vm, version, maxFreqHz, txFreqHz, onFreqChange, modifier)` from Task 1 (signature unchanged by this task).
- Produces: same signature; rendering only.

- [ ] **Step 1: Swap the amber band for the red goalpost**

In `WaterfallPanel.kt`, change the import:

```kotlin
// old
import net.ft8vc.app.ui.theme.Ft8Amber
// new
import net.ft8vc.app.ui.theme.Ft8Red
```

Replace the band + leading-edge block inside the `Canvas` (everything after the `hzToX` declaration) with:

```kotlin
                // TX marker, WSJT-X style: light red fill over the 50 Hz FT8
                // footprint, goalpost caps at top/bottom, and a solid line at
                // the exact TX tone so the operator can read their footprint
                // directly against the band traces.
                val bandStart = hzToX(txFreqHz)
                val bandEnd = hzToX(txFreqHz + FT8_SIGNAL_WIDTH_HZ)
                val bandWidth = (bandEnd - bandStart).coerceAtLeast(1f)
                drawRect(
                    color = Ft8Red.copy(alpha = 0.18f),
                    topLeft = Offset(bandStart, 0f),
                    size = Size(bandWidth, size.height),
                )
                val capStroke = 3.dp.toPx()
                drawLine(
                    color = Ft8Red,
                    start = Offset(bandStart, capStroke / 2f),
                    end = Offset(bandEnd, capStroke / 2f),
                    strokeWidth = capStroke,
                )
                drawLine(
                    color = Ft8Red,
                    start = Offset(bandStart, size.height - capStroke / 2f),
                    end = Offset(bandEnd, size.height - capStroke / 2f),
                    strokeWidth = capStroke,
                )
                // Solid leading edge at the exact TX tone.
                drawLine(
                    color = Ft8Red,
                    start = Offset(bandStart, 0f),
                    end = Offset(bandStart, size.height),
                    strokeWidth = 2.5.dp.toPx(),
                )
```

(`Ft8Red = Color(0xFFE63946)` already exists in `app/src/main/java/net/ft8vc/app/ui/theme/Color.kt:9`. `TxToneIndicator`'s amber chip is intentionally untouched.)

- [ ] **Step 2: Compile**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`. If `Ft8Amber` is now an unused import warning, confirm the import was removed in Step 1.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/operate/WaterfallPanel.kt
git commit -m "feat(spectrum): bold red WSJT-X-style TX goalpost marker

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Remove the dead `spectrumMarkersEnabled` setting end-to-end

After Task 1 no UI reads this preference. Delete every layer of its plumbing. This is a pure-deletion task; the existing round-trip test is deleted with the feature, and the full unit suite is the regression net.

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt`
- Modify: `app/src/main/java/net/ft8vc/app/settings/StationSettings.kt`
- Modify: `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt`
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt`
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`
- Delete: `app/src/test/java/net/ft8vc/app/settings/SettingsRepositorySpectrumMarkersTest.kt`

**Interfaces:**
- Consumes: nothing from other tasks (independent of Tasks 2/4, but must run after Task 1 removed the UI references `state.spectrumMarkersEnabled` / `vm::setSpectrumMarkersEnabled`).
- Produces: `StationSettings`, `SettingsSlice`, and `OperateUiState` without the `spectrumMarkersEnabled` field; `SettingsRepository` and `OperateViewModel` without `setSpectrumMarkersEnabled`.

- [ ] **Step 1: `SettingsRepository.kt` — remove key, read, and setter**

Remove these three snippets (currently at lines 47, 141–143, 246):

```kotlin
            spectrumMarkersEnabled = prefs[Keys.SPECTRUM_MARKERS_ENABLED] ?: true,
```

```kotlin
    suspend fun setSpectrumMarkersEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.SPECTRUM_MARKERS_ENABLED] = enabled }
    }
```

```kotlin
        val SPECTRUM_MARKERS_ENABLED = booleanPreferencesKey("spectrum_markers_enabled")
```

(The stale `spectrum_markers_enabled` boolean simply lingers unused in existing installs' DataStore files — harmless, standard for retired preferences.)

- [ ] **Step 2: `StationSettings.kt` — remove the field (lines 35–36)**

```kotlin
    /** Show CQ decode labels on the Spectrum waterfall. Default ON. */
    val spectrumMarkersEnabled: Boolean = true,
```

- [ ] **Step 3: `SettingsBridge.kt` — remove both references (lines 68, 102)**

```kotlin
        spectrumMarkersEnabled = spectrumMarkersEnabled,
```

```kotlin
    val spectrumMarkersEnabled: Boolean = true,
```

- [ ] **Step 4: `OperateUiState.kt` — remove the field (lines 73–74)**

```kotlin
    /** Show CQ decode labels on the Spectrum waterfall. */
    val spectrumMarkersEnabled: Boolean = true,
```

- [ ] **Step 5: `OperateViewModel.kt` — remove the mapping (line 217) and the setter (lines 572–574)**

```kotlin
                spectrumMarkersEnabled = settings.spectrumMarkersEnabled,
```

```kotlin
    fun setSpectrumMarkersEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setSpectrumMarkersEnabled(enabled) }
    }
```

- [ ] **Step 6: Delete the setting's test**

```bash
git rm app/src/test/java/net/ft8vc/app/settings/SettingsRepositorySpectrumMarkersTest.kt
```

- [ ] **Step 7: Verify zero references remain, then run the unit suite**

```bash
grep -rn "spectrumMarkersEnabled\|SPECTRUM_MARKERS\|setSpectrumMarkersEnabled" app/src --include="*.kt" | grep -v worktrees
```
Expected: no output.

```bash
./gradlew :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, no failures.

- [ ] **Step 8: Commit**

```bash
git add -A app/src
git commit -m "refactor(settings): remove dead spectrumMarkersEnabled preference

The Labels overlay it gated was removed with the waterfall-occupancy
redesign; delete the DataStore key, repo setter, slice/state fields,
VM mapping, and round-trip test.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Palette contrast tuning + stale id-comment fix

In the field screenshot the mid-band saturates to yellow/pink and individual signals disappear. Rebias the ramp so the bottom ~55% of the normalized range stays black→blue (noise) and only the top half is bright (signals), raise the floor offset, and drop the washed-out white endpoint. These are starting values; final tune happens in Task 5's on-device check.

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/ui/Waterfall.kt`
- Modify: `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt:62`

**Interfaces:**
- Consumes: nothing from other tasks (independent).
- Produces: same `Waterfall` public API (`floorOffsetDb`, `rangeDb`, `addColumn`, `snapshot`, `clear` unchanged in shape); only defaults and the private `colorFor` change.

- [ ] **Step 1: Raise the default floor offset in `Waterfall.kt`**

```kotlin
    /** dB above the estimated noise floor where the color ramp starts (higher = darker). */
    @Volatile
    var floorOffsetDb: Float = 4f
```
becomes
```kotlin
    /** dB above the estimated noise floor where the color ramp starts (higher = darker). */
    @Volatile
    var floorOffsetDb: Float = 8f
```

`rangeDb` stays `45f` (a full FT8 band spans wide SNRs; narrowing it re-saturates strong signals).

- [ ] **Step 2: Rebias `colorFor` toward a dark noise floor**

Replace the existing `colorFor` with:

```kotlin
    /**
     * Waterfall ramp biased dark: the bottom ~55% of the range stays
     * black -> blue (noise), the top half runs cyan -> green -> yellow -> red
     * (signals). WSJT-X-like contrast so individual FT8 traces separate.
     */
    private fun colorFor(t: Float): Int {
        val r: Int
        val g: Int
        val b: Int
        when {
            t < 0.35f -> { val u = t / 0.35f; r = 0; g = 0; b = (170 * u).toInt() }
            t < 0.55f -> { val u = (t - 0.35f) / 0.2f; r = 0; g = (200 * u).toInt(); b = (170 + 85 * u).toInt() }
            t < 0.72f -> { val u = (t - 0.55f) / 0.17f; r = 0; g = (200 + 55 * u).toInt(); b = (255 * (1 - u)).toInt() }
            t < 0.88f -> { val u = (t - 0.72f) / 0.16f; r = (255 * u).toInt(); g = 255; b = 0 }
            else -> { val u = (t - 0.88f) / 0.12f; r = 255; g = (255 - 155 * u).toInt(); b = 0 }
        }
        return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }
```

Also update the class KDoc line above `colorFor`'s old comment if it still says `-> white` anywhere (the old ramp comment `black -> blue -> cyan -> green -> yellow -> red -> white` is replaced by the new KDoc above).

- [ ] **Step 3: Fix the stale id comment in `DecodeController.kt` (line 62)**

```kotlin
 * Stable DecodeRow ids (`slotStart * 1000 + indexInSlot`) and an
```
becomes
```kotlin
 * Stable DecodeRow ids ([DecodeRowKey.stableId] hashes) and an
```

- [ ] **Step 4: Compile and run audio/app unit suites**

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, no failures (`Waterfall` has no unit tests — it needs `android.graphics.Bitmap`; verification is visual in Task 5).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/Waterfall.kt app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt
git commit -m "feat(spectrum): darker noise floor + higher-contrast waterfall palette

Bias the color ramp so ~55% of the normalized range stays black->blue
and raise floorOffsetDb 4->8 dB: on busy bands the old ramp saturated
the whole mid-band yellow/pink and individual FT8 traces vanished.
Also fix the stale slotStart*1000 id comment in DecodeController.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Full verification + on-device visual check

**Files:**
- None (verification only).

**Interfaces:**
- Consumes: all previous tasks complete on `multi-rig`.
- Produces: verified build + operator-confirmed visuals; palette/marker constants adjusted here if the device check demands it.

- [ ] **Step 1: Full clean-ish verification build**

```bash
./gradlew :app:testDebugUnitTest :core:test :audio:testDebugUnitTest :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`, zero test failures.

- [ ] **Step 2: Install on the field phone (wireless adb per project memory)**

```bash
adb devices          # confirm the phone is connected
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: `Success`. If the field phone runs a different build channel, confirm with the operator before installing (memory: always confirm build channel before attributing behavior).

- [ ] **Step 3: Operator visual checklist on live band audio (FT-891 + Digirig, 14.074 MHz)**

Ask the operator to confirm, with a screenshot:
1. Individual FT8 traces are distinguishable from noise (occupancy as clear as WSJT-X) — the mid-band no longer saturates to a yellow/pink block.
2. The red TX marker (line + 50 Hz goalpost) is obvious against busy traces.
3. Tap and drag on the waterfall still move the TX tone; `TX n Hz` chip updates.
4. No "Labels" checkbox remains on the Spectrum tab.

If (1) or (2) fail: adjust `floorOffsetDb` (range 6–12), the `colorFor` breakpoints, or marker alpha/stroke in place, rebuild, re-check, and amend the Task 4/Task 2 values in a follow-up commit (`fix(spectrum): tune waterfall palette from device check`).

- [ ] **Step 4: Wrap up**

Run the superpowers:verification-before-completion skill, then superpowers:finishing-a-development-branch (work stays on `multi-rig`; field verification on the FT-891 remains the promotion bar per the milestone).
