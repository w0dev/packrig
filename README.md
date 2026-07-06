# FT8VC

**FT8, vibe-coded.** An open-source Android FT8 transceiver app for amateur radio,
designed to drive a rig through a USB audio + serial interface (e.g. the
[Digirig Mobile](https://digirig.net/)) directly from your phone — no laptop in the field.

> **Status:** v1.0.0 from tagged commits on `main`. Day-to-day development and field
> testing happen on **`unstable`** with CI-built APKs. See [docs/RELEASE.md](docs/RELEASE.md).

## Why

FT8VC aims for a clean, focused operating UI with a reliable decoder, distributed as
signed APKs from GitHub Releases. The reference field setup is a **Yaesu FT-891 +
Digirig Mobile over USB-C OTG** — but anything that exposes a USB audio device and
a CP2102-style serial bridge will get you on the air for RX.

## Install

1. Open the [Releases page](https://github.com/w0dev/ft8vc/releases) and download
   the latest `net.ft8vc-*.apk`.
2. Allow installs from unknown sources for your browser/file manager when prompted.
3. Tap the APK to install. The app is signed with a stable key, so future versions
   upgrade in place.
4. Want the bleeding edge? Grab the `net.ft8vc.unstable` artifact from a recent
   **Unstable APK** workflow run. Unstable and stable install side by side.

A valid amateur radio license is required to transmit. The app boots into
**receive-only** and will not key the rig until you enable TX in Settings.

## Documentation

The [Operator's Manual](docs/manual/README.md) is the user-facing
documentation. [Getting started](docs/manual/getting-started.md) — install,
hookup, configuration, and your first receive session — is written today;
operating, logging, settings-reference, and troubleshooting chapters are
planned. The quick start below is the condensed version.

## Quick start

1. **Plug in the radio** — phone → USB-C OTG cable → Digirig → rig DATA and CAT
   ports. A powered OTG hub is sometimes needed.
2. **Grant USB permission** when Android prompts for the Digirig audio and serial
   devices.
3. **Set up Station** (Settings → Station) — enter your callsign and 4- or 6-char
   Maidenhead grid. POTA activators can flip on **Activation (POTA)** and enter the
   park reference; CQs become `CQ POTA <call> <grid>` and ADIF exports add the
   `MY_SIG` / `MY_SIG_INFO` fields.
4. **Pick the audio input** (Settings → Audio) — choose the Digirig USB device.
5. **Read the rig** (Settings → Rig) — confirm CAT comes back with a frequency and
   mode, then **Set DATA-U** if you are not already in FT8 mode.
6. **Acknowledge the license** and enable transmit when you are ready to call.
7. **Operate tab → Start** — decodes appear within one 15 s slot. Tap a CQ to
   answer, or **Start CQ** to call. Completed QSOs auto-log to the Log tab; export
   ADIF from there.

See [docs/HARDWARE.md](docs/HARDWARE.md) for the FT-891 menu values that the
reference setup uses.

## Features

Four tabs — **Operate**, **Spectrum**, **Log**, **Settings** — cover a full
portable FT8 session.

### Operate

- USB audio RX from the Digirig (12 kHz, UTC slot-aligned decode)
- Compact 3-row status bar: dial MHz (tap to retune via CAT), mode, **TX tone**
  chip, POTA chip, **Halt TX** button; slot progress, UTC countdown, **Even/Odd**
  TX slot chips with a **TX Ns** countdown to your next TX period. A **clock
  offset chip** appears when the device clock drifts from band time — tap it to
  align to the decoded slot (see Settings → Clock alignment)
- Decode list: CQ highlighted, traffic directed to you in amber; QSO partner in
  bold; **distance (km)** shown when a 4-char grid is in the message; a two-letter
  **country (CC)** column; the UTC cell is tinted by slot parity so Even/Odd slots
  read apart at a glance. **Long-press a row to block** that sender
- **Focus** mode (default) hides chatter unrelated to your call, the active QSO
  partner, or signals near your TX tone. **Band** mode shows the full passband.
  Optional **CQ·73** chip narrows further when browsing.
- Inline level meter and one-tap input gain while operating
- A capture-stall **watchdog** auto-restarts USB audio if the RX thread goes
  silent; a **tap-to-retry** chip appears if automatic recovery is exhausted

### Spectrum

- Full-screen waterfall on its own tab; tap or drag to set the TX audio offset
- TX tone persists across tabs and shows on the Operate status bar
- Decode **markers** overlay the waterfall — CQ callers get labelled ticks, and a
  **Labels** toggle shows or hides the callsign text. Your 50 Hz TX band is drawn
  on the waterfall and tints red when it clashes with a nearby decode; a live
  **TX Hz** readout confirms your offset (markers toggle persists in Settings)
- **Dial** label (when CAT is ready): tap to pick a band / preset FT8 dial
  frequency and tune the rig over CAT
- Dark/Light mode toggle in Settings → Display (whole app commits to the choice)

### TX and QSO automation

- PTT via Digirig serial (CP2102 RTS) or CAT (`TX1;`/`TX0;`); preference in Settings
- Yaesu **FT-891 CAT**: read band/mode, set dial frequency, one-tap **DATA-U**
- Auto-seq QSO state machine (CQ → grid → reports → RRR → 73)
- **Start CQ** waits for your chosen Even/Odd TX slot; tap any CQ to answer
- **Answer when called** auto-resumes when someone calls you; **Auto answer CQ**
  hunts CQs when idle; **Resume CQ after QSO** keeps calling CQ once a contact you
  started by calling CQ wraps up (Search & Pounce QSOs do not auto-resume).
  Independent toggles in Settings → Operating
- Answer selection policy: **First**, **Best SNR**, or **Furthest** (great-circle
  km from your grid)
- **Abandon after N TX cycles** with no reply auto-drops the non-responder from
  further auto-answer / auto-CQ selection for the rest of the session
- Single **End QSO** button ends the active contact; long-press a decode to block
  a sender outright. Blocked stations are hidden from the decode list and managed
  in **Settings → Blocklist** (per-call **Unblock** or **Clear all**)
- License acknowledgment gates the first TX

### Activation (POTA)

- POTA mode toggle and park reference (e.g. `US-3315`)
- CQs become `CQ POTA <call> <grid>` on-air
- ADIF export adds `MY_SIG = POTA` and `MY_SIG_INFO = <ref>`; export fails closed
  if POTA mode is on without a valid park reference

### Log and ADIF

- Room-backed logbook; completed QSOs auto-log
- **Export ADIF** (3.1.4) via Android share intent
- Persisted station profile: call, grid, audio device, TX tone, theme choice,
  auto-TX preferences

## Architecture

Kotlin + Jetpack Compose UI on top of a C/C++ NDK core that wraps
[`kgoba/ft8_lib`](https://github.com/kgoba/ft8_lib) (MIT) for FT8 encode/decode.

```
ft8vc/
  app/          Compose UI (Operate / Spectrum / Log / Settings), ViewModels
  core/         Slot scheduler, FT8 message models, QSO state machine
  audio/        12 kHz USB audio capture/playback, DSP, waterfall
  rig/          PTT + CAT backends; Digirig first
  data/         Room logbook + ADIF export
  ft8-native/   NDK module: JNI bridge → ft8_lib + DSP front-end
```

## Building from source

See [docs/SDK_SETUP.md](docs/SDK_SETUP.md) for the exact SDK / NDK / CMake versions
to install. Quick start:

```bash
# macOS / Linux
./gradlew testDebugUnitTest assembleDebug
```

```powershell
# Windows
.\gradlew.bat testDebugUnitTest assembleDebug
```

Install the debug APK on a device with USB OTG + Digirig, or use Android Studio
**Run**. First native build downloads `ft8_lib` via CMake `FetchContent` and needs
internet.

## Hardware

The reference field setup is a **Yaesu FT-891 + Digirig Mobile** via USB-C OTG.
[docs/HARDWARE.md](docs/HARDWARE.md) lists the FT-891 menu values, the Digirig
audio/serial wiring, and a validation checklist for first-on-air.

## Documentation

| Topic | Document |
|-------|----------|
| Component overview | [docs/README.md](docs/README.md) |
| App UI and ViewModels | [docs/APP.md](docs/APP.md) |
| Release and unstable APKs | [docs/RELEASE.md](docs/RELEASE.md) |
| SDK / NDK setup | [docs/SDK_SETUP.md](docs/SDK_SETUP.md) |
| Field hardware | [docs/HARDWARE.md](docs/HARDWARE.md) |
| Testing | [docs/TESTING.md](docs/TESTING.md) |

For contributors and AI coding agents: [AGENTS.md](AGENTS.md) covers the test +
docs definition of done and the toolchain pins.

## Current limitations

- FT-891 CAT only (VFO-A, DATA-U). Other rigs RX-only via USB audio.
- No ADIF import
- No split-frequency operation

## Legal

Transmitting requires a valid amateur radio license for your jurisdiction. The
app defaults to receive-only; TX must be explicitly enabled in Settings, and
testing into a dummy load first is strongly recommended.

## Acknowledgements

FT8VC stands on the work of others:

- **FT8 itself** was created by **Steven Franke, K9AN, and Joe Taylor, K1JT**
  (the name stands for "Franke–Taylor design, 8-FSK modulation") and first
  shipped in WSJT-X in 2017. The openly published FT4/FT8 protocol
  specification is what makes independent implementations like this one
  possible. Thank you.
- [`kgoba/ft8_lib`](https://github.com/kgoba/ft8_lib) by **Kārlis Goba,
  YL3JG**, does the actual FT8 encode/decode in FT8VC's native core.
- **Kiss FFT** by **Mark Borgerding**, bundled with ft8_lib, powers the FFT.
- The FT8 software that came before —
  [WSJT-X](https://wsjt.sourceforge.io/wsjtx.html) and the WSJT development
  group, JTDX, and Android pioneers like FT8CN.

## License

FT8VC is licensed under the [MIT License](LICENSE). Bundled third-party
components and their license texts are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
