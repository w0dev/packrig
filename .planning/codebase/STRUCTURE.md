# Codebase Structure

**Analysis Date:** 2026-06-21

## Directory Layout

```
ft8vc/
├── app/                          # Android app module (Compose UI, ViewModels)
│   ├── src/main/
│   │   ├── java/net/ft8vc/app/
│   │   │   ├── MainActivity.kt   # Entry point activity
│   │   │   ├── OperateViewModel.kt  # Main orchestrator VM
│   │   │   ├── OperateUiState.kt    # Compose state data class
│   │   │   ├── LogViewModel.kt      # Log tab VM
│   │   │   ├── SnackbarEvent.kt     # Event model for notifications
│   │   │   ├── ui/                  # Composable screens
│   │   │   │   ├── nav/Ft8NavHost.kt       # Navigation + bottom tabs
│   │   │   │   ├── nav/Ft8Destinations.kt  # Route constants
│   │   │   │   ├── operate/         # Operate tab screens
│   │   │   │   │   ├── OperateScreen.kt
│   │   │   │   │   ├── OperateStatusBar.kt
│   │   │   │   │   ├── DecodeListPanel.kt
│   │   │   │   │   ├── OperateControls.kt
│   │   │   │   │   ├── TxToneIndicator.kt
│   │   │   │   │   └── [other UI components]
│   │   │   │   ├── spectrum/SpectrumScreen.kt   # Waterfall tab
│   │   │   │   ├── log/LogScreen.kt            # Log tab
│   │   │   │   ├── theme/                      # Material3 theme
│   │   │   │   │   ├── Color.kt
│   │   │   │   │   ├── Theme.kt
│   │   │   │   │   └── Type.kt
│   │   │   │   ├── Waterfall.kt        # Waterfall render component
│   │   │   │   ├── Tooltip.kt          # Reusable tooltip UI
│   │   │   │   └── [other shared UI]
│   │   │   └── settings/            # Settings tab + persistence
│   │   │       ├── SettingsScreen.kt
│   │   │       ├── SettingsRepository.kt
│   │   │       ├── StationSettings.kt
│   │   │       └── PttPreference.kt
│   │   ├── res/
│   │   │   ├── values/strings.xml
│   │   │   ├── xml/usb_device_filter.xml   # Digirig USB VID/PID
│   │   │   └── mipmap/                    # App icon assets
│   │   └── AndroidManifest.xml      # App permissions + main activity
│   └── build.gradle.kts            # App module build config
│
├── core/                           # Pure FT8 logic (no Android deps)
│   ├── src/main/java/net/ft8vc/core/
│   │   ├── AppInfo.kt              # Sample rate, slot timing constants
│   │   ├── QsoMachine.kt           # FT8 state machine (unit-testable)
│   │   ├── QsoMessages.kt          # Parse/format FT8 messages
│   │   ├── QsoForm.kt              # UI form model for TX fields
│   │   ├── QsoResume.kt            # Partial QSO persistence model
│   │   ├── QsoSnapshot.kt          # Contact model
│   │   ├── OperateTxOptions.kt     # TX configuration options
│   │   ├── QsoTxStep.kt            # TX message type enum
│   │   ├── SlotCollector.kt        # 15-sec UTC slot buffer
│   │   ├── SlotTiming.kt           # UTC slot boundary calculations
│   │   ├── MaidenheadGrid.kt       # 4-char grid ↔ lat/lon + distance
│   │   ├── DecodeDistance.kt       # Great-circle km from grid pair
│   │   ├── DecodeViewMode.kt       # Focus/Band decode filter mode
│   │   ├── DecodePrefix.kt         # Call sign prefix extraction
│   │   ├── MonitorDecodeFilter.kt  # Decode list filtering logic
│   │   ├── AnswerSelector.kt       # CQ selection policy (First/SNR/Furthest)
│   │   ├── AnswerPolicy.kt         # Answer policy enum
│   │   ├── AbandonedPartners.kt    # Session blocklist for auto-modes
│   │   ├── ActivationProfile.kt    # POTA profile + CQ modifier
│   │   ├── TxSlotParity.kt         # Even/Odd slot selection
│   │   ├── TxSlotSelection.kt      # TX slot boundary from parity
│   │   ├── StationProfileValidator.kt  # Validate call/grid before TX
│   │   ├── WavIo.kt                # WAV file read/write (testing)
│   │   └── [utility enums/models]
│   ├── src/test/java/net/ft8vc/core/
│   │   ├── QsoMachineTest.kt       # QSO state machine tests
│   │   ├── QsoMessagesTest.kt      # Message parsing tests
│   │   ├── SlotCollectorTest.kt    # Slot buffer tests
│   │   ├── SlotTimingTest.kt       # UTC slot calculation tests
│   │   ├── MaidenheadGridTest.kt   # Grid/distance tests
│   │   ├── [20+ more unit tests]
│   │   └── resources/test_samples/  # WAV files for integration tests
│   └── build.gradle.kts
│
├── audio/                          # USB audio capture/playback + DSP
│   ├── src/main/java/net/ft8vc/audio/
│   │   ├── AudioEngine.kt          # RX/TX interface
│   │   ├── AudioInputs.kt          # Input device list model
│   │   ├── AudioOutputs.kt         # Output device list model
│   │   ├── UsbAudioCapture.kt      # 12 kHz RX from Digirig
│   │   ├── UsbAudioPlayback.kt     # 12 kHz TX to Digirig
│   │   └── dsp/                    # DSP module
│   │       ├── SpectrumProcessor.kt    # FFT + waterfall
│   │       ├── FirDecimator.kt         # 48→12kHz decimation
│   │       ├── Fft.kt                  # FFT computation
│   │       └── Upsampler.kt            # 12→48kHz interpolation
│   ├── src/test/java/net/ft8vc/audio/
│   │   └── dsp/FftTest.kt
│   └── build.gradle.kts
│
├── rig/                            # USB device discovery + PTT/CAT
│   ├── src/main/java/net/ft8vc/rig/
│   │   ├── RigController.kt        # USB device manager + fallback
│   │   ├── RigBackend.kt           # PTT/CAT interface
│   │   ├── DigirigRigBackend.kt    # Digirig (CP2102 RTS + CAT)
│   │   ├── NoOpRigBackend.kt       # Fallback when no device
│   │   ├── CatControl.kt           # CAT interface
│   │   ├── Ft891Cat.kt             # Yaesu FT-891 CAT command/response
│   │   └── Cp210x.kt               # Silicon Labs CP2102 USB VID/PID
│   ├── src/test/java/net/ft8vc/rig/
│   │   ├── Ft891CatTest.kt         # CAT parsing tests
│   │   └── Cp210xTest.kt           # USB VID/PID tests
│   └── build.gradle.kts
│
├── data/                           # Room database + ADIF export
│   ├── src/main/java/net/ft8vc/data/
│   │   ├── Logbook.kt              # Logbook interface + RoomLogbook impl
│   │   ├── model/
│   │   │   └── QsoContact.kt       # Contact data class
│   │   ├── db/
│   │   │   ├── Ft8vcDatabase.kt    # Room database + singleton
│   │   │   ├── QsoEntity.kt        # Room @Entity
│   │   │   └── QsoDao.kt           # Room @Dao
│   │   └── adif/
│   │       ├── AdifWriter.kt       # ADIF 3.1.4 export
│   │       ├── AdifExportContext.kt # Export options (POTA, MY_SIG)
│   │       ├── AdifField.kt        # ADIF field model
│   │       ├── AdifNormalizer.kt   # Normalize field values
│   │       └── AdifValidator.kt    # Validate ADIF before write
│   ├── src/test/java/net/ft8vc/data/
│   │   ├── adif/AdifWriterTest.kt
│   │   └── adif/AdifValidatorTest.kt
│   └── build.gradle.kts
│
├── ft8-native/                     # NDK module: JNI + ft8_lib
│   ├── src/main/
│   │   ├── java/net/ft8vc/ft8native/
│   │   │   └── Ft8Native.kt        # JNI method stubs (encode, decode)
│   │   └── cpp/
│   │       ├── ft8_jni.cpp         # JNI bridge implementation
│   │       └── CMakeLists.txt      # NDK build config (fetches ft8_lib)
│   ├── src/androidTest/
│   │   └── assets/                 # Test audio files (WAV)
│   └── build.gradle.kts
│
├── docs/                           # Project documentation
│   ├── README.md                   # Feature overview
│   ├── APP.md                      # UI/ViewModel architecture
│   ├── HARDWARE.md                 # FT-891 + Digirig setup
│   ├── SDK_SETUP.md                # Android SDK/NDK/CMake versions
│   ├── RELEASE.md                  # Release process + APK channels
│   └── TESTING.md                  # Testing guide
│
├── gradle/wrapper/                 # Gradle version
├── .planning/codebase/             # GSD codebase analysis (this file)
│
├── README.md                        # Project quick start
├── AGENTS.md                        # AI coding agent guidelines
├── build.gradle.kts                # Root build config
├── settings.gradle.kts             # Included modules
├── gradle.properties               # JVM + Kotlin version pins
├── .gitignore                      # Version control exclusions
└── local.properties                # Local SDK path (not committed)
```

