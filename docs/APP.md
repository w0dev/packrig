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
| **Settings** | `SettingsScreen` | Station, audio, rig, TX, advanced manual TX |

`OperateViewModel` is activity-scoped and shared between Operate and Settings.

## Operate screen

Compact, field-first layout (decode list gets maximum vertical space):

- **Status bar (3 tight rows)** — dial MHz (tap to change band when CAT ready), mode, TX tone chip, POTA chip, compact **Halt** button; slot progress + UTC countdown + **Even/Odd TX slot** chips (:00/:30 vs :15/:45; countdown shows **TX Ns** until your next TX period); call/grid or QSO state, **speaker icon** (input gain, left of level meter), inline level meter while operating
- **Decode list** — single header row: **Band / Focus / CQ·73** chips, decode count, clear. Each row: UTC time, SNR, **distance (km)** when the message includes a 4-char grid, audio offset (Hz), message. CQ highlighted; tap to answer; QSO partner bold (**Focus** default: CQs, your-call traffic, QSO partner, ±150 Hz around TX tone; **Band** = full passband). **CQ·73** appears only on Band.
- **TX message** — one row: monospace message field + **Text…** (idle) or **Msg ▾** (active QSO step menu). Short status line + **Auto** when overridden.
- **Action bar** — one row: **Start/Stop**, **Start CQ** / **Stop QSO** + **Abandon** when applicable. **Start CQ** waits for your chosen Even/Odd TX period (no more timing the button press).
- **Input gain** — speaker icon in the status bar opens a slider (auto-hides when you release). Level meter shows inline while operating. Full control also in Settings → Audio.
- **Auto Seq / Answer when called / Auto answer CQ** — configured in Settings → Operating (auto TX), not on the Operate screen

## Spectrum tab

Full-screen waterfall on its own tab (shared `OperateViewModel` — RX continues while viewing):

- Tap or drag to set TX audio offset (Hz); persisted and shown on Operate status bar
- **Dial** label (when CAT ready): tap to pick band / preset dial frequency; tunes rig via Digirig CAT (common FT8 spots, e.g. 20m 14.074 and 14.090 MHz)
- Waterfall brightness in Settings → Display

## Settings

Persisted via DataStore (`SettingsRepository`):

- Callsign, grid, TX tone, audio device, waterfall brightness
- **Operating (auto TX)** — Auto Seq, Answer when called, Auto answer CQ, **TX slot (Even/Odd)**, Answer selection policy, **Abandon after no reply** (default 5 TX cycles), clear abandoned-station blocklist
- **Activation (POTA)** — POTA mode toggle, park reference (`US-3315`)
- License acknowledgment + TX enable switch
- Rig band/mode (FT-891 CAT), PTT preference, USB diagnostics
- Waterfall brightness (Spectrum tab)
- Advanced manual TX (bench testing)

## ViewModel architecture

```
OperateViewModel
 ├── UsbAudioCapture → SlotCollector → Ft8Native.decode
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
