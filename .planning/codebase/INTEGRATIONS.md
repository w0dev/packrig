# External Integrations

**Analysis Date:** 2026-06-21

## APIs & External Services

**None detected.**

The application is entirely self-contained and does not make HTTP requests to external APIs or cloud services. All communication is local to the device via USB or internal APIs.

---

## Data Storage

**Databases:**
- SQLite (Android Room)
  - Database: `ft8vc_logbook.db` (in app-private data directory)
  - Client: AndroidX Room 2.7.2 ORM
  - Access: `Ft8vcDatabase` singleton in `net.ft8vc.data.db.Ft8vcDatabase`
  - Schema: Single entity `QsoEntity` (logbook of completed QSO contacts)
  - DAO: `QsoDao` with operations: insert, observeAll, getAll, deleteAll, observeCount
  - Implementation: `RoomLogbook` wraps DAO and exposes coroutine-safe interface

**Key-Value Storage (Settings):**
- AndroidX DataStore Preferences
  - Data store name: `ft8vc_settings`
  - Stores: Station profile (callsign, grid), audio device ID, PTT preference, TX settings, theme choice, auto-operating preferences
  - Access: `SettingsRepository` in `net.ft8vc.app.settings.SettingsRepository`
  - Encrypted by default (DataStore uses EncryptedSharedPreferences on Android 5.0+)

**File Storage:**
- Local filesystem only
  - ADIF export via Android Share Intent (user selects output destination)
  - FileProvider at `androidx.core.content.FileProvider` (authorities: `${applicationId}.fileprovider`)
  - File paths config: `@xml/file_paths` (not analyzed — in resources)

**Caching:**
- None — decoded FT8 messages and waterfall data held in memory only

---

## Authentication & Identity

**Auth Provider:**
- None — local amateur radio station profile only
  - User provides own callsign (via Settings → Station)
  - User provides own 4 or 6-character Maidenhead grid
  - POTA (Parks on the Air) mode optional with user-entered park reference (e.g., `US-3315`)

**License Management:**
- Amateur radio license acknowledgment gate in Settings
  - Boolean flag: `LICENSE_ACK` (DataStore key in `SettingsRepository`)
  - TX disabled by default; must be explicitly enabled after license acknowledgment
  - App boots in receive-only mode

---

## Hardware Integration

**USB Communication:**
- USB Host (android.hardware.usb.host required)
- Primary target: Digirig Mobile (USB audio device + CP2102 serial bridge)
- Audio device: 12 kHz mono USB audio capture via Android AudioRecord
- Serial device: CP2102-style or CAT (hardware-dependent)

**USB Audio:**
- Capture: `UsbAudioCapture` in `net.ft8vc.audio.UsbAudioCapture`
- Playback: `UsbAudioPlayback` in `net.ft8vc.audio.UsbAudioPlayback`
- Managed through `AudioInputs` and `AudioOutputs` registries

**Rig Control (CAT & PTT):**
- Backends in `net.ft8vc.rig.DigirigRigBackend` (primary)
- PTT: Two modes (user selectable in Settings):
  - RTS (Request To Send) via CP2102 serial control
  - CAT command (`TX1;` / `TX0;`) via Yaesu FT-891 CAT protocol
- CAT: Read band/mode, set dial frequency, configure FT-891 for DATA-U mode
- Limited to Yaesu FT-891 for full CAT support; other rigs RX-only

---

## Monitoring & Observability

**Error Tracking:**
- None detected — no external error reporting service

**Logs:**
- Android native logging via System.out.println and kotlinx logging patterns
- No Logcat integration or external aggregation

**Debugging:**
- Standard Android debugging via adb logcat
- AGENTS.md references test harness and CI workflow debugging

---

## CI/CD & Deployment

**Hosting:**
- GitHub Releases (APK distribution)
  - Stable releases: `net.ft8vc-*.apk` (main branch)
  - Unstable builds: `net.ft8vc.unstable-*.apk` (unstable branch, CI-built)
  - Signed with stable key; upgrades in-place

**CI Pipeline:**
- GitHub Actions (APK build and release automation)
- Environment variables injected at build time:
  - `FT8VC_VERSION_CODE` - Build number
  - `FT8VC_VERSION_NAME_SUFFIX` - Release suffix
  - `FT8VC_UNSTABLE` - Boolean flag to enable unstable branding
  - `FT8VC_KEYSTORE`, `FT8VC_KEYSTORE_PASSWORD`, `FT8VC_KEY_ALIAS`, `FT8VC_KEY_PASSWORD` - Signing credentials
- Workflows defined in `.github/workflows/` (not analyzed — CI configuration)

**Build Artifacts:**
- Native: CMake-built libft8vc.so (C/C++ wrapper around ft8_lib)
  - FetchContent downloads ft8_lib pinned commit at build time
  - ABI filters: arm64-v8a, armeabi-v7a, x86_64

---

## Environment Configuration

**Required env vars (Release/CI):**
- `FT8VC_VERSION_CODE` - Integer; defaults to 100 if unset
- `FT8VC_VERSION_NAME_SUFFIX` - Release tag suffix (default "-unstable")
- `FT8VC_KEYSTORE` - Path to release keystore file
- `FT8VC_KEYSTORE_PASSWORD` - Keystore password
- `FT8VC_KEY_ALIAS` - Key alias in keystore
- `FT8VC_KEY_PASSWORD` - Key password
- `FT8VC_UNSTABLE` - Set to "true" to build unstable variant

**Secrets Location:**
- None in repository (no .env files committed)
- CI secrets stored in GitHub Actions secrets
- Build-time keystore injected by CI only

**DataStore Keys (Runtime Configuration):**
See `SettingsRepository` — keys in `net.ft8vc.app.settings.SettingsRepository.Keys`:
- Station: `my_call`, `my_grid`, `tx_tone_hz`, `pota_mode`, `pota_park_ref`
- Audio: `audio_device_id`, `input_gain`
- Rig/PTT: `ptt_preference`, `last_dial_freq_hz`
- Transmit: `tx_enabled`, `license_ack`, `auto_seq`, `answer_when_called`, `auto_answer_cq`, `answer_policy`, `max_unanswered_tx`, `tx_slot_parity`
- UI: `use_dark_theme`, `decode_view_mode`, `cq73_filter`

---

## Webhooks & Callbacks

**Incoming:**
- None — no external webhooks

**Outgoing:**
- None — app does not post to external services

**Intent Filters:**
- USB device attached: `android.hardware.usb.action.USB_DEVICE_ATTACHED`
  - Triggers app when Digirig (or compatible device) plugged in
  - Device filter configured in `@xml/usb_device_filter`

**Share Intent:**
- ADIF export via Android Share Intent (user selects destination: email, Drive, file manager, etc.)
- Implemented in Log tab via `android.intent.action.SEND`

---

*Integration audit: 2026-06-21*
