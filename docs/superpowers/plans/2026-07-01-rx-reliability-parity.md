# RX Reliability Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Callsign hash table persists across decode slots with age-based eviction, the decode/display/TX-tone passband extends to 4000 Hz, and a status chip warns when the phone clock drifts ≥1 s from FT8 band time.

**Architecture:** Three independent slices. (1) JNI: drop the per-call `hashtable_init`, port upstream ft8_lib's age-based `hashtable_cleanup`, add a probe cap and a test-only clear hook surfaced through `Ft8DecoderApi`. (2) Constants: `cfg.f_max`/`kMaxCandidates` in the JNI, `SpectrumProcessor` default, and the TX-tone coercion ceiling. (3) A pure core `ClockOffsetEstimator` fed by `DecodeController`'s full-pass decodes, surfaced via `DecodeSlice` → `OperateUiState` → an `OperateStatusBar` chip.

**Tech Stack:** C++ (JNI, ft8_lib), Kotlin 2.3.21, JUnit 4, AndroidX instrumented tests, Jetpack Compose (one chip).

**Spec:** `docs/superpowers/specs/2026-07-01-rx-reliability-parity-design.md`

## Global Constraints

- All three changes are unconditional — no settings, no toggles.
- RX-only: TX/CAT code paths untouched. Allowed modules: `ft8-native/`, `audio/` (SpectrumProcessor constant only), `core/`, `app/` (display/plumbing only). `rig/` untouched.
- Hash eviction age: `kHashMaxAgeSlots = 40` (≈10 min). The add loop must be probe-capped — a full table skips the add, never spins.
- Passband values: decode `f_max = 4000`, `kMaxCandidates = 180`, SpectrumProcessor `maxFreqHz = 4000`, TX-tone coercion `300..4000`. `SnrEstimator`'s 200–3000 noise band intentionally unchanged.
- Estimator constants: `WINDOW_SLOTS = 4`, `MIN_SAMPLES = 4`, `NOMINAL_DT_S = 0.5f`, `WARN_S = 1.0f`, `SEVERE_S = 2.0f`. Sign convention: fast phone clock → positive offset.
- Chip copy exact: text `Clock %+.1fs` formatted with `Locale.US` (e.g. `Clock +1.4s`); tooltip "Phone clock differs from FT8 band time — fix in Android date & time settings". Amber at `|offset| ≥ 1.0`, red at `≥ 2.0`, hidden below.
- Unit test commands: `./gradlew :core:testDebugUnitTest`, `./gradlew :app:testDebugUnitTest`. Instrumented: `./gradlew :ft8-native:connectedDebugAndroidTest` — if no device is attached, verify compilation with `./gradlew :ft8-native:assembleDebugAndroidTest`, note the deferred run, and execute before promotion.
- Commit style: conventional commits ending with the trailer line `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- The working tree may contain a pre-existing uncommitted `app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorTest.kt` — never stage, commit, or modify it; always `git add` explicit paths.

---

### Task 1: Hash table persistence, eviction, probe cap, clear hook

**Files:**
- Modify: `ft8-native/src/main/cpp/ft8_jni.cpp` (hashtable block ~lines 39-89, `nativeDecode` ~line 195)
- Modify: `ft8-native/src/main/java/net/ft8vc/ft8native/Ft8Native.kt`
- Modify: `ft8-native/src/main/java/net/ft8vc/ft8native/Ft8DecoderApi.kt`

**Interfaces:**
- Consumes: existing `g_callsignHashtable`, `g_decodeMutex`, `hashtable_init`.
- Produces (used by Task 2): `Ft8DecoderApi.clearCallsignTable()` (default no-op; `Ft8Native` overrides with the JNI call). Native: table persists across `nativeDecode` calls; `hashtable_cleanup(kHashMaxAgeSlots)` runs at the start of each decode.

- [ ] **Step 1: Port hashtable_cleanup and add the probe cap**

In `ft8-native/src/main/cpp/ft8_jni.cpp`, after `hashtable_init()` (line ~50), add:

```cpp
// Entries not re-heard within this many decode passes (~15 s each) age out.
static const uint8_t kHashMaxAgeSlots = 40;

