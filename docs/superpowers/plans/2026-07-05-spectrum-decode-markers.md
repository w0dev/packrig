# Spectrum Decode Markers + Honest TX Picker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Label CQ decodes on the Spectrum waterfall at their audio frequency, replace the 1px TX marker with a ~50 Hz collision-aware band, and add a live Hz readout — all toggle-gated and persisted.

**Architecture:** All work lives in the `app` UI layer. A pure `SpectrumMarkers` helper derives the current-slot marker set from `OperateUiState.decodes` (which already carries `freqHz`/`isCq`/`message`), so there is **no `core`/`audio`/decoder change**. `WaterfallPanel` gains overlay drawing; `SpectrumScreen` wires derivation, a persisted "Labels" toggle, and a live readout.

**Tech Stack:** Kotlin, Jetpack Compose (Canvas + `rememberTextMeasurer`), DataStore preferences, JUnit4 + MockK + Turbine for JVM unit tests.

## Global Constraints

- Tech stack: Kotlin + Jetpack Compose + Coroutines. No new top-level dependencies.
- Platform: Android `minSdk = 28`; JVM 17.
- Behavior parity: RX/TX/CAT/QSO behavior must be byte-equivalent to v1.0 on the reference rig. This is a Spectrum-screen-only UX delta — the Operate main screen, decode list, and QSO machine are untouched.
- Naming: PascalCase files, one top-level public type per file; camelCase functions; `isX`/`hasX` predicates; no wildcard imports; 4-space indent; no semicolons.
- Default: the "Labels" toggle defaults ON.
- `CLASH_WINDOW_HZ` starts at **30** (center-to-center), a named constant for field tuning.
- Collision tint stays active even when the Labels toggle is OFF (it is a TX-safety aid, not a labeling nicety) — per spec Open Question #1 default.

---

### Task 1: `SpectrumMarkers` pure derivation + clash helper

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/ui/spectrum/SpectrumMarkers.kt`
- Test: `app/src/test/java/net/ft8vc/app/ui/spectrum/SpectrumMarkersTest.kt`

**Interfaces:**
- Consumes: `net.ft8vc.app.DecodeRow` (fields `id: Long`, `freqHz: Int`, `isCq: Boolean`, `message: String`, `source: net.ft8vc.core.DecodeRowSource`); `net.ft8vc.core.QsoMessages.parse`; `net.ft8vc.core.QsoRx`.
- Produces:
  - `data class SpectrumMarker(val freqHz: Int, val callsign: String, val isCq: Boolean)`
  - `object SpectrumMarkers` with:
    - `const val CLASH_WINDOW_HZ: Int = 30`
    - `fun forLatestSlot(decodes: List<DecodeRow>): List<SpectrumMarker>`
    - `fun txClashes(txFreqHz: Int, markers: List<SpectrumMarker>, windowHz: Int = CLASH_WINDOW_HZ): Boolean`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/ft8vc/app/ui/spectrum/SpectrumMarkersTest.kt`:

