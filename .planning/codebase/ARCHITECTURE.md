<!-- refreshed: 2026-06-21 -->
# Architecture

**Analysis Date:** 2026-06-21

## System Overview

FT8VC is an Android FT8 transceiver app with a layered Kotlin/Jetpack Compose UI above modular backends for audio capture, FT8 encode/decode, rig control, and logging. The app drives amateur radio equipment through USB audio and serial interfaces (reference: Yaesu FT-891 + Digirig Mobile).

```text
┌─────────────────────────────────────────────────────────────┐
│                     UI Layer (Compose)                       │
├──────────────────┬──────────────────┬───────────────────────┤
│   Operate Tab    │  Spectrum Tab    │   Log / Settings      │
│  `app/ui/       │  `app/ui/        │   `app/ui/log`,       │
│   operate/`     │   spectrum/`     │   `app/settings/`     │
└────────┬─────────┴────────┬─────────┴──────────┬────────────┘
         │                  │                     │
         ▼                  ▼                     ▼
┌─────────────────────────────────────────────────────────────┐
│          OperateViewModel (Orchestrator)                    │
│          `app/OperateViewModel.kt`                          │
│  • State composition from all backends                      │
│  • Lifecycle mgmt for RX/TX/CAT                            │
│  • QSO machine sequencing driver                           │
└─────────────────────────────────────────────────────────────┘
         │
    ┌────┼────────────────────────────────┬──────────────────┐
    │    │                                │                  │
    ▼    ▼                                ▼                  ▼
┌──────────────────┐  ┌──────────────┐  ┌─────────────────┐ ┌──────────────┐
│ Audio RX Layer   │  │ Rig Control  │  │  Core Logic     │ │ Data Layer   │
│ `audio/`         │  │ `rig/`       │  │ `core/`         │ │ `data/`      │
│ • USB capture    │  │ • CAT ctrl   │  │ • QsoMachine    │ │ • Room DB    │
│ • DSP (12kHz)    │  │ • PTT (RTS)  │  │ • Decode logic  │ │ • ADIF exp.  │
│ • Waterfall      │  │ • Digirig    │  │ • Slot timing   │ │ • Logbook    │
│                  │  │   backend    │  │ • Messages      │ │              │
└──────────────────┘  └──────────────┘  └─────────────────┘ └──────────────┘
    │ frames              │ freq/ptt        │ state         │ contacts
    │ spectrum            │ CAT state       │ sequences     │ ADIF
    ▼                     │                 │               │
┌──────────────────┐     │                 ▼               │
│  Native FT8 JNI  │◄────┘            ┌──────────────────┐ │
│ `ft8-native/`    │                  │ Settings Repo    │ │
│ • ft8_lib wrap   │                  │ `app/settings/`  │ │
│ • PCM encode     │                  │ • Preferences    │ │
│ • FT8 decode     │                  │ • Station data   │ │
└──────────────────┘                  └──────────────────┘ │
                                                            │
                                           ┌────────────────┘
                                           │
                                           ▼
                                    ┌──────────────────┐
                                    │  Room Database   │
                                    │ `data/db/`       │
                                    └──────────────────┘
```

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| **MainActivity** | Entry point, Compose root setup | `app/src/main/java/net/ft8vc/app/MainActivity.kt` |
| **OperateViewModel** | Central orchestrator for all screen state and I/O drivers | `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` |
| **Ft8vcApp** | Navigation host, tab routing, theme composition | `app/src/main/java/net/ft8vc/app/ui/nav/Ft8NavHost.kt` |
| **OperateScreen** | Primary operating UI (Operate tab: decodes, TX controls, status) | `app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt` |
| **SpectrumScreen** | Waterfall display and TX tone picker | `app/src/main/java/net/ft8vc/app/ui/spectrum/SpectrumScreen.kt` |
| **LogScreen** | QSO log display and export | `app/src/main/java/net/ft8vc/app/ui/log/LogScreen.kt` |
| **SettingsScreen** | Station profile, audio device, CAT, TX preferences | `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt` |
| **UsbAudioCapture** | USB audio RX, decimation to 12 kHz | `audio/src/main/java/net/ft8vc/audio/UsbAudioCapture.kt` |
| **UsbAudioPlayback** | USB audio TX, encodes FT8 to 12 kHz samples | `audio/src/main/java/net/ft8vc/audio/UsbAudioPlayback.kt` |
| **SpectrumProcessor** | FFT and waterfall for display | `audio/src/main/java/net/ft8vc/audio/dsp/SpectrumProcessor.kt` |
| **RigController** | USB device discovery, permission mgmt, PTT/CAT routing | `rig/src/main/java/net/ft8vc/rig/RigController.kt` |
| **DigirigRigBackend** | Digirig PTT (RTS) and CAT protocol (Yaesu FT-891) | `rig/src/main/java/net/ft8vc/rig/DigirigRigBackend.kt` |
| **Ft891Cat** | FT-891 CAT command/response parsing (freq, mode, DATA-U) | `rig/src/main/java/net/ft8vc/rig/Ft891Cat.kt` |
| **QsoMachine** | Pure FT8 QSO state machine (CQ → grid → reports → 73) | `core/src/main/java/net/ft8vc/core/QsoMachine.kt` |
| **SlotCollector** | Buffers PCM samples, flushes on 15-second UTC slot boundary | `core/src/main/java/net/ft8vc/core/SlotCollector.kt` |
| **QsoMessages** | Parses/formats FT8 message types (CQ, grid, reports, RRR, 73) | `core/src/main/java/net/ft8vc/core/QsoMessages.kt` |
| **SettingsRepository** | Preferences (DataStore) for station, audio, rig, TX prefs | `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt` |
| **RoomLogbook** | Room database abstraction: persist contacts, export ADIF | `data/src/main/java/net/ft8vc/data/Logbook.kt` |
| **Ft8vcDatabase** | Room DAO for QSO contacts | `data/src/main/java/net/ft8vc/data/db/Ft8vcDatabase.kt` |
| **Ft8Native** | JNI bridge to ft8_lib C/C++ for encode/decode | `ft8-native/src/main/java/net/ft8vc/ft8native/Ft8Native.kt` |