// Ported from ft8_lib demo/decode_ft8.c: age lives in bits 24-31 of the stored
// hash; survivors age by one per call, entries older than max_age are freed.
static void hashtable_cleanup(uint8_t max_age) {
    for (int idx = 0; idx < CALLSIGN_HASHTABLE_SIZE; ++idx) {
        if (g_callsignHashtable[idx].callsign[0] != '\0') {
            uint8_t age = (uint8_t)(g_callsignHashtable[idx].hash >> 24);
            if (age > max_age) {
                g_callsignHashtable[idx].callsign[0] = '\0';
                g_callsignHashtable[idx].hash = 0;
                g_callsignHashtableSize--;
            } else {
                g_callsignHashtable[idx].hash =
                    (((uint32_t)age + 1u) << 24) | (g_callsignHashtable[idx].hash & 0x3FFFFFu);
            }
        }
    }
}
```

Replace the body of `hashtable_add` with a probe-capped version (the ONLY changes are the `probes` counter and the bail-out line — keep everything else identical):

```cpp
static void hashtable_add(const char* callsign, uint32_t hash) {
    uint16_t hash10 = (hash >> 12) & 0x3FFu;
    int idx = (hash10 * 23) % CALLSIGN_HASHTABLE_SIZE;
    int probes = 0;
    while (g_callsignHashtable[idx].callsign[0] != '\0') {
        if (((g_callsignHashtable[idx].hash & 0x3FFFFFu) == hash) &&
            (0 == std::strcmp(g_callsignHashtable[idx].callsign, callsign))) {
            g_callsignHashtable[idx].hash &= 0x3FFFFFu;  // known call re-heard: reset age
            return;
        }
        // Full table: skip the add rather than probing forever (upstream lacks
        // this guard; with persistence a spin here would ANR the decode thread).
        if (++probes >= CALLSIGN_HASHTABLE_SIZE) return;
        idx = (idx + 1) % CALLSIGN_HASHTABLE_SIZE;
    }
    g_callsignHashtableSize++;
    std::strncpy(g_callsignHashtable[idx].callsign, callsign, 11);
    g_callsignHashtable[idx].callsign[11] = '\0';
    g_callsignHashtable[idx].hash = hash;
}
```

- [ ] **Step 2: Persist across decodes; add the clear hook**

In `Java_net_ft8vc_ft8vc..._nativeDecode` (exact symbol `Java_net_ft8vc_ft8native_Ft8Native_nativeDecode`), replace the line `hashtable_init();` with:

```cpp
    hashtable_cleanup(kHashMaxAgeSlots);
```

At the bottom of the file (after `nativeEncode`), add:

```cpp
extern "C" JNIEXPORT void JNICALL
Java_net_ft8vc_ft8native_Ft8Native_nativeClearCallsignTable(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> guard(g_decodeMutex);
    hashtable_init();
}
```

(`hashtable_init` stays — it is now the clear hook's implementation and the static zero-init documentational anchor.)

- [ ] **Step 3: Kotlin surface**

In `ft8-native/src/main/java/net/ft8vc/ft8native/Ft8DecoderApi.kt`, add to the interface:

```kotlin
    /** Test hook: clears the persistent callsign hash table. Production never calls it. */
    fun clearCallsignTable() {}
```

In `ft8-native/src/main/java/net/ft8vc/ft8native/Ft8Native.kt`, add the override (near `decode`) and the external declaration (with the others):

```kotlin
    override fun clearCallsignTable() {
        if (loaded) runCatching { nativeClearCallsignTable() }
    }
```

```kotlin
    private external fun nativeClearCallsignTable()
