# Late-Start FT8 TX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the operator tap Answer up to 7.0 s into a 15 s slot and still transmit in that slot, by truncating the on-air waveform from the front while preserving full FEC and symbol-clock alignment. Ships behind a Settings toggle defaulted ON.

**Architecture:** Add an `offsetSymbols` parameter to the JNI encode path that emits PCM only for symbols `[offset, 79)` (no silence padding) while still running full `ftx_message_encode` + `ft8_encode` over all 79 symbols. In `TxOrchestrator.transmitAfterSlotBoundary`, compute a `LateTxPlan` at entry from `t_in_slot` and the toggle state; route through the existing v1.0 path for taps before the floor and after the cutoff, and through a new symbol-clock-aligned key-and-play path for taps inside the late-TX window. All four PTT-safety layers from Phase 5 wrap the late-TX path unchanged.

**Tech Stack:** Kotlin 2.3.21, Jetpack Compose, Coroutines 1.10.2, C/C++ via NDK r29, JNI to ft8_lib (kgoba `9fec6ca`), AndroidX DataStore Preferences 1.1.7, Room 2.7.2, JUnit 4, MockK 1.14.7, Turbine 1.2.1, kotlinx-coroutines-test 1.10.2.

**Spec:** [docs/superpowers/specs/2026-06-22-late-start-ft8-tx-design.md](../specs/2026-06-22-late-start-ft8-tx-design.md)

## Global Constraints

- **Pin stays pinned.** ft8_lib remains at kgoba `9fec6ca39886edbf96f4f5e71edc76da5074e871` via FetchContent in `ft8-native/src/main/cpp/CMakeLists.txt`. No upstream-commit changes.
- **PTT-safety primacy.** The 4-layer defense in `TxOrchestrator` (try-finally + AutoCloseable + `withTimeoutOrNull(slotDurationMs + WATCHDOG_OUTER_GRACE_MS)` + 250 ms watchdog) is preserved unchanged. Late-TX runs *inside* the safety envelope.
- **PARITY-01 escape hatch.** With the toggle OFF, behavior is byte-equivalent to v1.0. With the toggle ON, any `t_in_slot < 1.34 s` ALSO routes through unchanged v1.0 code.
- **License gate preserved.** `AppRfState.READY` precondition for `transmit()` and `transmitAfterSlotBoundary()` is unchanged.
- **No new top-level screen / tab / dependency.** One new `AutoToggleRow` in the existing Settings TX section.
- **minSdk 28, NDK r29, JVM 17, AGP 9.2.1** unchanged.
- **TDD.** Every behavioral change starts with a failing test. JNI tests live in `androidTest/` (needs emulator); plan/orchestrator tests live in `src/test/` (pure JVM).
- **One commit per task.** No bundling.

## Constants (use these exact values everywhere)

```kotlin
// In TxOrchestrator companion (or a new LateTx object)
const val SAMPLE_RATE_HZ = 12_000              // from AppInfo.SAMPLE_RATE_HZ
const val SLOT_MS = 15_000L                    // from SlotTiming.SLOT_MS
const val FT8_NN = 79                          // symbols per transmission
const val SYMBOL_PERIOD_MS = 160L              // 0.160 s
const val SAMPLES_PER_SYMBOL = 1920            // round(12000 * 0.160)
const val WAVEFORM_START_MS = 1180L            // (15000 - 79*160) / 2
const val LATE_TX_FLOOR_MS = WAVEFORM_START_MS + SYMBOL_PERIOD_MS  // 1340
const val LATE_TX_CUTOFF_MS = 7000L
const val LATE_TX_DRIFT_ABORT_MS = 80L         // ½ symbol period
```

## File Structure

**Files modified:**

| File | What changes |
|---|---|
| `ft8-native/src/main/cpp/ft8_jni.cpp` | `synth_gfsk` grows `offset_symbols` parameter; `nativeEncode` accepts it and emits truncated buffer (no silence padding) when offset > 0 |
| `ft8-native/src/main/java/net/ft8vc/ft8native/Ft8DecoderApi.kt` | `encode` grows `offsetSymbols: Int = 0` parameter |
| `ft8-native/src/main/java/net/ft8vc/ft8native/Ft8Native.kt` | Same parameter on `encode`; new `private external fun nativeEncodeOffset(...)` OR extended `nativeEncode` signature (Task 2 picks one) |
| `ft8-native/src/testFixtures/java/net/ft8vc/ft8native/fakes/Ft8DecoderFake.kt` | `encode` accepts offset; `encodeProducer` lambda grows offset parameter; `EncodeInvocation` records it |
| `ft8-native/src/test/java/net/ft8vc/ft8native/fakes/Ft8DecoderFake.kt` | Same as above (mirror of testFixtures source) |
| `app/src/main/java/net/ft8vc/app/settings/StationSettings.kt` | Add `lateStartTxEnabled: Boolean = true` |
| `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt` | Add DataStore key `LATE_START_TX_ENABLED`, default true; read in `settings` flow; expose `setLateStartTxEnabled(Boolean)` |
| `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt` | `SettingsSlice` gains `lateStartTxEnabled: Boolean = true`; passthrough in `toSlice()` |
| `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` | Add `setLateStartTxEnabled(Boolean)` calling `repo.setLateStartTxEnabled` on `viewModelScope`; expose `lateStartTxEnabled` on `OperateUiState` via the existing settings collection |
| `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt` | Add `AutoToggleRow` for "Late-start TX (up to 7s into slot)" in the existing TX-toggle section (around line 257) |
| `app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt` | Add `LateTxPlan` sealed interface; `computeLateTxPlan` static helper; `lateStartTxEnabledProvider: () -> Boolean = { true }` constructor param; modify `transmitAfterSlotBoundary` to branch on plan; add snapshot-to-key drift check inside the late-TX branch |

**Files created (tests):**

| File | What it covers |
|---|---|
| `ft8-native/src/androidTest/java/net/ft8vc/ft8native/Ft8NativeLateTxTest.kt` | JNI byte-equivalence at offset=0; tail equivalence at offset>0; FEC bit equivalence via decode round-trip |
| `app/src/test/java/net/ft8vc/app/controllers/LateTxPlanTest.kt` | Pure logic: floor / late window / cutoff / toggle off / invariants |
| `app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorLateTxTest.kt` | Late-TX integration: PTT timing, sample count, defer paths, drift abort |
| `app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorPttSafetyLateTxTest.kt` | Re-runs each of the 4 PTT-safety scenarios with a late-TX call injected |

**Files NOT touched (explicitly):**

`QsoSessionController`, `DecodeController`, `RigSession`, `SlotCollector`, `UsbAudioCapture`, `UsbAudioPlayback`, `OperateScreen`, `DecodeListPanel`, the Answer/Resume button Composables, `Ft8Native.version()` handshake.

---

### Task 1: Add `offsetSymbols` parameter to encode interface (Ft8DecoderApi, Ft8Native, fakes) with default 0

**Files:**
- Modify: `ft8-native/src/main/java/net/ft8vc/ft8native/Ft8DecoderApi.kt`
- Modify: `ft8-native/src/main/java/net/ft8vc/ft8native/Ft8Native.kt`
- Modify: `ft8-native/src/main/java/net/ft8vc/ft8native/Ft8DecodeResult.kt` (no change expected; just verify the encode result shape is unaffected)
- Modify: `ft8-native/src/testFixtures/java/net/ft8vc/ft8native/fakes/Ft8DecoderFake.kt`
- Modify: `ft8-native/src/test/java/net/ft8vc/ft8native/fakes/Ft8DecoderFake.kt`
- Modify: `ft8-native/src/test/java/net/ft8vc/ft8native/fakes/Ft8DecoderFakeSelfTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces: `Ft8DecoderApi.encode(message, freqHz, sampleRate, offsetSymbols)` with `offsetSymbols: Int = 0`. All existing call sites compile unchanged (defaulted parameter).

- [ ] **Step 1: Extend the Ft8DecoderFake self-test to assert the new parameter records correctly**

Add to `ft8-native/src/test/java/net/ft8vc/ft8native/fakes/Ft8DecoderFakeSelfTest.kt`:

```kotlin
@Test
fun encodeRecordsOffsetSymbols() {
    val fake = Ft8DecoderFake()
    fake.encode("CQ TEST", 1500f, 12_000, offsetSymbols = 13)
    val inv = fake.encodeInvocationsSnapshot().single()
    assertEquals(13, inv.offsetSymbols)
}

