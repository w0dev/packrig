# Waterfall Noise-Floor Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the spectrum waterfall estimate its noise floor from the median of each FFT column (instead of the 15th percentile) so band noise renders dark and the red TX marker / signal traces stand out.

**Architecture:** `Waterfall.addColumn()` in the app module currently computes an inline 15th-percentile floor that lands in the rig filter's silent bins and washes the display out green/orange. Extract the estimator into a pure companion function `Waterfall.estimateFloor()` (testable on the JVM — the class itself constructs an `android.graphics.Bitmap` and cannot be instantiated in unit tests) and change the percentile to the median. No other rendering constants change.

**Tech Stack:** Kotlin, JUnit4 (plain `org.junit.Assert`, no Robolectric), Gradle.

**Spec:** `docs/superpowers/specs/2026-07-15-waterfall-noise-floor-design.md`

## Global Constraints

- Display-only change: no RX/TX/CAT or decode-path behavior may change (v1.x behavior-parity rule).
- Keep EMA smoothing (α = 0.1), `floorOffsetDb` default, `rangeDb` (45 dB), and the `colorFor()` ramp exactly as they are.
- Preserve the zero-allocation pattern: `addColumn()` reuses the member `scratch` buffer; the extracted function must take the scratch buffer as a parameter, not allocate.
- Kotlin official style, 4-space indent, no new dependencies.
- Land on `unstable`.

---

### Task 1: Median floor estimator in `Waterfall`

**Files:**
- Modify: `app/src/main/java/net/packrig/app/ui/Waterfall.kt:38-65` (the `addColumn` body; add a `companion object`)
- Test: `app/src/test/java/net/packrig/app/ui/WaterfallFloorTest.kt` (create)

**Interfaces:**
- Consumes: nothing from other tasks (single-task plan).
- Produces: `Waterfall.estimateFloor(column: FloatArray, n: Int, scratch: FloatArray): Float` — companion function; sorts the first `n` values of `column` into `scratch` and returns the median. `addColumn()` calls it in place of its inline percentile code.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/net/packrig/app/ui/WaterfallFloorTest.kt`:

```kotlin
package net.packrig.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WaterfallFloorTest {
    // Field report 2026-07-15: the rig's RX filter leaves the bins above
    // ~2.9 kHz near-silent, so a low percentile measured the filter floor
    // instead of the band noise and the whole passband rendered green/orange.
    // The estimate must land in the in-band noise block, not the silent block.
    @Test fun floor_lands_in_band_noise_not_filter_floor() {
        val quiet = FloatArray(40) { -100f }   // silent filter-rolloff bins
        val noise = FloatArray(60) { -60f }    // in-band noise bins
        val column = quiet + noise
        val floor = Waterfall.estimateFloor(column, column.size, FloatArray(column.size))
        assertEquals(-60f, floor, 0.01f)
    }

    @Test fun floor_of_uniform_column_is_that_level() {
        val column = FloatArray(100) { -75f }
        val floor = Waterfall.estimateFloor(column, column.size, FloatArray(column.size))
        assertEquals(-75f, floor, 0.01f)
    }

    @Test fun floor_ignores_bins_beyond_n() {
        // Only the first n bins count; trailing garbage must not skew the sort.
        val column = FloatArray(100) { -70f } + FloatArray(20) { 0f }
        val floor = Waterfall.estimateFloor(column, 100, FloatArray(100))
        assertEquals(-70f, floor, 0.01f)
    }

    @Test fun floor_rides_above_strong_signals_minority() {
        // A crowded band: 20% of bins carry strong signals. The median must
        // still report the noise level, not the signal level.
        val noise = FloatArray(80) { -60f }
        val signals = FloatArray(20) { -30f }
        val column = noise + signals
        val floor = Waterfall.estimateFloor(column, column.size, FloatArray(column.size))
        assertEquals(-60f, floor, 0.01f)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.packrig.app.ui.WaterfallFloorTest"`
Expected: FAILED — compilation error, `unresolved reference: estimateFloor` (the companion function does not exist yet).

- [ ] **Step 3: Implement the estimator and wire it into `addColumn`**

In `app/src/main/java/net/packrig/app/ui/Waterfall.kt`, replace the inline percentile in `addColumn` (currently):

```kotlin
            // Robust noise-floor estimate: the 15th percentile of this column.
            // Using a percentile (not the min) keeps the floor stable when a few
            // bins are very quiet or a strong signal is present.
            val n = minOf(bins, column.size)
            System.arraycopy(column, 0, scratch, 0, n)
            java.util.Arrays.sort(scratch, 0, n)
            val floorSample = scratch[(n * 15) / 100]
```

with:

```kotlin
            val n = minOf(bins, column.size)
            val floorSample = estimateFloor(column, n, scratch)
```

and add a companion object at the bottom of the class (after `colorFor`):

```kotlin
    companion object {
        /**
         * Noise-floor estimate for one spectrum column: the median of the
         * first [n] bins. The rig's RX filter leaves a large block of bins
         * near-silent, so a low percentile measures the filter floor instead
         * of the band noise and washes the display out; the median lands in
         * the in-band noise block while still sitting below signal bins.
         *
         * Sorts into [scratch] (no allocation; callers pass a reusable buffer
         * of at least [n] floats).
         */
        fun estimateFloor(column: FloatArray, n: Int, scratch: FloatArray): Float {
            System.arraycopy(column, 0, scratch, 0, n)
            java.util.Arrays.sort(scratch, 0, n)
            return scratch[n / 2]
        }
    }
```

Do not touch `floorOffsetDb`, `rangeDb`, the EMA block, or `colorFor`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.packrig.app.ui.WaterfallFloorTest"`
Expected: BUILD SUCCESSFUL, 4 tests passed.

- [ ] **Step 5: Run the full app-module unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no failures (guards against anything else referencing the removed inline code).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/net/packrig/app/ui/Waterfall.kt \
        app/src/test/java/net/packrig/app/ui/WaterfallFloorTest.kt
git commit -m "fix(spectrum): estimate waterfall noise floor from column median

The 15th-percentile floor landed in the rig filter's silent bins, so
in-band noise rendered 30-40 dB hot (green/orange) and signals saturated
into the same red as the TX marker. The median lands in the in-band
noise block, restoring dark noise and visible signal traces.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Verification

- Unit: `./gradlew :app:testDebugUnitTest` green.
- Field (pre-promotion gate, reference FT-891 + Digirig): with the rig on a
  live band, the waterfall background must render black/dark blue, FT8 signal
  traces cyan→green→yellow, and the red TX marker clearly visible. Note in
  the field log whether the weakest traces remain visible on a crowded band.
