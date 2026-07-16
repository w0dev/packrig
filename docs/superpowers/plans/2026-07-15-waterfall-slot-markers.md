# Waterfall Slot-Boundary Markers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Draw a WSJT-X-style horizontal marker with a UTC `HH:MM:SS` label across the spectrum waterfall at every 15-second FT8 slot boundary.

**Architecture:** A new pure class `SlotMarkerTracker` maintains marker rows for the scrolling waterfall (shift up per column, record on slot-start change, drop off the top). `DecodeController` passes its injected `clock()` timestamp with each spectrum column; `Waterfall` feeds the tracker under its existing lock and exposes the marks; `WaterfallPanel` draws each mark as a translucent hairline plus a monospace time label in the same Compose Canvas layer as the red TX marker.

**Tech Stack:** Kotlin, Jetpack Compose Canvas + `TextMeasurer`, `java.time` (minSdk 28 OK), JUnit4 (plain `org.junit.Assert`, no Robolectric).

**Spec:** `docs/superpowers/specs/2026-07-15-waterfall-slot-markers-design.md`

## Global Constraints

- Display-only feature: RX/TX/CAT/QSO behavior must not change; the decode path only gains a pass-through timestamp on the spectrum sink.
- `SlotMarkerTracker` must have no Android imports (JVM-testable; `Waterfall` itself constructs a Bitmap and cannot be instantiated in unit tests).
- Marker time source is `DecodeController`'s injected `clock()` — the same clock `SlotCollector` uses — not a new time source.
- Kotlin official style, 4-space indent, no new dependencies.
- Land on `unstable`.

---

### Task 1: `SlotMarkerTracker` (pure logic, TDD)

**Files:**
- Create: `app/src/main/java/net/packrig/app/ui/SlotMarkerTracker.kt`
- Test: `app/src/test/java/net/packrig/app/ui/SlotMarkerTrackerTest.kt` (create)