```kotlin
package net.ft8vc.app.ui.spectrum

import net.ft8vc.app.DecodeRow
import net.ft8vc.core.DecodeRowSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumMarkersTest {

    /** id = slotStart * 1000 + index, matching DecodeRowKey.stableId. */
    private fun row(
        slotStart: Long,
        index: Int,
        freqHz: Int,
        message: String,
        isCq: Boolean,
        source: DecodeRowSource = DecodeRowSource.Rx,
    ) = DecodeRow(
        id = slotStart * 1000 + index,
        timeUtc = "000000",
        snr = 0,
        dtSeconds = 0f,
        freqHz = freqHz,
        message = message,
        isCq = isCq,
        source = source,
    )

    @Test
    fun emptyDecodesYieldNoMarkers() {
        assertTrue(SpectrumMarkers.forLatestSlot(emptyList()).isEmpty())
    }

    @Test
    fun onlyLatestSlotContributesMarkers() {
        val decodes = listOf(
            row(200L, 0, 1500, "CQ K1ABC FN42", isCq = true),   // latest slot
            row(100L, 0, 1200, "CQ W2XYZ EM12", isCq = true),   // older slot
        )
        val markers = SpectrumMarkers.forLatestSlot(decodes)
        assertEquals(1, markers.size)
        assertEquals(1500, markers[0].freqHz)
        assertEquals("K1ABC", markers[0].callsign)
        assertTrue(markers[0].isCq)
    }

    @Test
    fun nonCqSenderParsedForCollisionMarker() {
        val markers = SpectrumMarkers.forLatestSlot(
            listOf(row(200L, 0, 1800, "K1ABC W2XYZ -12", isCq = false)),
        )
        assertEquals(1, markers.size)
        assertEquals("W2XYZ", markers[0].callsign)
        assertFalse(markers[0].isCq)
    }

    @Test
    fun syntheticTxRowsExcluded() {
        val markers = SpectrumMarkers.forLatestSlot(
            listOf(row(200L, 0, 1500, "CQ K1ABC FN42", isCq = true, source = DecodeRowSource.Tx)),
        )
        assertTrue(markers.isEmpty())
    }

    @Test
    fun txClashesWhenWithinWindow() {
        val markers = listOf(SpectrumMarker(1500, "K1ABC", true))
        assertTrue(SpectrumMarkers.txClashes(1520, markers))   // 20 Hz apart
        assertTrue(SpectrumMarkers.txClashes(1500, markers))   // exact
    }

    @Test
    fun noClashJustOutsideWindow() {
        val markers = listOf(SpectrumMarker(1500, "K1ABC", true))
        assertFalse(SpectrumMarkers.txClashes(1531, markers))  // 31 Hz apart, window 30
    }

    @Test
    fun noClashWithNoMarkers() {
        assertFalse(SpectrumMarkers.txClashes(1500, emptyList()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.ui.spectrum.SpectrumMarkersTest"`
Expected: FAIL — `SpectrumMarkers` / `SpectrumMarker` unresolved (compilation error).

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/net/ft8vc/app/ui/spectrum/SpectrumMarkers.kt`:

```kotlin
package net.ft8vc.app.ui.spectrum

import net.ft8vc.app.DecodeRow
import net.ft8vc.core.DecodeRowSource
import net.ft8vc.core.QsoMessages
import net.ft8vc.core.QsoRx
import kotlin.math.abs

/** One decoded signal's position on the waterfall. [callsign] is "" when unparsable. */
data class SpectrumMarker(val freqHz: Int, val callsign: String, val isCq: Boolean)

/**
 * Derives waterfall markers from the decode rows the Spectrum screen already
 * receives in [net.ft8vc.app.OperateUiState.decodes]. Pure — no Android deps.
 *
 * Only the most recent slot's RX decodes contribute, so markers refresh in place
 * every 15 s and nothing accumulates. CQ markers drive on-screen labels; all
 * markers (CQ and directed) contribute occupied frequencies for [txClashes].
 */
object SpectrumMarkers {
    /** Center-to-center Hz within which the TX tone is treated as clashing. Field-tunable. */
    const val CLASH_WINDOW_HZ: Int = 30

    fun forLatestSlot(decodes: List<DecodeRow>): List<SpectrumMarker> {
        val rx = decodes.filter { it.source == DecodeRowSource.Rx }
        if (rx.isEmpty()) return emptyList()
        val latestSlot = rx.maxOf { it.id / 1000 }
        return rx
            .filter { it.id / 1000 == latestSlot }
            .map { row ->
                SpectrumMarker(
                    freqHz = row.freqHz,
                    callsign = callsignOf(row.message),
                    isCq = row.isCq,
                )
            }
    }

