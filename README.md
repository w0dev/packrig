# FT8VC

**FT8, vibe-coded.** An open-source Android FT8 transceiver app for amateur radio,
designed to drive a rig through a USB audio + serial interface (e.g. a
[Digirig Mobile](https://digirig.net/)) from your phone.

> **v1.0.0** ships from tagged commits on `main`. Day-to-day development and field
> testing happen on **`unstable`** with CI-built APKs. See [docs/RELEASE.md](docs/RELEASE.md).

## Why

FT8VC aims for a clean, focused operating UI with a reliable decoder,
distributed as signed APKs from GitHub Releases. Target field setup:
**Yaesu FT-891 + Digirig Mobile** over USB-C OTG.

## Features

Four tabs — **Operate**, **Spectrum**, **Log**, and **Settings** — cover a full portable FT8 session.

### Operate

- USB audio RX from the Digirig (12 kHz, UTC slot-aligned decode)
- Decode list: CQ highlighted in green, traffic directed to you in amber
- UTC slot countdown, rig frequency/mode, **TX tone indicator**, and QSO state in the status bar
- Optional **CQ/73 only** filter; **Operate** focus (CQ + replies to you + QSO partner) on decode list
- Input level meter and gain control while operating

### Spectrum

- Full-screen waterfall on a dedicated tab; tap/drag TX frequency cursor
- TX tone persists and shows on Operate and Spectrum

### TX and QSO automation

- PTT via Digirig serial (CP2102 RTS)
- Yaesu **FT-891 CAT**: read band/mode, pick FT8 dial frequencies, one-tap **DATA-U**
- Auto-seq QSO state machine (CQ → grid → reports → RRR → 73)
- **Start CQ**, tap a CQ to answer, **Stop QSO** / resume when called again
- Auto-answer when called (toggle on Operate); license acknowledgment before first TX
- Manual TX message for bench testing (Settings → Advanced)

### Log and settings

- Room logbook; completed QSOs logged automatically
- **Export ADIF** via Android share intent
- Persisted station profile: call, grid, audio device, TX tone, waterfall brightness

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

## Documentation

| Topic | Document |
|-------|----------|
| Component overview | [docs/README.md](docs/README.md) |
| App UI and ViewModels | [docs/APP.md](docs/APP.md) |
| Release and unstable APKs | [docs/RELEASE.md](docs/RELEASE.md) |
| SDK / NDK setup | [docs/SDK_SETUP.md](docs/SDK_SETUP.md) |
| Field hardware | [docs/HARDWARE.md](docs/HARDWARE.md) |

## Current limitations

- FT-891 CAT only (VFO-A, DATA-U)
- No ADIF import
- No split-frequency operation

## Legal

Transmitting requires a valid amateur radio license for your jurisdiction. The
app defaults to receive-only; TX must be explicitly enabled in Settings and
tested into a dummy load.

## License

[MIT](LICENSE). Built on `kgoba/ft8_lib` (MIT).
