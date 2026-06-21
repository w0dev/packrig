# Testing

FT8VC has two test tiers: **JVM unit tests** (fast, no device) and **Android
instrumented tests** (device/emulator, loads native code). CI runs unit tests only;
instrumented tests are run locally when validating the native decoder.

## Prerequisites

| Requirement | Unit tests | Instrumented tests |
|-------------|------------|-------------------|
| JDK 17 | Yes | Yes |
| Android SDK (API 36 platform, build-tools 36.0.0) | Yes (Gradle test task) | Yes |
| NDK 29.0.14206865 + CMake 4.1.2 | No | Yes (builds `libft8vc.so`) |
| Connected device or emulator | No | Yes |
| Internet on first native build | No | Yes (`FetchContent` downloads `ft8_lib`) |

See [SDK_SETUP.md](SDK_SETUP.md) for SDK Manager install steps.

## Running all unit tests

From the repo root:

```powershell
.\gradlew.bat testDebugUnitTest
```

Linux/macOS:

```bash
./gradlew testDebugUnitTest
```

This runs every module's `src/test` suite in parallel where Gradle allows it.

### Run tests for one module

```powershell
.\gradlew.bat :core:testDebugUnitTest
.\gradlew.bat :audio:testDebugUnitTest
.\gradlew.bat :rig:testDebugUnitTest
.\gradlew.bat :data:testDebugUnitTest
```

The `app` module has no unit tests yet. `ft8-native` has no JVM unit tests — its
tests are instrumented (see below).

### CI

GitHub Actions (`.github/workflows/build.yml`) runs `gradle testDebugUnitTest` on
every push/PR to `main`, then `assembleDebug`. Instrumented tests are **not** run
in CI.

---

## Unit tests by module

### `core` — 6 test classes, 30+ tests

Pure Kotlin logic; no Android framework dependencies.

| Test class | What it verifies |
|------------|------------------|
| `SlotTimingTest` | UTC slot grid, `isEvenSlot`, `secondsUntilNextSlot` |
| `TxSlotSelectionTest` | Even/Odd TX parity and countdown to next TX period |
| `SlotCollectorTest` | PCM slot accumulation and boundary flush |
| `QsoMessagesTest` | FT8 message format/parse, CQ modifier |
| `MonitorDecodeFilterTest` | Operate/All decode list display filters |
| `AbandonedPartnersTest` | Session blocklist for abandoned incomplete QSOs |
| `AnswerSelectorTest` | Pileup / CQ / resume selection by answer policy |
| `MaidenheadGridTest` | 4-char grid validation and distance |
| `DecodeDistanceTest` | Grid extraction from decodes and km label formatting |
| `ActivationProfileTest` | POTA park ref, CQ modifier |
| `QsoMachineTest` | Full QSO sequences, CQ POTA TX, pileup policy, no-reply counter, abandoned pileup skip, manual override, `applyForm`, `QsoSnapshot`, edge cases |
| `QsoFormLogicTest` | Step labels, compose/effective message, report parsing |
| `OperateTxOptionsTest` | Idle vs active QSO TX dropdown entries |
| `WavIoTest` | WAV I/O for golden decode fixtures |

Run:

```powershell
.\gradlew.bat :core:testDebugUnitTest
```

### `audio` — 3 test classes, 7 tests

DSP helpers used for USB audio rate conversion and the waterfall.

| Test class | What it verifies |
|------------|------------------|
| `FftTest` | Rejects non-power-of-two sizes; cosine peaks at expected bin; DC input in bin 0 |
| `FirDecimatorTest` | Output length is input/factor; passband tone survives, aliasing tone is attenuated |
| `UpsamplerTest` | Linear upsampling by factor; factor 1 returns input unchanged |

Run:

```powershell
.\gradlew.bat :audio:testDebugUnitTest
```

### `rig` — 2 test classes, 15 tests

Pure protocol/constant helpers; no USB hardware required.

| Test class | What it verifies |
|------------|------------------|
| `Cp210xTest` | CP2102 `SET_MHS` RTS values, 8N1 line control, little-endian baud rate bytes |
| `Ft891CatTest` | FT-891 CAT frequency set/query (`FA`), mode set/query (`MD0`), padding, range checks, parsing |

Run:

```powershell
.\gradlew.bat :rig:testDebugUnitTest
```

### `data` — 2 test classes

| Test class | What it verifies |
|------------|------------------|
| `AdifWriterTest` | ADIF 3.1.4 header, FT8 records, POTA fields, validation |
| `AdifValidatorTest` | Length checks, required fields, rejects SUBMODE for FT8 |

Run:

```powershell
.\gradlew.bat :data:testDebugUnitTest
```

---

## Instrumented tests (`ft8-native`)

These run on a connected Android device or emulator because they load `libft8vc.so`.

| Test class | Location |
|------------|----------|
| `Ft8DecodeInstrumentedTest` | `ft8-native/src/androidTest/java/net/ft8vc/ft8native/` |

### Test cases

| Test | Description | Skips when |
|------|-------------|------------|
| `nativeLibraryLoads` | Asserts `Ft8Native.isAvailable()` and `Ft8Native.version()` | Never (fails if `.so` missing) |
| `encodeThenDecodeRoundTrips` | Encodes `"CQ W0DEV EM26"`, decodes the PCM, asserts exact message recovery | Never |
| `decodesGoldenWavIfPresent` | Loads `ft8_test.wav`, runs decode, asserts ≥1 message (+ optional expected substrings) | `ft8_test.wav` absent |

### Golden WAV assets

Place test clips in `ft8-native/src/androidTest/assets/`:

| File | Required | Description |
|------|----------|-------------|
| `ft8_test.wav` | For golden test | 12 kHz mono 16-bit PCM WAV of a real FT8 slot (~15 s). Export from WSJT-X or similar. |
| `ft8_test.expected.txt` | Optional | One expected message substring per line (e.g. a callsign or `CQ`). Each line must appear in some decode. |

If `ft8_test.wav` is absent, `decodesGoldenWavIfPresent` **self-skips** via JUnit
`Assume.assumeTrue`, so CI stays green without committed capture data. The repo
currently includes a `ft8_test.wav` asset.

See also the inline README at
`ft8-native/src/androidTest/assets/README.md`.

### Running instrumented tests

Connect a device or start an emulator, then:

```powershell
.\gradlew.bat :ft8-native:connectedDebugAndroidTest
```

View decode output in logcat (tag `Ft8DecodeTest`):

```powershell
adb logcat -s Ft8DecodeTest Ft8Native
```

The round-trip test validates encode + decode without any radio hardware. The golden
WAV test validates decode against real on-air or recorded audio.

---

## Writing new tests

- **Pure logic** (no Android APIs): add to the module's `src/test/java/` and run
  with `testDebugUnitTest`. Prefer keeping business logic in `core` or pure helpers
  in `rig` so tests stay on the JVM.
- **Native codec / JNI**: add instrumented tests under `ft8-native/src/androidTest/`.
  Use `WavIo.readPcm16` from `core` to load WAV fixtures.
- **UI / USB / audio hardware**: not yet covered by automated tests; manual
  validation on a Digirig + FT-891 is documented in [HARDWARE.md](HARDWARE.md).

### Conventions

- JUnit 4 (`org.junit.Test`)
- Test method names describe behavior (`initiatorRunsFullSequence`, not `test1`)
- Slot timing tests use fixed epoch milliseconds (1970-01-01 UTC) for determinism
- QSO tests build `List<QsoDecode>` via small helpers rather than mocking decoders
