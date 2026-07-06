# App module (`app/`)

The Android application: Jetpack Compose UI, navigation, `OperateViewModel`, and
integration with audio, rig, codec, and logbook modules.

## Navigation

`MainActivity` hosts `Ft8vcApp` with a bottom nav bar:

| Tab | Screen | Purpose |
|-----|--------|---------|
| **Operate** | `OperateScreen` | Decodes, QSO controls, slot clock, TX tone indicator |
| **Spectrum** | `SpectrumScreen` | Full-screen waterfall; tap/drag TX frequency |
| **Log** | `LogScreen` | QSO history, ADIF export |
| **Settings** | `SettingsScreen` | Station, display, audio, rig, TX, auto behavior |

`OperateViewModel` is activity-scoped and shared between Operate and Settings.

## Operate screen

Compact, field-first layout (decode list gets maximum vertical space):

- **Status bar (3 tight rows)** — dial MHz (tap to change band when CAT ready), mode, TX tone chip, POTA chip; slot progress + UTC countdown + **Even/Odd TX slot** chips (:00/:30 vs :15/:45; countdown shows **TX Ns** until your next TX period); call/grid or QSO state, **speaker icon** (input gain, left of level meter), inline level meter while operating. **Halt** button appears in the top row only while a TX is in progress. A **Clock ±N.Ns** chip surfaces when the device clock drifts from decoded band time (`ClockOffsetEstimator.WARN_S`); tapping calls `onAlignClock`. An **"Audio capture failed — tap to retry"** chip appears when the capture watchdog exhausts its restart budget; tapping calls `onRetryCapture`.
- **Advisories** — amber banner above the decode list when the station profile is incomplete ("Set your callsign and grid in Settings →"), and a second banner when CAT reports a non-DATA-U mode (tap to switch the rig to DATA-U).
- **Decode list** — single header row: **Band / Focus / CQ·73** chips, decode count, clear. Each row: UTC time, **CC** (two-letter ISO country from `CallsignCountry`, blank when unresolved), SNR, **distance (km)** when the message includes a 4-char grid, audio offset (Hz), message. Each message carries a single-glyph prefix — `●` for CQ, `→` for traffic directed at you, `▸` for the active QSO partner — so the row type is visible without relying on color. The UTC cell is tinted by slot parity (`slotTintAlpha`) so Even/Odd slots read apart. **Long-press a row to block** its sender (base-call via `DecodeBlocklist.senderToBlock`); blocked rows drop out of the list. Rows have a 40dp tap target.
- **TX message** — one row: monospace message field + **Text…** (idle) or **Msg ▾** (active QSO step menu). Short status line + **Auto** when overridden.
- **Action bar** — one row: **Start/Stop**, **Start CQ**, and a single **End QSO** button when a contact is active (replaces the old Stop QSO + Abandon pair; calls `vm.stopQso`). **Start CQ** is disabled until call and grid are valid; it waits for your chosen Even/Odd TX period before keying.
- **Input gain** — speaker icon in the status bar opens a slider (auto-hides when you release). Level meter shows inline while operating. Full control also in Settings → Audio.
- **Auto Seq / Answer when called / Auto answer CQ / Resume CQ after QSO** — configured in Settings → Operating (auto TX), not on the Operate screen

## Spectrum tab

Full-screen waterfall on its own tab (shared `OperateViewModel` — RX continues while viewing):

- Tap or drag to set TX audio offset (Hz); persisted and shown on Operate status bar
- **Decode markers** (derived by `SpectrumMarkers`, `app/ui/spectrum/`): CQ callers drawn as labelled ticks on the waterfall, a **Labels** toggle shows/hides the callsign text, and the 50 Hz TX band is overlaid — tinting red when `SpectrumMarkers.txClashes` detects a decode in your TX slot. A live **TX Hz** readout confirms the offset. The markers toggle persists via `spectrumMarkersEnabled` (`Keys.SPECTRUM_MARKERS_ENABLED`).
- **Dial** label (when CAT ready): tap to pick band / preset dial frequency; tunes rig via Digirig CAT (common FT8 spots, e.g. 20m 14.074 and 14.090 MHz)
- Dark/Light theme toggle in Settings → Display

## Settings

Persisted via DataStore (`SettingsRepository`):

- Callsign, grid, TX tone, audio device, theme choice
- **Operating (auto TX)** — Auto Seq, Answer when called, Auto answer CQ, **Resume CQ after QSO** ("After a QSO you started by calling CQ, keep calling CQ" — gated to CQ-origin QSOs; Search & Pounce contacts do not auto-resume), **TX slot (Even/Odd)**, Answer selection policy, **Abandon after no reply** (default 5 TX cycles)
- **Blocklist** — manages operator-blocked stations (added by long-pressing a decode). Lists each blocked call with an **Unblock** button, plus **Clear all** (`vm.clearAbandonedPartners`, which also clears the transient auto-suppressed set); shows "No blocked stations." when empty
- **Clock alignment** — **Align now** (`vm.alignClock`) applies the residual device-vs-band-time offset; **Reset** (`vm.resetClockAlignment`) clears it. Backed by `ClockCorrection` in `core`
- **Activation (POTA)** — POTA mode toggle, park reference (`US-3315`)
- License acknowledgment + TX enable switch
- Rig band/mode (FT-891 CAT), PTT preference, USB diagnostics (collapsible)
- **Display** — Dark mode toggle (whole UI commits)

## ViewModel architecture

```
OperateViewModel
 ├── UsbAudioCapture → SlotCollector → Ft8Native.decode
 ├── CaptureWatchdog (app/controllers/ — restarts a silently-stalled capture; retryCapture)
 ├── ClockCorrection (core — device-vs-band-time offset; alignClock/resetClockAlignment)
 ├── QsoMachine (auto-seq on background thread)
 ├── RigController (PTT + CAT)
 ├── SettingsRepository (DataStore)
 └── RoomLogbook (auto-log on QSO complete)

LogViewModel
 └── RoomLogbook.contacts() / exportAdif(AdifExportContext)
     └── SettingsRepository (POTA fields at export time)
```

## Building

```powershell
.\gradlew.bat :app:assembleDebug
```

Requires `RECORD_AUDIO` at runtime. USB host for Digirig.

## Related docs

- [RELEASE.md](RELEASE.md) — signed release and smoke checklist
- [HARDWARE.md](HARDWARE.md) — FT-891 + Digirig setup
- [DATA.md](DATA.md) — logbook schema