```

- [ ] **Step 4: Build**

Run: `./gradlew :ft8-native:assembleDebug :app:assembleDebug`
Expected: BUILD SUCCESSFUL (all ABIs compile; the default interface method keeps every existing `Ft8DecoderApi` fake compiling unchanged).

Run: `./gradlew :app:testDebugUnitTest :core:testDebugUnitTest`
Expected: PASS (no JVM-visible behavior change).

- [ ] **Step 5: Commit**

```bash
git add ft8-native/src/main/cpp/ft8_jni.cpp ft8-native/src/main/java/net/ft8vc/ft8native/Ft8Native.kt ft8-native/src/main/java/net/ft8vc/ft8native/Ft8DecoderApi.kt
git commit -m "feat(native): persist callsign hash table across slots with age-based eviction"
```

---

### Task 2: Instrumented native tests — cross-slot hash + DT nominal

**Files:**
- Create: `ft8-native/src/androidTest/java/net/ft8vc/ft8native/Ft8HashPersistenceTest.kt`
- Reference (read, don't modify): `ft8-native/src/androidTest/java/net/ft8vc/ft8native/Ft8SnrCalibrationTest.kt` (for the calibration-WAV loading pattern)

**Interfaces:**
- Consumes: `Ft8Native.decode/encode/clearCallsignTable` (Task 1).
- Produces: device-gated evidence for the two riskiest assumptions (cross-slot resolution works; `NOMINAL_DT_S = 0.5f` is correct).

- [ ] **Step 1: Write the tests**

Create `ft8-native/src/androidTest/java/net/ft8vc/ft8native/Ft8HashPersistenceTest.kt`:

```kotlin
package net.ft8vc.ft8native

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cross-slot hashed-callsign resolution (spec §1). Encode and decode share the
 * global table, so every scenario starts from clearCallsignTable().
 */
@RunWith(AndroidJUnit4::class)
class Ft8HashPersistenceTest {

    @Before
    fun requireNative() {
        assumeTrue(Ft8Native.isAvailable())
    }

    /** Encode both slots FIRST (encoding seeds the table), then clear before decoding. */
    private fun buildSlots(): Pair<ShortArray, ShortArray> {
        Ft8Native.clearCallsignTable()
        val cqSlot = Ft8Native.encode("CQ PJ4/K1ABC EM26", 1000f)
        val hashedSlot = Ft8Native.encode("PJ4/K1ABC W0DEV -10", 1500f)
        assertTrue("encode CQ failed", cqSlot.isNotEmpty())
        assertTrue("encode directed failed", hashedSlot.isNotEmpty())
        return cqSlot to hashedSlot
    }

    @Test
    fun hashedFormUnresolvedWithoutPriorSlot() {
        val (_, hashedSlot) = buildSlots()
        Ft8Native.clearCallsignTable()
        val decoded = Ft8Native.decode(hashedSlot)
        assertEquals(1, decoded.size)
        // Without the CQ slot's table entry the nonstandard call shows hashed.
        assertTrue(
            "expected unresolved <...> form, got: ${decoded[0].message}",
            decoded[0].message.contains("<"),
        )
    }

    @Test
    fun hashedFormResolvesFromEarlierSlot() {
        val (cqSlot, hashedSlot) = buildSlots()
        Ft8Native.clearCallsignTable()
        val first = Ft8Native.decode(cqSlot)
        assertTrue(
            "CQ slot must decode the full call: ${first.joinToString { it.message }}",
            first.any { it.message.contains("PJ4/K1ABC") },
        )
        val second = Ft8Native.decode(hashedSlot)
        assertEquals(1, second.size)
        assertTrue(
            "expected resolved call from earlier slot, got: ${second[0].message}",
            second[0].message.contains("PJ4/K1ABC") && !second[0].message.contains("<"),
        )
    }

    /**
     * Spec §3 validation gate for ClockOffsetEstimator.NOMINAL_DT_S: encoded
     * slots place the waveform centered via silence padding — the DT the
     * decoder reports for our own encode output tells us the buffer-relative
     * nominal. The estimator's constant must match the SLOT-ALIGNED nominal
     * (0.5 s), which this asserts within tolerance using the encode layout:
     * (15 s - 12.64 s) / 2 = 1.18 s pad → expected DT ≈ 1.18 for encode
     * output. The LIVE-capture nominal is 0.5 s (signals start 0.5 s after
     * the boundary and SlotCollector buffers are boundary-aligned), so this
     * test documents the encode-buffer DT and sanity-checks the decoder's DT
     * axis; the 0.5 s live constant is confirmed in field gate (iii).
     */
    @Test
    fun decoderDtAxisMatchesEncodePadding() {
        Ft8Native.clearCallsignTable()
        val slot = Ft8Native.encode("CQ W0DEV EM26", 1200f)
        val decoded = Ft8Native.decode(slot)
        assertEquals(1, decoded.size)
        val dt = decoded[0].dtSeconds
        assertTrue("encode-buffer DT expected ≈1.18 s, got $dt", dt > 0.9f && dt < 1.5f)
    }

