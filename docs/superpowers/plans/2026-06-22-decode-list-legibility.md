# Decode List Legibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the operate-screen decode list legible mid-QSO by inlining our own TX, adding a sticky column header, and color-coding rows by worked-before status.

**Architecture:** Pure additive UI/data layer. Two enums live in `core/` (`DecodeRowSource`, `WorkedBefore`) and feed two new optional fields on `DecodeRow`. `TxOrchestrator` exposes a new `SharedFlow<TxLogEvent>` that `OperateViewModel` collects and appends as synthetic rows. A `WorkedBeforeCache` (app-layer) queries a new `QsoDao.workedBands(call)` lazily and invalidates per-callsign when a QSO is logged. Renderer in `DecodeListPanel` branches on the new fields.

**Tech Stack:** Kotlin 2.3.21, Jetpack Compose (BOM 2026.05.01), AndroidX Room 2.7.2, Kotlin Coroutines 1.10.2, JUnit 4.

## Global Constraints

- `minSdk = 28`, `compileSdk = 36`, JVM target 17. Kotlin Official style, 4-space indent.
- No new top-level dependencies. Reuse existing Compose, Room, coroutines.
- `core/` module must stay Android-free (Kotlin stdlib only).
- Behavior parity with v1.0 on Yaesu FT-891 + Digirig Mobile is the bar. **No change to RX/TX/CAT timing or signaling.**
- All colors via `MaterialTheme.colorScheme`; no hardcoded hex outside `app/src/main/java/net/ft8vc/app/ui/theme/` (which currently holds `Ft8Amber` / `Ft8Green`).
- License gate unchanged: TX stays gated behind `AppRfState.READY` + license acknowledgment.
- Each task ends with a single atomic commit.

---

## File map

**Created**
- `core/src/main/java/net/ft8vc/core/DecodeRowSource.kt`
- `core/src/main/java/net/ft8vc/core/WorkedBefore.kt`
- `core/src/test/java/net/ft8vc/core/WorkedBeforeTest.kt`
- `app/src/main/java/net/ft8vc/app/ui/Ft8BandsLoose.kt` (or extend `Ft8Bands.kt`)
- `app/src/test/java/net/ft8vc/app/ui/Ft8BandsTest.kt`
- `app/src/main/java/net/ft8vc/app/controllers/WorkedBeforeCache.kt`
- `app/src/test/java/net/ft8vc/app/controllers/WorkedBeforeCacheTest.kt`

**Modified**
- `data/src/main/java/net/ft8vc/data/db/QsoDao.kt` — add `workedBands(call)`.
- `data/src/main/java/net/ft8vc/data/Logbook.kt` — expose `workedBands(call)` on the interface.
- `app/src/main/java/net/ft8vc/app/OperateUiState.kt` — extend `DecodeRow` with `source` and `workedBefore`.
- `app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt` — add `txLog: SharedFlow<TxLogEvent>` and emit on successful transmit.
- `app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorTest.kt` — assert emission.
- `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` — collect `txLog` → append TX rows; populate `workedBefore` on RX append; invalidate cache on QSO log.
- `app/src/main/java/net/ft8vc/app/ui/operate/DecodeListPanel.kt` — sticky column header, TX row background, worked-before message colors.

---

## Task 1: `core/` enums + classifier