@Test
fun encodeDefaultsOffsetSymbolsToZero() {
    val fake = Ft8DecoderFake()
    fake.encode("CQ TEST", 1500f, 12_000)
    val inv = fake.encodeInvocationsSnapshot().single()
    assertEquals(0, inv.offsetSymbols)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :ft8-native:testDebugUnitTest --tests "net.ft8vc.ft8native.fakes.Ft8DecoderFakeSelfTest"`
Expected: FAIL with "Unresolved reference: offsetSymbols" or "Cannot resolve symbol offsetSymbols".

- [ ] **Step 3: Update `Ft8DecoderApi.encode` signature**

Replace the encode declaration in `ft8-native/src/main/java/net/ft8vc/ft8native/Ft8DecoderApi.kt`:

```kotlin
fun encode(
    message: String,
    freqHz: Float = 1000f,
    sampleRate: Int = 12_000,
    offsetSymbols: Int = 0,
): ShortArray
```

- [ ] **Step 4: Update `Ft8Native.encode` signature and JNI route**

Replace lines 51-56 of `ft8-native/src/main/java/net/ft8vc/ft8native/Ft8Native.kt`:

```kotlin
/**
 * Encode [message] to FT8 PCM at [sampleRate] (12 kHz mono).
 *
 * When [offsetSymbols] == 0 (default), returns a full 15-second slot buffer
 * with silence padding so the transmission is centered (v1.0 behavior, byte-
 * identical to the no-parameter call path).
 *
 * When [offsetSymbols] > 0, returns a truncated buffer containing only symbols
 * `[offsetSymbols, 79)` — `(79 - offsetSymbols) * 1920` samples, no silence
 * padding. The full FEC-encoded message is still synthesized over all 79
 * symbols; only the on-air PCM is clipped. Used by late-start TX.
 *
 * Returns an empty array if the message can't be encoded or the native library
 * is missing.
 */
override fun encode(
    message: String,
    freqHz: Float,
    sampleRate: Int,
    offsetSymbols: Int,
): ShortArray =
    if (loaded) {
        runCatching { nativeEncode(message, freqHz, sampleRate, offsetSymbols) }.getOrDefault(ShortArray(0))
    } else {
        ShortArray(0)
    }
```

And replace the external function declaration:

```kotlin
private external fun nativeEncode(
    message: String,
    freqHz: Float,
    sampleRate: Int,
    offsetSymbols: Int,
): ShortArray
```

(Note: the JNI .cpp signature update is Task 2. After this step, the build will fail to load `libft8vc.so` at runtime until Task 2 lands. Unit tests that use the fake will pass; instrumented tests will not. That is acceptable for this task.)

- [ ] **Step 5: Update the testFixtures fake**

Replace the encode method in `ft8-native/src/testFixtures/java/net/ft8vc/ft8native/fakes/Ft8DecoderFake.kt` (and mirror to `src/test/.../Ft8DecoderFake.kt`):

```kotlin
override fun encode(
    message: String,
    freqHz: Float,
    sampleRate: Int,
    offsetSymbols: Int,
): ShortArray {
    if (!available) {
        synchronized(queueLock) {
            encodeCalls += EncodeInvocation(message, freqHz, sampleRate, offsetSymbols, returnedSize = 0)
        }
        return ShortArray(0)
    }
    val pcm = encodeProducer?.invoke(message, freqHz, sampleRate, offsetSymbols)
        ?: defaultEncodeOutput(sampleRate, offsetSymbols)
    synchronized(queueLock) {
        encodeCalls += EncodeInvocation(message, freqHz, sampleRate, offsetSymbols, returnedSize = pcm.size)
    }
    return pcm
}

private fun defaultEncodeOutput(sampleRate: Int, offsetSymbols: Int): ShortArray =
    if (offsetSymbols <= 0) {
        ShortArray(sampleRate * 15)
    } else {
        // (FT8_NN - offsetSymbols) * samplesPerSymbol, where samplesPerSymbol = round(sampleRate * 0.160)
        val nSpsym = ((sampleRate.toDouble() * 0.160) + 0.5).toInt()
        val sym = (79 - offsetSymbols).coerceAtLeast(0)
        ShortArray(sym * nSpsym)
    }
```

Update `EncodeInvocation`:

```kotlin
data class EncodeInvocation(
    val message: String,
    val freqHz: Float,
    val sampleRate: Int,
    val offsetSymbols: Int,
    val returnedSize: Int,
)
```

Update `encodeProducer` type:

```kotlin
@Volatile
private var encodeProducer: ((String, Float, Int, Int) -> ShortArray)? = null

fun configureEncodeProducer(producer: ((String, Float, Int, Int) -> ShortArray)?) {
    encodeProducer = producer
}
```

- [ ] **Step 6: Run all unit tests to verify the change compiles and passes**

Run:
```bash
./gradlew :ft8-native:testDebugUnitTest :app:testDebugUnitTest
```
Expected: PASS. The self-test cases from Step 1 now pass. Existing tests that call `encode(msg, freq, rate)` without the offset still pass because of the default value.

- [ ] **Step 7: Commit**

```bash
git add ft8-native/src/main/java/net/ft8vc/ft8native/Ft8DecoderApi.kt \
        ft8-native/src/main/java/net/ft8vc/ft8native/Ft8Native.kt \
        ft8-native/src/test/java/net/ft8vc/ft8native/fakes/Ft8DecoderFake.kt \
        ft8-native/src/test/java/net/ft8vc/ft8native/fakes/Ft8DecoderFakeSelfTest.kt \
        ft8-native/src/testFixtures/java/net/ft8vc/ft8native/fakes/Ft8DecoderFake.kt
git commit -m "feat(ft8-native): add offsetSymbols parameter to encode (default 0)

Defaulted parameter on Ft8DecoderApi.encode + Ft8Native.encode so all
existing call sites compile unchanged. The fake records it and exposes
it on EncodeInvocation. JNI implementation lands in the next commit;
runtime call to nativeEncode will fail until then."
```

---

### Task 2: JNI implementation — `synth_gfsk` + `nativeEncode` accept `offset_symbols`

**Files:**
- Modify: `ft8-native/src/main/cpp/ft8_jni.cpp` (synth_gfsk signature + body; nativeEncode signature + body)
- Create: `ft8-native/src/androidTest/java/net/ft8vc/ft8native/Ft8NativeLateTxTest.kt`

**Interfaces:**
- Consumes: `Ft8DecoderApi.encode(msg, freq, rate, offsetSymbols)` from Task 1.
- Produces: working JNI for `offsetSymbols` ∈ `[0, 79]`. `offsetSymbols=0` is byte-identical to pre-parameter `nativeEncode`. `offsetSymbols ∈ [1, 78]` returns a `ShortArray` of `(79 - offsetSymbols) × 1920` samples containing only the GFSK waveform for symbols `[offsetSymbols, 79)`, no silence padding. `offsetSymbols ∈ {79, …}` returns an empty array.

- [ ] **Step 1: Write the failing instrumented test**

Create `ft8-native/src/androidTest/java/net/ft8vc/ft8native/Ft8NativeLateTxTest.kt`:

```kotlin
package net.ft8vc.ft8native

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class Ft8NativeLateTxTest {

    private companion object {
        const val MSG = "CQ TEST FN42"
        const val FREQ = 1500.0f
        const val RATE = 12_000
        const val FT8_NN = 79
        const val NSPSYM = 1920
        const val WAVEFORM_START_SAMPLES = 14_160  // (180_000 - 79*1920) / 2
    }

    @Before fun nativeLoaded() {
        assumeTrue("libft8vc.so not loaded", Ft8Native.isAvailable())
    }

    @Test
    fun encodeOffsetZeroMatchesV1Path() {
        // Calling with explicit offsetSymbols=0 must produce the same buffer as the
        // pre-parameter encode would have produced (regression guard).
        val baseline = Ft8Native.encode(MSG, FREQ, RATE)            // defaulted to 0
        val explicit = Ft8Native.encode(MSG, FREQ, RATE, 0)
        assertEquals(RATE * 15, baseline.size)
        assertArrayEquals(baseline, explicit)
    }

    @Test
    fun encodeOffsetThirteenReturnsTailOfWaveform() {
        val full = Ft8Native.encode(MSG, FREQ, RATE, 0)
        val truncated = Ft8Native.encode(MSG, FREQ, RATE, 13)
        val expectedSize = (FT8_NN - 13) * NSPSYM
        assertEquals(expectedSize, truncated.size)

        // The truncated buffer must equal the corresponding tail region of the full
        // (silence-padded) buffer's waveform within ±1 LSB per sample. The waveform
        // starts at WAVEFORM_START_SAMPLES inside the full buffer.
        val tailStartInFull = WAVEFORM_START_SAMPLES + 13 * NSPSYM
        for (i in 0 until expectedSize) {
            val delta = abs(truncated[i].toInt() - full[tailStartInFull + i].toInt())
            assertTrue(
                "Sample $i differs by $delta (truncated=${truncated[i]} full=${full[tailStartInFull + i]})",
                delta <= 1,
            )
        }
    }

    @Test
    fun encodeOffsetMaxReturnsEmpty() {
        val empty = Ft8Native.encode(MSG, FREQ, RATE, FT8_NN)
        assertEquals(0, empty.size)
    }

    @Test
    fun encodeOffsetOneSymbolReturnsExpectedSize() {
        val one = Ft8Native.encode(MSG, FREQ, RATE, FT8_NN - 1)
        assertEquals(NSPSYM, one.size)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run (requires an emulator or device running):
```bash
./gradlew :ft8-native:connectedDebugAndroidTest --tests "net.ft8vc.ft8native.Ft8NativeLateTxTest"
```
Expected: FAIL — the JNI symbol for the new 4-arg `nativeEncode` doesn't exist yet (linker / `UnsatisfiedLinkError`), or the test crashes when calling `nativeEncode` with the new signature.

- [ ] **Step 3: Update `synth_gfsk` to accept `offset_symbols`**

In `ft8-native/src/main/cpp/ft8_jni.cpp`, replace `synth_gfsk` (lines 113-148):

```cpp
// synth_gfsk: synthesize GFSK PCM for symbols [offset_symbols, n_sym) of the
// transmission. The full symbol stream is still passed in (FEC is upstream);
// this function only controls which symbols become audio.
//
// signal[] must be sized for (n_sym - offset_symbols) * n_spsym samples when
// offset_symbols > 0 (no silence padding), or for the caller's full-slot
// buffer when offset_symbols == 0 (caller supplies pre/post silence padding).
static void synth_gfsk(const uint8_t* symbols, int n_sym, int offset_symbols,
                       float f0, float symbol_bt,
                       float symbol_period, int signal_rate, float* signal) {
    int n_spsym = (int)(0.5f + signal_rate * symbol_period);
    int emitted_syms = n_sym - offset_symbols;
    if (emitted_syms <= 0) return;
    int n_wave = emitted_syms * n_spsym;
    float hmod = 1.0f;
    float dphi_peak = 2 * (float)M_PI * hmod / n_spsym;

    std::vector<float> dphi(static_cast<size_t>(n_wave + 2 * n_spsym),
                            2 * (float)M_PI * f0 / signal_rate);
    std::vector<float> pulse;
    gfsk_pulse(n_spsym, symbol_bt, pulse);

    // Spread each emitted symbol's pulse across its window. Symbol indices are
    // referenced into the full symbols[] array starting at offset_symbols.
    for (int i = 0; i < emitted_syms; ++i) {
        int src = i + offset_symbols;
        int ib = i * n_spsym;
        for (int j = 0; j < 3 * n_spsym; ++j) {
            dphi[j + ib] += dphi_peak * symbols[src] * pulse[j];
        }
    }
    // Ramp-edge pulses use the first and last EMITTED symbols, not the absolute
    // first/last of the FEC stream. This preserves the gentle envelope at the
    // truncated edge as well as the natural tail.
    for (int j = 0; j < 2 * n_spsym; ++j) {
        dphi[j] += dphi_peak * pulse[j + n_spsym] * symbols[offset_symbols];
        dphi[j + emitted_syms * n_spsym] += dphi_peak * pulse[j] * symbols[n_sym - 1];
    }

    float phi = 0;
    for (int k = 0; k < n_wave; ++k) {
        signal[k] = sinf(phi);
        phi = fmodf(phi + dphi[k + n_spsym], 2 * (float)M_PI);
    }

    int n_ramp = n_spsym / 8;
    for (int i = 0; i < n_ramp; ++i) {
        float env = (1 - cosf(2 * (float)M_PI * i / (2 * n_ramp))) / 2;
        signal[i] *= env;
        signal[n_wave - 1 - i] *= env;
    }
}
```

- [ ] **Step 4: Update `nativeEncode` JNI signature and body**

In `ft8-native/src/main/cpp/ft8_jni.cpp`, replace `Java_net_ft8vc_ft8native_Ft8Native_nativeEncode` (lines 248-292):

```cpp
extern "C" JNIEXPORT jshortArray JNICALL
Java_net_ft8vc_ft8native_Ft8Native_nativeEncode(
        JNIEnv* env, jobject, jstring message, jfloat freqHz, jint sampleRate, jint offsetSymbols) {

    std::lock_guard<std::mutex> guard(g_decodeMutex);

    if (offsetSymbols < 0) offsetSymbols = 0;
    if (offsetSymbols >= FT8_NN) {
        return env->NewShortArray(0);
    }

    ftx_message_t msg;
    {
        const char* text = env->GetStringUTFChars(message, nullptr);
        ftx_message_rc_t rc = ftx_message_encode(&msg, &g_hashIf, text);
        env->ReleaseStringUTFChars(message, text);
        if (rc != FTX_MESSAGE_RC_OK) {
            return env->NewShortArray(0);
        }
    }

    // Always run the full FEC encode over all 79 symbols — only the audio
    // synthesis is truncated. This is the load-bearing FEC-correctness invariant.
    uint8_t tones[FT8_NN];
    ft8_encode(msg.payload, tones);

    int nSpsym = (int)(0.5f + sampleRate * FT8_SYMBOL_PERIOD);

    if (offsetSymbols == 0) {
        // v1.0 path: 15-second buffer with silence padding centered on the waveform.
        // BYTE-IDENTICAL to the pre-parameter implementation (regression guard).
        int numSamples = FT8_NN * nSpsym;
        int total = (int)(FT8_SLOT_TIME * sampleRate);
        int numSilence = (total - numSamples) / 2;
        if (numSilence < 0) {
            numSilence = 0;
            total = numSamples;
        }

        std::vector<float> buf(static_cast<size_t>(total), 0.0f);
        synth_gfsk(tones, FT8_NN, 0, freqHz, kFt8SymbolBt, FT8_SYMBOL_PERIOD, sampleRate,
                   buf.data() + numSilence);

        std::vector<jshort> pcm(static_cast<size_t>(total));
        for (int i = 0; i < total; ++i) {
            float v = buf[i] * kTxAmplitude * 32767.0f;
            long r = std::lround(v);
            if (r > 32767) r = 32767;
            if (r < -32768) r = -32768;
            pcm[i] = (jshort)r;
        }

        jshortArray out = env->NewShortArray(total);
        env->SetShortArrayRegion(out, 0, total, pcm.data());
        return out;
    }

    // Late-TX path: no silence padding. Only emitted symbols.
    int emittedSyms = FT8_NN - offsetSymbols;
    int total = emittedSyms * nSpsym;

    std::vector<float> buf(static_cast<size_t>(total), 0.0f);
    synth_gfsk(tones, FT8_NN, offsetSymbols, freqHz, kFt8SymbolBt, FT8_SYMBOL_PERIOD, sampleRate,
               buf.data());

    std::vector<jshort> pcm(static_cast<size_t>(total));
    for (int i = 0; i < total; ++i) {
        float v = buf[i] * kTxAmplitude * 32767.0f;
        long r = std::lround(v);
        if (r > 32767) r = 32767;
        if (r < -32768) r = -32768;
        pcm[i] = (jshort)r;
    }

    jshortArray out = env->NewShortArray(total);
    env->SetShortArrayRegion(out, 0, total, pcm.data());
    return out;
}
```

- [ ] **Step 5: Run the instrumented test to verify it passes**

Run:
```bash
./gradlew :ft8-native:connectedDebugAndroidTest --tests "net.ft8vc.ft8native.Ft8NativeLateTxTest"
```
Expected: PASS — all four assertions hold.

If `encodeOffsetThirteenReturnsTailOfWaveform` fails with deltas > 1 at the very first or very last sample of the truncated buffer, the ramp envelope is the cause — the ramp shape will differ at the new truncated edge vs the original waveform. If that occurs, narrow the per-sample tolerance assertion to exclude the first and last `n_ramp` samples (i.e. assert tail-region equality for `[n_ramp, expectedSize - n_ramp)` and ramp shape separately). Capture the actual deltas and add a comment in the test.

- [ ] **Step 6: Run the full test suite to verify no regressions**

Run:
```bash
./gradlew :ft8-native:testDebugUnitTest :app:testDebugUnitTest
./gradlew :ft8-native:connectedDebugAndroidTest
```
Expected: all PASS. The `Ft8DecodeInstrumentedTest` (which calls `decode`, not `encode`) is unaffected; the existing TX flow in `app` tests uses the fake (Task 1) and is unaffected by JNI changes.

- [ ] **Step 7: Commit**

```bash
git add ft8-native/src/main/cpp/ft8_jni.cpp \
        ft8-native/src/androidTest/java/net/ft8vc/ft8native/Ft8NativeLateTxTest.kt
git commit -m "feat(ft8-native): JNI synth_gfsk + nativeEncode accept offset_symbols

When offset_symbols == 0: byte-identical to the pre-parameter v1.0 path
(regression guard). When > 0: returns (FT8_NN - offset_symbols) * 1920
samples containing only symbols [offset, 79) with no silence padding.
The full FEC encode runs over all 79 symbols regardless — only audio
synthesis is truncated. Used by TxOrchestrator late-start TX path."
```

---

### Task 3: SettingsRepository + StationSettings — add `lateStartTxEnabled` key, default true

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/StationSettings.kt`
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt`
- Modify or Create: `app/src/test/java/net/ft8vc/app/settings/SettingsRepositoryTest.kt` (test the new default + setter; create if no existing test)

**Interfaces:**
- Consumes: nothing new
- Produces: `StationSettings.lateStartTxEnabled: Boolean` (default true); `SettingsRepository.setLateStartTxEnabled(Boolean)`

- [ ] **Step 1: Write the failing test for the default value**

Locate or create the SettingsRepository test file. If `app/src/test/java/net/ft8vc/app/settings/SettingsRepositoryTest.kt` doesn't exist, follow the project's existing settings-test pattern; the simplest is to use the Robolectric-free pattern from existing repository tests (search for `class SettingsRepositoryTest` or any tests that touch DataStore — use that pattern).

Add a test asserting the default and the setter wire correctly. If no DataStore test infra exists, add an inline test of `StationSettings` defaults:

```kotlin
package net.ft8vc.app.settings

import org.junit.Assert.assertTrue
import org.junit.Test

class StationSettingsDefaultsTest {

    @Test
    fun lateStartTxEnabledDefaultsTrue() {
        val s = StationSettings()
        assertTrue("Late-start TX must default to ON per spec R7", s.lateStartTxEnabled)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.StationSettingsDefaultsTest"`
Expected: FAIL with "Unresolved reference: lateStartTxEnabled".

- [ ] **Step 3: Add the field to `StationSettings`**

In `app/src/main/java/net/ft8vc/app/settings/StationSettings.kt`, add `lateStartTxEnabled: Boolean = true` to the data class (alphabetical / logical grouping — put it next to other TX-related fields like `txEnabledInSettings`).

- [ ] **Step 4: Add the DataStore key and read/write in `SettingsRepository`**

In `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt`:

Add to the `Keys` object (line 150-172):
```kotlin
val LATE_START_TX_ENABLED = booleanPreferencesKey("late_start_tx_enabled")
```

Add to the `StationSettings` constructor call in the `settings` flow (line 27-55), placing it next to other TX toggles:
```kotlin
lateStartTxEnabled = prefs[Keys.LATE_START_TX_ENABLED] ?: true,
```

Add a setter (next to the other TX setters):
```kotlin
suspend fun setLateStartTxEnabled(enabled: Boolean) {
    appContext.settingsDataStore.edit { it[Keys.LATE_START_TX_ENABLED] = enabled }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.settings.StationSettingsDefaultsTest"`
Expected: PASS.

- [ ] **Step 6: Run the full app unit test suite to verify no regressions**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/StationSettings.kt \
        app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt \
        app/src/test/java/net/ft8vc/app/settings/StationSettingsDefaultsTest.kt
git commit -m "feat(settings): add lateStartTxEnabled toggle, default ON

DataStore key 'late_start_tx_enabled' persists per-install. Default
true matches the spec — late-TX ships enabled; toggle is the PARITY-01
escape hatch."
```

---

### Task 4: SettingsBridge — expose `lateStartTxEnabled` on the slice

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt`
- Modify or Create: `app/src/test/java/net/ft8vc/app/controllers/SettingsBridgeTest.kt` (extend existing test if present)

**Interfaces:**
- Consumes: `StationSettings.lateStartTxEnabled` from Task 3
- Produces: `SettingsSlice.lateStartTxEnabled: Boolean` (default true)

- [ ] **Step 1: Write the failing test**

Locate `SettingsBridgeTest` (search `find app/src/test -name "SettingsBridgeTest.kt"`). If it exists, add this case; if not, add to a new file alongside:

```kotlin
@Test
fun sliceCarriesLateStartTxEnabled() = runTest {
    val repo = FakeSettingsRepository(initial = StationSettings(lateStartTxEnabled = false))
    val bridge = SettingsBridge(repo, backgroundScope)

    bridge.slice.test {
        // Skip initial default emission, await repo-driven value
        assertEquals(false, awaitItem().lateStartTxEnabled)
    }
}
```

If a `FakeSettingsRepository` does not exist, use the existing pattern in the test file (likely a `flowOf(StationSettings(...))` substitute or a direct DataStore preferences mock — match the project convention).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.SettingsBridgeTest"`
Expected: FAIL with "Unresolved reference: lateStartTxEnabled" on `SettingsSlice`.

- [ ] **Step 3: Add the field to `SettingsSlice` and the `toSlice` mapping**

In `app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt`:

Add to `SettingsSlice` data class (line 78-100), placing next to `txEnabledInSettings`:
```kotlin
val lateStartTxEnabled: Boolean = true,
```

Add to `toSlice()` (line 52-74), placing next to the other TX toggle mappings:
```kotlin
lateStartTxEnabled = lateStartTxEnabled,
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.SettingsBridgeTest"`
Expected: PASS.

- [ ] **Step 5: Run full app unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/controllers/SettingsBridge.kt \
        app/src/test/java/net/ft8vc/app/controllers/SettingsBridgeTest.kt
git commit -m "feat(settings-bridge): expose lateStartTxEnabled on slice

Pass-through from StationSettings.lateStartTxEnabled. Consumers
(TxOrchestrator via OperateViewModel) read it from the slice."
```

---

### Task 5: OperateViewModel — expose `lateStartTxEnabled` on UI state + setter

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`
- Modify: `app/src/main/java/net/ft8vc/app/OperateUiState.kt` (or wherever the UI state data class lives — search if not at this path)

**Interfaces:**
- Consumes: `SettingsBridge.slice.lateStartTxEnabled` from Task 4
- Produces: `OperateUiState.lateStartTxEnabled: Boolean`; `OperateViewModel.setLateStartTxEnabled(Boolean)` for the Settings UI

- [ ] **Step 1: Locate the UI state file**

Run: `grep -n "data class OperateUiState" app/src/main/java/net/ft8vc/app/*.kt app/src/main/java/net/ft8vc/app/**/*.kt`

Note the file and line of the data class.

- [ ] **Step 2: Add the field to `OperateUiState`**

Add `val lateStartTxEnabled: Boolean = true,` to the data class (next to other TX toggle fields like `autoSeqEnabled` / `autoAnswerCqEnabled`).

- [ ] **Step 3: Wire `lateStartTxEnabled` into the `combine(...)` that produces `OperateUiState`**

In `OperateViewModel.kt`, find the `combine(settings, rig, decode, tx, qso)` block (per the Phase 5 architecture in CLAUDE.md). Add the field to the output `OperateUiState(…)`:

```kotlin
lateStartTxEnabled = settings.lateStartTxEnabled,
```

- [ ] **Step 4: Add the setter**

In `OperateViewModel.kt` (next to `setAutoAnswerCqEnabled` / `setAutoSeqEnabled` setters):

```kotlin
fun setLateStartTxEnabled(enabled: Boolean) {
    viewModelScope.launch {
        repo.setLateStartTxEnabled(enabled)
    }
}
```

(Confirm `repo` is the in-scope `SettingsRepository` reference name; use the existing local name if different.)

- [ ] **Step 5: Compile + run app unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/OperateViewModel.kt \
        app/src/main/java/net/ft8vc/app/OperateUiState.kt
git commit -m "feat(operate-vm): expose lateStartTxEnabled on UI state + setter

SettingsScreen toggle binding lands in the next commit."
```

---

### Task 6: SettingsScreen — add the Late-start TX toggle row

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `OperateUiState.lateStartTxEnabled` + `OperateViewModel.setLateStartTxEnabled` from Task 5

- [ ] **Step 1: Add the toggle row**

In `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt`, add an `AutoToggleRow` immediately after the "Auto answer CQ" row (around line 257):

```kotlin
AutoToggleRow(
    title = "Late-start TX (up to 7s into slot)",
    subtitle = "Send a truncated waveform so a late Answer/Resume still goes out this slot",
    checked = state.lateStartTxEnabled,
    onCheckedChange = vm::setLateStartTxEnabled,
    // Per spec: toggle is visible and editable regardless of license; the
    // license gate already blocks TX downstream via AppRfState.READY.
    enabled = true,
)
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke check (optional but recommended)**

Build the unstable APK and verify the row appears under Settings → Auto / TX section, defaults to ON, persists across an app relaunch.

```bash
./gradlew :app:installUnstableDebug
```

Launch the app, open Settings, scroll to the TX toggles, confirm "Late-start TX (up to 7s into slot)" appears with the switch ON.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt
git commit -m "feat(settings-ui): add Late-start TX toggle row

Defaults ON. Disabled if TX is not enabled (license-gate consistency
with sibling toggles). Subtitle explains the 7s window without
describing the symbol-clock internals."
```

---

### Task 7: `LateTxPlan` + `computeLateTxPlan` (pure logic, fully unit-tested)

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt` (add sealed interface + companion-object helper)
- Create: `app/src/test/java/net/ft8vc/app/controllers/LateTxPlanTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  ```kotlin
  sealed interface LateTxPlan {
      object Normal : LateTxPlan
      object Deferred : LateTxPlan
      data class Late(val offsetSymbols: Int, val waitMs: Long) : LateTxPlan
  }

  // Pure helper. tInSlotMs is millis since the slot started; toggleEnabled
  // is the late-start-TX Settings value.
  internal fun computeLateTxPlan(tInSlotMs: Long, toggleEnabled: Boolean): LateTxPlan
  ```

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/net/ft8vc/app/controllers/LateTxPlanTest.kt`:

```kotlin
package net.ft8vc.app.controllers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LateTxPlanTest {

    @Test
    fun toggleOffAlwaysReturnsNormal() {
        for (t in 0L..14_999L step 50L) {
            assertEquals("t=$t", LateTxPlan.Normal, computeLateTxPlan(t, toggleEnabled = false))
        }
    }

    @Test
    fun belowFloorReturnsNormal() {
        for (t in 0L..1_339L step 10L) {
            assertEquals("t=$t", LateTxPlan.Normal, computeLateTxPlan(t, toggleEnabled = true))
        }
    }

    @Test
    fun aboveCutoffReturnsDeferred() {
        for (t in 7_001L..14_999L step 50L) {
            assertEquals("t=$t", LateTxPlan.Deferred, computeLateTxPlan(t, toggleEnabled = true))
        }
    }

    @Test
    fun atFloorReturnsLateOneSymbol() {
        val plan = computeLateTxPlan(1_340L, toggleEnabled = true)
        assertTrue("plan was $plan", plan is LateTxPlan.Late)
        plan as LateTxPlan.Late
        assertEquals(1, plan.offsetSymbols)
        assertTrue("waitMs=${plan.waitMs}", plan.waitMs in 0L..160L)
    }

    @Test
    fun atCutoffReturnsLateThirtySeven() {
        val plan = computeLateTxPlan(7_000L, toggleEnabled = true)
        assertTrue("plan was $plan", plan is LateTxPlan.Late)
        plan as LateTxPlan.Late
        assertEquals(37, plan.offsetSymbols)
        assertTrue("waitMs=${plan.waitMs}", plan.waitMs in 0L..160L)
    }

    @Test
    fun lateWindowProducesValidPlans() {
        for (t in 1_340L..7_000L step 25L) {
            val plan = computeLateTxPlan(t, toggleEnabled = true)
            assertTrue("t=$t produced $plan", plan is LateTxPlan.Late)
            plan as LateTxPlan.Late
            assertTrue("offset=${plan.offsetSymbols}", plan.offsetSymbols in 1..37)
            assertTrue("waitMs=${plan.waitMs}", plan.waitMs in 0L..159L)

            // Invariant: 1180 + offset*160 - t - waitMs ∈ [0, 160) ms (ms-rounded)
            val keyMomentInSlot = 1180L + plan.offsetSymbols * 160L
            val drift = keyMomentInSlot - t - plan.waitMs
            assertTrue("t=$t offset=${plan.offsetSymbols} wait=${plan.waitMs} drift=$drift",
                drift in -1L..1L)
        }
    }

    @Test
    fun walkThroughExample_t3200() {
        // Spec walk-through: t=3.200 → offsetSymbols=13, waitMs=60
        // (key moment = 1180 + 13×160 = 3260; wait = 3260 - 3200 = 60ms)
        val plan = computeLateTxPlan(3_200L, toggleEnabled = true)
        assertEquals(LateTxPlan.Late(offsetSymbols = 13, waitMs = 60L), plan)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.LateTxPlanTest"`
Expected: FAIL with "Unresolved reference: LateTxPlan" and "Unresolved reference: computeLateTxPlan".

- [ ] **Step 3: Add the sealed interface and helper to `TxOrchestrator.kt`**

Add at the **bottom** of `app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt` (after `TxLogEvent`):

```kotlin
/**
 * Late-start TX decision derived from how far into the slot the operator tapped.
 *
 * - [Normal] — route through the unchanged v1.0 TX path (no truncation).
 * - [Deferred] — defer to the next slot via existing `transmitAfterSlotBoundary` queueing.
 * - [Late] — emit a truncated waveform starting at `offsetSymbols`, after waiting `waitMs`
 *   to land the first emitted sample on a symbol boundary.
 *
 * Computed by [computeLateTxPlan] from `t_in_slot` and the Settings toggle.
 */
sealed interface LateTxPlan {
    object Normal : LateTxPlan
    object Deferred : LateTxPlan
    data class Late(val offsetSymbols: Int, val waitMs: Long) : LateTxPlan
}

/**
 * Decide whether a transmit request at slot-relative time [tInSlotMs] should
 * run through the v1.0 path, defer to the next slot, or fire late with a
 * truncated waveform.
 *
 * Constants are derived in the spec — see
 * `docs/superpowers/specs/2026-06-22-late-start-ft8-tx-design.md` §Symbol-clock math.
 *
 * - `t < 1340` (one symbol period past waveform start): [Normal]
 * - `1340 ≤ t ≤ 7000`: [Late] with `offsetSymbols ∈ [1, 37]`
 * - `t > 7000`: [Deferred]
 * - `toggleEnabled == false`: always [Normal] (PARITY-01 escape hatch)
 */
internal fun computeLateTxPlan(tInSlotMs: Long, toggleEnabled: Boolean): LateTxPlan {
    if (!toggleEnabled) return LateTxPlan.Normal
    if (tInSlotMs < LATE_TX_FLOOR_MS) return LateTxPlan.Normal
    if (tInSlotMs > LATE_TX_CUTOFF_MS) return LateTxPlan.Deferred

    // (tInSlot - waveformStart) / symbolPeriod, rounded UP so the first emitted
    // sample is never inside a partial symbol.
    val rawOffset = (tInSlotMs - WAVEFORM_START_MS).toDouble() / SYMBOL_PERIOD_MS
    val offsetSymbols = kotlin.math.ceil(rawOffset).toInt().coerceAtLeast(1)
    val keyMomentInSlot = WAVEFORM_START_MS + offsetSymbols * SYMBOL_PERIOD_MS
    val waitMs = (keyMomentInSlot - tInSlotMs).coerceAtLeast(0L)
    return LateTxPlan.Late(offsetSymbols = offsetSymbols, waitMs = waitMs)
}

// Constants — see spec §Symbol-clock math.
internal const val WAVEFORM_START_MS = 1180L
internal const val SYMBOL_PERIOD_MS = 160L
internal const val LATE_TX_FLOOR_MS = WAVEFORM_START_MS + SYMBOL_PERIOD_MS  // 1340
internal const val LATE_TX_CUTOFF_MS = 7000L
internal const val LATE_TX_DRIFT_ABORT_MS = 80L
internal const val FT8_NN = 79
internal const val SAMPLES_PER_SYMBOL = 1920
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.LateTxPlanTest"`
Expected: PASS — all seven test methods pass. If `walkThroughExample_t3200` fails with `offsetSymbols=13 waitMs=79` (off-by-one ms), accept the discrepancy by widening the spec to `waitMs ∈ {79, 80}` OR change the rounding in `computeLateTxPlan` to use `round` instead of integer truncation. The invariant test (`lateWindowProducesValidPlans`) catches the actual correctness property.

- [ ] **Step 5: Run full app tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt \
        app/src/test/java/net/ft8vc/app/controllers/LateTxPlanTest.kt
git commit -m "feat(tx-orchestrator): add LateTxPlan + computeLateTxPlan helper

Pure logic — no I/O, no coroutines. Parameterized over t_in_slot and
toggle state. Wiring into transmitAfterSlotBoundary lands in the next
commit; this commit makes the decision logic testable in isolation."
```

---

### Task 8: Wire `computeLateTxPlan` into `TxOrchestrator.transmitAfterSlotBoundary`

**Files:**
- Modify: `app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt`
- Modify: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (pass the toggle provider into TxOrchestrator constructor)
- Create: `app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorLateTxTest.kt`

**Interfaces:**
- Consumes: `computeLateTxPlan` from Task 7; `Ft8DecoderApi.encode(..., offsetSymbols)` from Task 1; `SettingsBridge.slice.lateStartTxEnabled` from Task 4.
- Produces: `TxOrchestrator(..., lateStartTxEnabledProvider: () -> Boolean = { true })` new constructor parameter. `transmitAfterSlotBoundary` branches on `computeLateTxPlan`.

- [ ] **Step 1: Write the failing integration test**

Create `app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorLateTxTest.kt`:

```kotlin
package net.ft8vc.app.controllers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.ft8vc.app.SnackbarEvent
import net.ft8vc.audio.fakes.FakeUsbAudioPlayback
import net.ft8vc.core.AppInfo
import net.ft8vc.ft8native.fakes.Ft8DecoderFake
import net.ft8vc.rig.fakes.FakeRigBackend  // import path may differ; align with project's fake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TxOrchestratorLateTxTest {

    // Choose a known UTC instant that is exactly N ms into a slot.
    // SLOT_MS = 15_000; slotStart at 1_700_000_000_000 (mod 15000 = 0 conveniently?
    // Compute and pin: slotStart = epoch - epoch.mod(15000). Use a base.
    private val slotStartUtc = 1_700_000_000_000L - (1_700_000_000_000L % 15_000L)

    @Test
    fun lateTapAtSixPointFiveSecondsKeysImmediatelyAndPlaysTruncatedBuffer() = runTest {
        val tInSlot = 6_500L
        var now = slotStartUtc + tInSlot
        val clock = { now }

        val decoder = Ft8DecoderFake()
        // Producer: respect offsetSymbols and return a sized PCM buffer that mimics
        // the JNI contract (no silence padding when offset > 0).
        decoder.configureEncodeProducer { _, _, _, offset ->
            if (offset == 0) ShortArray(15 * 12_000) else ShortArray((79 - offset) * 1920)
        }

        val playback = FakeUsbAudioPlayback()
        val rig = FakeRigBackend()
        val rigSession = newRigSessionWith(rig)  // helper from existing TxOrchestratorTest; or inline
        val orchestrator = newOrchestrator(
            decoder = decoder,
            playback = playback,
            rigSession = rigSession,
            clock = clock,
            lateStartTxEnabledProvider = { true },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val txJob = launch {
            orchestrator.transmitAfterSlotBoundary("CQ TEST FN42", txFreqHz = 1500)
        }
        advanceUntilIdle()

        // Inspect: encode was called with offsetSymbols = ceil((6500 - 1180) / 160) = 34
        val invocations = decoder.encodeInvocationsSnapshot()
        assertEquals(1, invocations.size)
        assertEquals(34, invocations.single().offsetSymbols)

        // Inspect: playback was called with the expected sample count
        val played = playback.playInvocationsSnapshot()
        assertEquals(1, played.size)
        assertEquals((79 - 34) * 1920, played.single().pcmSize)

        // PTT was keyed exactly once
        assertEquals(1, rig.pttKeyCount())
        assertEquals(1, rig.pttReleaseCount())

        txJob.join()
    }

    @Test
    fun postCutoffTapDefersToNextSlotAndKeysAtBoundary() = runTest {
        val tInSlot = 7_500L
        var now = slotStartUtc + tInSlot
        val clock = { now }

        val decoder = Ft8DecoderFake()
        decoder.configureEncodeProducer { _, _, _, offset ->
            if (offset == 0) ShortArray(15 * 12_000) else ShortArray((79 - offset) * 1920)
        }
        val playback = FakeUsbAudioPlayback()
        val rig = FakeRigBackend()
        val rigSession = newRigSessionWith(rig)
        val orchestrator = newOrchestrator(
            decoder, playback, rigSession,
            clock = { now },
            lateStartTxEnabledProvider = { true },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val txJob = launch {
            orchestrator.transmitAfterSlotBoundary("CQ TEST FN42", txFreqHz = 1500)
        }

        // Wait for the slot boundary; advance virtual time
        now += 15_000L - tInSlot  // jump to next slot boundary
        advanceUntilIdle()

        val invocations = decoder.encodeInvocationsSnapshot()
        assertEquals(1, invocations.size)
        // After deferring to next slot the v1.0 path runs → offsetSymbols == 0
        assertEquals(0, invocations.single().offsetSymbols)
        assertEquals(15 * 12_000, playback.playInvocationsSnapshot().single().pcmSize)

        txJob.join()
    }

    @Test
    fun belowFloorTapRoutesThroughV1Path() = runTest {
        val tInSlot = 400L  // below 1340 floor
        var now = slotStartUtc + tInSlot
        val clock = { now }

        val decoder = Ft8DecoderFake()
        decoder.configureEncodeProducer { _, _, _, offset ->
            if (offset == 0) ShortArray(15 * 12_000) else ShortArray((79 - offset) * 1920)
        }
        val playback = FakeUsbAudioPlayback()
        val rig = FakeRigBackend()
        val rigSession = newRigSessionWith(rig)
        val orchestrator = newOrchestrator(
            decoder, playback, rigSession,
            clock = { now },
            lateStartTxEnabledProvider = { true },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val txJob = launch {
            orchestrator.transmitAfterSlotBoundary("CQ TEST FN42", txFreqHz = 1500)
        }
        now += 15_000L - tInSlot  // jump to next slot boundary
        advanceUntilIdle()

        assertEquals(0, decoder.encodeInvocationsSnapshot().single().offsetSymbols)
        txJob.join()
    }

    @Test
    fun toggleOffRoutesThroughV1Path() = runTest {
        val tInSlot = 3_000L
        var now = slotStartUtc + tInSlot

        val decoder = Ft8DecoderFake()
        decoder.configureEncodeProducer { _, _, _, offset ->
            if (offset == 0) ShortArray(15 * 12_000) else ShortArray((79 - offset) * 1920)
        }
        val playback = FakeUsbAudioPlayback()
        val rig = FakeRigBackend()
        val rigSession = newRigSessionWith(rig)
        val orchestrator = newOrchestrator(
            decoder, playback, rigSession,
            clock = { now },
            lateStartTxEnabledProvider = { false },  // toggle OFF
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val txJob = launch {
            orchestrator.transmitAfterSlotBoundary("CQ TEST FN42", txFreqHz = 1500)
        }
        now += 15_000L - tInSlot
        advanceUntilIdle()

        assertEquals(0, decoder.encodeInvocationsSnapshot().single().offsetSymbols)
        txJob.join()
    }

    // -- helpers --

    // newOrchestrator / newRigSessionWith are project-specific helpers — adapt to
    // the patterns in the existing TxOrchestratorTest. If no helper exists, inline
    // the constructor with the test scope and dispatcher. The key new parameter is
    // lateStartTxEnabledProvider.
}
```

Note: this test sketch uses helpers (`newOrchestrator`, `newRigSessionWith`) that the implementer should align with the existing `TxOrchestratorTest` patterns in the codebase. The structure of the assertions is the load-bearing part; adapt the construction to whatever the project's fake-wiring convention is.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.TxOrchestratorLateTxTest"`
Expected: FAIL — either compilation (no `lateStartTxEnabledProvider` parameter on `TxOrchestrator` ctor; `FakeUsbAudioPlayback.playInvocationsSnapshot` / `FakeRigBackend.pttKeyCount` may need to be added; etc.) or assertion failure on `offsetSymbols`.

- [ ] **Step 3: Add the `lateStartTxEnabledProvider` constructor parameter**

In `app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt`, add to the constructor parameter list (next to `clock`):

```kotlin
private val lateStartTxEnabledProvider: () -> Boolean = { true },
```

- [ ] **Step 4: Modify `transmitAfterSlotBoundary` to branch on `computeLateTxPlan`**

Replace the body of `transmitAfterSlotBoundary` (lines 135-143):

```kotlin
suspend fun transmitAfterSlotBoundary(message: String, txFreqHz: Int): Boolean {
    if (!preflight(message)) return false

    val plan = computeLateTxPlan(
        tInSlotMs = clock() - SlotTiming.slotStart(clock()),
        toggleEnabled = lateStartTxEnabledProvider(),
    )

    return when (plan) {
        is LateTxPlan.Normal,
        LateTxPlan.Deferred -> {
            // v1.0 path: wait for next slot boundary then full transmit.
            val waitMs = SlotTiming.millisUntilNextSlot(clock())
            if (waitMs > 0) {
                _slice.update { it.copy(txStatus = "TX in ${(waitMs + 999) / 1000}s…") }
                delay(waitMs)
            }
            doTransmit(message, txFreqHz, waitForSlotBoundary = true, offsetSymbols = 0)
        }
        is LateTxPlan.Late -> {
            doTransmitLate(message, txFreqHz, plan)
        }
    }
}
```

Add the late-TX entry next to `doTransmit`:

```kotlin
private suspend fun doTransmitLate(
    message: String,
    txFreqHz: Int,
    plan: LateTxPlan.Late,
): Boolean {
    // Snapshot the tap timestamp — used by the drift abort check after encode.
    val planTsMs = clock()

    val pcm = withContext(encodeDispatcher) {
        decoder.encode(message, txFreqHz.toFloat(), AppInfo.SAMPLE_RATE_HZ, plan.offsetSymbols)
    }
    if (pcm.isEmpty()) {
        notifyFn("Encoder rejected: $message", SnackbarEvent.Tag.ERROR)
        return false
    }

    // Drift-abort check: if encode + scheduling took longer than DRIFT_ABORT_MS,
    // the planned key moment may have slipped — fall through to next-slot v1.0 path.
    val driftMs = clock() - planTsMs
    if (driftMs > LATE_TX_DRIFT_ABORT_MS) {
        val waitMs = SlotTiming.millisUntilNextSlot(clock())
        if (waitMs > 0) {
            _slice.update { it.copy(txStatus = "TX in ${(waitMs + 999) / 1000}s…") }
            delay(waitMs)
        }
        return doTransmit(message, txFreqHz, waitForSlotBoundary = true, offsetSymbols = 0)
    }

    if (plan.waitMs > 0) delay(plan.waitMs)

    return runTxBody(message, txFreqHz, pcm)
}
```

And refactor `doTransmit` to accept `offsetSymbols` for the v1.0 path AND extract the PTT-key-and-play body into a shared `runTxBody`:

```kotlin
private suspend fun doTransmit(
    message: String,
    txFreqHz: Int,
    waitForSlotBoundary: Boolean,
    offsetSymbols: Int = 0,
): Boolean {
    val pcm = withContext(encodeDispatcher) {
        decoder.encode(message, txFreqHz.toFloat(), AppInfo.SAMPLE_RATE_HZ, offsetSymbols)
    }
    if (pcm.isEmpty()) {
        notifyFn("Encoder rejected: $message", SnackbarEvent.Tag.ERROR)
        return false
    }
    return runTxBody(message, txFreqHz, pcm)
}

private suspend fun runTxBody(message: String, txFreqHz: Int, pcm: ShortArray): Boolean {
    captureControl.pauseForTx()
    _slice.update { it.copy(isTransmitting = true, txStatus = "TX: $message") }

    val result: Boolean = try {
        withTimeoutOrNull(slotDurationMs + WATCHDOG_OUTER_GRACE_MS) {
            TxSession().use { session ->
                session.keyPtt()
                try {
                    val outputId = outputDeviceIdProvider()
                    withContext(encodeDispatcher) { playback.playBlocking(pcm, outputId) }
                } finally {
                    session.releasePtt()
                }
            }
        } ?: run {
            forceReleasePtt()
            notifyFn("TX timeout — PTT released", SnackbarEvent.Tag.ERROR)
            _slice.update { it.copy(txSafetyHaltActive = true) }
            false
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        forceReleasePtt()
        notifyFn(t.message ?: "Transmit failed", SnackbarEvent.Tag.ERROR)
        false
    }

    _slice.update {
        it.copy(
            isTransmitting = false,
            txStatus = if (result) "Sent: $message" else "TX halted",
        )
    }
    if (result) {
        _txLog.tryEmit(TxLogEvent(utcMillis = clock(), freqHz = txFreqHz, message = message))
    }
    captureControl.resumeAfterTx()
    return result
}
```

Note `runTxBody` is the existing body of `doTransmit` extracted verbatim — the 4-layer PTT defense is unchanged.

- [ ] **Step 5: Update `OperateViewModel`'s `TxOrchestrator` construction to pass the toggle provider**

Find where `TxOrchestrator(…)` is constructed in `OperateViewModel`. Add the named argument:

```kotlin
lateStartTxEnabledProvider = { settingsBridge.slice.value.lateStartTxEnabled },
```

(Use whatever the in-scope `SettingsBridge` reference name is — search for `SettingsBridge(` to confirm.)

- [ ] **Step 6: Run the integration tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.TxOrchestratorLateTxTest"`
Expected: PASS — all four test methods. Resolve any compile-time mismatches between the test sketch's helpers and the project's existing fake-wiring convention.

- [ ] **Step 7: Run the full app test suite to verify no regressions in existing `TxOrchestratorTest`**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. The existing `TxOrchestratorTest` should still pass — it constructs `TxOrchestrator` without `lateStartTxEnabledProvider`, which defaults to `{ true }`, but its tests use slot-boundary-aligned clocks so they always route through the `LateTxPlan.Normal` (sub-floor) or `Deferred` (post-cutoff) v1.0 path. If any existing test fails, examine the clock setup — it likely lands in the late-TX window unintentionally.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/net/ft8vc/app/controllers/TxOrchestrator.kt \
        app/src/main/java/net/ft8vc/app/OperateViewModel.kt \
        app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorLateTxTest.kt
git commit -m "feat(tx-orchestrator): wire late-TX into transmitAfterSlotBoundary

At entry, computeLateTxPlan picks Normal / Deferred / Late based on
t_in_slot and the lateStartTxEnabledProvider toggle. Normal and
Deferred route through the existing v1.0 path unchanged. Late
encodes with offsetSymbols, drift-aborts if scheduling slipped >80ms,
waits to symbol boundary, then runs the shared PTT-and-play body
(runTxBody) inside the existing 4-layer safety envelope."
```

---

### Task 9: PTT-safety re-runs with late-TX call injected

**Files:**
- Create: `app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorPttSafetyLateTxTest.kt`

**Interfaces:**
- Consumes: everything from prior tasks. No production code change.
- Produces: regression-locked assertion that the 4 PTT-safety layers still fire under late-TX.

- [ ] **Step 1: Locate the existing PTT-safety test cases**

Run: `grep -n "fun .*Ptt\|watchdog\|playBlocking.*throw\|AutoCloseable\|use {" app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorTest.kt`

Identify the four scenarios:
- (a) `playBlocking` throws synchronously → PTT released in finally
- (b) coroutine cancellation → AutoCloseable `close()` force-releases
- (c) `withTimeoutOrNull` trips → cancel + release
- (d) 250 ms watchdog → forced release + safety-halt snackbar

- [ ] **Step 2: Write a re-run test per scenario, injecting a late-TX call**

Create `app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorPttSafetyLateTxTest.kt`. For each scenario, copy the original test's structure, but:
- Set `clock` so `t_in_slot ∈ [1340, 7000]` (e.g. 3000 ms) so the late-TX path runs.
- Set `lateStartTxEnabledProvider = { true }`.
- Configure the decoder fake's `encodeProducer` to respect `offsetSymbols` and return a truncated buffer.
- Assert the same PTT-released / snackbar / state-update invariants as the original test.

Example structure for scenario (a):

```kotlin
@Test
fun lateTxPlaybackThrowReleasesPttInFinally() = runTest {
    val tInSlot = 3_000L
    val slotStart = 1_700_000_000_000L - (1_700_000_000_000L % 15_000L)
    var now = slotStart + tInSlot

    val decoder = Ft8DecoderFake().apply {
        configureEncodeProducer { _, _, _, offset ->
            if (offset == 0) ShortArray(15 * 12_000) else ShortArray((79 - offset) * 1920)
        }
    }
    val playback = FakeUsbAudioPlayback().apply {
        configureNextPlayThrows(RuntimeException("synthetic playback failure"))
    }
    val rig = FakeRigBackend()
    val rigSession = newRigSessionWith(rig)
    val orchestrator = newOrchestrator(
        decoder, playback, rigSession,
        clock = { now },
        lateStartTxEnabledProvider = { true },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    val result = orchestrator.transmitAfterSlotBoundary("CQ TEST", txFreqHz = 1500)
    advanceUntilIdle()

    assertEquals(false, result)
    assertEquals(1, rig.pttKeyCount())
    assertEquals(1, rig.pttReleaseCount())  // released in finally despite throw
}
```

Repeat for (b) cancellation, (c) outer timeout, (d) watchdog — adapt from the existing equivalent in `TxOrchestratorTest`. If `FakeUsbAudioPlayback.configureNextPlayThrows(…)` doesn't exist, add it (1-line state + check in `playBlocking`).

- [ ] **Step 3: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests "net.ft8vc.app.controllers.TxOrchestratorPttSafetyLateTxTest"`
Expected: PASS — every layer fires under late-TX exactly as under v1.0 TX.

- [ ] **Step 4: Run the full app suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/net/ft8vc/app/controllers/TxOrchestratorPttSafetyLateTxTest.kt \
        app/src/testFixtures/java/net/ft8vc/audio/fakes/FakeUsbAudioPlayback.kt  # if you extended the fake
git commit -m "test(tx-orchestrator): re-run 4 PTT-safety scenarios with late-TX

Locks in the Phase 5 PARITY invariant that all four PTT-defense
layers fire under the late-TX path as they do under v1.0 TX. No
production code change — pure regression guard."
```

---

### Task 10: Golden-trace gate — verify replay passes with toggle ON and OFF

**Files:** No code change. CI gate verification.

**Interfaces:**
- Consumes: existing golden-trace harness from Phase 0 FOUND-06 (search `docs/planning/field-sessions/` and `core/src/test` for the harness entry point — likely a `GoldenTraceTest`).

- [ ] **Step 1: Locate the golden-trace test and CI workflow**

Run:
```bash
find . -name "GoldenTrace*.kt" -o -name "*golden-trace*"
grep -rn "golden-trace\|GoldenTrace" .github/workflows/ 2>/dev/null | head -5
```

- [ ] **Step 2: Run the golden-trace replay locally with toggle OFF**

Force the toggle off in the test setup (either via a test-only `lateStartTxEnabledProvider = { false }` injection or by setting the DataStore preference). Run the harness.

Expected: PASS with byte-identical state-transition output to the Phase 0–7 baseline. If the test setup doesn't expose a toggle path, file a small follow-up TODO inline (do NOT push the test gate change to a separate task; surface the gap immediately).

- [ ] **Step 3: Run the golden-trace replay locally with toggle ON**

Same harness with the toggle defaulted ON.

Expected: PASS. The baseline trace contains no late-TX events (captured pre-feature), so every TX in it routes through `LateTxPlan.Normal` or `LateTxPlan.Deferred` and produces identical output.

- [ ] **Step 4: Confirm the CI workflow runs both paths**

If the CI workflow runs golden-trace replay only once (default toggle state), extend it to run both — toggle OFF and toggle ON — as two separate test invocations. The PARITY-01 escape-hatch assertion specifically requires both.

- [ ] **Step 5: Commit any CI workflow changes**

```bash
git add .github/workflows/<workflow>.yml  # if changed
git commit -m "ci(golden-trace): run replay with late-TX toggle ON and OFF

PARITY-01 escape-hatch assertion: with the toggle OFF, the golden-trace
replay must produce byte-identical state transitions to the Phase 0
baseline. With the toggle ON, the same baseline must still pass — the
v1.0 paths are reached for all sub-floor and post-cutoff taps."
```

If no CI change is needed (the workflow already invokes the harness in a way that exercises both paths), skip the commit and document that in the PR description.

---

### Task 11: Field verification — promotion-to-`main` gate (manual)

**Files:**
- Create: `docs/planning/field-sessions/late-tx-<YYYY-MM-DD>/README.md` (manual operator step)
- Create: `docs/planning/field-sessions/late-tx-<YYYY-MM-DD>/trace.jsonl` (captured by the operator on-rig)

**Interfaces:** None — this is a manual gate, not an automated task.

This task is the operator's promotion-to-`main` checkbox. Do NOT skip; do NOT promote without it.

- [ ] **Step 1: Build the unstable APK and install on the reference Android device**

```bash
./gradlew :app:installUnstableRelease
```

(Use `installUnstableDebug` if a release-keystore env isn't configured locally; the field gate cares about behavior, not signing.)

- [ ] **Step 2: Run an on-air session against a real partner on the FT-891 + Digirig**

Cover all required path coverage:

- **Late-TX path:** Tap Answer/Resume at `t > 3.0 s` into the slot during a real hunt. Confirm the trace shows PTT keyed at `t > 3.0 s` AND the partner's next decode confirms reception.
- **Past-cutoff path:** Tap Answer at `t > 7.0 s`. Confirm trace shows silent queue to next slot (no PTT in current slot).
- **Floor path:** Make a normal-timing TX (e.g. start a CQ) and confirm trace shows PTT keyed near slot boundary.
- **Toggle OFF path:** Disable the Settings toggle. Make a full QSO cycle. Confirm v1.0 behavior end-to-end (Answer always waits for next slot).

- [ ] **Step 3: Verify the recompose-baseline (Phase 0 FOUND-08) is not exceeded**

During the session, capture an Operate-tab recompose count over one full slot cycle (per the FOUND-08 methodology under `docs/planning/field-sessions/recompose-baseline-*/METHODOLOGY.md`). Confirm it does **not exceed** the Phase 0 baseline at all (late-TX has zero new Compose surfaces).

- [ ] **Step 4: Commit the session artifact**

```bash
git add docs/planning/field-sessions/late-tx-$(date +%Y-%m-%d)/
git commit -m "field-session: late-TX on-air verification on FT-891 + Digirig

All four path-coverage gates passed: late-TX (t > 3.0s), past-cutoff
queue (t > 7.0s), floor (normal-start), toggle OFF (v1.0 parity).
Recompose-count gate held against Phase 0 baseline."
```

- [ ] **Step 5: Reference the artifact in the promotion PR**

When opening the promotion-to-`main` PR, include the relative path to the session directory in the PR description under "Field verification."

---

## Self-Review

**Spec coverage check:**

| Spec section | Task |
|---|---|
| R1: JNI offset parameter | Tasks 1, 2 |
| R2: TxOrchestrator scheduling envelope | Task 8 |
| R3: ~~UI affordance~~ (spec says fully transparent — no task) | n/a (intentional) |
| R4: ~~A-priori~~ (out of scope) | n/a (out of scope) |
| R5: ~~AP orchestration~~ (out of scope) | n/a (out of scope) |
| R6: ~~AP badge~~ (out of scope) | n/a (out of scope) |
| R7: Settings toggle | Tasks 3, 4, 5, 6 |
| R8: Behavior-parity preservation on toggle OFF | Tasks 8, 10 |
| Symbol-clock math (1.18s framing) | Task 7 constants, Task 2 JNI |
| Snapshot-to-key drift abort (race window) | Task 8 |
| 4-layer PTT defense preserved | Task 9 |
| Field verification | Task 11 |

**Placeholder scan:** none — every step has either complete code, a verified-output command, or (for Task 11 manual gate) explicit operator actions.

**Type consistency:**
- `offsetSymbols: Int` — same name and type across `Ft8DecoderApi`, `Ft8Native`, fake, JNI signature, `LateTxPlan.Late`, `computeLateTxPlan` return, `encode` invocation in `doTransmit` / `doTransmitLate`. ✓
- `LateTxPlan.Late(offsetSymbols, waitMs)` constructor — both fields appear consistently in tests and in `computeLateTxPlan`. ✓
- `lateStartTxEnabledProvider: () -> Boolean` — same signature in `TxOrchestrator` constructor and in `OperateViewModel`'s construction site. ✓
- `WAVEFORM_START_MS`, `SYMBOL_PERIOD_MS`, `LATE_TX_FLOOR_MS`, `LATE_TX_CUTOFF_MS`, `LATE_TX_DRIFT_ABORT_MS` — all defined once in `TxOrchestrator.kt` (Task 7) and referenced symbolically afterwards. ✓
- `lateStartTxEnabled: Boolean` — same name in `StationSettings`, `SettingsSlice`, `OperateUiState`. ✓

**Known assumption flags for the implementer:**

1. **`OperateUiState` location** — Task 5 has the implementer locate it via grep. If the data class lives somewhere other than `app/src/main/java/net/ft8vc/app/OperateUiState.kt`, adapt paths.
2. **`SettingsBridge` reference name in `OperateViewModel`** — Task 5 / Task 8 assume the in-scope name is `settingsBridge`. If different, use whatever is in scope.
3. **`FakeUsbAudioPlayback` test API** — Task 8 / Task 9 assume `playInvocationsSnapshot()` and `configureNextPlayThrows(…)` exist or are easy to add. If the fake API differs, adapt the assertions while preserving the load-bearing checks (PTT key count, release count, encode offset, played sample size).
4. **`FakeRigBackend` test API** — Task 8 assumes `pttKeyCount()` / `pttReleaseCount()`. Adapt to the project's actual API.
5. **Walk-through example `waitMs` of 80 vs 79** — Task 7 acceptance accepts either due to ms rounding; the invariant test (`lateWindowProducesValidPlans`) is the load-bearing correctness check.

If any of these assumptions doesn't match reality, surface as a question rather than making a silent choice.
