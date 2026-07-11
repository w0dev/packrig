# FT8VC

**FT8, vibe-coded.** An open-source Android FT8 transceiver app for amateur radio,
designed to drive a rig through a USB audio + serial interface (e.g. the
[Digirig Mobile](https://digirig.net/)) or the rig's own USB port, directly from
your phone — no laptop in the field.

> **Status:** v1.0.0 from tagged commits on `main`. Day-to-day development and field
> testing happen on **`unstable`** with CI-built APKs. See [docs/RELEASE.md](docs/RELEASE.md).

## Why

FT8VC aims for a clean, focused operating UI with a reliable decoder, distributed as
signed APKs from GitHub Releases. The verified reference setups are a **Yaesu FT-891 +
Digirig Mobile** and a **Yaesu FTX-1 over its built-in USB port**, both via USB-C OTG —
and anything that exposes a USB audio device will get you on the air for RX.

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

The [Operator's Manual](docs/manual/README.md) is the user-facing documentation:

- [Getting started](docs/manual/getting-started.md) — install, hookup,
  configuration, first receive session
- [Operating](docs/manual/operating.md) — Operate and Spectrum tabs, calling,
  answering, QSO automation
- [Settings reference](docs/manual/settings.md) — every setting, defaults and
  behavior
- [Logging and ADIF](docs/manual/logging.md) — the logbook, exports, backups,
  POTA activation files
- [Troubleshooting](docs/manual/troubleshooting.md) — symptom-first fixes

The quick start below is the condensed version.

## Quick start

1. **Plug in the radio** — phone → USB-C OTG cable → Digirig → rig DATA and CAT
   ports, or straight into the rig's built-in USB port (FTX-1 style). A powered
   OTG hub is sometimes needed.
2. **Grant USB permission** when Android prompts for the interface's audio and
   serial devices.
3. **Set up Station** (Settings → Station) — enter your callsign and 4- or 6-char
   Maidenhead grid. POTA activators can flip on **POTA mode** (Settings → POTA)
   and enter park reference(s); CQs become `CQ POTA <call> <grid>` and ADIF
   exports add the `MY_SIG` / `MY_SIG_INFO` fields.
4. **Add your rig** (Settings → Radio → **Add rig**) — pick your radio model or a
   generic preset, confirm the CAT baud, and hit **Test CAT** to verify the link.
   Then **Read rig** should show frequency and mode; tap **Set FT8 mode (DATA-U)**
   if you are not already in the data mode.
5. **Check audio** (Settings → Audio) — the USB audio device is selected
   automatically when attached; receive even starts on its own when the radio
   connects (toggleable).
6. **Acknowledge the license** and enable transmit when you are ready to call.
7. **Operate tab → Start** — decodes appear within one 15 s slot. Tap a CQ to
   answer, or **Start CQ** to call. Completed QSOs auto-log to the Log tab; ADIF
   also auto-exports to `Documents/ft8vc` after every contact.

See [docs/HARDWARE.md](docs/HARDWARE.md) for the FT-891 menu values that the
reference setup uses, and [docs/RIG_MODELS.md](docs/RIG_MODELS.md) for the full
supported-radio table.

## Features

Four tabs — **Operate**, **Spectrum**, **Log**, **Settings** — cover a full
portable FT8 session.

### Operate

- USB audio RX (12 kHz, UTC slot-aligned decode); capture **auto-starts when the
  radio is plugged in** and a **Monitoring** chip shows when RX runs before you
  hit Start
- Compact 3-row status bar: dial MHz (tap to retune via CAT), mode, **TX tone**
  chip, POTA chip, **HALT** button; slot progress, UTC countdown, **Even/Odd**
  TX slot chips with a **TX Ns** countdown to your next TX period. A **clock
  offset chip** appears when the device clock drifts from band time — tap it to
  align to the decoded slot (see Settings → Clock alignment)
- Decode list: CQ highlighted, traffic directed to you in amber; QSO partner in
  bold; **distance (km)** shown when a 4-char grid is in the message; a two-letter
  **country (CC)** column; the UTC cell is tinted by slot parity so Even/Odd slots
  read apart at a glance. Row colors are customizable in Settings → Display.
  **Long-press a row to block** that sender
- **Focus** mode (default) hides chatter unrelated to your call, the active QSO
  partner, or signals near your TX tone. **Band** mode shows the full passband.
  Optional **CQ·73** chip narrows further when browsing.
- Inline level meter and one-tap input gain while operating
- A capture-stall **watchdog** auto-restarts USB audio if the RX thread goes
  silent; a **tap-to-retry** chip appears if automatic recovery is exhausted

### Spectrum

- Full-screen waterfall on its own tab; tap or drag to set the TX audio offset
- TX tone persists across tabs and shows on the Operate status bar
- Bold red WSJT-X-style **TX goalpost marker** over your 50 Hz footprint, with a
  live **TX Hz** readout; high-contrast palette with a dark noise floor
- **Dial** label (when CAT is ready): tap to pick a band / preset FT8 dial
  frequency and tune the rig over CAT — the band list adapts to the selected
  radio model (6 m / 2 m / 70 cm where the rig covers them)
- Dark/Light mode toggle in Settings → Display (whole app commits to the choice)

### Rig control (multi-rig)

- **Rig profiles**: save up to 5 named rigs (Settings → Radio → **Add rig**) and
  switch between them. Model presets prefill baud, CAT port, and PTT method:
  - **Yaesu FT-891** — verified reference rig (Digirig / CP2102)
  - **Yaesu FTX-1** — verified second reference (built-in USB, covers VHF/UHF)
  - **Yaesu FT-991A, FTDX10, FT-710, FTDX101D/MP** — CAT tables authored from
    the manuals, awaiting hardware verification
  - **Generic presets** for unlisted hardware: Digirig (CAT + RTS PTT), USB CAT
    cable / built-in USB (CAT PTT), and serial-PTT-only (RTS, no CAT)
- **Test CAT** probe in the profile editor reports sync, wrong-baud garbage, or
  silence in plain language before you commit the profile
- CAT: read band/mode, set dial frequency, one-tap **Set FT8 mode (DATA-U)**
- No-CAT rigs: set a **manual dial frequency** so the display and log stay right
- PTT via serial RTS or CAT commands (`TX1;`/`TX0;`), per profile
- USB diagnostics expandable in Settings → Radio for cable/permission debugging

### TX and QSO automation

- Auto-seq QSO state machine (CQ → grid → reports → RRR/RR73 → 73)
- **Start CQ** waits for your chosen Even/Odd TX slot; tap any CQ to answer
- **Answer when called** auto-resumes when someone calls you; **Auto answer CQ**
  hunts CQs when idle; **Resume CQ after QSO** keeps calling CQ once a contact you
  started by calling CQ wraps up (Search & Pounce QSOs do not auto-resume).
  Independent toggles in Settings → Auto TX
- Answer selection policy: **First**, **Best SNR**, or **Furthest** (great-circle
  km from your grid)
- **Abandon after N unanswered TX cycles** auto-drops the non-responder from
  further auto-answer / auto-CQ selection for the rest of the session
- **Late-start TX** (optional): send a truncated waveform up to 7 s into the
  slot so a late answer still goes out; **Early decode** (optional) surfaces CQs
  ~3 s sooner with an extra mid-slot decode pass
- **Send RR73** option: log on send, or send RRR and log on the partner's 73
- Single **End QSO** button ends the active contact; long-press a decode to block
  a sender outright. Blocked stations are hidden from the decode list and managed
  in **Settings → Auto TX → Blocklist** (per-call **Unblock** or **Clear all**)
- License acknowledgment gates the first TX; a **TX safety halt** latches if the
  watchdog ever has to force-release PTT, and TX stays gated until you
  acknowledge it in Settings

### POTA

- POTA mode toggle and park reference(s) — comma-separate for a multi-park
  activation (e.g. `US-3315, US-0891`)
- CQs become `CQ POTA <call> <grid>` on-air
- ADIF export adds `MY_SIG = POTA` and `MY_SIG_INFO = <ref>`; export fails closed
  if POTA mode is on without a valid park reference
- **POTA activation export** on the Log tab: one validated ADIF file per park per
  UTC day, ready to upload to pota.app

### Log and ADIF

- Room-backed logbook; completed QSOs auto-log
- **ADIF auto-backup after every QSO** to app-private storage *and*
  `Documents/ft8vc` (survives uninstall), plus a manual **Backup now** button
- **Export ADIF** (3.1, validated) via Android share intent
- **Import ADIF** to merge an existing log
- Log management: multi-select delete, set park references on logged QSOs,
  clear log
- Persisted station profile: call, grid, rig profiles, TX tone, theme and decode
  colors, auto-TX preferences

## Architecture

Kotlin + Jetpack Compose UI on top of a C/C++ NDK core that wraps
[`kgoba/ft8_lib`](https://github.com/kgoba/ft8_lib) (MIT) for FT8 encode/decode.

```
ft8vc/
  app/          Compose UI (Operate / Spectrum / Log / Settings), ViewModels
  core/         Slot scheduler, FT8 message models, QSO state machine
  audio/        12 kHz USB audio capture/playback, DSP, waterfall
  rig/          Rig profiles, PTT + CAT backends (Yaesu new-CAT), serial transport
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

The reference field setups are a **Yaesu FT-891 + Digirig Mobile** and a **Yaesu
FTX-1** (built-in USB), via USB-C OTG. [docs/HARDWARE.md](docs/HARDWARE.md) lists
the FT-891 menu values, the Digirig audio/serial wiring, and a validation
checklist for first-on-air. [docs/RIG_MODELS.md](docs/RIG_MODELS.md) tracks every
supported model and its verification status.

## Documentation

| Topic | Document |
|-------|----------|
| Operator's Manual | [docs/manual/README.md](docs/manual/README.md) |
| Component overview | [docs/README.md](docs/README.md) |
| Supported radios | [docs/RIG_MODELS.md](docs/RIG_MODELS.md) |
| Release and unstable APKs | [docs/RELEASE.md](docs/RELEASE.md) |
| SDK / NDK setup | [docs/SDK_SETUP.md](docs/SDK_SETUP.md) |
| Field hardware | [docs/HARDWARE.md](docs/HARDWARE.md) |
| Testing | [docs/TESTING.md](docs/TESTING.md) |

For contributors and AI coding agents: [AGENTS.md](AGENTS.md) covers the test +
docs definition of done and the toolchain pins.

## Current limitations

- CAT protocol is Yaesu new-CAT only (VFO-A). Kenwood and Icom CI-V are on the
  roadmap; unlisted rigs work today via the generic presets (CAT-less rigs are
  RX + RTS-keyed TX with a manually set dial frequency).
- No split-frequency operation
- No contest exchange support (contest-style messages are not auto-sequenced)

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
