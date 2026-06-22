# FT8VC documentation

Component-level documentation for the FT8VC Android project. For project overview, features, and build instructions, see the [root README](../README.md).

For AI agents and contributors: see [AGENTS.md](../AGENTS.md) (mandatory unit tests + doc updates for applicable changes).

Start here for component docs; existing setup guides remain at [SDK_SETUP.md](SDK_SETUP.md) and [HARDWARE.md](HARDWARE.md).

## Setup and hardware

| Document | Description |
|----------|-------------|
| [SDK_SETUP.md](SDK_SETUP.md) | Android SDK, NDK, and CMake versions to install |
| [HARDWARE.md](HARDWARE.md) | Yaesu FT-891 + Digirig Mobile field setup |

## Architecture modules

| Document | Module |
|----------|--------|
| [APP.md](APP.md) | `app/` — Operate / Spectrum / Log / Settings UI, ViewModels |
| [CORE.md](CORE.md) | `core/` — Slot timing, QSO state machine, activation profile, WAV I/O |
| [AUDIO.md](AUDIO.md) | `audio/` — USB capture/playback, DSP, waterfall |
| [FT8_NATIVE.md](FT8_NATIVE.md) | `ft8-native/` — NDK JNI bridge to `kgoba/ft8_lib` |
| [RIG.md](RIG.md) | `rig/` — Digirig PTT + FT-891 CAT over CP2102 |
| [DATA.md](DATA.md) | `data/` — Room logbook + ADIF export (incl. POTA fields) |

## Testing

| Document | Description |
|----------|-------------|
| [TESTING.md](TESTING.md) | Unit tests, instrumented tests, golden WAV assets, CI |
| [RELEASE.md](RELEASE.md) | Signed release, tagging, field smoke checklist |

## Module dependency graph

```
app
 ├── core
 ├── audio ──► core
 ├── rig
 ├── data
 └── ft8-native ──► core
```

The `app` module wires everything together in `MonitorViewModel`. Pure logic lives
in `core`, `rig` (CAT string builders), and `ft8-native` (codec). Android-specific
I/O lives in `audio` and `rig`.
