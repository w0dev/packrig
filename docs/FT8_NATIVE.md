# FT8 Native module (`ft8-native/`)

Android NDK library (`libpackrig.so`) that wraps [`kgoba/ft8_lib`](https://github.com/kgoba/ft8_lib)
(MIT) for FT8 encode and decode. Exposed to Kotlin via JNI as `Ft8Native`.

## Purpose

Phase 2 deliverable: a reproducible, testable FT8 codec running on-device. The app
feeds 12 kHz mono PCM; the native layer handles FT8 modulation, sync, LDPC decode,
and message extraction.

## Kotlin API

### `Ft8Native` (object)

| Method | Description |
|--------|-------------|
| `isAvailable()` | True if `libpackrig.so` loaded successfully |
| `version()` | Native build identifier string |
| `decode(samples, sampleRate)` | Decode one FT8 slot; returns `Array<Ft8DecodeResult>` |
| `encode(message, freqHz, sampleRate)` | Encode message to a full ~15 s slot of PCM |

Default sample rate is 12_000 Hz. On load failure, decode/encode return empty results
without crashing.

### `Ft8DecodeResult`

One decoded message (field order matches JNI constructor):

| Field | Type | Meaning |
|-------|------|---------|
| `message` | String | Decoded text, e.g. `"CQ K1ABC FN42"` |
| `snr` | Int | Estimated SNR (dB, approximate) |
| `dtSeconds` | Float | Time offset within the slot |
| `freqHz` | Float | Audio frequency of the signal |
| `score` | Int | Raw Costas sync score (decoder confidence) |

## Native build

### CMake (`src/main/cpp/CMakeLists.txt`)

- **FetchContent** downloads `kgoba/ft8_lib` at pinned commit
  `9fec6ca39886edbf96f4f5e71edc76da5074e871` (first configure needs internet).
  To bump the pin, follow [FT8_LIB_UPGRADE.md](FT8_LIB_UPGRADE.md)
- Compiles a subset of ft8_lib (no PortAudio / file I/O — PCM comes from Android)
- Links `ft8_jni.cpp` JNI bridge
- **16 KB page alignment** linker flags for Android 15+ / Pixel emulators (NDK r29)

### ABIs

`arm64-v8a`, `armeabi-v7a`, `x86_64` (emulator)

### Toolchain pins

See `gradle/libs.versions.toml` and [SDK_SETUP.md](SDK_SETUP.md):

- NDK 29.0.14206865
- CMake 4.1.2

After NDK changes, do a full native clean rebuild:

```powershell
.\gradlew.bat clean :ft8-native:externalNativeBuildCleanDebug :app:assembleDebug
```

## Dependencies

- `core` — `WavIo` used by instrumented tests
- Android NDK, CMake

## Tests

Instrumented only (loads native library). See [TESTING.md](TESTING.md#instrumented-tests-ft8-native).

| Test | Validates |
|------|-----------|
| `nativeLibraryLoads` | JNI link and version string |
| `encodeThenDecodeRoundTrips` | Full TX waveform path without radio |
| `decodesGoldenWavIfPresent` | Real capture clip regression |

Golden WAV assets live in `ft8-native/src/androidTest/assets/`. See
`ft8-native/src/androidTest/assets/README.md`.

```powershell
.\gradlew.bat :ft8-native:connectedDebugAndroidTest
```

## Integration in the app

`OperateViewModel`:

1. `SlotCollector` delivers ~15 s of PCM per UTC slot
2. `Ft8Native.decode()` runs on a background executor
3. Results populate the decode list and feed `QsoMachine.onDecodes()`
4. TX uses `Ft8Native.encode()` before `UsbAudioPlayback.playBlocking()`

## Related docs

- [CORE.md](CORE.md) — slot timing and WAV I/O
- [AUDIO.md](AUDIO.md) — 12 kHz capture/playback
- [TESTING.md](TESTING.md) — golden WAV test setup
