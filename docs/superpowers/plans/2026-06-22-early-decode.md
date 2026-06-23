# Early-Decode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run a second standard FT8 decode pass at slot-relative t=12.000s over the in-progress sample buffer, dedup against the full-slot pass, and emit each unique decode exactly once. Combined with the late-TX phase, this restores the WSJT-X / POTACAT hunt-and-pounce ergonomic.

**Architecture:** A new `EarlyDecodeScheduler` inside `DecodeController` schedules one coroutine per slot at `slotStart + 12.000s` on the existing single-thread `decodeDispatcher`. It snapshots `SlotCollector`'s in-progress buffer (new pure `snapshot()` accessor on the core module), runs the existing JNI decode path, and feeds results into the same `decodeSlot(...)` machinery now extended with a per-slot `seenKeys` dedup set. A stable cross-pass `DecodeRow.id` (derived from `(slotStart, freqBin, message)`) lets the full-slot pass update existing rows in place and **omit them from `_decodesOut`**, so `QsoSessionController` sees each unique decode exactly once.

**Tech Stack:** Kotlin 2.3.21, Jetpack Compose, kotlinx.coroutines 1.10.2, Room 2.7.2, AndroidX DataStore Preferences 1.1.7, kotlinx-collections-immutable, JUnit 4 + Coroutines Test 1.10.2, Compose UI Test.

## Global Constraints

- ft8_lib pin: kgoba `9fec6ca39886edbf96f4f5e71edc76da5074e871` via CMake FetchContent — do NOT bump.
- minSdk 28, NDK r29, JVM 17 unchanged.
- CRC-14 validation is bedrock — no early-pass code path may relax or skip it. Decodes come from the existing `Ft8DecoderApi.decode(...)` — no new native code.
- No console logging in production code (CLAUDE.md). Do NOT add `Log.i` / `Log.d` / `println` to `DecodeController` or anything new in this phase. R7 duration is a `DecodeSlice` field only.
- No new top-level dependencies. No new top-level screen / tab. Settings row joins the existing "Advanced decoding / TX" group introduced by the late-TX phase.
- PTT-safety primacy: do NOT modify `TxOrchestrator`'s 4-layer PTT defense, `RigSession.keyPtt`/`releasePtt` ordering, or `AppRfState`. Early-decode is RX-side; it reaches TX only via the unchanged `_decodesOut` → `QsoSessionController` → `TxOrchestrator.transmit()` chain.
- License gate preserved: `AppRfState.READY` precondition unchanged.
- DecodeListPanel must render `passSource = EARLY` rows pixel-identically to `passSource = FULL` rows — zero visual delta. No branching on `passSource` inside any composable.
- File naming: PascalCase for one top-level type per file; camelCase for functions and properties.
- All RX-decode `DecodeRow.id` values use the stable cross-pass hash (Task 4). TX synthetic rows in `OperateViewModel` (`id = ev.utcMillis`) are NOT in scope for this phase — leave their construction untouched.
- Run tests with `./gradlew :core:test` for core, `./gradlew :app:test` for app/JVM tests, and `./gradlew :app:testDebugUnitTest` for Robolectric-flavored unit tests. Compose UI tests run via `./gradlew :app:connectedDebugAndroidTest` only when explicitly required (Task 11).

---

## File Structure

| Path | Status | Purpose |
|---|---|---|
| `core/src/main/java/net/ft8vc/core/DecodePassSource.kt` | Create | Sealed interface `Early | Full` distinguishing which decode pass produced a row |
| `core/src/main/java/net/ft8vc/core/SlotCollector.kt` | Modify | Add pure `snapshot(): ShortArray?` accessor |
| `core/src/test/java/net/ft8vc/core/SlotCollectorSnapshotTest.kt` | Create | Tests for snapshot defensive-copy contract |
| `app/src/main/java/net/ft8vc/app/OperateUiState.kt` | Modify | Add `passSource: DecodePassSource = Full` to `DecodeRow`; add `lastDecodePassDurationMs` and `lastDecodePassSource` to slice fields used by tests (mirrored into `DecodeSlice`) |
| `app/src/main/java/net/ft8vc/app/controllers/DecodeRowKey.kt` | Create | Pure `stableId(slotStart, freqHz, message)` function |
| `app/src/test/java/net/ft8vc/app/controllers/DecodeRowKeyTest.kt` | Create | Tests for cross-pass id stability + collision resistance |
| `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt` | Modify | Extend `decodeSlot` with `source: DecodePassSource`; add per-slot `seenKeys`; in-place update on FULL collision; conditional `_decodesOut.emit`; duration instrumentation; `EarlyDecodeScheduler` |
| `app/src/test/java/net/ft8vc/app/controllers/DecodeControllerEarlyDedupTest.kt` | Create | Tests for the dedup contract via direct `decodeSlot` invocation |
| `app/src/test/java/net/ft8vc/app/controllers/EarlyDecodeSchedulerTest.kt` | Create | Tests for the scheduler timing + skip-if-too-few-samples + toggle-OFF behavior |
| `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt` | Modify | Add `earlyDecodeEnabled` to `StationSettings`; persist via DataStore key `early_decode_enabled`, default `true` |
| `app/src/test/java/net/ft8vc/app/settings/SettingsRepositoryEarlyDecodeTest.kt` | Create | Tests for default value + persistence round-trip |
| `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt` | Modify | Expose `earlyDecodeEnabled: Boolean` on `SettingsSlice` |
| `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt` (or current Settings UI file) | Modify | Add one row under "Advanced decoding / TX": "Early decode (CQs ~3s sooner)" |
| `app/src/androidTest/java/net/ft8vc/app/ui/operate/DecodeListPanelEarlyParityTest.kt` | Create | Compose snapshot test asserting zero pixel delta between EARLY and FULL rows |
| `app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorPttSafetyEarlyDecodeTest.kt` | Create | Re-runs each Phase-5 PTT-safety scenario under an EARLY-armed trigger |
| `app/src/test/java/net/ft8vc/app/controllers/EarlyDecodeLateTxIntegrationTest.kt` | Create | Combined-feature hunt-and-pounce integration test |
| `app/src/test/java/net/ft8vc/app/controllers/GoldenTraceEarlyDecodeParityTest.kt` | Create | Golden-trace replay with toggle OFF (byte-identical) and toggle ON (additive tolerance, zero key collisions) |

---

## Task 1: `DecodePassSource` sealed interface (foundation)

**Files:**
- Create: `core/src/main/java/net/ft8vc/core/DecodePassSource.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `sealed interface DecodePassSource { object Early; object Full }`

- [ ] **Step 1: Create the file**

```kotlin
package net.ft8vc.core

/**
 * Distinguishes which decode pass produced a [net.ft8vc.app.DecodeRow]:
 * - [Early]: partial-slot pass at slot-relative t=12.000s
 * - [Full]: full 15s slot pass at the slot boundary (the v1.0 pass)
 *
 * UI must NOT branch on this — early and full rows render pixel-identically.
 * Exists for telemetry, dedup bookkeeping, and tests.
 */