**Interfaces:**
- Consumes: `net.packrig.core.SlotTiming.slotStart(epochMillisUtc: Long): Long` (existing; app module already depends on core).
- Produces (used by Task 2 and Task 3):
  - `class SlotMarkerTracker(history: Int)`
  - `data class SlotMarkerTracker.SlotMark(row: Int, slotStartEpochMillis: Long)`
  - `fun onColumn(epochMillisUtc: Long)`
  - `fun markers(): List<SlotMark>`
  - `fun clear()`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/packrig/app/ui/SlotMarkerTrackerTest.kt`. FT8 slots start at epoch-ms multiples of 15 000, so small literal times keep the arithmetic readable.

```kotlin
package net.packrig.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotMarkerTrackerTest {
    @Test fun boundary_crossing_records_mark_at_bottom_with_new_slot_start() {
        val tracker = SlotMarkerTracker(history = 320)
        tracker.onColumn(14_000L)          // slot [0, 15000)
        tracker.onColumn(15_100L)          // first column of slot [15000, 30000)
        val marks = tracker.markers()
        assertEquals(1, marks.size)
        assertEquals(319, marks[0].row)
        assertEquals(15_000L, marks[0].slotStartEpochMillis)
    }

    @Test fun non_boundary_columns_shift_marks_up_and_add_nothing() {
        val tracker = SlotMarkerTracker(history = 320)
        tracker.onColumn(14_000L)
        tracker.onColumn(15_100L)          // mark at row 319
        tracker.onColumn(15_200L)
        tracker.onColumn(15_300L)
        val marks = tracker.markers()
        assertEquals(1, marks.size)
        assertEquals(317, marks[0].row)
    }

    @Test fun marks_scrolled_past_the_top_are_dropped() {
        val tracker = SlotMarkerTracker(history = 3)
        tracker.onColumn(14_000L)
        tracker.onColumn(15_100L)          // mark at row 2
        tracker.onColumn(15_200L)          // row 1
        tracker.onColumn(15_300L)          // row 0
        tracker.onColumn(15_400L)          // off the top
        assertTrue(tracker.markers().isEmpty())
    }

    @Test fun first_column_ever_records_no_mark() {
        val tracker = SlotMarkerTracker(history = 320)
        tracker.onColumn(15_100L)
        assertTrue(tracker.markers().isEmpty())
    }

    @Test fun clear_empties_marks_and_next_column_records_none() {
        val tracker = SlotMarkerTracker(history = 320)
        tracker.onColumn(14_000L)
        tracker.onColumn(15_100L)
        tracker.clear()
        assertTrue(tracker.markers().isEmpty())
        tracker.onColumn(45_100L)          // first column after clear: no mark
        assertTrue(tracker.markers().isEmpty())
    }

    @Test fun consecutive_boundaries_each_get_their_own_mark() {
        val tracker = SlotMarkerTracker(history = 320)
        tracker.onColumn(14_000L)
        tracker.onColumn(15_100L)          // mark A
        tracker.onColumn(30_050L)          // mark B (slot [30000, 45000))
        val marks = tracker.markers()
        assertEquals(2, marks.size)
        assertEquals(318, marks[0].row)    // A shifted up once
        assertEquals(15_000L, marks[0].slotStartEpochMillis)
        assertEquals(319, marks[1].row)
        assertEquals(30_000L, marks[1].slotStartEpochMillis)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.packrig.app.ui.SlotMarkerTrackerTest"`
Expected: FAILED — compilation error, unresolved reference `SlotMarkerTracker`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/net/packrig/app/ui/SlotMarkerTracker.kt`:

```kotlin
package net.packrig.app.ui

import net.packrig.core.SlotTiming

/**
 * Tracks 15-second slot-boundary marker rows for a scrolling waterfall of
 * [history] rows. Each incoming column shifts the image (and therefore every
 * mark) up one row; the first column of a new UTC slot records a mark on the
 * bottom row carrying the new slot's start time.
 *
 * Pure logic with no Android imports so it stays JVM-testable: [Waterfall]
 * owns one but constructs a Bitmap and cannot be instantiated in unit tests.
 * Not thread-safe; [Waterfall] calls it under its own lock.
 */
class SlotMarkerTracker(private val history: Int) {

    data class SlotMark(val row: Int, val slotStartEpochMillis: Long)

    private val marks = ArrayDeque<SlotMark>()
    private var lastSlotStart: Long? = null

    /** A new column arrived at [epochMillisUtc]; advance the marks one row. */
    fun onColumn(epochMillisUtc: Long) {
        for (i in marks.indices) marks[i] = marks[i].copy(row = marks[i].row - 1)
        while (marks.isNotEmpty() && marks.first().row < 0) marks.removeFirst()

        val slotStart = SlotTiming.slotStart(epochMillisUtc)
        if (lastSlotStart != null && slotStart != lastSlotStart) {
            marks.addLast(SlotMark(history - 1, slotStart))
        }
        lastSlotStart = slotStart
    }

    fun markers(): List<SlotMark> = marks.toList()

    fun clear() {
        marks.clear()
        lastSlotStart = null
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.packrig.app.ui.SlotMarkerTrackerTest"`
Expected: BUILD SUCCESSFUL, 6 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/packrig/app/ui/SlotMarkerTracker.kt \
        app/src/test/java/net/packrig/app/ui/SlotMarkerTrackerTest.kt
git commit -m "feat(spectrum): add SlotMarkerTracker for waterfall slot boundaries

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Timestamp plumbing through `DecodeController` and `Waterfall`

**Files:**
- Modify: `app/src/main/java/net/packrig/app/controllers/DecodeController.kt:90` (sink type) and `:207` (sink call)
- Modify: `app/src/main/java/net/packrig/app/ui/Waterfall.kt` (`addColumn` signature, tracker ownership, `slotMarkers()`, `clear()`)

**Interfaces:**
- Consumes (from Task 1): `SlotMarkerTracker(history)`, `onColumn(epochMillisUtc: Long)`, `markers(): List<SlotMark>`, `clear()`.
- Produces (used by Task 3):
  - `Waterfall.addColumn(column: FloatArray, epochMillisUtc: Long)`
  - `Waterfall.slotMarkers(): List<SlotMarkerTracker.SlotMark>` — locked snapshot.
  - `DecodeController.spectrumSink: (FloatArray, Long) -> Unit`
- Note: `OperateViewModel.kt:178-179` wires `decodeController.spectrumSink = it::addColumn`; the method reference matches the new two-parameter type, so that file needs **no edit**.

- [ ] **Step 1: Change the sink type in `DecodeController`**

In `app/src/main/java/net/packrig/app/controllers/DecodeController.kt` line 90, replace:

```kotlin
    @Volatile var spectrumSink: (FloatArray) -> Unit = {}
```

with:

```kotlin
    /** Receives each spectrum column with the epoch-ms clock time it arrived. */
    @Volatile var spectrumSink: (FloatArray, Long) -> Unit = { _, _ -> }
```

and line 207, replace:

```kotlin
        spectrum.process(pcm) { column -> spectrumSink(column) }
```

with:

```kotlin
        spectrum.process(pcm) { column -> spectrumSink(column, clock()) }
```

- [ ] **Step 2: Feed the tracker in `Waterfall`**

In `app/src/main/java/net/packrig/app/ui/Waterfall.kt`:

Add a field next to the existing `scratch` declaration (after `private val scratch = FloatArray(bins)`):

```kotlin
    private val slotMarkerTracker = SlotMarkerTracker(history)
```

Change the `addColumn` signature and feed the tracker as the first statement inside the `synchronized(lock)` block:

```kotlin
    fun addColumn(column: FloatArray, epochMillisUtc: Long) {
        synchronized(lock) {
            slotMarkerTracker.onColumn(epochMillisUtc)
            val n = minOf(bins, column.size)
            val floorSample = estimateFloor(column, n, scratch)
```

(the rest of the method body is unchanged).

Add after `snapshot()`:

```kotlin
    /** Slot-boundary marks currently on screen (row 0 = oldest/top). */
    fun slotMarkers(): List<SlotMarkerTracker.SlotMark> =
        synchronized(lock) { slotMarkerTracker.markers() }
```

Extend `clear()`:

```kotlin
    fun clear() {
        synchronized(lock) {
            pixels.fill(0xFF000000.toInt())
            primed = false
            slotMarkerTracker.clear()
        }
    }
```

- [ ] **Step 3: Run the full app unit test suite (compile check + regressions)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no failures. (Nothing outside `OperateViewModel`'s method reference consumes `spectrumSink` or `addColumn` — verified by grep during planning — so a compile pass is the meaningful gate here.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/packrig/app/controllers/DecodeController.kt \
        app/src/main/java/net/packrig/app/ui/Waterfall.kt
git commit -m "feat(spectrum): thread column timestamps into the waterfall

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Render marks in `WaterfallPanel`

**Files:**
- Modify: `app/src/main/java/net/packrig/app/ui/operate/WaterfallPanel.kt`

**Interfaces:**
- Consumes (from Task 2): `vm.waterfall.slotMarkers(): List<SlotMarkerTracker.SlotMark>`, `vm.waterfall.history: Int` (existing public property, 320).
- Produces: nothing consumed downstream; visual output only.

- [ ] **Step 1: Add imports and the UTC formatter**

In `app/src/main/java/net/packrig/app/ui/operate/WaterfallPanel.kt` add imports:

```kotlin
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
```

and a top-level private value next to `FT8_SIGNAL_WIDTH_HZ`:

```kotlin
/** Slot marks are labeled with the slot's UTC start time, WSJT-X style. */
private val SLOT_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)
```

- [ ] **Step 2: Draw the marks**

In the `WaterfallPanel` composable, before the `Column(modifier = modifier)` line's `Canvas` (i.e., in the composable scope above the `Box`), create the measurer and label style:

```kotlin
    val textMeasurer = rememberTextMeasurer()
    val markColor = Color.White.copy(alpha = 0.55f)
    val markLabelStyle = MaterialTheme.typography.labelSmall.copy(
        fontFamily = FontFamily.Monospace,
        color = markColor,
    )
```

Inside the `Canvas` block, after `drawImage(...)` and before the `if (maxFreqHz <= 0) return@Canvas` line, insert:

```kotlin
                // 15-second slot boundaries, WSJT-X style: hairline across the
                // panel with the slot's UTC start time at the left edge.
                val history = vm.waterfall.history
                for (mark in vm.waterfall.slotMarkers()) {
                    val y = (mark.row + 0.5f) / history * size.height
                    drawLine(
                        color = markColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                    val label = SLOT_TIME_FORMAT.format(Instant.ofEpochMilli(mark.slotStartEpochMillis))
                    val layout = textMeasurer.measure(AnnotatedString(label), markLabelStyle)
                    val pad = 4.dp.toPx()
                    // Label sits below the line; flip above near the bottom edge.
                    val below = y + pad
                    val textY = if (below + layout.size.height > size.height) {
                        y - pad - layout.size.height
                    } else {
                        below
                    }
                    drawText(layout, topLeft = Offset(pad, textY))
                }
```

(`MaterialTheme`, `Color`, `FontFamily`, `Offset`, and `dp` are already imported in this file.)

- [ ] **Step 3: Build and run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, no test failures.

- [ ] **Step 4: Install on the field phone and verify by eye**

Run: `~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: `Success`.

Manual check (needs live capture): on the Spectrum tab, a translucent white horizontal line appears each time a 15-second slot begins, labeled with the slot's UTC `HH:MM:SS` at the left edge; lines scroll upward with the image; markers clear when the waterfall clears (e.g., dial change). This is the user's look-check — report and wait for their verdict before promoting anything.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/net/packrig/app/ui/operate/WaterfallPanel.kt
git commit -m "feat(spectrum): draw labeled 15s slot-boundary marks on the waterfall

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Verification

- Unit: `./gradlew :app:testDebugUnitTest` green (SlotMarkerTracker fully covered).
- Visual: debug install on the field phone shows labeled slot lines scrolling with the waterfall.
- Field gate (pre-promotion, FT-891 + Digirig): marks land where slots audibly/visibly begin (first column of each decode period), and the labels read correctly against band noise.