    /**
     * Spec §3: NOMINAL_DT_S validation. The WSJT-X calibration WAV is
     * slot-boundary aligned, so the median DT of its decodes IS the live
     * nominal. Load it exactly the way Ft8SnrCalibrationTest does (mirror
     * that file's asset/WavIo loading code). If this fails, report BLOCKED
     * with the measured median — ClockOffsetEstimator.NOMINAL_DT_S must then
     * be corrected to the measured value, not the test loosened.
     */
    @Test
    fun calibrationWavMedianDtNearNominal() {
        Ft8Native.clearCallsignTable()
        val samples = loadCalibrationWav() // mirror Ft8SnrCalibrationTest's loader
        val decoded = Ft8Native.decode(samples)
        assertTrue("calibration WAV must decode signals", decoded.size >= 4)
        val dts = decoded.map { it.dtSeconds }.sorted()
        val median = dts[dts.size / 2]
        assertTrue(
            "median DT expected ≈0.5 s (NOMINAL_DT_S), got $median",
            median > 0.2f && median < 0.8f,
        )
    }
}
```

(`loadCalibrationWav()` is a private helper you write by copying the WAV-loading code from `Ft8SnrCalibrationTest.kt` in the same package — same asset, same `WavIo`/reader path. Do not modify that file.)

- [ ] **Step 2: Run (device) or compile (fallback)**

Run: `./gradlew :ft8-native:connectedDebugAndroidTest`
Expected: PASS (all `Ft8HashPersistenceTest` tests plus existing instrumented tests).
If no device attached: `./gradlew :ft8-native:assembleDebugAndroidTest` must BUILD SUCCESSFUL; record the deferred run.

- [ ] **Step 3: Commit**

```bash
git add ft8-native/src/androidTest/java/net/ft8vc/ft8native/Ft8HashPersistenceTest.kt
git commit -m "test(native): cross-slot hashed-callsign resolution and DT-axis checks"
```

---

### Task 3: Passband constants to 4000 Hz

**Files:**
- Modify: `ft8-native/src/main/cpp/ft8_jni.cpp:27-28` (`kMaxCandidates`), `cfg.f_max` (~line 190)
- Modify: `audio/src/main/java/net/ft8vc/audio/dsp/SpectrumProcessor.kt:13` (default `maxFreqHz`)
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt:68` (`setTxToneHz` coercion)

**Interfaces:**
- Consumes: nothing new.
- Produces: decode band 200–4000 Hz; waterfall/tone UI range 0–4000 (derived); TX tone settable to 4000.

- [ ] **Step 1: JNI constants**

In `ft8-native/src/main/cpp/ft8_jni.cpp` change:

```cpp
static const int kMaxCandidates = 140;
```

to:

```cpp
static const int kMaxCandidates = 180;
```

and in `nativeDecode`'s monitor config change `cfg.f_max = 3000;` to:

```cpp
    cfg.f_max = 4000;
```

- [ ] **Step 2: Display default**

In `audio/src/main/java/net/ft8vc/audio/dsp/SpectrumProcessor.kt` change the constructor default `maxFreqHz: Int = 3000` to `maxFreqHz: Int = 4000`, and update the class KDoc line "FT8 audio lives roughly in the 0-3000 Hz passband" to "FT8 audio lives roughly in the 0-4000 Hz passband".

- [ ] **Step 3: Tone ceiling**

In `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt` change `hz.coerceIn(300, 3000)` to `hz.coerceIn(300, 4000)`.

- [ ] **Step 4: Sweep for stale 3000s**

Run: `grep -rn "3000" app/src/main audio/src/main core/src/main --include="*.kt" | grep -vi "snr\|noise"`
Expected: any remaining hit is either unrelated (timeouts etc.) or the SnrEstimator noise band (which stays per spec). Fix only genuine passband references. `SnrEstimator.NOISE_HI_HZ = 3000` MUST remain 3000.

- [ ] **Step 5: Build and test**

Run: `./gradlew :app:testDebugUnitTest :core:testDebugUnitTest :app:assembleDebug`
Expected: PASS / BUILD SUCCESSFUL. If any audio/spectrum unit test hardcodes 3000 for bin math, update its expectation to the derived 4000-based value (the intent — "bins cover the passband" — is unchanged).

