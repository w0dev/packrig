# FT8VC

**FT8, vibe-coded.** An open-source Android FT8 transceiver app for amateur radio,
designed to drive a rig through a USB audio + serial interface (e.g. a
[Digirig Mobile](https://digirig.net/)) from your phone.

> Status: **Phase 0 (skeleton)**. The module structure, NDK/JNI toolchain, and CI
> build are in place. On-air decoding and transmit land in later phases — see
> [Roadmap](#roadmap).

## Why

Existing mobile/web FT8 apps are either buggy or have rough UX. FT8VC aims for a
clean, POTAcat-inspired operating UI with a reliable decoder, distributed as
signed APKs from GitHub Releases. Target field setup: **Yaesu FT-891 + Digirig
Mobile** over USB-C OTG.

## Architecture

Kotlin + Jetpack Compose UI on top of a C/C++ NDK core that wraps
[`kgoba/ft8_lib`](https://github.com/kgoba/ft8_lib) (MIT) for FT8 encode/decode.

```
ft8vc/
  app/          Compose UI, navigation, MainActivity
  core/         Slot scheduler, FT8 message models, app constants  (pure Kotlin + unit tests)
  audio/        12 kHz USB audio capture/playback (AAudio/Oboe)    [Phase 1 / 3]
  rig/          PTT + CAT backends; Digirig first                  [Phase 3 / v1.x]
  data/         Room logbook + ADIF import/export                  [Phase 5]
  ft8-native/   NDK module: JNI bridge -> ft8_lib + DSP front-end  [Phase 2]
```

The native library builds to `libft8vc.so`; `net.ft8vc.ft8native.Ft8Native`
is the Kotlin entry point. In Phase 0 it only reports a version string to prove
the JNI bridge loads.

## Building

### Android Studio (recommended)

Install [Android Studio](https://developer.android.com/studio) (bundles the JDK,
Android SDK, NDK, and CMake), then **Open** this folder and run the `app`
configuration on a device or emulator.

### Command line

Requires JDK 17, the Android SDK, NDK `26.1.10909125`, and CMake `3.22.1`.

This repo does not commit the `gradle-wrapper.jar` binary. Generate the wrapper
once (needs a local Gradle 8.9+), then use it:

```bash
gradle wrapper --gradle-version 8.9
./gradlew assembleDebug        # build the debug APK
./gradlew testDebugUnitTest    # run unit tests (e.g. SlotTiming)
```

CI (`.github/workflows/build.yml`) provisions Gradle directly and is the
canonical build until the wrapper jar is added.

## Hardware

Field target is the **Yaesu FT-891 + Digirig Mobile** via USB-C OTG. The Digirig
presents a USB audio device (CM108) and a USB-serial bridge (CP2102); PTT is keyed
on the serial RTS line, and FT-891 CAT control rides the same port. Some phones
need a **powered OTG hub**. See [docs/HARDWARE.md](docs/HARDWARE.md).

## Roadmap

| Phase | Scope |
|-------|-------|
| 0 | Skeleton: modules, NDK toolchain, CI (**done**) |
| 1 | USB audio capture + live waterfall |
| 2 | FT8 decode core (`ft8_lib` via JNI) + golden-WAV tests |
| 3 | TX + PTT + QSO state machine (validated into a dummy load) |
| 4 | POTAcat-style operating UI |
| 5 | Room logbook + ADIF, signed release, v1.0.0 |

## Legal

Transmitting requires a valid amateur radio license for your jurisdiction. The
app defaults to receive-only; TX must be explicitly enabled and tested into a
dummy load. You are responsible for lawful operation.

## License

[MIT](LICENSE). Built on `kgoba/ft8_lib` (MIT). Do not copy GPL-licensed
WSJT-X / JS8Call source into this repository.