sealed interface DecodePassSource {
    object Early : DecodePassSource
    object Full : DecodePassSource
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/net/ft8vc/core/DecodePassSource.kt
git commit -m "feat(core): DecodePassSource sealed interface for early/full decode bookkeeping"
```

---

## Task 2: `SlotCollector.snapshot()` accessor

**Files:**
- Modify: `core/src/main/java/net/ft8vc/core/SlotCollector.kt`
- Create: `core/src/test/java/net/ft8vc/core/SlotCollectorSnapshotTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `fun SlotCollector.snapshot(): ShortArray?` — returns defensive copy of in-progress buffer or null if empty

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/net/ft8vc/core/SlotCollectorSnapshotTest.kt`:

```kotlin
package net.ft8vc.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlotCollectorSnapshotTest {
    private val sampleRate = AppInfo.SAMPLE_RATE_HZ
    private val slotStartMs = 0L  // any slot start

    @Test
    fun `snapshot returns null before any samples`() {
        val collector = SlotCollector(sampleRate)
        assertNull(collector.snapshot())
    }

    @Test
    fun `snapshot returns defensive copy of in-progress buffer`() {
        val collector = SlotCollector(sampleRate)
        val frames = ShortArray(12_000) { (it and 0xFF).toShort() }  // 1s of samples
        collector.add(frames, slotStartMs) { _, _ -> }

        val snap = collector.snapshot()
        assertEquals(12_000, snap?.size)
        assertArrayEquals(frames, snap)
    }

    @Test
    fun `mutating returned snapshot does not affect subsequent onSlot output`() {
        val collector = SlotCollector(sampleRate)
        val frames = ShortArray(12_000) { (it and 0xFF).toShort() }
        collector.add(frames, slotStartMs) { _, _ -> }

        val snap = collector.snapshot()!!
        snap.fill(0xFFFF.toShort())  // mutate caller copy

        // Advance to next slot, capture the flushed buffer
        var flushed: ShortArray? = null
        collector.add(ShortArray(0), slotStartMs + 15_000) { samples, _ -> flushed = samples }

        // Original samples should be intact, not the mutated values
        assertEquals(12_000, flushed?.size)
        assertArrayEquals(frames, flushed)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "net.ft8vc.core.SlotCollectorSnapshotTest"`
Expected: compilation error — `snapshot` is unresolved

- [ ] **Step 3: Add `snapshot()` to `SlotCollector`**

Edit `core/src/main/java/net/ft8vc/core/SlotCollector.kt` — add this method right above `reset()`:

```kotlin
    /**
     * Defensive copy of the in-progress slot buffer. Returns `null` if no
     * samples have been accumulated yet for the current slot.
     *
     * Used by the early-decode scheduler in DecodeController to run a
     * partial-slot decode pass without disturbing the boundary-driven
     * `onSlot` flow. Mutating the returned array MUST NOT affect a
     * subsequent `add(...)` or `onSlot` invocation.
     */
    fun snapshot(): ShortArray? = if (count > 0) buffer.copyOf(count) else null
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "net.ft8vc.core.SlotCollectorSnapshotTest"`
Expected: 3 passed

Run: `./gradlew :core:test --tests "net.ft8vc.core.SlotCollectorTest"`
Expected: existing tests still pass (regression guard)

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/ft8vc/core/SlotCollector.kt core/src/test/java/net/ft8vc/core/SlotCollectorSnapshotTest.kt
git commit -m "feat(core): SlotCollector.snapshot() — read-only defensive copy of in-progress buffer"
```

---

## Task 3: `DecodeRowKey.stableId` — pure cross-pass id derivation

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/controllers/DecodeRowKey.kt`
- Create: `app/src/test/java/net/ft8vc/app/controllers/DecodeRowKeyTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `object DecodeRowKey { const val FREQ_BIN_HZ = 6.25; fun stableId(slotStartEpochMs: Long, freqHz: Double, message: String): Long }`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/ft8vc/app/controllers/DecodeRowKeyTest.kt`:

```kotlin
package net.ft8vc.app.controllers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DecodeRowKeyTest {
    private val slotStart = 1_700_000_000_000L

    @Test
    fun `same slot freq bin and message produce equal ids across passes`() {
        // 1499.8 Hz and 1500.4 Hz both fall in the 1500 Hz bin (round to 6.25 Hz grid)
        val a = DecodeRowKey.stableId(slotStart, 1499.8, "CQ K1ABC FN42")
        val b = DecodeRowKey.stableId(slotStart, 1500.4, "CQ K1ABC FN42")
        assertEquals(a, b)
    }

    @Test
    fun `different message produces distinct id`() {
        val a = DecodeRowKey.stableId(slotStart, 1500.0, "CQ K1ABC FN42")
        val b = DecodeRowKey.stableId(slotStart, 1500.0, "CQ W2DEF EM12")
        assertNotEquals(a, b)
    }

    @Test
    fun `different freq bin produces distinct id`() {
        // 1497 and 1506 are in different 6.25 Hz bins (1497.5 and 1506.25 centers)
        val a = DecodeRowKey.stableId(slotStart, 1497.0, "CQ K1ABC FN42")
        val b = DecodeRowKey.stableId(slotStart, 1506.0, "CQ K1ABC FN42")
        assertNotEquals(a, b)
    }

    @Test
    fun `different slot produces distinct id`() {
        val a = DecodeRowKey.stableId(slotStart, 1500.0, "CQ K1ABC FN42")
        val b = DecodeRowKey.stableId(slotStart + 15_000, 1500.0, "CQ K1ABC FN42")
        assertNotEquals(a, b)
    }

    @Test
    fun `message text is trimmed before hashing`() {
        val a = DecodeRowKey.stableId(slotStart, 1500.0, "CQ K1ABC FN42")
        val b = DecodeRowKey.stableId(slotStart, 1500.0, "  CQ K1ABC FN42  ")
        assertEquals(a, b)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.DecodeRowKeyTest"`
Expected: compilation error — `DecodeRowKey` unresolved

- [ ] **Step 3: Implement `DecodeRowKey`**

Create `app/src/main/java/net/ft8vc/app/controllers/DecodeRowKey.kt`:

```kotlin
package net.ft8vc.app.controllers

import kotlin.math.roundToLong

/**
 * Cross-pass-stable hash for [net.ft8vc.app.DecodeRow.id].
 *
 * The early-decode (t=12s) pass and the full-slot (t=15s) pass each call
 * the JNI separately and report slightly different `freqHz` for the same
 * logical decode (sub-bin jitter). Snapping `freqHz` to a 6.25 Hz grid
 * (one FT8 tone bin) and folding with the slot start + trimmed message
 * text produces a key that collides for the same logical decode across
 * both passes, so [net.ft8vc.app.controllers.DecodeController]'s per-slot
 * seenKeys set deduplicates them.
 */
object DecodeRowKey {
    /** One FT8 tone-bin = 6.25 Hz (12000 Hz / 2 / 960 baseband bins). */
    const val FREQ_BIN_HZ: Double = 6.25

    fun stableId(slotStartEpochMs: Long, freqHz: Double, message: String): Long {
        val bin = (freqHz / FREQ_BIN_HZ).roundToLong()
        // Mix slotStart, bin, and message hash into a 64-bit id with low
        // collision over a per-slot decode set (<= ~50 decodes/slot in practice).
        val msgHash = message.trim().hashCode().toLong() and 0xFFFFFFFFL
        val binHash = bin and 0xFFFFL
        val slotHash = slotStartEpochMs and 0x7FFFFFFFFFFFL  // 47 bits
        return (slotHash shl 17) xor (binHash shl 32) xor msgHash
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.DecodeRowKeyTest"`
Expected: 5 passed

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/controllers/DecodeRowKey.kt app/src/test/java/net/ft8vc/app/controllers/DecodeRowKeyTest.kt
git commit -m "feat(app): DecodeRowKey.stableId — cross-pass stable id derivation (6.25 Hz bin)"
```

---

## Task 4: Add `passSource` field to `DecodeRow`

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt`

**Interfaces:**
- Consumes: `DecodePassSource` (Task 1)
- Produces: `DecodeRow.passSource: DecodePassSource = Full` field; all existing callers compile via defaulted parameter.

- [ ] **Step 1: Add the field**

Edit `app/src/main/java/net/ft8vc/app/OperateUiState.kt` — find the `DecodeRow` data class (line ~17). Add `passSource` as the last field with a default of `Full` so existing call sites stay source-compatible:

```kotlin
@Immutable
data class DecodeRow(
    /** Stable cross-pass key for Compose LazyColumn. See [DecodeRowKey.stableId]. */
    val id: Long,
    val timeUtc: String,
    val snr: Int,
    val dtSeconds: Float,
    val freqHz: Int,
    val message: String,
    val isCq: Boolean,
    val isToMe: Boolean = false,
    /** Great-circle km when message carries a 4-char grid; null otherwise. */
    val distanceKm: Int? = null,
    /** UTC slot parity (Even/Odd) when this decode was received. */
    val slotParity: TxSlotParity = TxSlotParity.EVEN,
    /** Whether this row was received (RX) or synthesized from our own TX. */
    val source: net.ft8vc.core.DecodeRowSource = net.ft8vc.core.DecodeRowSource.Rx,
    /** Whether the sender's callsign has been worked before. */
    val workedBefore: net.ft8vc.core.WorkedBefore = net.ft8vc.core.WorkedBefore.Never,
    /**
     * Which decode pass produced this row. Used only for telemetry + dedup
     * bookkeeping. UI must NOT branch on this — early and full rows render
     * pixel-identically.
     */
    val passSource: net.ft8vc.core.DecodePassSource = net.ft8vc.core.DecodePassSource.Full,
)
```

(Note: only the `id` KDoc and the new `passSource` field are added. Do not reorder existing fields.)

- [ ] **Step 2: Verify the project still compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL — existing call sites in `DecodeController` and `OperateViewModel` still compile (no `passSource` argument needed yet; default applies).

- [ ] **Step 3: Run the full app JVM test suite to verify no regression**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all existing tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateUiState.kt
git commit -m "feat(app): DecodeRow.passSource — Early/Full bookkeeping field (defaults to Full)"
```

---

## Task 5: Settings — `earlyDecodeEnabled` DataStore key + bridge slice

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt`
- Modify: `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt`
- Create: `app/src/test/java/net/ft8vc/app/settings/SettingsRepositoryEarlyDecodeTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `StationSettings.earlyDecodeEnabled: Boolean = true`; DataStore key `"early_decode_enabled"`; `SettingsSlice.earlyDecodeEnabled: Boolean`; setter `SettingsRepository.setEarlyDecodeEnabled(enabled: Boolean)`.

- [ ] **Step 1: Inspect existing SettingsRepository conventions**

Read `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt` and find an existing boolean key + setter (e.g. `autoSeqEnabled` or `autoAnswerCqEnabled`). Mirror its pattern exactly: the `Keys` object, the `StationSettings` field, the mapping in the `settings` Flow, and the public setter.

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/net/ft8vc/app/settings/SettingsRepositoryEarlyDecodeTest.kt`. Use the existing repository tests (e.g. for `autoSeqEnabled`) as the template for DataStore setup; the project already has the in-memory DataStore harness wired up.

```kotlin
package net.ft8vc.app.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
// Replicate whatever harness existing SettingsRepository tests use
// (likely an in-memory DataStore via a TestRule). Copy the setup from
// the closest sibling test before writing this one.

class SettingsRepositoryEarlyDecodeTest {
    @get:Rule val dsRule = SettingsTestDataStoreRule()  // or whatever the project uses

    @Test
    fun `earlyDecodeEnabled defaults to true on fresh install`() = runTest {
        val repo = SettingsRepository(dsRule.dataStore)
        val settings = repo.settings.first()
        assertTrue(settings.earlyDecodeEnabled)
    }

    @Test
    fun `earlyDecodeEnabled persists round-trip`() = runTest {
        val repo = SettingsRepository(dsRule.dataStore)
        repo.setEarlyDecodeEnabled(false)
        val settings = repo.settings.first()
        assertFalse(settings.earlyDecodeEnabled)
    }
}
```

If the existing harness uses a different rule name, swap it in. Do NOT invent a new DataStore harness — copy the closest sibling exactly.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.SettingsRepositoryEarlyDecodeTest"`
Expected: compile error or test failure — field/setter does not exist.

- [ ] **Step 4: Add `earlyDecodeEnabled` to `StationSettings` + key + setter**

Edit `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt`:

- Add field to `StationSettings` data class with `= true` default (place it near the other "advanced decoding / TX" toggles introduced by the late-TX phase if present, otherwise near `autoSeqEnabled`):
  ```kotlin
  val earlyDecodeEnabled: Boolean = true,
  ```
- Add to `Keys`:
  ```kotlin
  val earlyDecodeEnabled = booleanPreferencesKey("early_decode_enabled")
  ```
- Add to the `settings: Flow<StationSettings>` mapping with the same `?: true` fallback as other boolean defaults:
  ```kotlin
  earlyDecodeEnabled = prefs[Keys.earlyDecodeEnabled] ?: true,
  ```
- Add setter following the existing pattern:
  ```kotlin
  suspend fun setEarlyDecodeEnabled(enabled: Boolean) {
      dataStore.edit { prefs -> prefs[Keys.earlyDecodeEnabled] = enabled }
  }
  ```

- [ ] **Step 5: Expose on `SettingsSlice` via `SettingsBridge`**

Edit `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt`:

- Add to `SettingsSlice`:
  ```kotlin
  val earlyDecodeEnabled: Boolean = true,
  ```
- Add to the `StationSettings.toSlice()` mapping:
  ```kotlin
  earlyDecodeEnabled = earlyDecodeEnabled,
  ```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.SettingsRepositoryEarlyDecodeTest"`
Expected: 2 passed.

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.SettingsBridgeTest"`
Expected: existing tests still pass (new field is defaulted; existing slice constructors work unchanged).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt app/src/test/java/net/ft8vc/app/settings/SettingsRepositoryEarlyDecodeTest.kt
git commit -m "feat(app): SettingsRepository.earlyDecodeEnabled (default true) + bridge slice exposure"
```

---

## Task 6: DecodeController — extend `decodeSlot` with `source`, dedup, duration, in-place update

This is the central task. It changes the signature of `decodeSlot`, threads the stable id, adds the per-slot `seenKeys` map, implements in-place update on FULL collision, and adds duration instrumentation to `DecodeSlice`. The new `EarlyDecodeScheduler` lands in Task 7 — this task ONLY changes the existing FULL-pass path to use the new infrastructure (so existing tests still pass) and prepares the dedup machinery.

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt`
- Create: `app/src/test/java/net/ft8vc/app/controllers/DecodeControllerEarlyDedupTest.kt`

**Interfaces:**
- Consumes: `DecodePassSource` (Task 1), `DecodeRowKey.stableId` (Task 3), `DecodeRow.passSource` (Task 4)
- Produces:
  - `DecodeSlice` gains `lastDecodePassDurationMs: Long = 0L` and `lastDecodePassSource: DecodePassSource? = null`
  - `DecodeController.decodeSlot(samples, slotStartEpochMs, source: DecodePassSource = Full)` — `source` is suspend-internal; not part of the public surface
  - Per-slot dedup state: `seenKeys: MutableMap<Long, HashSet<Long>>` keyed by slotStart, capped at 4 most-recent slots
  - `_decodesOut` emission contract: each unique stableId appears at most once per slot across both passes

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/ft8vc/app/controllers/DecodeControllerEarlyDedupTest.kt`. Use the existing `DecodeController` test fixtures (an `Ft8DecoderFake` should exist already — the project has `Ft8DecoderApi` test seams from earlier phases; copy the fake from the closest sibling test such as `DecodeControllerTest` if present):

```kotlin
package net.ft8vc.app.controllers

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.ft8vc.core.DecodePassSource
import net.ft8vc.ft8native.Ft8DecodeResult
import net.ft8vc.ft8native.Ft8DecoderApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DecodeControllerEarlyDedupTest {
    private val slotStart = 1_700_000_000_000L

    private fun result(message: String, freqHz: Double, snr: Int, dt: Float = 0f) =
        Ft8DecodeResult(message = message, freqHz = freqHz, snr = snr, dtSeconds = dt, score = snr)

    // Sequentially returns the queued result lists, one per decode call.
    private class QueuedFake(private val queue: ArrayDeque<List<Ft8DecodeResult>>) : Ft8DecoderApi {
        override fun decode(samples: ShortArray, sampleRate: Int): List<Ft8DecodeResult> =
            if (queue.isNotEmpty()) queue.removeFirst() else emptyList()
        // implement any other Ft8DecoderApi members as no-ops, mirroring existing fakes in this package
    }

    @Test
    fun `early then full pass dedup union not sum`() = runTest {
        val earlyResults = listOf(result("CQ K1ABC FN42", 1500.0, snr = -10))
        val fullResults  = listOf(
            result("CQ K1ABC FN42", 1500.4, snr = -8),       // same as early
            result("CQ W2DEF EM12", 1700.0, snr = -12),      // new
        )
        val fake = QueuedFake(ArrayDeque(listOf(earlyResults, fullResults)))
        val scope = TestScope(StandardTestDispatcher())
        val controller = DecodeController(decoder = fake, scope = scope)

        val emitted = mutableListOf<DecodeBatch>()
        val collectJob = scope.launch { controller.decodesOut.toList(emitted) }

        controller.decodeSlot(ShortArray(115_200), slotStart, source = DecodePassSource.Early)
        controller.decodeSlot(ShortArray(180_000), slotStart, source = DecodePassSource.Full)
        scope.advanceUntilIdle()

        // Union, not sum: 2 unique decodes, not 3
        assertEquals(2, controller.slice.value.decodes.size)
        // Each unique key emitted at most once across both batches
        val allMessages = emitted.flatMap { it.decodes.map { d -> d.text } }
        assertEquals(2, allMessages.distinct().size)
        assertEquals(allMessages.size, allMessages.distinct().size)

        // Full pass updated the early row in place with the higher SNR
        val updated = controller.slice.value.decodes.first { it.message == "CQ K1ABC FN42" }
        assertEquals(-8, updated.snr)

        collectJob.cancel()
        controller.close()
    }

    @Test
    fun `duration instrumentation updates after each pass`() = runTest {
        val fake = QueuedFake(ArrayDeque(listOf(emptyList(), emptyList())))
        val scope = TestScope(StandardTestDispatcher())
        val controller = DecodeController(decoder = fake, scope = scope)

        controller.decodeSlot(ShortArray(115_200), slotStart, source = DecodePassSource.Early)
        scope.advanceUntilIdle()
        assertEquals(DecodePassSource.Early, controller.slice.value.lastDecodePassSource)
        // duration is wall-clock; just assert non-negative + the field is wired
        assert(controller.slice.value.lastDecodePassDurationMs >= 0)

        controller.decodeSlot(ShortArray(180_000), slotStart, source = DecodePassSource.Full)
        scope.advanceUntilIdle()
        assertEquals(DecodePassSource.Full, controller.slice.value.lastDecodePassSource)

        controller.close()
    }
}
```

Note: `decodeSlot` is currently `private suspend`. To make this testable, it must become `internal suspend` (still scoped to the module). The QSO-side `decodesOut` and `slice` flows stay public. If the project already exposes a test-internal entry point on DecodeController, prefer that over re-scoping.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.DecodeControllerEarlyDedupTest"`
Expected: compile error — `decodeSlot` is private, `source` parameter does not exist, `lastDecodePassSource`/`lastDecodePassDurationMs` not on slice.

- [ ] **Step 3: Extend `DecodeSlice`**

Edit `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt` — extend the `DecodeSlice` data class (at the bottom of the file) with two new fields:

```kotlin
data class DecodeSlice(
    val decodes: ImmutableList<DecodeRow> = persistentListOf(),
    val lastSlotDecodeCount: Int = -1,
    val levelDbfs: Float = OperateUiState.SILENCE_DBFS,
    val clip: Boolean = false,
    val waterfallVersion: Long = 0L,
    val decodeFailureCount: Long = 0L,
    val decodeFailureRecent: Boolean = false,
    val zeroSampleSlots: Int = 0,
    /** Wall-clock duration of the most recent decode pass, ms. */
    val lastDecodePassDurationMs: Long = 0L,
    /** Which pass produced [lastDecodePassDurationMs]. Null before any pass runs. */
    val lastDecodePassSource: net.ft8vc.core.DecodePassSource? = null,
)
```

- [ ] **Step 4: Add dedup state + reshape `decodeSlot`**

Edit `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt`:

Add to the class body (near the other private state):

```kotlin
    /**
     * Per-slot dedup set: slotStart → set of [DecodeRowKey.stableId] values
     * already inserted into the list and emitted on [_decodesOut] for that
     * slot. Capped at 4 most-recent slots — oldest evicted when a 5th slot
     * arrives (bounded memory; aged slots are off the screen anyway).
     */
    private val seenKeys: LinkedHashMap<Long, HashSet<Long>> =
        object : LinkedHashMap<Long, HashSet<Long>>(/* initialCapacity = */ 8) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, HashSet<Long>>): Boolean =
                size > 4
        }
```

Change the signature of `decodeSlot`:

```kotlin
    internal suspend fun decodeSlot(
        samples: ShortArray,
        slotStartEpochMs: Long,
        source: net.ft8vc.core.DecodePassSource = net.ft8vc.core.DecodePassSource.Full,
    ) {
        // existing zero-sample tracking unchanged
        // ...

        val passDurationMs: Long
        val results = try {
            var out: List<net.ft8vc.ft8native.Ft8DecodeResult> = emptyList()
            passDurationMs = kotlin.system.measureTimeMillis {
                out = decoder.decode(samples, AppInfo.SAMPLE_RATE_HZ)
            }
            out
        } catch (t: Throwable) {
            failureCount.incrementAndGet()
            consecutiveSuccessfulSlots = 0
            _slice.update {
                it.copy(
                    decodeFailureCount = failureCount.get(),
                    decodeFailureRecent = true,
                    lastDecodePassSource = source,
                )
            }
            return
        }

        // success → duration + source → slice
        _slice.update {
            it.copy(
                lastDecodePassDurationMs = passDurationMs,
                lastDecodePassSource = source,
            )
        }

        // existing successful-pass bookkeeping (decodeFailureRecent clear after N successes)
        // ...

        val ctx = stationContext
        val time = utcTimeFormat.format(java.util.Date(slotStartEpochMs))
        val slotParity = net.ft8vc.core.TxSlotSelection.slotParity(slotStartEpochMs)
        val sorted = results.sortedByDescending { it.score }

        val keysThisSlot = seenKeys.getOrPut(slotStartEpochMs) { HashSet() }
        val newlyEmitted = mutableListOf<net.ft8vc.core.QsoDecode>()
        val newRows = mutableListOf<DecodeRow>()
        val updates = mutableMapOf<Long, net.ft8vc.ft8native.Ft8DecodeResult>()  // stableId → fresher result

        for (r in sorted) {
            val message = r.message.trim()
            val id = DecodeRowKey.stableId(slotStartEpochMs, r.freqHz, message)
            if (keysThisSlot.add(id)) {
                // First time we see this key in this slot — insert new row + emit
                newRows += DecodeRow(
                    id = id,
                    timeUtc = time,
                    snr = r.snr,
                    dtSeconds = r.dtSeconds,
                    freqHz = Math.round(r.freqHz),
                    message = message,
                    isCq = message.startsWith("CQ"),
                    isToMe = net.ft8vc.core.QsoResume.isDirectedToMe(ctx.myCall, message),
                    distanceKm = net.ft8vc.core.DecodeDistance.kmFromMessage(ctx.myGrid, message),
                    slotParity = slotParity,
                    passSource = source,
                )
                newlyEmitted += net.ft8vc.core.QsoDecode(message, r.snr)
            } else if (source == net.ft8vc.core.DecodePassSource.Full) {
                // FULL pass: update the already-inserted EARLY row in place
                updates[id] = r
            }
            // EARLY pass duplicate within itself: no-op (shouldn't happen given the single
            // early trigger per slot, but cheap defense-in-depth).
        }

        _slice.update { s ->
            val withUpdates: List<DecodeRow> = if (updates.isEmpty()) s.decodes else s.decodes.map { row ->
                val r = updates[row.id]
                if (r == null) row else row.copy(
                    snr = r.snr,
                    dtSeconds = r.dtSeconds,
                    freqHz = Math.round(r.freqHz),
                )
            }
            val combined = (newRows + withUpdates).take(OperateUiState.MAX_DECODE_ROWS)
            s.copy(
                decodes = combined.toPersistentList(),
                lastSlotDecodeCount = newRows.size,  // only newly inserted count
            )
        }

        if (newlyEmitted.isNotEmpty()) {
            _decodesOut.emit(
                DecodeBatch(
                    slotStartEpochMs = slotStartEpochMs,
                    slotParity = slotParity,
                    decodes = newlyEmitted,
                ),
            )
        }
    }
```

Notes for the implementer:
- Keep the existing `consecutiveSuccessfulSlots` increment + decay logic exactly as it was — the snippet above elides it but you must preserve it.
- The existing call site in `onFrames` becomes:
  ```kotlin
  slotCollector.add(pcm, clock()) { samples, slotStart ->
      scope.launch(decodeDispatcher) {
          decodeSlot(samples, slotStart, source = net.ft8vc.core.DecodePassSource.Full)
      }
  }
  ```
- Do NOT add any `Log.i` / `Log.d` / `println` calls (CLAUDE.md rule).

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.DecodeControllerEarlyDedupTest"`
Expected: 2 passed.

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.DecodeControllerTest"`
Expected: existing controller tests still pass (the FULL-only path is functionally unchanged for callers that never invoke the EARLY pass — `_decodesOut` still emits a batch with all the slot's decodes because nothing was seen before).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt app/src/test/java/net/ft8vc/app/controllers/DecodeControllerEarlyDedupTest.kt
git commit -m "feat(app): DecodeController dedup contract + duration instrumentation + DecodePassSource plumbing"
```

---

## Task 7: `EarlyDecodeScheduler` — schedule one-shot at slotStart+12s

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt`
- Create: `app/src/test/java/net/ft8vc/app/controllers/EarlyDecodeSchedulerTest.kt`

**Interfaces:**
- Consumes: `SlotCollector.snapshot()` (Task 2), `decodeSlot(..., source = Early)` (Task 6), `SettingsBridge.SettingsSlice.earlyDecodeEnabled` (Task 5)
- Produces: behavior — when the toggle is ON and the in-progress slot has ≥ 115_200 samples at `slotStart + 12.000s`, an EARLY-pass `decodeSlot` runs on `decodeDispatcher`. Cancelled when a new slot transition arrives.

The simplest correct shape: a `MutableStateFlow<Long>` tracking the current slot start, plus a single `scope.launch { currentSlot.collectLatest { ... delay ... } }`. `collectLatest` gives free cancellation on slot transition.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/ft8vc/app/controllers/EarlyDecodeSchedulerTest.kt`:

```kotlin
package net.ft8vc.app.controllers

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.ft8vc.core.DecodePassSource
import net.ft8vc.ft8native.Ft8DecodeResult
import net.ft8vc.ft8native.Ft8DecoderApi
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class EarlyDecodeSchedulerTest {
    private val slotStart = 1_700_000_000_000L
    private val sampleRate = 12_000

    private class CountingFake(val earlyCount: AtomicInteger, val fullCount: AtomicInteger) : Ft8DecoderApi {
        override fun decode(samples: ShortArray, sampleRate: Int): List<Ft8DecodeResult> = emptyList()
        // ... mirror existing fake stubs
    }

    @Test
    fun `early pass fires once per slot at t plus 12s when toggle on and samples sufficient`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        val fakeClock = AtomicClock(slotStart)
        val earlyCount = AtomicInteger(0)
        val fullCount = AtomicInteger(0)
        val fake = CountingFake(earlyCount, fullCount)

        val controller = DecodeController(
            decoder = fake,
            scope = scope,
            clock = { fakeClock.now },
        )
        controller.setEarlyDecodeEnabled(true)

        // Feed enough samples to clear the 115_200 threshold by the time t=12s arrives
        // (controller's onFrames threads through SlotCollector; supply 115_200+).
        val frames = ShortArray(120_000) { 1.toShort() }
        controller.onFrames(frames)

        // Advance the fake clock + the dispatcher together
        fakeClock.now = slotStart + 12_001
        scope.advanceTimeBy(12_001)
        scope.advanceUntilIdle()

        assertEquals(1, earlyCount.get())
        controller.close()
    }

    @Test
    fun `early pass is skipped when fewer than 115_200 samples present`() = runTest {
        // ... mirrors the first test but supplies only 100_000 samples
        // assert earlyCount.get() == 0 after t+12s elapses
    }

    @Test
    fun `slot boundary cancels still-pending early job`() = runTest {
        // ... start a slot, advance to t+11s (before early fires), advance clock past slot boundary
        // to start a new slot, advance to original-slot t+12s, assert old slot's early did NOT run
    }

    @Test
    fun `with toggle off no early pass fires`() = runTest {
        // ... mirrors the first test but with setEarlyDecodeEnabled(false)
        // assert earlyCount.get() == 0 across 10 simulated slots
    }
}
```

The test relies on:
- A new public method on `DecodeController`: `fun setEarlyDecodeEnabled(enabled: Boolean)` — kept simple. Wire it via a `MutableStateFlow<Boolean>` field. In production, `OperateViewModel` will call this from its `SettingsBridge.slice` collector.
- An `AtomicClock` helper. Define it in the test file:
  ```kotlin
  private class AtomicClock(initial: Long) { @Volatile var now: Long = initial }
  ```
- The CountingFake's `decode` impl can distinguish EARLY vs FULL by inspecting the `samples.size`: 115_200–120_000 → EARLY, 180_000 → FULL. Cleaner: have the controller expose a test seam (`@Volatile var lastPassSource: DecodePassSource? = null`) that the test reads. Either approach is acceptable; the simpler one is to add the seam.

(The implementer should fill in the elided test bodies following the pattern of the first test. Each `// ...` comment marks where to repeat the setup.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.EarlyDecodeSchedulerTest"`
Expected: compile error — `setEarlyDecodeEnabled` does not exist, no scheduler wired.

- [ ] **Step 3: Add the scheduler to `DecodeController`**

Edit `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt`:

Add as a class field (near other state):

```kotlin
    private val earlyDecodeEnabled = MutableStateFlow(true)
    private val currentSlotStart = MutableStateFlow<Long?>(null)
    private val earlyMinSamples = AppInfo.SAMPLE_RATE_HZ * 12 * 8 / 10  // 80% of 12s buffer = 115_200

    fun setEarlyDecodeEnabled(enabled: Boolean) {
        earlyDecodeEnabled.value = enabled
    }
```

In the `onFrames(...)` body, after the existing slot-transition detection in `slotCollector.add(...) { samples, slotStart -> ... }`, also detect the *start* of a new slot. The cleanest way is to update `currentSlotStart` whenever the slot start observed by SlotTiming changes. Add this right before the `slotCollector.add(...)` call:

```kotlin
        val slotStartNow = net.ft8vc.core.SlotTiming.slotStart(clock())
        if (currentSlotStart.value != slotStartNow) {
            currentSlotStart.value = slotStartNow
        }
```

In the `init { ... }` block (or class init), launch the scheduler:

```kotlin
    init {
        scope.launch(decodeDispatcher) {
            currentSlotStart.collectLatest { slotStart ->
                if (slotStart == null) return@collectLatest
                if (!earlyDecodeEnabled.value) return@collectLatest
                val targetMs = slotStart + EARLY_OFFSET_MS
                val waitMs = targetMs - clock()
                if (waitMs > 0) kotlinx.coroutines.delay(waitMs)
                // re-check the toggle on wake (it may have flipped during the delay)
                if (!earlyDecodeEnabled.value) return@collectLatest
                val snap = slotCollector.snapshot() ?: return@collectLatest
                if (snap.size < earlyMinSamples) return@collectLatest
                decodeSlot(snap, slotStart, source = net.ft8vc.core.DecodePassSource.Early)
            }
        }
    }

    companion object {
        const val DECODE_FAILURE_DECAY_SLOTS = 5
        /** Slot-relative offset (ms) at which the early decode pass fires. */
        const val EARLY_OFFSET_MS = 12_000L
    }
```

Imports to add at the top of the file:

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
```

(`DECODE_FAILURE_DECAY_SLOTS` already exists in the companion — preserve it; only add `EARLY_OFFSET_MS`.)

Note: `init` blocks run before properties initialized later in the source, so place this `init` block AFTER `slotCollector` and the dispatcher fields are declared. If the existing code has no `init`, add one in the right position.

- [ ] **Step 4: Wire the toggle through `OperateViewModel`**

Find where `OperateViewModel` collects `SettingsBridge.slice` and reacts to changes (e.g. for `autoSeqEnabled` or other toggles). Add a parallel line:

```kotlin
viewModelScope.launch {
    settingsBridge.slice.collect { s ->
        decodeController.setEarlyDecodeEnabled(s.earlyDecodeEnabled)
        // ... existing handling for other toggles
    }
}
```

If there is already a single `slice.collect` loop fanning out toggle updates, add the call inside it. Do NOT duplicate the collect loop.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.EarlyDecodeSchedulerTest"`
Expected: 4 passed.

Run: `./gradlew :app:testDebugUnitTest`
Expected: full suite green (no regressions).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt app/src/main/java/net/ft8vc/app/OperateViewModel.kt app/src/test/java/net/ft8vc/app/controllers/EarlyDecodeSchedulerTest.kt
git commit -m "feat(app): EarlyDecodeScheduler — one-shot at slotStart+12s on decodeDispatcher"
```

---

## Task 8: Settings UI row — "Early decode (CQs ~3s sooner)"

**Files:**
- Modify: the Settings composable file that hosts the late-TX phase's "Advanced decoding / TX" group. If late-TX has not landed yet, create the group here.

**Interfaces:**
- Consumes: `SettingsRepository.setEarlyDecodeEnabled(enabled: Boolean)` (Task 5), `SettingsSlice.earlyDecodeEnabled` (Task 5)
- Produces: a Switch / toggle row in Settings

- [ ] **Step 1: Locate the Settings UI file**

Find the file that hosts the "Advanced decoding / TX" group (the late-TX phase introduces it). Likely `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt` or similar. If the late-TX group doesn't exist yet, create it following the pattern of existing groups (e.g. the "Station" group).

- [ ] **Step 2: Add the row**

Insert a row mirroring the existing toggle pattern in the file. The label is `"Early decode (CQs ~3s sooner)"`; subtitle `"Runs an extra decode pass partway through each slot."`. Wire `checked` to `state.earlyDecodeEnabled` and `onCheckedChange` to a ViewModel call that invokes `settingsRepository.setEarlyDecodeEnabled(it)`.

The exact composable shape MUST match the late-TX row's shape — copy that file's pattern (Switch + Text + spacing modifiers) verbatim.

- [ ] **Step 3: Smoke-build the app module**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual sanity check (no automated UI test for this row)**

The implementer must launch the app in an emulator or device, open Settings, scroll to "Advanced decoding / TX", confirm the row appears with the correct label/subtitle and defaults to ON. Toggling it persists across a kill-and-relaunch (DataStore handles this — verified by Task 5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt
git commit -m "feat(app/settings): Early decode (CQs ~3s sooner) row under Advanced decoding / TX"
```

---

## Task 9: DecodeListPanel pixel-parity Compose snapshot test

**Files:**
- Create: `app/src/androidTest/java/net/ft8vc/app/ui/operate/DecodeListPanelEarlyParityTest.kt`

**Interfaces:**
- Consumes: `DecodeRow.passSource` (Task 4), existing `DecodeListPanel` composable
- Produces: a Compose UI test asserting zero pixel delta between EARLY and FULL rows

- [ ] **Step 1: Inspect the existing snapshot-test pattern in this codebase**

Search the project for existing Compose snapshot tests. If snapshot infrastructure (e.g. Paparazzi, Roborazzi, or a captureToImage harness) is already wired up, use it. If not, this test reduces to a `composeTestRule.onNodeWithText(...)` assertion that both EARLY and FULL rows render the same text + that no extra node (chip, badge, marker) appears for the EARLY row.

- [ ] **Step 2: Write the test using whichever harness exists**

```kotlin
package net.ft8vc.app.ui.operate

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import kotlinx.collections.immutable.persistentListOf
import net.ft8vc.app.DecodeRow
import net.ft8vc.core.DecodePassSource
import org.junit.Rule
import org.junit.Test

class DecodeListPanelEarlyParityTest {
    @get:Rule val composeTestRule = createComposeRule()

    private fun row(passSource: DecodePassSource) = DecodeRow(
        id = 1_700_000_000L,
        timeUtc = "120000",
        snr = -10,
        dtSeconds = 0.1f,
        freqHz = 1500,
        message = "CQ K1ABC FN42",
        isCq = true,
        passSource = passSource,
    )

    @Test
    fun `early and full rows render the same set of text nodes`() {
        composeTestRule.setContent {
            DecodeListPanel(
                rows = persistentListOf(row(DecodePassSource.Early), row(DecodePassSource.Full)),
                // ... wire any other required composable params from the existing DecodeListPanel signature
            )
        }
        // Both rows display the same message text
        composeTestRule.onAllNodesWithText("CQ K1ABC FN42").assertCountEquals(2)
        // No EARLY-only marker text exists ("E", "EARLY", "Preview", etc.)
        composeTestRule.onAllNodesWithText("EARLY").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("E").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Preview").assertCountEquals(0)
    }
}
```

If Paparazzi or Roborazzi is wired up, prefer the actual snapshot-diff approach. Match the existing precedent for any other snapshot test in this codebase.

- [ ] **Step 3: Run the UI test**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "net.ft8vc.app.ui.operate.DecodeListPanelEarlyParityTest"`
Expected: PASS.

If no device/emulator is connected for Compose UI testing in CI, gate this test behind `@Ignore` with a comment pointing at the field-session verification, and document the manual verification in Task 8's smoke check. The spec's pixel-parity acceptance criterion is preferentially automated but degrades to manual review if the project has no UI test infrastructure.

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/java/net/ft8vc/app/ui/operate/DecodeListPanelEarlyParityTest.kt
git commit -m "test(app): DecodeListPanelEarlyParityTest — Early/Full rows render identically"
```

---

## Task 10: PTT-safety re-runs under EARLY-armed trigger

**Files:**
- Create: `app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorPttSafetyEarlyDecodeTest.kt`

**Interfaces:**
- Consumes: existing `TxOrchestratorTest` fixtures (FakeRigBackend, FakeUsbAudioPlayback), `_decodesOut` SharedFlow, `QsoSessionController`
- Produces: assertion that all four Phase-5 PTT-safety layers still fire when the TX call originates from an EARLY-pass-armed auto-answer

- [ ] **Step 1: Locate the existing PTT-safety test fixtures**

Look at the existing `TxOrchestratorTest` (or `TxOrchestratorPttSafetyTest`) for the four PTT-safety scenarios from Phase 5: (a) `try-finally` release on synchronous throw, (b) `AutoCloseable` release on cancellation, (c) `withTimeoutOrNull(SLOT_DURATION_MS + 500)` timeout-fires-release, (d) 250 ms watchdog.

- [ ] **Step 2: Write the test (one method per scenario)**

For each of the four scenarios, parameterize the existing scenario test (or duplicate it) and trigger the `transmit()` call by emitting an EARLY-source `DecodeBatch` on `_decodesOut` rather than calling `transmit()` directly. Use the existing FakeRigBackend / FakeUsbAudioPlayback fault-injection seams.

Skeleton:

```kotlin
package net.ft8vc.app.controllers

import kotlinx.coroutines.test.runTest
import net.ft8vc.core.DecodePassSource
import org.junit.Test

class TxOrchestratorPttSafetyEarlyDecodeTest {

    // Each test mirrors a Phase-5 scenario from TxOrchestratorPttSafetyTest
    // but injects the TX-arming signal via an EARLY-pass DecodeBatch.

    @Test
    fun `layer A try-finally releases PTT when playback throws under early-armed TX`() = runTest {
        // 1. Build controller + fakes per existing TxOrchestratorTest setUp
        // 2. Arrange QsoSessionController so a matching DecodeBatch arms TX
        // 3. Emit DecodeBatch carrying a directed reply, with the underlying
        //    DecodeRow.passSource = DecodePassSource.Early
        // 4. Configure FakeUsbAudioPlayback to throw mid-stream
        // 5. Assert: PTT released; SafetyChip surfaced; AppRfState transitions per Phase 5
    }

    // ... three more methods for layers B, C, D
}
```

The implementer MUST copy the setUp from the existing PTT-safety test verbatim and only change the trigger path. Do not invent new fake behavior.

- [ ] **Step 3: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.TxOrchestratorPttSafetyEarlyDecodeTest"`
Expected: 4 passed.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorPttSafetyEarlyDecodeTest.kt
git commit -m "test(app): re-run Phase-5 PTT-safety scenarios under EARLY-armed TX trigger"
```

---

## Task 11: Combined-feature hunt-and-pounce integration test

**Files:**
- Create: `app/src/test/java/net/ft8vc/app/controllers/EarlyDecodeLateTxIntegrationTest.kt`

**Interfaces:**
- Consumes: full controller stack (DecodeController, QsoSessionController, TxOrchestrator), late-TX `transmit()` entry from companion phase
- Produces: end-to-end assertion of the hunt-and-pounce loop

- [ ] **Step 1: Prerequisite check**

The late-TX phase must be merged before this task. If it has not landed yet, this task and Task 12 are blocked. Pause here, do not write a stub, and surface the blocker to the user.

- [ ] **Step 2: Write the test**

```kotlin
package net.ft8vc.app.controllers

import kotlinx.coroutines.test.runTest
import net.ft8vc.core.DecodePassSource
import org.junit.Assert.assertTrue
import org.junit.Test

class EarlyDecodeLateTxIntegrationTest {
    private val slotStart = 1_700_000_000_000L

    @Test
    fun `early CQ arms late-TX reply on next slot boundary`() = runTest {
        // 1. Build DecodeController + QsoSessionController + TxOrchestrator
        //    with the test fakes used by TxOrchestratorTest + DecodeControllerTest.
        // 2. Configure SettingsRepository with earlyDecodeEnabled = true AND
        //    lateStartTxEnabled = true (from companion phase).
        // 3. Feed PCM with a directed reply CQ targeting our call,
        //    advancing the fake clock to slotStart + 12_000.
        // 4. Assert: EARLY pass discovers the reply, _decodesOut emits a batch
        //    with one decode of passSource=Early.
        // 5. Assert: QsoSessionController arms an auto-answer.
        // 6. Advance to slotStart + 15_000 (next slot boundary).
        // 7. Assert: TxOrchestrator.transmit(reply) is invoked.
        //    Time-in-slot ∈ [0.0s, 7.0s]. PTT keyed within 50 ms (FakeRigBackend
        //    records the key event timestamp).
        // 8. Advance to the full-slot pass. Assert that _decodesOut does NOT
        //    emit a duplicate of the EARLY-emitted reply (dedup contract).
    }
}
```

Fill in the elided setup by copying from the closest existing controller-integration test in `app/src/test/java/net/ft8vc/app/controllers/`. Do NOT invent new fake constructors.

- [ ] **Step 3: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.EarlyDecodeLateTxIntegrationTest"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/net/ft8vc/app/controllers/EarlyDecodeLateTxIntegrationTest.kt
git commit -m "test(app): EarlyDecodeLateTxIntegrationTest — hunt-and-pounce loop end-to-end"
```

---

## Task 12: Golden-trace parity assertions (toggle ON + OFF)

**Files:**
- Create: `app/src/test/java/net/ft8vc/app/controllers/GoldenTraceEarlyDecodeParityTest.kt`

**Interfaces:**
- Consumes: existing FOUND-06 golden-trace harness, the post-Phase-9 baseline trace file
- Produces: byte-identical-with-toggle-OFF assertion + additive-tolerance-with-toggle-ON assertion (zero key collisions over 100 simulated slots)

- [ ] **Step 1: Prerequisite check**

The FOUND-07 baseline trace must be captured and committed under `.planning/field-sessions/late-tx-<date>/` (or wherever the late-TX phase's field session lives). If the baseline is missing, pause and surface the blocker. The spec calls out this prerequisite explicitly.

- [ ] **Step 2: Locate the golden-trace replay harness**

Find the FOUND-06 harness — it should already exist from Phase 0 and have been re-used by the late-TX phase. Mirror that test file's structure exactly.

- [ ] **Step 3: Write the test (two methods)**

```kotlin
package net.ft8vc.app.controllers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GoldenTraceEarlyDecodeParityTest {

    @Test
    fun `byte-identical trace when earlyDecodeEnabled is false`() = runTest {
        // 1. Load post-Phase-9 baseline trace
        // 2. Replay with earlyDecodeEnabled = false
        // 3. Assert: produced state-transition output equals baseline byte-for-byte
    }

    @Test
    fun `additive tolerance with earlyDecodeEnabled true zero key collisions over 100 slots`() = runTest {
        // 1. Load post-Phase-9 baseline trace
        // 2. Replay with earlyDecodeEnabled = true
        // 3. For each slot in the trace: collect every _decodesOut emission
        // 4. Build a set of (slotStart, DecodeRowKey.stableId) tuples
        // 5. Assert: no duplicate tuples across the 100-slot trace
        // 6. Assert: every baseline decode appears in the captured emissions
        //    (EARLY appears earlier, FULL appears at boundary, but each key
        //    appears at most once across both passes)
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.GoldenTraceEarlyDecodeParityTest"`
Expected: 2 passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/net/ft8vc/app/controllers/GoldenTraceEarlyDecodeParityTest.kt
git commit -m "test(app): golden-trace parity for early-decode toggle ON and OFF"
```

---

## Final Steps (manual, not coding tasks)

After all coding tasks pass and CI is green, the implementer hands the build to the operator for the on-air field session under `.planning/field-sessions/early-decode-<date>/`:

- At least one QSO completed where the auto-answer was triggered by an EARLY-pass decode (operator confirms by inspecting trace timestamps — auto-answer-armed timestamp falls between `slotStart + 12.0 s` and `slotStart + 15.0 s`).
- Toggle OFF for one full QSO cycle, confirming v1.0 single-pass timing end-to-end.
- Late-TX still works under combined load: toggle ON for both early-decode and late-TX, at least one QSO completed via the hunt-and-pounce loop.
- Operate-tab recompose count over one full slot cycle does not exceed the Phase 0 FOUND-08 baseline by more than 5%.
- `DecodeSlice.lastDecodePassDurationMs` values from the session log reviewed; sustained values above a sanity ceiling are flagged.

Session is committed to `.planning/field-sessions/early-decode-<date>/` and referenced in the PR before promotion to `main`.

---

## Self-Review Notes

- **Spec coverage:** Every locked decision (1–8) is mapped to at least one task. Specifically: D1 (feed QSO machine) → Task 6 + Task 11; D2 (dedup) → Tasks 3, 6; D3 (single trigger at t=12s) → Task 7; D4 (ON by default + toggle) → Tasks 5, 7, 8; D5 (zero UI delta) → Tasks 4, 9; D6 (AP not in early) → enforced by spec — no AP code change in this plan, intentional; D7 (skip if <80% samples) → Task 7; D8 (no console logging) → Global Constraints + Task 6.
- **Acceptance-criteria coverage:** Each acceptance criterion in the spec is covered by a test in tasks 2 (snapshot), 3 (key stability), 5 (settings), 6 (dedup + duration + no logging), 7 (scheduler timing + skip + cancel + toggle-off), 9 (pixel parity), 10 (PTT safety), 11 (combined integration), 12 (golden trace). The "no `Log.i` / `Log.d` / `println`" criterion is enforced by the Global Constraints + a grep check the implementer can run before the Task 6 commit.
- **Field-session criteria** are explicitly manual and called out in the Final Steps section.
- **Type consistency:** `DecodePassSource` (sealed interface), `DecodeRow.passSource` (field), `DecodeRowKey.stableId(slotStart, freqHz, message)` (function), `DecodeSlice.lastDecodePassDurationMs` + `lastDecodePassSource` (fields), `SettingsSlice.earlyDecodeEnabled` (field), `DecodeController.setEarlyDecodeEnabled(enabled)` and `decodeSlot(samples, slotStartEpochMs, source)` (methods) — names appear identically across the tasks that produce and consume them.
- **Decomposition risk:** Tasks 6 and 7 both touch DecodeController. Order matters — Task 6 lands the dedup machinery + duration + signature change, then Task 7 adds the scheduler that calls into it. Each task ends with a passing test set independently.