**Files:**
- Create: `core/src/main/java/net/ft8vc/core/DecodeRowSource.kt`
- Create: `core/src/main/java/net/ft8vc/core/WorkedBefore.kt`
- Create: `core/src/test/java/net/ft8vc/core/WorkedBeforeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `sealed interface DecodeRowSource { object Rx; object Tx }`
  - `enum class WorkedBefore { Never, ThisBand, OtherBand }`
  - `fun WorkedBefore.Companion.classify(currentBand: String?, workedBands: Set<String>): WorkedBefore` — classifies given the band the rig is on and the set of bands a callsign has been worked on.

- [ ] **Step 1: Write the failing test**

File: `core/src/test/java/net/ft8vc/core/WorkedBeforeTest.kt`

```kotlin
package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkedBeforeTest {
    @Test fun never_when_no_bands_worked() {
        assertEquals(WorkedBefore.Never, WorkedBefore.classify("20m", emptySet()))
    }

    @Test fun this_band_when_current_band_in_set() {
        assertEquals(WorkedBefore.ThisBand, WorkedBefore.classify("20m", setOf("20m", "40m")))
    }

    @Test fun other_band_when_set_nonempty_but_current_missing() {
        assertEquals(WorkedBefore.OtherBand, WorkedBefore.classify("20m", setOf("40m", "15m")))
    }

    @Test fun never_when_current_band_null() {
        // No dial preset matched — treat as no information.
        assertEquals(WorkedBefore.Never, WorkedBefore.classify(null, setOf("20m")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.WorkedBeforeTest"`
Expected: FAIL — `WorkedBefore` and `DecodeRowSource` symbols unresolved.

- [ ] **Step 3: Write minimal implementation**

File: `core/src/main/java/net/ft8vc/core/DecodeRowSource.kt`

```kotlin
package net.ft8vc.core

/** Source of a row in the decode list. */
sealed interface DecodeRowSource {
    object Rx : DecodeRowSource
    object Tx : DecodeRowSource
}
```

File: `core/src/main/java/net/ft8vc/core/WorkedBefore.kt`

```kotlin
package net.ft8vc.core

/** Whether a callsign has been worked on the current band, another band, or never. */
enum class WorkedBefore {
    Never,
    ThisBand,
    OtherBand,
    ;

    companion object {
        fun classify(currentBand: String?, workedBands: Set<String>): WorkedBefore = when {
            currentBand == null -> Never
            currentBand in workedBands -> ThisBand
            workedBands.isNotEmpty() -> OtherBand
            else -> Never
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.WorkedBeforeTest"`
Expected: PASS — 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/ft8vc/core/DecodeRowSource.kt \
        core/src/main/java/net/ft8vc/core/WorkedBefore.kt \
        core/src/test/java/net/ft8vc/core/WorkedBeforeTest.kt
git commit -m "feat(core): DecodeRowSource + WorkedBefore enums and classifier"
```

---

## Task 2: Loose band-from-Hz helper

**Why:** `bandLabelForFreq(hz)` in `app/.../ui/Ft8Bands.kt` matches presets by *exact equality* (e.g. only `14_074_000L`). The rig can sit on any nearby frequency (14.075, 14.078, …) and we still want "20m" returned. Worked-before classification needs a tolerant variant.

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/ui/Ft8Bands.kt` (append a new function; do not change the existing one — its exact-match semantics are used elsewhere)
- Create: `app/src/test/java/net/ft8vc/app/ui/Ft8BandsTest.kt`

**Interfaces:**
- Consumes: `Ft8DialPresets` (existing).
- Produces: `fun bandLabelForFreqLoose(hz: Long?): String?` — returns the band of the *closest* preset within ±200 kHz, else null.

- [ ] **Step 1: Write the failing test**

File: `app/src/test/java/net/ft8vc/app/ui/Ft8BandsTest.kt`

```kotlin
package net.ft8vc.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ft8BandsTest {
    @Test fun loose_returns_band_for_exact_preset() {
        assertEquals("20m", bandLabelForFreqLoose(14_074_000L))
    }

    @Test fun loose_returns_band_for_nearby_freq() {
        assertEquals("20m", bandLabelForFreqLoose(14_078_500L))
        assertEquals("40m", bandLabelForFreqLoose(7_080_000L))
    }

    @Test fun loose_returns_null_for_far_freq() {
        // 12.000 MHz is > 200 kHz from any preset.
        assertNull(bandLabelForFreqLoose(12_000_000L))
    }

    @Test fun loose_returns_null_for_null_input() {
        assertNull(bandLabelForFreqLoose(null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.ui.Ft8BandsTest"`
Expected: FAIL — `bandLabelForFreqLoose` unresolved.

- [ ] **Step 3: Write minimal implementation**

Append to `app/src/main/java/net/ft8vc/app/ui/Ft8Bands.kt`:

```kotlin
/**
 * Returns the band label of the dial preset whose frequency is closest to [hz],
 * provided it's within 200 kHz. Use when you need a band classification for an
 * arbitrary rig frequency (worked-before lookups, etc.) — for exact preset
 * matching prefer [bandLabelForFreq].
 */
fun bandLabelForFreqLoose(hz: Long?): String? {
    if (hz == null) return null
    val best = Ft8DialPresets.minByOrNull { kotlin.math.abs(it.hz - hz) } ?: return null
    return if (kotlin.math.abs(best.hz - hz) <= 200_000L) best.label else null
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.ui.Ft8BandsTest"`
Expected: PASS — 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/Ft8Bands.kt \
        app/src/test/java/net/ft8vc/app/ui/Ft8BandsTest.kt
git commit -m "feat(app): bandLabelForFreqLoose — closest-preset band classifier"
```

---

## Task 3: `QsoDao.workedBands` query + `Logbook` exposure

**Files:**
- Modify: `data/src/main/java/net/ft8vc/data/db/QsoDao.kt`
- Modify: `data/src/main/java/net/ft8vc/data/Logbook.kt`

**Interfaces:**
- Consumes: existing `qso_contacts` schema (`dxCall`, `band`).
- Produces:
  - `suspend fun QsoDao.workedBands(call: String): List<String>` — distinct, non-null bands logged for this callsign.
  - `suspend fun Logbook.workedBands(call: String): Set<String>` — same data, deduped into a `Set<String>`.

- [ ] **Step 1: Add the DAO query**

Modify `data/src/main/java/net/ft8vc/data/db/QsoDao.kt` — add inside the `interface QsoDao` body, alongside the existing `@Query` methods:

```kotlin
    @Query("SELECT DISTINCT band FROM qso_contacts WHERE dxCall = :call AND band IS NOT NULL")
    suspend fun workedBands(call: String): List<String>
```

- [ ] **Step 2: Expose on the Logbook interface and implement**

Modify `data/src/main/java/net/ft8vc/data/Logbook.kt`:

In the `interface Logbook` body, add:

```kotlin
    suspend fun workedBands(call: String): Set<String>
```

In `class RoomLogbook`, add:

```kotlin
    override suspend fun workedBands(call: String): Set<String> =
        dao.workedBands(call).toSet()
```

- [ ] **Step 3: Compile the data module**

Run: `./gradlew :data:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Room annotation processor (KSP) generates the new query implementation. If it fails with "method missing override", recheck the `override` keyword on `RoomLogbook.workedBands`.

- [ ] **Step 4: Verify existing tests still pass**

Run: `./gradlew :data:testDebugUnitTest`
Expected: All AdifWriter/AdifValidator tests pass. (No new Room-instrumented test added here — `WorkedBeforeCache` in Task 4 exercises the contract via a fake.)

- [ ] **Step 5: Commit**

```bash
git add data/src/main/java/net/ft8vc/data/db/QsoDao.kt \
        data/src/main/java/net/ft8vc/data/Logbook.kt
git commit -m "feat(data): Logbook.workedBands(call) for worked-before lookup"
```

---

## Task 4: `WorkedBeforeCache` (app-layer)

**Files:**
- Create: `app/src/main/java/net/ft8vc/app/controllers/WorkedBeforeCache.kt`
- Create: `app/src/test/java/net/ft8vc/app/controllers/WorkedBeforeCacheTest.kt`

**Interfaces:**
- Consumes: `net.ft8vc.data.Logbook.workedBands(call)` (Task 3); `net.ft8vc.core.WorkedBefore.classify(...)` (Task 1).
- Produces:
  - `class WorkedBeforeCache(private val logbook: Logbook)`
  - `suspend fun classify(call: String, currentBand: String?): WorkedBefore` — lazy lookup with in-memory cache.
  - `fun invalidate(call: String)` — drop one cached entry (call after logging a new QSO with that callsign).
  - `fun clear()` — drop all (call from `onCleared`).

- [ ] **Step 1: Write the failing test**

File: `app/src/test/java/net/ft8vc/app/controllers/WorkedBeforeCacheTest.kt`

```kotlin
package net.ft8vc.app.controllers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import net.ft8vc.core.WorkedBefore
import net.ft8vc.data.Logbook
import net.ft8vc.data.adif.AdifExportContext
import net.ft8vc.data.model.QsoContact
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private class FakeLogbook(
    private val bandsByCall: MutableMap<String, Set<String>> = mutableMapOf(),
) : Logbook {
    val calls = AtomicInteger(0)
    override suspend fun log(contact: QsoContact): Long = 0L
    override fun contacts(): Flow<List<QsoContact>> = emptyFlow()
    override suspend fun exportAdif(context: AdifExportContext): String = ""
    override fun contactCount(): Flow<Int> = emptyFlow()
    override suspend fun clearAll() {}
    override suspend fun workedBands(call: String): Set<String> {
        calls.incrementAndGet()
        return bandsByCall[call] ?: emptySet()
    }
    fun set(call: String, bands: Set<String>) { bandsByCall[call] = bands }
}

class WorkedBeforeCacheTest {
    @Test fun classifies_never_other_this() = runTest {
        val log = FakeLogbook()
        log.set("K1ABC", setOf("40m", "15m"))
        log.set("W9XYZ", setOf("20m"))
        val cache = WorkedBeforeCache(log)

        assertEquals(WorkedBefore.Never, cache.classify("N0CALL", "20m"))
        assertEquals(WorkedBefore.OtherBand, cache.classify("K1ABC", "20m"))
        assertEquals(WorkedBefore.ThisBand, cache.classify("W9XYZ", "20m"))
    }

    @Test fun caches_lookup_per_call() = runTest {
        val log = FakeLogbook()
        log.set("K1ABC", setOf("20m"))
        val cache = WorkedBeforeCache(log)

        cache.classify("K1ABC", "20m")
        cache.classify("K1ABC", "20m")
        cache.classify("K1ABC", "40m")
        assertEquals(1, log.calls.get())
    }

    @Test fun invalidate_forces_refetch_for_one_call() = runTest {
        val log = FakeLogbook()
        log.set("K1ABC", setOf("20m"))
        log.set("W9XYZ", setOf("20m"))
        val cache = WorkedBeforeCache(log)

        cache.classify("K1ABC", "20m")
        cache.classify("W9XYZ", "20m")
        assertEquals(2, log.calls.get())

        cache.invalidate("K1ABC")
        cache.classify("K1ABC", "20m")
        cache.classify("W9XYZ", "20m")  // still cached
        assertEquals(3, log.calls.get())
    }

    @Test fun clear_drops_everything() = runTest {
        val log = FakeLogbook()
        log.set("K1ABC", setOf("20m"))
        val cache = WorkedBeforeCache(log)

        cache.classify("K1ABC", "20m")
        cache.clear()
        cache.classify("K1ABC", "20m")
        assertEquals(2, log.calls.get())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.WorkedBeforeCacheTest"`
Expected: FAIL — `WorkedBeforeCache` unresolved.

- [ ] **Step 3: Write minimal implementation**

File: `app/src/main/java/net/ft8vc/app/controllers/WorkedBeforeCache.kt`

```kotlin
package net.ft8vc.app.controllers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.ft8vc.core.WorkedBefore
import net.ft8vc.data.Logbook

/**
 * In-memory cache of `Logbook.workedBands(call)` results, keyed by callsign.
 *
 * Entries are populated lazily on the first [classify] call for a callsign and
 * stay until [invalidate] (after a new QSO with that call is logged) or
 * [clear] (ViewModel teardown).
 */
class WorkedBeforeCache(private val logbook: Logbook) {
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Set<String>>()

    suspend fun classify(call: String, currentBand: String?): WorkedBefore {
        val bands = mutex.withLock { cache[call] } ?: run {
            val fetched = logbook.workedBands(call)
            mutex.withLock { cache[call] = fetched }
            fetched
        }
        return WorkedBefore.classify(currentBand, bands)
    }

    suspend fun invalidate(call: String) {
        mutex.withLock { cache.remove(call) }
    }

    suspend fun clear() {
        mutex.withLock { cache.clear() }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.WorkedBeforeCacheTest"`
Expected: PASS — 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/controllers/WorkedBeforeCache.kt \
        app/src/test/java/net/ft8vc/app/controllers/WorkedBeforeCacheTest.kt
git commit -m "feat(app): WorkedBeforeCache — lazy per-call lookup with invalidation"
```

---

## Task 5: Extend `DecodeRow` with `source` + `workedBefore`

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt:17-31`

**Interfaces:**
- Consumes: `DecodeRowSource`, `WorkedBefore` (Task 1).
- Produces: extended `DecodeRow` data class with two new optional fields.

- [ ] **Step 1: Add the two fields with defaults**

Modify the existing `data class DecodeRow(...)` in `app/src/main/java/net/ft8vc/app/OperateUiState.kt`. Replace lines 17-31 with:

```kotlin
@Immutable
data class DecodeRow(
    /** Stable key for Compose LazyColumn: slotStart * 1000 + indexInSlot. */
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
)
```

- [ ] **Step 2: Verify the module still builds and existing tests pass**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all existing app tests pass (the defaults preserve current behavior for every existing call site).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateUiState.kt
git commit -m "feat(app): DecodeRow gains source + workedBefore (defaulted, additive)"
```

---

## Task 6: `TxOrchestrator.txLog` flow + emission

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt`
- Modify: `app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorTest.kt`

**Interfaces:**
- Consumes: existing `doTransmit` return value.
- Produces:
  - `data class TxLogEvent(val utcMillis: Long, val freqHz: Int, val message: String)`
  - `val TxOrchestrator.txLog: SharedFlow<TxLogEvent>` — emits once per successful transmit, with the UTC of the slot boundary the transmit ran in (or current clock if non-slot-aligned).

- [ ] **Step 1: Write the failing test**

Open `app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorTest.kt` and add a new `@Test` (use the existing fixture/factory helpers in that file as a template — the test class already wires fakes for `decoder`, `playback`, `rigSession`, etc.):

```kotlin
    @Test
    fun txLog_emits_on_successful_transmit() = runTest {
        val orchestrator = newOrchestratorReady()  // existing helper that returns READY-state orchestrator
        val collected = mutableListOf<TxLogEvent>()
        val job = launch { orchestrator.txLog.collect { collected += it } }

        val ok = orchestrator.transmit(message = "CQ K1ABC FN42", txFreqHz = 1500)
        runCurrent()

        assertTrue("transmit should succeed", ok)
        assertEquals(1, collected.size)
        assertEquals(1500, collected[0].freqHz)
        assertEquals("CQ K1ABC FN42", collected[0].message)
        job.cancel()
    }
```

(If `newOrchestratorReady()` isn't the existing helper name, read the top of `TxOrchestratorTest.kt` and use whatever the existing tests use to build a ready orchestrator; do not reinvent fakes.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.TxOrchestratorTest.txLog_emits_on_successful_transmit"`
Expected: FAIL — `txLog` and `TxLogEvent` unresolved.

- [ ] **Step 3: Add the flow + event**

In `app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt`:

Add to the top-level imports (alongside existing kotlinx.coroutines imports):

```kotlin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
```

Inside `class TxOrchestrator(...)`, alongside `_slice` (around line 101):

```kotlin
    private val _txLog = MutableSharedFlow<TxLogEvent>(
        replay = 0,
        extraBufferCapacity = 16,
    )
    /** Emits one event per successful transmit. UI layer collects to render synthetic decode rows. */
    val txLog: SharedFlow<TxLogEvent> = _txLog.asSharedFlow()
```

At the end of `doTransmit`, *just before* `captureControl.resumeAfterTx()` (around line 291), add:

```kotlin
        if (result) {
            _txLog.tryEmit(TxLogEvent(utcMillis = clock(), freqHz = txFreqHz, message = message))
        }
```

At the bottom of the file, after `enum class AppRfState`:

```kotlin
data class TxLogEvent(
    val utcMillis: Long,
    val freqHz: Int,
    val message: String,
)
```

- [ ] **Step 4: Run the new test and the full TxOrchestrator suite**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.TxOrchestratorTest"`
Expected: All existing TxOrchestrator tests still pass; new `txLog_emits_on_successful_transmit` passes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt \
        app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorTest.kt
git commit -m "feat(app): TxOrchestrator.txLog SharedFlow emits on successful transmit"
```

---

## Task 7: Wire `txLog` + `WorkedBeforeCache` into `OperateViewModel`

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`

**Interfaces:**
- Consumes: `TxOrchestrator.txLog` (Task 6); `WorkedBeforeCache` (Task 4); `bandLabelForFreqLoose` (Task 2); existing `decodeController.decodesOut`; existing logbook.
- Produces: TX rows appended to the decode list, RX rows annotated with `workedBefore`.

This is the highest-judgment task — the viewmodel is large and tightly woven. Read the surrounding code before each edit. Read `OperateViewModel.kt` lines around 113 (decode controller), 287-300 (existing decode collectors), 656 (capture start), and 712 (close) before starting.

- [ ] **Step 1: Construct the cache alongside other singletons**

Locate where `RoomLogbook` is constructed in `OperateViewModel` (search for `RoomLogbook(` or `Logbook`). Immediately after the logbook is created, construct the cache:

```kotlin
    private val workedBeforeCache = WorkedBeforeCache(logbook)
```

(Import `net.ft8vc.app.controllers.WorkedBeforeCache` at the top.)

- [ ] **Step 2: Collect the TX log into a synthetic DecodeRow**

In the `init { ... }` block that already collects `decodeController.slice` / `decodeController.decodesOut` (around lines 287-300), add another collector. Use `viewModelScope`. Insert this block adjacent to the existing decode collectors:

```kotlin
        viewModelScope.launch {
            txOrchestrator.txLog.collect { ev ->
                val timeUtc = java.time.Instant.ofEpochMilli(ev.utcMillis)
                    .atZone(java.time.ZoneOffset.UTC)
                    .toLocalTime()
                    .withNano(0)
                    .toString()  // "HH:mm:ss"
                val row = DecodeRow(
                    id = ev.utcMillis,  // unique enough; TX rows are rare per slot
                    timeUtc = timeUtc,
                    snr = 0,
                    dtSeconds = 0f,
                    freqHz = ev.freqHz,
                    message = ev.message,
                    isCq = false,
                    isToMe = false,
                    distanceKm = null,
                    source = net.ft8vc.core.DecodeRowSource.Tx,
                )
                decodeController.appendSyntheticRow(row)
            }
        }
```

This calls a method that does not yet exist on `decodeController`. The next step adds it.

- [ ] **Step 3: Add `appendSyntheticRow` to `DecodeController`**

Locate `DecodeController` (search: `class DecodeController`). It owns the `decodes` list that flows out via `decodesOut`/`slice`. Add a public method that pushes a single row through the same update path the decoder uses, while honoring `MAX_DECODE_ROWS`. Match the existing append/eviction pattern in that file — do not rewrite it.

Sketch (adapt to actual field names found in `DecodeController`):

```kotlin
    /** Append a row not produced by the native decoder (e.g. synthesized TX row). */
    fun appendSyntheticRow(row: DecodeRow) {
        // Use the same MutableStateFlow update + MAX_DECODE_ROWS eviction the
        // decoder batch path uses, but with this single row.
        // … keep ordering by timeUtc ascending if the existing list is ordered;
        // otherwise just append.
    }
```

If `DecodeController.decodes` is a private `MutableStateFlow<ImmutableList<DecodeRow>>`, the body is:

```kotlin
        _decodes.update { current ->
            val combined = (current + row)
                .sortedBy { it.timeUtc }
                .takeLast(OperateUiState.MAX_DECODE_ROWS)
            combined.toImmutableList()
        }
```

(Use whatever the existing path uses — do not introduce a second sorting strategy if the existing one is index-based.)

- [ ] **Step 4: Populate `workedBefore` on RX rows**

In the existing `decodeController.decodesOut.collect { batch -> qsoSession.onDecodeBatch(...) }` collector (around line 299), enrich each row before they're stored. Easiest path: do the classification inside `DecodeController.onBatch` (or wherever rows are constructed from native decodes) by injecting a `(String, String?) -> WorkedBefore` callback at construction time. Wire it from `OperateViewModel`:

```kotlin
    private val decodeController = DecodeController(
        decoder = Ft8Native,
        // ...existing params...
        workedBeforeLookup = { call ->
            // Synchronous wrapper around the suspending cache; safe because the
            // cache hits an in-memory map after the first miss per call.
            runBlocking { workedBeforeCache.classify(call, currentBandLabel()) }
        },
    )

    private fun currentBandLabel(): String? =
        net.ft8vc.app.ui.bandLabelForFreqLoose(state.value.rigFreqHz)
```

In `DecodeController`, accept the callback and use it to fill `workedBefore` when constructing each `DecodeRow` from a decode (the sender callsign is parseable from `message` via the existing `QsoMessages` helper).

**Avoid `runBlocking` on the decode hot path if it shows up in profiling.** A better pattern is to do classification asynchronously *after* the row is appended (emit row with `Never`, then patch it). For v1, `runBlocking` is acceptable because:
- First lookup per call hits the DB once (suspending Room query, but the cache fronts it).
- Every subsequent lookup is an in-memory map read.
- Decode batches arrive at 15-second intervals.

If profiling shows a stall, replace with `viewModelScope.launch` + a follow-up `copy(workedBefore = ...)` patch.

- [ ] **Step 5: Invalidate cache when a QSO is logged**

Find where completed QSOs are written to the logbook (search: `logbook.log(`). Immediately after the successful log call, add:

```kotlin
            viewModelScope.launch { workedBeforeCache.invalidate(contact.dxCall) }
```

- [ ] **Step 6: Clear cache on `onCleared`**

In `OperateViewModel.onCleared()` (around line 712 area where `decodeController.close()` is called), add:

```kotlin
        viewModelScope.launch { workedBeforeCache.clear() }
```

(If `onCleared` is non-suspending and `viewModelScope` is being cancelled, prefer a `runBlocking { workedBeforeCache.clear() }` — clearing a `mutableMapOf` is cheap.)

- [ ] **Step 7: Build and run existing test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: All existing tests pass; no decode-list regressions. If `DecodeController` test fixtures need a `workedBeforeLookup` arg, supply `{ WorkedBefore.Never }` in those fixtures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateViewModel.kt \
        app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt \
        app/src/test/java/net/ft8vc/app/controllers/DecodeControllerTest.kt
git commit -m "feat(app): wire TX-log rows and worked-before classification into decode list"
```

---

## Task 8: `DecodeListPanel` — header strip, TX row visual, worked-before colors

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/DecodeListPanel.kt`

**Interfaces:**
- Consumes: extended `DecodeRow` (Task 5).
- Produces: visual changes only — no API change.

- [ ] **Step 1: Add the sticky column header**

In `DecodeListPanel.kt`, inside the `Column { ... }` that holds the filter row + list (starting around line 87), insert a header strip *between* the filter row (lines 88-138) and the `if (visibleDecodes.isEmpty())` block (around line 147).

```kotlin
            if (visibleDecodes.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DecodeHeaderCell("UTC", widthChars = 6)      // "HH:MM:SS" trims display elsewhere
                    DecodeHeaderCell("SNR", widthChars = 3)
                    DecodeHeaderCell("DIST", widthChars = 4)
                    DecodeHeaderCell("Hz", widthChars = 4)
                    Text(
                        text = "MSG",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
```

And add a helper inside the file (below `CompactFilterChip`):

```kotlin
@Composable
private fun DecodeHeaderCell(label: String, widthChars: Int) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
```

Note: `widthChars` is unused for now — kept in the signature so a future tightening (Modifier.widthIn over the monospace cap-width) can be added without restructuring callers.

- [ ] **Step 2: Branch `DecodeRowItem` on `source = Tx` for the background tint**

Replace the existing `Row` modifier chain in `DecodeRowItem` (around line 242) so that TX rows get a muted amber background. The full updated body of `DecodeRowItem`:

```kotlin
@Composable
private fun DecodeRowItem(
    row: DecodeRow,
    qsoDx: String?,
    qsoActive: Boolean,
    onClick: (() -> Unit)?,
) {
    val isTx = row.source is net.ft8vc.core.DecodeRowSource.Tx
    val isPartner = qsoDx != null && row.message.contains(qsoDx)
    val dimmed = qsoActive && !row.isCq && !isPartner && !isTx
    val textColor = when {
        isTx -> MaterialTheme.colorScheme.onSurface
        row.isCq -> Ft8Green
        row.isToMe && !qsoActive -> Ft8Amber
        isPartner -> MaterialTheme.colorScheme.primary
        dimmed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        row.workedBefore == net.ft8vc.core.WorkedBefore.ThisBand ->
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        row.workedBefore == net.ft8vc.core.WorkedBefore.OtherBand ->
            MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val prefix = if (isTx) "" else DecodePrefix.prefixFor(
        message = row.message,
        isCq = row.isCq,
        isToMe = row.isToMe,
        qsoActive = qsoActive,
        qsoDx = qsoDx,
    )
    val rowBackground = if (isTx) Ft8Amber.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background(rowBackground)
            .then(if (onClick != null && !isTx) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.timeUtc,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (isTx) "   " else "%+3d".format(row.snr),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (isTx) "    " else DecodeDistance.label(row.distanceKm),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "%4d".format(row.freqHz),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "$prefix${row.message}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isPartner || isTx) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}
```

- [ ] **Step 3: TX rows are never filtered out**

In `MonitorDecodeFilter.visibleForDisplay(...)` (search the project for that function), ensure that TX rows bypass the filter. The caller of `visibleForDisplay` is the `decodes.filter { row -> ... }` block at line 60. Two options:

a. Push the bypass into the call site:

```kotlin
    val visibleDecodes = decodes.filter { row ->
        row.source is net.ft8vc.core.DecodeRowSource.Tx ||
        MonitorDecodeFilter.visibleForDisplay(
            message = row.message,
            isCq = row.isCq,
            myCall = myCall,
            freqHz = row.freqHz,
            txToneHz = txToneHz,
            viewMode = decodeViewMode,
            cq73OnlyFilter = cq73OnlyFilter,
            qsoDx = qsoDx,
            qsoActive = qsoActive,
        )
    }
```

Use option (a) — keeps `MonitorDecodeFilter` agnostic of row sources.

- [ ] **Step 4: Build, run all tests, build the debug APK**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/ui/operate/DecodeListPanel.kt
git commit -m "feat(ui): decode list — sticky header, TX row tint, worked-before colors"
```

---

## Task 9: Manual on-rig regression

**Files:** none modified. This is the milestone behavior-parity bar; not optional.

- [ ] **Step 1: Install the debug APK on the test phone**

Run: `./gradlew :app:installDebug`
Expected: APK installs on the connected device.

- [ ] **Step 2: Connect FT-891 + Digirig over USB-C OTG, grant permissions**

- [ ] **Step 3: Run a 5-QSO session, watching for:**

- TX rows appear in the decode list at the slots they were sent, with the muted amber background.
- The column header strip stays put while scrolling decodes.
- A station previously logged on the current band shows a dimmed message; a station only logged on another band shows a tertiary-colored message; never-worked stations look default.
- CQ stations still render green, to-me amber, partner blue — semantic colors win over worked-before.
- RX / TX / CAT behavior unchanged: decodes arrive at the same cadence, PTT keys cleanly, no stuck PTT, decode count and ADIF export unaffected.

- [ ] **Step 4: Record results**

Append a short note to the bottom of `docs/superpowers/plans/2026-06-22-decode-list-legibility.md` under a `## Verification log` section:
- Date, callsigns/grids of partners, band, observed colors, any regressions.

- [ ] **Step 5: Commit verification log**

```bash
git add docs/superpowers/plans/2026-06-22-decode-list-legibility.md
git commit -m "docs(plan): on-rig verification log for decode list legibility"
```

---

## Self-review

**Spec coverage**

- Inline own-TX rows ............ Tasks 5, 6, 7 (model + flow + wire), Task 8 (visual)
- Sticky column header strip .... Task 8
- Worked-before three-tier ...... Tasks 1, 2, 3, 4 (model + helper + DAO + cache), Task 7 (wire), Task 8 (color)
- Cache invalidation ............ Task 7 step 5
- TX row not filtered ........... Task 8 step 3
- Behavior parity verification .. Task 9

No gaps.

**Type consistency**

- `DecodeRowSource.Rx` / `.Tx` referenced consistently across Tasks 1, 5, 7, 8.
- `WorkedBefore.{Never, ThisBand, OtherBand}` consistent across Tasks 1, 4, 8.
- `TxLogEvent(utcMillis, freqHz, message)` matches between Task 6 (definition + emission) and Task 7 (collection).
- `WorkedBeforeCache.classify(call, currentBand)` signature consistent between Task 4 (definition) and Task 7 (call site).
- `bandLabelForFreqLoose(hz: Long?)` consistent between Task 2 (definition) and Task 7 (call site via `currentBandLabel`).
- `Logbook.workedBands(call): Set<String>` consistent between Task 3 (interface) and Task 4 (fake + cache).

**Placeholder scan**

- Task 7 step 3 has guidance ("adapt to actual field names found in `DecodeController`") rather than a verbatim diff. This is intentional — `DecodeController` was not read end-to-end while drafting and its append/eviction pattern is the source of truth for ordering. The implementer must read the file and match it. A sketch is provided alongside the most likely shape.
- Task 7 step 4 similarly leaves the exact integration into `DecodeController`'s row construction to the implementer because the row-construction site wasn't audited. The contract is fully specified (callback type, when to call it).

These are acknowledged uncertainties rather than placeholders — the contract is concrete, the in-file mechanics need a quick read.

---

Plan complete and saved to `docs/superpowers/plans/2026-06-22-decode-list-legibility.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