## Pattern Overview

**Overall:** Layered MVVM with a monolithic orchestrator (OperateViewModel) wiring all backends and publishing composed state to Compose screens.

**Key Characteristics:**
- **Separation of concerns by domain** — Audio, Rig, Core (FT8 logic), and Data are independent modules with Kotlin interfaces; the ViewModel imports from all four
- **Pure logic isolation** — Core module classes like `QsoMachine`, `SlotCollector`, `QsoMessages` have no Android/UI dependencies; they accept injected time and callbacks for unit testing
- **Coroutine-driven concurrency** — All I/O (audio threads, CAT reads, decode) is coordinated via `viewModelScope` and StateFlow/SharedFlow
- **Single source of truth** — `OperateUiState` (single StateFlow) holds all screen and background state; derives from settings, audio level, QSO progress, and rig status
- **Android lifecycle tying** — OperateViewModel lifecycle is tied to Activity via AndroidViewModel; `onCreate`/`onDestroy` hooks manage audio capture, playback, and decode threads

## Layers

**Presentation Layer (app module):**
- Purpose: Compose UI, navigation, ViewModels
- Location: `app/src/main/java/net/ft8vc/app/`
- Contains: Screens (Operate, Spectrum, Log, Settings), OperateViewModel, OperateUiState, SettingsRepository
- Depends on: Jetpack Compose, Lifecycle, Navigation, all backend modules (core, audio, rig, data)
- Used by: MainActivity (entry point)

