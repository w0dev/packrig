# FT8VC

**FT8, vibe-coded.** An open-source Android FT8 transceiver app for amateur radio,
designed to drive a rig through a USB audio + serial interface (e.g. a
[Digirig Mobile](https://digirig.net/)) from your phone.

> Status: **v1.0.0** on `main` when tagged; day-to-day work lands on **`unstable`**
> with CI-built test APKs. See [docs/RELEASE.md](docs/RELEASE.md) and [Roadmap](#roadmap).

## Why

FT8VC aims for a clean, focused operating UI with a reliable decoder,
distributed as signed APKs from GitHub Releases. Target field setup:
**Yaesu FT-891 + Digirig Mobile** over USB-C OTG.

## Architecture

Kotlin + Jetpack Compose UI on top of a C/C++ NDK core that wraps
[`kgoba/ft8_lib`](https://github.com/kgoba/ft8_lib) (MIT) for FT8 encode/decode.

```
ft8vc/
  app/          Compose UI (Operate / Log / Settings), ViewModels
  core/         Slot scheduler, FT8 message models, QSO state machine
  audio/        12 kHz USB audio capture/playback (AAudio/Oboe)
  rig/          PTT + CAT backends; Digirig first
  data/         Room logbook + ADIF export
  ft8-native/   NDK module: JNI bridge -> ft8_lib + DSP front-end
```

## Building

See [docs/SDK_SETUP.md](docs/SDK_SETUP.md). Quick start:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

Install the debug APK on a device with USB OTG + Digirig, or use Android Studio **Run**.

## Hardware

Field target is the **Yaesu FT-891 + Digirig Mobile** via USB-C OTG. See
[docs/HARDWARE.md](docs/HARDWARE.md) for menu settings and validation checklist.

## Roadmap

| Phase | Scope |
|-------|-------|
| 0–2 | Skeleton, USB audio, FT8 decode (**done**) |
| 3 | TX + PTT + QSO state machine (**done**) |
| 4 | Operating UI: Operate / Settings / slot clock (**done**) |
| 5 | Room logbook, ADIF export, v1.0.0 release (**done**) |

Post-v1: ADIF import, NTP clock, decode filters, PSK Reporter.

## Legal

Transmitting requires a valid amateur radio license for your jurisdiction. The
app defaults to receive-only; TX must be explicitly enabled in Settings and
tested into a dummy load.

## License

[MIT](LICENSE). Built on `kgoba/ft8_lib` (MIT).