    fun txClashes(
        txFreqHz: Int,
        markers: List<SpectrumMarker>,
        windowHz: Int = CLASH_WINDOW_HZ,
    ): Boolean = markers.any { abs(it.freqHz - txFreqHz) <= windowHz }

    private fun callsignOf(message: String): String =
        when (val rx = QsoMessages.parse(message)) {
            is QsoRx.Cq -> rx.call
            is QsoRx.GridReply -> rx.sender
            is QsoRx.Report -> rx.sender
            is QsoRx.RReport -> rx.sender
            is QsoRx.Roger -> rx.sender
            is QsoRx.RogerBye -> rx.sender
            is QsoRx.Bye -> rx.sender
            QsoRx.Other -> ""
        }
}
```

**Note:** `DecodeRowSource` is a sealed interface with `Rx` and `Tx` (confirmed). The filter keeps only `== DecodeRowSource.Rx`; the test's synthetic row uses `DecodeRowSource.Tx`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.ui.spectrum.SpectrumMarkersTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/spectrum/SpectrumMarkers.kt \
        app/src/test/java/net/ft8vc/app/ui/spectrum/SpectrumMarkersTest.kt
git commit -m "feat(spectrum): pure SpectrumMarkers derivation + TX clash helper"
```

---

### Task 2: Persist the "Labels" toggle end-to-end

Threads a new `spectrumMarkersEnabled` boolean through the full settings chain
(`StationSettings` → `SettingsRepository` → `SettingsBridge`/`SettingsSlice` →
`OperateViewModel` combine → `OperateUiState`) plus a VM setter, mirroring the
existing `earlyDecodeEnabled` pref.

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/StationSettings.kt`
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt`
- Modify: `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt`
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt`
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`
- Test: `app/src/test/java/net/ft8vc/app/settings/SettingsRepositorySpectrumMarkersTest.kt`

**Interfaces:**
- Consumes: Task 1 nothing; existing `SettingsBridge`, `SettingsSlice`, `SettingsRepository`.
- Produces:
  - `StationSettings.spectrumMarkersEnabled: Boolean = true`
  - `SettingsSlice.spectrumMarkersEnabled: Boolean = true`
  - `SettingsRepository.setSpectrumMarkersEnabled(enabled: Boolean)`
  - `OperateUiState.spectrumMarkersEnabled: Boolean = true`
  - `OperateViewModel.setSpectrumMarkersEnabled(enabled: Boolean)`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/ft8vc/app/settings/SettingsRepositorySpectrumMarkersTest.kt`:

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
import net.ft8vc.app.controllers.SettingsBridge
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Mirrors SettingsRepositoryEarlyDecodeTest: default ON + slice round-trip. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositorySpectrumMarkersTest {

    private lateinit var bridgeScope: CoroutineScope

    @Before fun setUp() {
        bridgeScope = CoroutineScope(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        bridgeScope.cancel()
    }

    private fun makeRepo(initial: StationSettings): SettingsRepository {
        val flow = MutableStateFlow(initial)
        return mockk<SettingsRepository> { every { settings } returns flow }
    }

    @Test
    fun spectrumMarkersEnabledDefaultsTrue() {
        assertTrue(StationSettings().spectrumMarkersEnabled)
    }

    @Test
    fun sliceCarriesSpectrumMarkersEnabled_default() = kotlinx.coroutines.test.runTest {
        val bridge = SettingsBridge(makeRepo(StationSettings()), bridgeScope)
        bridge.slice.test {
            assertTrue(awaitItem().spectrumMarkersEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sliceCarriesSpectrumMarkersEnabled_roundTrip() = kotlinx.coroutines.test.runTest {
        val bridge = SettingsBridge(makeRepo(StationSettings(spectrumMarkersEnabled = false)), bridgeScope)
        bridge.slice.test {
            assertFalse(awaitItem().spectrumMarkersEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.SettingsRepositorySpectrumMarkersTest"`
Expected: FAIL — `spectrumMarkersEnabled` unresolved on `StationSettings`/`SettingsSlice`.