**Domain/Core Logic Layer (core module):**
- Purpose: FT8 protocol, QSO sequencing, slot timing, decode filtering
- Location: `core/src/main/java/net/ft8vc/core/`
- Contains: QsoMachine, QsoMessages, SlotCollector, DecodeViewMode, AnswerPolicy, MaidenheadGrid, AbandonedPartners
- Depends on: Kotlin stdlib only (no Android, no external libs)
- Used by: OperateViewModel, audio decode loop, test suites

**Audio Layer (audio module):**
- Purpose: USB audio capture/playback, DSP (decimation, FFT, waterfall)
- Location: `audio/src/main/java/net/ft8vc/audio/`
- Contains: UsbAudioCapture, UsbAudioPlayback, SpectrumProcessor, FirDecimator, Fft, Upsampler
- Depends on: Android AudioRecord/AudioTrack APIs, core module (sample rate const), ft8-native (encode)
- Used by: OperateViewModel (callbacks for frames and spectrum)

**Rig Control Layer (rig module):**
- Purpose: USB device discovery, PTT keying, CAT for frequency/mode
- Location: `rig/src/main/java/net/ft8vc/rig/`
- Contains: RigController, DigirigRigBackend, Ft891Cat, Cp210x (USB VID/PID), NoOpRigBackend
- Depends on: Android USB APIs, Kotlin stdlib
- Used by: OperateViewModel (read CAT, set PTT)

**Data/Persistence Layer (data module):**
- Purpose: Room database, ADIF export
- Location: `data/src/main/java/net/ft8vc/data/`
- Contains: RoomLogbook, Ft8vcDatabase, QsoEntity, QsoDao, AdifWriter, AdifNormalizer, AdifValidator
- Depends on: Room ORM, core module (models)
- Used by: OperateViewModel (log completed QSOs)

**Native Bridge (ft8-native module):**
- Purpose: JNI wrapper around kgoba/ft8_lib (C/C++) for FT8 encode/decode
- Location: `ft8-native/src/main/java/net/ft8vc/ft8native/`
- Contains: Ft8Native (JNI methods for encode, decode batch)
- Depends on: NDK, CMake, ft8_lib source (fetched at build time)
- Used by: OperateViewModel (decode pass on each slot), UsbAudioPlayback (TX encode)

## Data Flow

### Primary RX Decode Path

1. User taps "Start" in Operate tab → `OperateViewModel.startOperating()` (`app/OperateViewModel.kt:~200`)
2. UsbAudioCapture thread starts, reads frames from Digirig USB audio device every ~200ms
   - Source: `audio/src/main/java/net/ft8vc/audio/UsbAudioCapture.kt:39`
3. Frames decimated (48kHz → 12kHz) via FirDecimator, fed into SlotCollector callback
   - Source: `core/src/main/java/net/ft8vc/core/SlotCollector.kt:27`
4. When UTC slot boundary crosses (15 seconds elapsed), SlotCollector triggers decode callback
   - Source: `core/src/main/java/net/ft8vc/core/SlotCollector.kt:31`
5. Decode executor runs on worker thread: calls Ft8Native.decode(samples) → native ft8_lib
   - Source: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt:~400` (executor.execute)
6. Native returns raw message list; OperateViewModel parses via QsoMessages and filters via MonitorDecodeFilter
   - Source: `core/src/main/java/net/ft8vc/core/QsoMessages.kt:44`
7. Decoded list converted to DecodeRow (UI model) with distance, CQ flag, isToMe flag
8. State updated: `_state.update { it.copy(decodes = newList, waterfallVersion = version++) }`
9. Compose recomposes DecodeListPanel, showing new rows with colors (CQ cyan, toMe amber, etc)

### Primary TX QSO Path

1. User taps a CQ row → `OperateViewModel.answerCq(dxCall, dxGrid)`
   - Source: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt:~600`
2. QsoMachine instantiated and set to Answerer role, Answering state
   - Source: `core/src/main/java/net/ft8vc/core/QsoMachine.kt:45`