- [ ] **Step 6: Commit**

```bash
git add ft8-native/src/main/cpp/ft8_jni.cpp audio/src/main/java/net/ft8vc/audio/dsp/SpectrumProcessor.kt app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt
git commit -m "feat(rx): extend decode, waterfall, and TX-tone passband to 4000 Hz"
```

(Include any test files updated in Step 5 in the `git add`.)

---

### Task 4: ClockOffsetEstimator (core)

**Files:**
- Create: `core/src/main/java/net/ft8vc/core/ClockOffsetEstimator.kt`
- Test: `core/src/test/java/net/ft8vc/core/ClockOffsetEstimatorTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces (used by Task 5): `ClockOffsetEstimator` with `onSlotDts(dts: List<Float>)` (call once per FULL pass, empty list allowed and ages the window), `offsetSeconds: Float?`, `reset()`, and companion constants `WINDOW_SLOTS = 4`, `MIN_SAMPLES = 4`, `NOMINAL_DT_S = 0.5f`, `WARN_S = 1.0f`, `SEVERE_S = 2.0f`.

- [ ] **Step 1: Write the failing tests**

Create `core/src/test/java/net/ft8vc/core/ClockOffsetEstimatorTest.kt`:

```kotlin
package net.ft8vc.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockOffsetEstimatorTest {

    @Test
    fun nullUntilMinimumSamples() {
        val e = ClockOffsetEstimator()
        e.onSlotDts(listOf(0.5f, 0.5f, 0.5f))
        assertNull(e.offsetSeconds)
        e.onSlotDts(listOf(0.5f))
        assertEquals(0.0f, e.offsetSeconds!!, 0.001f)
    }

    @Test
    fun fastClockGivesPositiveOffset() {
        val e = ClockOffsetEstimator()
        // Phone clock fast → signals land late in our buffer → DT above nominal.
        e.onSlotDts(listOf(1.9f, 2.0f, 1.9f, 2.0f))
        assertEquals(1.45f, e.offsetSeconds!!, 0.06f)
    }

    @Test
    fun medianIgnoresOneOutlier() {
        val e = ClockOffsetEstimator()
        e.onSlotDts(listOf(0.5f, 0.5f, 0.5f, 0.5f, 9.9f))
        assertEquals(0.0f, e.offsetSeconds!!, 0.001f)
    }

    @Test
    fun windowRollsAndQuietSlotsExpireTheEstimate() {
        val e = ClockOffsetEstimator()
        e.onSlotDts(listOf(2.0f, 2.0f, 2.0f, 2.0f))
        assertEquals(1.5f, e.offsetSeconds!!, 0.001f)
        // Four quiet slots push the data out of the 4-slot window.
        repeat(4) { e.onSlotDts(emptyList()) }
        assertNull(e.offsetSeconds)
    }

    @Test
    fun resetClearsEverything() {
        val e = ClockOffsetEstimator()
        e.onSlotDts(listOf(2.0f, 2.0f, 2.0f, 2.0f))
        e.reset()
        assertNull(e.offsetSeconds)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.ClockOffsetEstimatorTest"`
Expected: FAIL — unresolved reference `ClockOffsetEstimator`.

- [ ] **Step 3: Implement**

Create `core/src/main/java/net/ft8vc/core/ClockOffsetEstimator.kt`:

```kotlin
package net.ft8vc.core

/**
 * Estimates the phone-clock offset versus FT8 band time from decode DTs.
 *
 * Live-capture buffers are slot-boundary aligned and FT8 transmissions
 * nominally begin [NOMINAL_DT_S] into the slot, so
 * `median(DT) - NOMINAL_DT_S` is the clock error: a FAST phone clock opens
 * the slot early, signals land late in the buffer, and the offset reads
 * positive. Only meaningful within the decoder's sync window (~±3 s) and
 * modulo the 15 s grid — a clock worse than that produces no decodes at all.
 *
 * Feed [onSlotDts] once per FULL decode pass (empty lists age the window so
 * a stale estimate expires after [WINDOW_SLOTS] quiet slots).
 */
class ClockOffsetEstimator {

    private val window = ArrayDeque<List<Float>>()

    fun onSlotDts(dts: List<Float>) {
        window.addLast(dts)
        while (window.size > WINDOW_SLOTS) window.removeFirst()
    }

    /** Signed offset in seconds, or null when fewer than [MIN_SAMPLES] recent DTs. */
    val offsetSeconds: Float?
        get() {
            val pooled = window.flatten()
            if (pooled.size < MIN_SAMPLES) return null
            return median(pooled) - NOMINAL_DT_S
        }

    fun reset() = window.clear()

    private fun median(values: List<Float>): Float {
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2f
    }

    companion object {
        const val WINDOW_SLOTS = 4
        const val MIN_SAMPLES = 4
        /** Nominal FT8 signal start within a slot-aligned buffer. */
        const val NOMINAL_DT_S = 0.5f
        /** Show the chip (amber) at this absolute offset. */
        const val WARN_S = 1.0f
        /** Chip turns red at this absolute offset. */
        const val SEVERE_S = 2.0f
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:testDebugUnitTest --tests "net.ft8vc.core.ClockOffsetEstimatorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/net/ft8vc/core/ClockOffsetEstimator.kt core/src/test/java/net/ft8vc/core/ClockOffsetEstimatorTest.kt
git commit -m "feat(core): median-DT clock-offset estimator"
```

---

### Task 5: Wire the estimator through DecodeController to the status chip

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt` (field, full-pass feed, `reset()`, `DecodeSlice`)
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt` (field)
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (combine mapping)
- Modify: `app/src/main/java/net/ft8vc/app/ui/operate/OperateStatusBar.kt` (chip)
- Test: `app/src/test/java/net/ft8vc/app/controllers/DecodeControllerTest.kt`

**Interfaces:**
- Consumes: `ClockOffsetEstimator` (Task 4), `DecodeSlice`/`DecodePassSource` (existing).
- Produces: `DecodeSlice.clockOffsetSeconds: Float? = null`; `OperateUiState.clockOffsetSeconds: Float? = null`.

- [ ] **Step 1: Write the failing controller test**

Open `app/src/test/java/net/ft8vc/app/controllers/DecodeControllerTest.kt`, mirror its existing construction/fake pattern, and add (adapting the fake to the file's existing `Ft8DecoderApi` test double if one exists — reuse it rather than duplicating):

```kotlin
    @Test
    fun clockOffset_fromFullPassesOnly_andExpiresOnQuiet() = runTest {
        // Fake decoder returning four decodes with DT 2.0 s (clock 1.5 s fast).
        val results = Array(4) { i ->
            Ft8DecodeResult("CQ K${i}AA EM1$i", 0, 2.0f, (500 + 100 * i).toFloat(), 10)
        }
        queueDecodes(results) // use/extend the file's existing fake-queueing helper
        val samples = ShortArray(AppInfo.SAMPLE_RATE_HZ * 15) { 100 } // non-zero PCM
        controller.decodeSlot(samples, slotStartEpochMs = 0L, source = DecodePassSource.Full)
        assertEquals(1.5f, controller.slice.value.clockOffsetSeconds!!, 0.01f)

        // Early passes must NOT feed the estimator.
        queueDecodes(results)
        controller.decodeSlot(samples, slotStartEpochMs = 15_000L, source = DecodePassSource.Early)

        // Four quiet FULL slots expire the estimate.
        repeat(4) { n ->
            queueDecodes(emptyArray())
            controller.decodeSlot(samples, slotStartEpochMs = 30_000L + n * 15_000L, source = DecodePassSource.Full)
        }
        assertNull(controller.slice.value.clockOffsetSeconds)
    }
```

(If `Ft8DecodeResult`'s constructor parameter order differs — it is `(message, snr, dtSeconds, freqHz, score)` per `Ft8DecodeResult.kt` — match the actual declaration. If the test file has no queueing fake, add a minimal one following the file's existing decoder-double style.)

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.DecodeControllerTest"`
Expected: FAIL — `clockOffsetSeconds` unresolved.

- [ ] **Step 3: Wire DecodeController**

In `app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt`:

Add import `net.ft8vc.core.ClockOffsetEstimator` and a field near `slotCollector`:

```kotlin
    private val clockOffset = ClockOffsetEstimator()
```

In `decodeSlot`, in the success path AFTER the `_slice.update` that records pass duration/source, add:

```kotlin
        if (source == DecodePassSource.Full) {
            clockOffset.onSlotDts(results.map { it.dtSeconds })
            _slice.update { it.copy(clockOffsetSeconds = clockOffset.offsetSeconds) }
        }
```

In `reset()`, add:

```kotlin
        clockOffset.reset()
```

and include `clockOffsetSeconds = null` in `reset()`'s existing `_slice.update { it.copy(...) }`.

Add to `DecodeSlice`:

```kotlin
    /** Estimated phone-clock offset vs FT8 band time (median DT), null when unknown. */
    val clockOffsetSeconds: Float? = null,
```

- [ ] **Step 4: UiState + ViewModel**

`app/src/main/java/net/ft8vc/app/OperateUiState.kt` — add near the decode fields:

```kotlin
    val clockOffsetSeconds: Float? = null,
```

`app/src/main/java/net/ft8vc/app/OperateViewModel.kt` — in the `combine` mapping next to the other `decode.` lines:

```kotlin
                clockOffsetSeconds = decode.clockOffsetSeconds,
```

- [ ] **Step 5: Status bar chip**

In `app/src/main/java/net/ft8vc/app/ui/operate/OperateStatusBar.kt`, after the `CompactChip(text = "TX ${state.txFreqHz}")` line, add (imports: `net.ft8vc.core.ClockOffsetEstimator`, `net.ft8vc.app.ui.theme.Ft8Red` if missing, `java.util.Locale`, `kotlin.math.abs`):

```kotlin
            state.clockOffsetSeconds?.let { off ->
                if (abs(off) >= ClockOffsetEstimator.WARN_S) {
                    val severe = abs(off) >= ClockOffsetEstimator.SEVERE_S
                    val color = if (severe) Ft8Red else Ft8Amber
                    WithTooltip(
                        text = "Phone clock differs from FT8 band time — fix in Android date & time settings",
                    ) {
                        Surface(shape = Ft8Compact.chipShape, color = color.copy(alpha = 0.2f)) {
                            Text(
                                text = "Clock %+.1fs".format(Locale.US, off),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = color,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
```

(Match the POTA chip's Surface/Text idiom already in this file; reuse existing imports where present.)

- [ ] **Step 6: Run suites and build**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/controllers/DecodeController.kt app/src/main/java/net/ft8vc/app/OperateUiState.kt app/src/main/java/net/ft8vc/app/OperateViewModel.kt app/src/main/java/net/ft8vc/app/ui/operate/OperateStatusBar.kt app/src/test/java/net/ft8vc/app/controllers/DecodeControllerTest.kt
git commit -m "feat(app): clock-offset status chip fed by full-pass decode DTs"
```

---

### Task 6: Full verification sweep

**Files:** none — verification only.

- [ ] **Step 1: All unit suites + build**

Run: `./gradlew :core:testDebugUnitTest :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 2: Instrumented (device required; else defer)**

Run: `./gradlew :ft8-native:connectedDebugAndroidTest :data:connectedDebugAndroidTest`
Expected: PASS (hash persistence, DT axis, SNR calibration at 4000 Hz, plus the phase-1 deferred data tests). If no device: `assembleDebugAndroidTest` for both modules must succeed; the run stays a promotion gate.

- [ ] **Step 3: Constraint check**

Run: `git diff --name-only BASE..HEAD | grep -E "^rig/"` (BASE = commit before Task 1, from the ledger) — Expected: empty. Confirm the only `audio/` change is SpectrumProcessor's constant/KDoc.

- [ ] **Step 4: Spec conformance read-through**

Re-read `docs/superpowers/specs/2026-07-01-rx-reliability-parity-design.md` section by section; confirm each requirement maps to landed code.

- [ ] **Step 5: Field gates (operator, before promotion)**

Per spec: (i) busy-band 3000–4000 Hz decodes incl. one worked with tone above 3000; (ii) compound call resolving from hash across slots; (iii) +2 s clock skew → red chip ≈ +2.0 s, clears ~4 slots after fixing; (iv) `lastDecodePassDurationMs` < 3 s throughout at 4000 Hz/180 candidates.