- [ ] **Step 3a: Add field to `StationSettings`**

In `app/src/main/java/net/ft8vc/app/settings/StationSettings.kt`, add after the `earlyDecodeEnabled` line (line 30):

```kotlin
    val earlyDecodeEnabled: Boolean = true,
    /** Show CQ decode labels on the Spectrum waterfall. Default ON. */
    val spectrumMarkersEnabled: Boolean = true,
```

- [ ] **Step 3b: Read + key + setter in `SettingsRepository`**

In the `StationSettings(...)` mapping, add after the `earlyDecodeEnabled = ...` line (line 44):

```kotlin
            earlyDecodeEnabled = prefs[Keys.EARLY_DECODE_ENABLED] ?: true,
            spectrumMarkersEnabled = prefs[Keys.SPECTRUM_MARKERS_ENABLED] ?: true,
```

Add a setter next to `setEarlyDecodeEnabled` (near line 125):

```kotlin
    suspend fun setSpectrumMarkersEnabled(enabled: Boolean) {
        appContext.settingsDataStore.edit { it[Keys.SPECTRUM_MARKERS_ENABLED] = enabled }
    }
```

Add the key in the `Keys` object next to `EARLY_DECODE_ENABLED` (near line 226):

```kotlin
        val SPECTRUM_MARKERS_ENABLED = booleanPreferencesKey("spectrum_markers_enabled")
```

- [ ] **Step 3c: Thread through `SettingsBridge`/`SettingsSlice`**

In `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt`:

In `toSlice()`, after `earlyDecodeEnabled = earlyDecodeEnabled,`:

```kotlin
        earlyDecodeEnabled = earlyDecodeEnabled,
        spectrumMarkersEnabled = spectrumMarkersEnabled,
```

In the `SettingsSlice` data class, after `val earlyDecodeEnabled: Boolean = true,`:

```kotlin
    val earlyDecodeEnabled: Boolean = true,
    val spectrumMarkersEnabled: Boolean = true,
```

- [ ] **Step 3d: Add field to `OperateUiState`**

In `app/src/main/java/net/ft8vc/app/OperateUiState.kt`, in the "Display" section after `decodeColors` (line 72):

```kotlin
    /** Show CQ decode labels on the Spectrum waterfall. */
    val spectrumMarkersEnabled: Boolean = true,
```

- [ ] **Step 3e: Wire into VM combine + add setter**

In `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`, in the `OperateUiState(...)` builder after `cq73OnlyFilter = settings.cq73OnlyFilter,` (line 190):

```kotlin
                cq73OnlyFilter = settings.cq73OnlyFilter,
                spectrumMarkersEnabled = settings.spectrumMarkersEnabled,
```

Add a setter next to `setCq73OnlyFilter` (near line 476):

```kotlin
    fun setSpectrumMarkersEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setSpectrumMarkersEnabled(enabled) }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.SettingsRepositorySpectrumMarkersTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/StationSettings.kt \
        app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt \
        app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt \
        app/src/main/java/net/ft8vc/app/OperateUiState.kt \
        app/src/main/java/net/ft8vc/app/OperateViewModel.kt \
        app/src/test/java/net/ft8vc/app/settings/SettingsRepositorySpectrumMarkersTest.kt
git commit -m "feat(settings): persist spectrumMarkersEnabled toggle (default on)"
```

---

### Task 3: Render markers + collision-aware TX band in `WaterfallPanel`