3. QsoMachine generates TX message: `{dxCall} {myCall} {myGrid}`
4. On next even/odd TX slot boundary, UsbAudioPlayback encodes message via Ft8Native.encode()
   - Source: `audio/src/main/java/net/ft8vc/audio/UsbAudioPlayback.kt:~150`
5. PTT keyed via RigController: either RTS (Digirig) or CAT TX1; command (Yaesu FT-891)
   - Source: `rig/src/main/java/net/ft8vc/rig/RigController.kt:~100`
6. Audio frames transmitted to USB playback device
7. After TX slot ends, PTT released
8. QsoMachine marked TX complete: `markTransmitted()`
9. Next RX slot waits for response; when decode arrives, QsoMachine advanced state if pattern matches
   - Source: `core/src/main/java/net/ft8vc/core/QsoMachine.kt:~120` (onDecodes method)
10. Sequence repeats: SendingReport, SendingRReport, SendingRoger, SendingSeventyThree → Complete
11. On Complete, QsoContact inserted to Room DB, contact count badge updated
    - Source: `data/src/main/java/net/ft8vc/data/Logbook.kt:22`

### CAT Frequency/Mode Read

1. OperateViewModel periodically polls RigController.readCat() (every 5s when operating)
   - Source: `rig/src/main/java/net/ft8vc/rig/RigController.kt:~60`
2. RigController routes to active DigirigRigBackend (if Digirig present)
3. Sends binary CAT command (Yaesu FT-891 protocol): read VFO-A frequency
4. Ft891Cat parses 16-byte response, extracts freq Hz and mode
   - Source: `rig/src/main/java/net/ft8vc/rig/Ft891Cat.kt:~80`
5. State updated: `_state.update { it.copy(rigFreqHz = freq, rigMode = mode) }`
6. Operate status bar shows dial MHz (tappable to open band preset picker)

**State Management:**
- All state flows through `OperateUiState` (single StateFlow in OperateViewModel)
- Settings persisted via DataStore (Preferences) in SettingsRepository
- Log persisted via Room DAO (Ft8vcDatabase.qsoDao())
- QSO session state (QsoMachine) is in-memory during operate; lost on app exit (by design — recover via Continue)

## Key Abstractions

**QsoMachine:**
- Purpose: Pure FT8 state machine (no I/O, no Android dependencies)
- Examples: `core/src/main/java/net/ft8vc/core/QsoMachine.kt`
- Pattern: Sealed enum of QsoState (Idle, CallingCq, Answering, SendingReport, etc); stateful accumulator with methods:
  - `txMessage()` → string to transmit on next TX
  - `markTransmitted()` → increment unanswered counter, prepare next state
  - `onDecodes(list)` → advance state if response matches expected pattern
  - Unit tests mock time injection via SlotTiming.slotStart(nowMs)

**QsoMessages:**
- Purpose: Parse/format FT8 message types (CQ, directed, reports, endings)
- Examples: `core/src/main/java/net/ft8vc/core/QsoMessages.kt`
- Pattern: Object with sealed class hierarchy (QsoRx.Cq, GridReply, Report, RReport, Roger, RogerBye, Bye, Other)
  - `parseMessage(text, myCall) → QsoRx` — parses a string decode into type-safe sealed class
  - `cq(myCall, myGrid, modifier?)` → formats CQ message
  - `formatReport(snr)` → formats signal report with sign and zero-padding

**SlotCollector:**
- Purpose: Buffers mono PCM samples into 15-second UTC slot windows
- Examples: `core/src/main/java/net/ft8vc/core/SlotCollector.kt`
- Pattern: Stateful buffer with wall-clock boundary detection:
  - `add(frames, nowMillisUtc, onSlot)` → appends frames, fires onSlot callback when slot ends
  - Uses SlotTiming.slotStart(nowMs) to detect boundary crossing
  - Buffers min 85% of slot samples before firing (skip short/incomplete slots)

