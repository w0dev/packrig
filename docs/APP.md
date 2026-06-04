# App module (`app/`)

The Android application: Jetpack Compose UI, navigation, `OperateViewModel`, and
integration with audio, rig, codec, and logbook modules.

## Navigation

`MainActivity` hosts `Ft8vcApp` with a bottom nav bar:

| Tab | Screen | Purpose |
|-----|--------|---------|
| **Operate** | `OperateScreen` | Waterfall, decodes, QSO controls, slot clock |
| **Log** | `LogScreen` | QSO history, ADIF export |
| **Settings** | `SettingsScreen` | Station, audio, rig, TX, advanced manual TX |

`OperateViewModel` is activity-scoped and shared between Operate and Settings.

## Operate screen

Field-first layout:

- **Status bar** — rig MHz, mode, UTC slot countdown, QSO state
- **Waterfall** — tap/drag TX frequency cursor
- **Decode list** — CQ highlighted; tap to answer; QSO partner bold
- **Operate toggle** — start/stop RX session (license gate on first use)
- **Auto Seq / Answer when called** — toggles (default on) when operating with TX enabled
- **Start CQ / Stop QSO** — when TX enabled in Settings
- **Decode list** — CQ green; directed to you amber (tap to resume after Stop QSO)

## Settings

Persisted via DataStore (`SettingsRepository`):

- Callsign, grid, TX tone, audio device, waterfall brightness, auto-seq / answer-when-called (also on Operate)
- License acknowledgment + TX enable switch
- Rig band/mode (FT-891 CAT), USB diagnostics
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
 └── RoomLogbook.contacts() / exportAdif()
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