## Directory Purposes

**app/**
- Purpose: Android app entry point, Compose UI, ViewModels orchestrating all I/O
- Contains: MainActivity, OperateViewModel, Composable screens (Operate, Spectrum, Log, Settings), theme, navigation
- Key files: `app/src/main/java/net/ft8vc/app/OperateViewModel.kt` (state orchestrator), `app/src/main/java/net/ft8vc/app/ui/nav/Ft8NavHost.kt` (navigation host)

**core/**
- Purpose: Pure FT8 domain logic with no Android/UI dependencies (unit-testable)
- Contains: QSO state machine, message parsing, slot timing, decode filtering, validation
- Key files: `core/src/main/java/net/ft8vc/core/QsoMachine.kt` (state machine), `core/src/main/java/net/ft8vc/core/QsoMessages.kt` (message codec)

**audio/**
- Purpose: USB audio capture, playback, DSP (decimation, FFT, waterfall)
- Contains: UsbAudioCapture/UsbAudioPlayback (I/O) and SpectrumProcessor + FirDecimator + Fft (DSP)
- Key files: `audio/src/main/java/net/ft8vc/audio/UsbAudioCapture.kt`, `audio/src/main/java/net/ft8vc/audio/dsp/SpectrumProcessor.kt`

**rig/**
- Purpose: USB device discovery, PTT keying, CAT frequency/mode commands
- Contains: RigController (device manager), DigirigRigBackend (Digirig+FT-891), fallback NoOp impl, Ft891Cat protocol
- Key files: `rig/src/main/java/net/ft8vc/rig/RigController.kt`, `rig/src/main/java/net/ft8vc/rig/Ft891Cat.kt`

**data/**
- Purpose: Room database persistence, ADIF export
- Contains: Room entities/DAO, Logbook interface, ADIF writer/validator
- Key files: `data/src/main/java/net/ft8vc/data/Logbook.kt`, `data/src/main/java/net/ft8vc/data/db/Ft8vcDatabase.kt`

**ft8-native/**
- Purpose: NDK JNI bridge to C/C++ ft8_lib (encode/decode)
- Contains: Java JNI stubs (Ft8Native.kt), C++ wrapper (ft8_jni.cpp), CMake build config
- Key files: `ft8-native/src/main/java/net/ft8vc/ft8native/Ft8Native.kt` (JNI interface), `ft8-native/src/main/cpp/ft8_jni.cpp`

**docs/**
- Purpose: Project documentation (architecture, hardware, testing, release)
- Contains: Feature overview (README.md), UI/ViewModel design (APP.md), hardware setup (HARDWARE.md), SDK setup (SDK_SETUP.md)

**.planning/codebase/**
- Purpose: GSD (Get Stuff Done) codebase analysis artifacts
- Contains: ARCHITECTURE.md, STRUCTURE.md, CONVENTIONS.md (per focus area)

## Key File Locations

**Entry Points:**
- `app/src/main/java/net/ft8vc/app/MainActivity.kt`: Main activity (app launch entry)
- `app/src/main/java/net/ft8vc/app/ui/nav/Ft8NavHost.kt`: Compose root, navigation host
- `app/src/main/AndroidManifest.xml`: Manifest with permissions + intent-filters

**Configuration:**
- `gradle.properties`: JVM/Kotlin versions, build phase pins
- `local.properties`: Local Android SDK path (generated, not committed)
- `build.gradle.kts`: Root Gradle build config
- `settings.gradle.kts`: Module includes (app, core, audio, rig, data, ft8-native)

**Core Logic:**
- `core/src/main/java/net/ft8vc/core/QsoMachine.kt`: FT8 state machine
- `core/src/main/java/net/ft8vc/core/QsoMessages.kt`: Message parsing
- `core/src/main/java/net/ft8vc/core/SlotCollector.kt`: 15-sec slot buffer

**UI/ViewModel:**
- `app/src/main/java/net/ft8vc/app/OperateViewModel.kt`: Main orchestrator
- `app/src/main/java/net/ft8vc/app/OperateUiState.kt`: Compose state model
- `app/src/main/java/net/ft8vc/app/ui/operate/OperateScreen.kt`: Operate tab
- `app/src/main/java/net/ft8vc/app/settings/SettingsScreen.kt`: Settings tab

**Audio/RX:**
- `audio/src/main/java/net/ft8vc/audio/UsbAudioCapture.kt`: 12 kHz RX capture
- `audio/src/main/java/net/ft8vc/audio/dsp/SpectrumProcessor.kt`: FFT + waterfall
- `audio/src/main/java/net/ft8vc/audio/dsp/FirDecimator.kt`: 48→12kHz decimation

**Rig/TX:**
- `rig/src/main/java/net/ft8vc/rig/RigController.kt`: USB device manager
- `rig/src/main/java/net/ft8vc/rig/Ft891Cat.kt`: Yaesu CAT commands
- `audio/src/main/java/net/ft8vc/audio/UsbAudioPlayback.kt`: 12 kHz TX playback

**Data/Log:**
- `data/src/main/java/net/ft8vc/data/Logbook.kt`: Logbook interface
- `data/src/main/java/net/ft8vc/data/db/Ft8vcDatabase.kt`: Room database
- `data/src/main/java/net/ft8vc/data/adif/AdifWriter.kt`: ADIF export

**Native:**
- `ft8-native/src/main/java/net/ft8vc/ft8native/Ft8Native.kt`: JNI method stubs
- `ft8-native/src/main/cpp/ft8_jni.cpp`: JNI implementation

**Testing:**
- `core/src/test/java/net/ft8vc/core/QsoMachineTest.kt`: QSO machine tests
- `rig/src/test/java/net/ft8vc/rig/Ft891CatTest.kt`: CAT protocol tests
- `data/src/test/java/net/ft8vc/data/adif/AdifWriterTest.kt`: ADIF export tests

## Naming Conventions

**Files:**
- Kotlin files use PascalCase matching class name: `QsoMachine.kt`, `UsbAudioCapture.kt`, `Ft891Cat.kt`
- Composable screens end in `Screen`: `OperateScreen.kt`, `SpectrumScreen.kt`, `LogScreen.kt`
- UI components end in their type: `StatusBar`, `Panel`, `Toggle`, `Indicator`, `Dialog`
- Test files match source with `Test` suffix: `QsoMachineTest.kt`, `Ft891CatTest.kt`
- Config/model files use noun names: `AppInfo.kt`, `StationSettings.kt`, `OperateUiState.kt`

**Directories:**
- Module root directories are lowercase (app, core, audio, rig, data, ft8-native)
- Package hierarchy follows domain: `net.ft8vc.app.ui.operate`, `net.ft8vc.core`, `net.ft8vc.audio.dsp`
- Feature/screen subdirectories use lowercase (ui/operate, ui/spectrum, ui/log)

**Classes/Interfaces:**
- PascalCase for classes: `QsoMachine`, `UsbAudioCapture`, `Ft891Cat`
- Sealed classes for sum types: `QsoRx`, `QsoState`, `QsoRole`
- Enums for fixed sets: `TxSlotParity`, `DecodeViewMode`, `AnswerPolicy`
- Interfaces for abstractions: `RigBackend`, `AudioEngine`, `Logbook`, `CatControl`

**Functions/Variables:**
- camelCase for all functions and properties: `startOperating()`, `onDecodes()`, `txMessage`
- Private members prefixed with underscore where needed (Kotlin convention): `_state`, `_events`
- Boolean fields/functions use "is" or "should" prefix: `isOperating`, `isCq`, `shouldLog`

## Where to Add New Code

**New Feature (e.g., auto-tune, band selection):**
- Primary code: Feature logic in `core/src/main/java/net/ft8vc/core/` (pure, testable)
  - Example: new enum, state machine, filtering logic → core module
- UI integration: Add Composable to `app/src/main/java/net/ft8vc/app/ui/` and wire into OperateViewModel
  - Example: `app/src/main/java/net/ft8vc/app/ui/BandSelectionSheet.kt` (new screen component)
- State: Add fields to `OperateUiState` and handle in OperateViewModel
- Tests: `core/src/test/java/net/ft8vc/core/YourNewLogicTest.kt`

**New Component/Module (e.g., frequency memory, macro storage):**
- Implementation: Create new module directory at root level (e.g., `memory/`) with Gradle build.gradle.kts
  - Structure: `memory/src/main/java/net/ft8vc/memory/` (logic) + `memory/src/test/java/` (tests)
- Interface: Define public API (interface) in module root package: `memory/src/main/java/net/ft8vc/memory/Memory.kt`
- Integration: Add to settings.gradle.kts (include(":memory")), then import in app/build.gradle.kts and wire in OperateViewModel
- Example: See how `data/` module is structured (Logbook interface, RoomLogbook impl, db subpackage)

**Utilities/Helpers:**
- Shared helpers (grid math, message parsing): Place in `core/src/main/java/net/ft8vc/core/` (reusable, no Android deps)
  - Example: `DecodeDistance.kt`, `MaidenheadGrid.kt`
- Android-specific utils (device list, preferences): Place in `app/src/main/java/net/ft8vc/app/settings/` or new `app/src/main/java/net/ft8vc/app/util/`
- DSP/Audio utilities: Place in `audio/src/main/java/net/ft8vc/audio/dsp/`

**UI Screens:**
- New top-level tab (like Operate, Spectrum, Log): Create `app/src/main/java/net/ft8vc/app/ui/[feature]/[Feature]Screen.kt`, add route to `Ft8Destinations` enum, add NavHost composable in `Ft8NavHost.kt`, add ViewModel if needed
- Dialog/bottom-sheet: Create in `app/src/main/java/net/ft8vc/app/ui/[feature]/` (co-located with feature)
- Reusable component: Create in `app/src/main/java/net/ft8vc/app/ui/` root (not in a feature folder) if used by multiple screens

**Tests:**
- Unit tests for core logic: `core/src/test/java/net/ft8vc/core/` (mirror package structure)
- Android-dependent tests: `audio/src/test/java/`, `rig/src/test/java/`, `data/src/test/java/` (module-specific)
- Instrumentation tests (AndroidTest): `ft8-native/src/androidTest/` (requires emulator/device)

## Special Directories

**build/ (per module):**
- Purpose: Gradle build output
- Generated: Yes (by Gradle)
- Committed: No (.gitignore)

**.cxx/ (ft8-native module):**
- Purpose: NDK CMake intermediate outputs
- Generated: Yes (by CMake/NDK)
- Committed: No (.gitignore)

**.gradle/ (root):**
- Purpose: Gradle daemon cache, wrapper JAR
- Generated: Yes
- Committed: No (.gitignore)

**.kotlin/sessions/ (root):**
- Purpose: Kotlin language server session cache (IDE)
- Generated: Yes
- Committed: No (.gitignore)

**.idea/ (root):**
- Purpose: Android Studio IDE settings
- Generated: Yes (partially)
- Committed: Partially (codeStyles committed, cache not)

---

*Structure analysis: 2026-06-21*