**AudioEngine (Interface):**
- Purpose: Abstraction for RX/TX audio, allows swapping implementations
- Examples: `audio/src/main/java/net/ft8vc/audio/AudioEngine.kt` (interface), UsbAudioCapture/UsbAudioPlayback
- Pattern: start(deviceId, callback) and stop() contract; onFrames/onEncoded callbacks carry data

**RigBackend (Interface):**
- Purpose: Abstraction for PTT and CAT, allows mocking and fallback
- Examples: `rig/src/main/java/net/ft8vc/rig/RigBackend.kt` (interface), DigirigRigBackend, NoOpRigBackend
- Pattern: keysender, unsendPtt(), readCat() → returns struct with freq, mode

**Logbook (Interface):**
- Purpose: Abstraction for log persistence (Room impl; could be swapped)
- Examples: `data/src/main/java/net/ft8vc/data/Logbook.kt` (interface), RoomLogbook
- Pattern: suspend fun log(contact), fun contacts() Flow<List>, suspend exportAdif(), contactCount() Flow

## Entry Points

**MainActivity:**
- Location: `app/src/main/java/net/ft8vc/app/MainActivity.kt`
- Triggers: Android app launch (LAUNCHER intent-filter + USB device attach intent-filter)
- Responsibilities: Creates AndroidView context, calls setContent { Ft8vcApp() }; Compose tree bootstrap

**Ft8vcApp (Composable):**
- Location: `app/src/main/java/net/ft8vc/app/ui/nav/Ft8NavHost.kt:29`
- Triggers: Emitted by MainActivity.onCreate
- Responsibilities: Instantiates OperateViewModel/LogViewModel, sets up NavHost with 4 bottom-tab destinations (Operate, Spectrum, Log, Settings)

**OperateViewModel.startOperating():**
- Location: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt:~150`
- Triggers: User taps "Start" button on Operate screen
- Responsibilities: Launch UsbAudioCapture, UsbAudioPlayback, SlotCollector, decode executor; enable CAT polling; enter operating state

## Architectural Constraints

- **Threading:** Single-threaded event loop (Kotlin coroutines on Main + viewModelScope) for state; separate executor threads for audio I/O (UsbAudioCapture reads thread-per-session, UsbAudioPlayback encodes on writer thread, decode on worker executor)
- **Global state:** QsoMachine instance lives in OperateViewModel memory (lost on app exit); SettingsRepository and Logbook are singletons created in OperateViewModel.init
- **Circular imports:** None known; modules import from lower layers only (app → core/audio/rig/data; audio/rig → core; core → none)
- **USB single-session:** One Digirig at a time (Android USB Host limitation); multiple USB devices routed to fallback NoOpRigBackend
- **Audio sample rate:** Hardcoded to 12 kHz (FT8 internal standard); capture decimates from 48/24kHz, playback upsamples to device rate
- **15-second slot alignment:** All RX slots aligned to UTC second boundaries; wall clock accuracy depends on device NTP sync (not yet implemented in app)
- **No TX collision detection:** Operator responsible for slot selection (Even/Odd UI toggle); app does not prevent same-slot RX/TX

## Anti-Patterns

### Monolithic OperateViewModel

**What happens:** All I/O coordination (audio, rig, decode, QSO, logging) is wired in a single ~2000-line OperateViewModel class.

**Why it's wrong:** State updates from independent sources (audio callback, CAT poll, decode finish, settings change) all go through _state.update{...} in sequence, making the causality hard to trace when features interact. Testing requires full Android context (AndroidViewModel) even for pure business logic tests.

**Do this instead:** Split into focused controllers per concern (DecodeController owns SlotCollector + decode executor + Ft8Native; TxOrchestrator owns UsbAudioPlayback + PTT; QsoSessionController wraps QsoMachine + auto-seq toggles; RigSession handles CAT polling + dial presets). Each controller has narrow state flow input/output; OperateViewModel composes them. This is listed as roadmap in the ViewModel's header comment (lines 63-80) and planned for v1.1 release.
  - Reference: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt:63`

### QSO State Lost on App Exit