Replaces the 1px amber TX line with a ~50 Hz band that tints on clash, and draws
CQ ticks + callsign labels when enabled. Consumes Task 1's pure helpers so this
file holds only drawing logic.

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/WaterfallPanel.kt`

**Interfaces:**
- Consumes: `net.ft8vc.app.ui.spectrum.SpectrumMarker`, `SpectrumMarkers.txClashes` (Task 1); `net.ft8vc.app.ui.theme.Ft8Amber`, `net.ft8vc.app.ui.theme.Ft8Red`.
- Produces: `WaterfallPanel(vm, version, maxFreqHz, txFreqHz, onFreqChange, markers: List<SpectrumMarker>, showMarkers: Boolean, modifier)` — two new params appended before `modifier`.

- [ ] **Step 1: Rewrite `WaterfallPanel.kt`**

This is a Compose Canvas change; verification is compile + manual (the clash
logic it calls is already unit-tested in Task 1). Replace the file contents with:

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ft8vc.app.OperateViewModel
import net.ft8vc.app.ui.spectrum.SpectrumMarker
import net.ft8vc.app.ui.spectrum.SpectrumMarkers
import net.ft8vc.app.ui.theme.Ft8Amber
import net.ft8vc.app.ui.theme.Ft8Red

/** FT8 occupied bandwidth: 8-FSK x 6.25 Hz tone spacing. */
private const val FT8_SIGNAL_WIDTH_HZ = 50

@Composable
fun WaterfallPanel(
    vm: OperateViewModel,
    version: Long,
    maxFreqHz: Int,
    txFreqHz: Int,
    onFreqChange: (Int) -> Unit,
    markers: List<SpectrumMarker>,
    showMarkers: Boolean,
    modifier: Modifier = Modifier,
) {
    fun freqForX(x: Float, widthPx: Int): Int =
        if (widthPx <= 0) txFreqHz else (x / widthPx * maxFreqHz).toInt()

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        color = Ft8Amber,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
    )

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

                // TX footprint band (txFreq .. txFreq + 50 Hz), tinted on clash.
                val bandStart = hzToX(txFreqHz)
                val bandEnd = hzToX(txFreqHz + FT8_SIGNAL_WIDTH_HZ)
                val clash = SpectrumMarkers.txClashes(txFreqHz, markers)
                val bandColor = if (clash) Ft8Red else Ft8Amber
                drawRect(
                    color = bandColor.copy(alpha = 0.22f),
                    topLeft = Offset(bandStart, 0f),
                    size = Size((bandEnd - bandStart).coerceAtLeast(1f), size.height),
                )

                // CQ ticks + callsign labels (declutter into two stacked rows).
                if (showMarkers) {
                    val cqMarkers = markers.filter { it.isCq }.sortedBy { it.freqHz }
                    var lastRightRow0 = Float.NEGATIVE_INFINITY
                    var lastRightRow1 = Float.NEGATIVE_INFINITY
                    for (m in cqMarkers) {
                        val x = hzToX(m.freqHz)
                        drawLine(
                            color = Ft8Amber.copy(alpha = 0.35f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                        val layout = textMeasurer.measure(m.callsign, style = labelStyle)
                        val labelX = x.coerceAtMost(size.width - layout.size.width)
                        // Choose the topmost row whose last label does not overlap.
                        val (rowY, placed) = when {
                            labelX >= lastRightRow0 -> 2.dp.toPx() to 0
                            labelX >= lastRightRow1 -> (2.dp.toPx() + layout.size.height + 1.dp.toPx()) to 1
                            else -> continue // no room in either row without overlap; keep tick only
                        }
                        drawText(layout, topLeft = Offset(labelX, rowY))
                        val right = labelX + layout.size.width + 4.dp.toPx()
                        if (placed == 0) lastRightRow0 = right else lastRightRow1 = right
                    }
                }

                // Solid leading edge at the exact TX tone (preserves v1.0 precision marker).
                drawLine(
                    color = bandColor,
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

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (`SpectrumScreen` still calls the old signature and will error — that is fixed in Task 4. If you run this before Task 4, expect a `SpectrumScreen.kt` "no value passed for parameter markers/showMarkers" error only; `WaterfallPanel.kt` itself must compile clean. Proceed to Task 4, then compile.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/operate/WaterfallPanel.kt
git commit -m "feat(spectrum): 50 Hz TX band + collision tint + CQ labels on waterfall"
```