**What happens:** QsoMachine instance and QSO progress live only in memory (OperateViewModel scope); closing/restarting the app resets to Idle state.

**Why it's wrong:** If a QSO is mid-progress (e.g., waiting for a response), closing and reopening loses context. User must manually recover or start over.

**Do this instead:** Persist QsoMachine state (current state enum, DX call, DX grid, reports) to a serializable on-disk snapshot. Add resume logic: on startup, if a QSO snapshot exists and is < 15 minutes old, restore it and resume listening. See QsoResume class (partial implementation, lines 22-40) which tracks QsoRole and last RX message but is not yet wired into app lifecycle.
  - Reference: `core/src/main/java/net/ft8vc/core/QsoResume.kt:22`

### Settings Sync Race in OperateViewModel

**What happens:** SettingsRepository.settings (a Flow) is collected in OperateViewModel.init block and updates _state reactively. When user edits a setting, the change appears in collected state immediately; but the update to DataStore is async (via edit { ... } suspend function), so if app crashes before edit completes, the change is lost.

**Why it's wrong:** No atomicity guarantee between state update and persistence. Settings like TX enable / CAT frequency should commit to disk before the UI acknowledges.

**Do this instead:** Make SettingsRepository.setXxx() functions expose the IO side-effect via Flow (or emits success/failure events). Block the UI button until the write returns success. Use structured concurrency (viewModelScope.launch) to track the write and emit errors to snackbar.
  - Reference: `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt:56`

## Error Handling

**Strategy:** Graceful degradation with user notification.

**Patterns:**
- **USB device not ready** → RigController falls back to NoOpRigBackend; CAT and PTT become no-ops; state.catReady remains false; Settings screen shows USB status
  - Reference: `rig/src/main/java/net/ft8vc/rig/RigController.kt:37` (volatile digirig field)
- **Audio capture fails** → Exception caught in UsbAudioCapture.start() thread loop; notify() emits SnackbarEvent; isCapturing remains false
  - Reference: `audio/src/main/java/net/ft8vc/audio/UsbAudioCapture.kt:54` (catch block)
- **CAT read timeout** → Ft891Cat returns Optional.empty(); state.rigFreqHz remains stale until next successful read
  - Reference: `rig/src/main/java/net/ft8vc/rig/Ft891Cat.kt:~150` (readFrequency timeout)
- **Decode out of samples** → SlotCollector skips slot if < 85% of expected samples; silent skip (no error)
  - Reference: `core/src/main/java/net/ft8vc/core/SlotCollector.kt:32` (count >= minSamples check)
- **ADIF export validation fails** → Export fails closed; exception propagates as SnackbarEvent with reason
  - Reference: `data/src/main/java/net/ft8vc/data/adif/AdifValidator.kt:~50` (validateContact throws)

## Cross-Cutting Concerns

**Logging:** Uses Android Log.d/e; no custom logging framework. Key events logged at module level (audio start/stop, CAT success/fail, QSO state change). Production APK has default log level INFO (suppresses DEBUG).

**Validation:** 
  - Station profile (call, grid) validated via StationProfileValidator before enabling TX (4-6 char grid, call 3-6 chars, alphanumeric)
    - Reference: `core/src/main/java/net/ft8vc/core/StationProfileValidator.kt:~30`
  - ADIF exports validated before write (all required fields present, RST in range, band matches freq)
    - Reference: `data/src/main/java/net/ft8vc/data/adif/AdifValidator.kt:~50`

**Authentication:** None. App is receive-only by default; TX requires explicit license acknowledgment checkbox in Settings (LicenseAckDialog on first TX attempt).
  - Reference: `app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt:~200` (showLicenseDialog)

**Concurrency:** Coroutine-based. All state updates marshal through Main dispatcher via StateFlow. Long-running I/O (audio, CAT, decode) offloaded to worker threads with callbacks; callbacks switch back to viewModelScope context to update _state.

---

*Architecture analysis: 2026-06-21*