---

### Task 4: Wire `SpectrumScreen` — markers, live readout, Labels toggle

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/ui/spectrum/SpectrumScreen.kt`

**Interfaces:**
- Consumes: `SpectrumMarkers.forLatestSlot` (Task 1); `OperateUiState.spectrumMarkersEnabled` + `OperateViewModel.setSpectrumMarkersEnabled` (Task 2); `WaterfallPanel(... markers, showMarkers ...)` (Task 3).
- Produces: nothing downstream.

- [ ] **Step 1: Rewrite `SpectrumScreen.kt`**

```kotlin
package net.ft8vc.app.ui.spectrum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    val markers = remember(state.decodes) { SpectrumMarkers.forLatestSlot(state.decodes) }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "TX ${state.txFreqHz} Hz",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                    Text(
                        "Labels",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Checkbox(
                        checked = state.spectrumMarkersEnabled,
                        onCheckedChange = vm::setSpectrumMarkersEnabled,
                    )
                }
            }
            WaterfallPanel(
                vm = vm,
                version = state.waterfallVersion,
                maxFreqHz = vm.maxAudioFreqHz,
                txFreqHz = state.txFreqHz,
                onFreqChange = vm::setTxFreqHz,
                markers = markers,
                showMarkers = state.spectrumMarkersEnabled,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}
```

- [ ] **Step 2: Compile the whole app module**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (Task 3 + Task 4 signatures now match).

- [ ] **Step 3: Run the full app unit test suite (regression)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all tests pass, including Task 1 & 2 additions.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/spectrum/SpectrumScreen.kt
git commit -m "feat(spectrum): live Hz readout + Labels toggle, feed markers to waterfall"
```

---

### Task 5: Field verification (reference FT-891 + Digirig)

**Files:** none (manual verification per project's `verification-before-completion` gate).

- [ ] **Step 1: Build + install the unstable/readiness variant** the field phone runs (confirm the build flavor before attributing behavior — see memory `field-report-2026-07-04`). Do not run `connectedAndroidTest` against the field phone without pulling the logbook first (memory `connected-tests-wipe-app-data`).

- [ ] **Step 2: On a live band, open the Spectrum tab and confirm:**
  - CQ callsign labels appear at plausible waterfall positions and their Hz matches the Operate decode-list frequencies for the same stations.
  - Toggling "Labels" off hides ticks + labels; on restores them; the setting survives an app restart.
  - Dragging the TX tone updates the `TX NNNN Hz` readout continuously and moves the ~50 Hz band.
  - The band tints red only when parked within ~30 Hz of a decoded signal, and clears when moved to open spectrum. **Confirm it is NOT red across the whole band** on a busy run (alarm-fatigue check); if it is, lower `SpectrumMarkers.CLASH_WINDOW_HZ`.

- [ ] **Step 3: Regression:** confirm tap/drag still sets the TX tone exactly as before and RX/TX/CAT/QSO behavior is unchanged.

- [ ] **Step 4:** Record the outcome (and any `CLASH_WINDOW_HZ` adjustment) before promotion.

---

## Notes for the executor

- **Test task names:** the module unit-test task is `:app:testDebugUnitTest`. Filter with `--tests "<fully.qualified.ClassName>"`.
- **DecodeRow construction in tests:** `DecodeRow` has many defaulted fields; the test helper in Task 1 sets only the required + relevant ones. If the constructor's required (non-default) parameter set differs, supply those too — check `app/src/main/java/net/ft8vc/app/OperateUiState.kt` lines 18-45.
- **`DecodeRowSource`** is a sealed interface with `Rx`/`Tx` (confirmed); the plan's test uses `DecodeRowSource.Tx` for the synthetic-source case.
- **No new dependencies** are introduced; `rememberTextMeasurer`/`drawText` ship with the pinned Compose BOM.
