
## Project

**FT8VC — v1.x Code Health Milestone**

FT8VC is an open-source Android FT8 transceiver that drives an amateur radio rig (reference: Yaesu FT-891 + Digirig Mobile) over USB audio + serial from a phone — no laptop in the field. v1.0 ships as signed APKs from GitHub Releases with `unstable` as the day-to-day development channel. This milestone is a focused **code-health pass** on the v1.0 codebase: refactor the monolithic orchestrator, harden threading and resource lifecycle, close the worst error-handling and security gaps, and bring it all under test — without expanding the feature surface.

**Core Value:** **The rig still keys, decodes still arrive, and QSOs still complete on a real FT-891 + Digirig in the field.** Every change in this milestone must preserve that. If a refactor risks RX/TX/CAT behavior on the reference setup, it doesn't ship.

### Constraints

- **Tech stack**: Kotlin + Jetpack Compose + Coroutines for the JVM side; C/C++ `ft8_lib` via JNI for DSP. No new top-level dependencies for this milestone unless they enable a controller seam (e.g., a DI / lifecycle helper).
- **Platform**: Android `minSdk = 28` (Android 9). API guards not required at this level.
- **Hardware fidelity**: The reference setup is **Yaesu FT-891 + Digirig Mobile over USB-C OTG**. Field verification on that rig is the bar before each promotion.
- **Release channel**: Land on `unstable` (`net.ft8vc.unstable`) phase-by-phase; promote to stable (`net.ft8vc` / `main`) only when the milestone is verified end-to-end.
- **Behavior parity**: RX/TX/CAT/QSO behavior must be byte-equivalent to v1.0 on the reference rig. UX deltas are allowed (CAT timeout status, USB disconnect snackbar, decode counter, ADIF auto-export), but they must not crowd or claim main-screen real estate.
- **License gate**: TX stays gated behind license acknowledgment; nothing in the refactor weakens the receive-only default.



## Technology Stack

## Languages

- Kotlin 2.3.21 - Main application code, Android modules
- C/C++ - FT8 encode/decode via NDK wrapper (ft8_lib binding)
- Java - JVM compatibility layer for Android framework
- CMake 4.1.2 - Native build configuration

## Runtime

- Android SDK 36 (compileSdk)
- Target SDK 36 (targetSdk 34 in practice for production)
- Minimum SDK 28 (Android 9.0)
- Android NDK r29 (pinned, not r30 RC/beta)
- Java 17 (sourceCompatibility and targetCompatibility)
- JVM target: JVM 17
- Gradle (Gradle Wrapper 7.x included in repo)
- Lockfile: gradle/wrapper/gradle-wrapper.properties

## Frameworks

- Android Jetpack Compose (BOM 2026.05.01) - Declarative UI framework
- Material Design 3 (`androidx.compose.material3`)
- Material Icons Extended - Icon library
- AndroidX Lifecycle 2.10.0 - ViewModel, LiveData support
- AndroidX Navigation 2.9.0 - Fragment-based navigation (not Compose Navigation)
- AndroidX Activity Compose 1.13.0 - Activity+Compose integration
- AndroidX Room 2.7.2 - Local SQLite database ORM
- AndroidX DataStore Preferences 1.1.7 - Encrypted key-value storage for settings
- Kotlin Coroutines 1.10.2 (`kotlinx-coroutines-android`)
- Kotlin Coroutines Test 1.10.2 - Testing library
- KSP (Kotlin Symbol Processing) 2.3.7 - Replacement for KAPT
- Kotlin Compose Compiler Plugin - Built into Kotlin 2.0+
- Android Gradle Plugin (AGP) 9.2.1

## Key Dependencies

- `ft8_lib` (kgoba GitHub) - Pinned commit 9fec6ca39886edbf96f4f5e71edc76da5074e871 via CMake FetchContent
- `usb-serial-for-android` 3.9.0 (mik3y) - CAT serial transport; JitPack scoped to `com.github.mik3y`, checksum-verified (see docs/USB_SERIAL_LIB_UPGRADE.md)
- AndroidX Core-KTX 1.16.0 - Core Android utilities
- Android System Libraries - USB Host, Audio Recording (via manifest permissions)

## Configuration

- Version code/name injected via CI env vars:
- Debug and release build variants configured in `app/build.gradle.kts`
- Root: `build.gradle.kts` (plugin declarations with `apply false`)
- Per-module: `**/build.gradle.kts` (Kotlin DSL)
- Version catalog: `gradle/libs.versions.toml`
- Settings: `settings.gradle.kts`
- JVM args: -Xmx2048m (build memory), UTF-8 encoding
- Caching and parallel builds enabled
- AndroidX enabled; non-transitive R class
- Kotlin code style: official
- Release keystore resolved from env var `FT8VC_KEYSTORE`
- Credentials via env vars: `FT8VC_KEYSTORE_PASSWORD`, `FT8VC_KEY_ALIAS`, `FT8VC_KEY_PASSWORD`
- ProGuard rules in `app/proguard-rules.pro`

## Platform Requirements

- Android Studio Panda (AGP 9.2 compatible)
- SDK Manager: SDK Platform 36, SDK Tools (CMake 4.1.2)
- NDK r29 (explicit requirement, r30 not used for production)
- CMake 4.1.2+ (install via SDK Manager)
- JDK 17+
- Android 9.0 (API 28) minimum
- Android 15+ (API 36) for latest features (16 KB page size support)
- USB Host hardware feature (android.hardware.usb.host required)
- Audio recording permission (RECORD_AUDIO)
- Signed APK via GitHub Releases
- Stable and unstable variants (separate package IDs: `net.ft8vc` vs `net.ft8vc.unstable`)
- Side-by-side installation with stable signing key reuse
- arm64-v8a (primary for modern Android devices)
- armeabi-v7a (32-bit ARM)
- x86_64 (emulator and some tablets)
- 16 KB page size alignment (Android 15+ / Pixel emulators)



## Conventions

## Naming Patterns

- PascalCase for class/object files: `QsoMachine.kt`, `AnswerSelector.kt`
- PascalCase for data classes: `QsoForm.kt`, `QsoSnapshot.kt`
- PascalCase for utility objects: `QsoMessages.kt`, `MaidenheadGrid.kt`, `SlotTiming.kt`
- Single top-level public type per file (one class/object/interface per file)
- camelCase for public and private functions
- Single-word or compound descriptive names: `startCq()`, `answerCq()`, `onDecodes()`, `formatReport()`
- Verb-first for action methods: `applyForm()`, `markTransmitted()`, `setManualControl()`
- Boolean getters as properties: `val isActive: Boolean`, `val isComplete: Boolean`
- Predicate functions as `isX` or `hasX`: `isValidCall()`, `isValid4()`, `hasCustomOverride()`
- Private functions in companion objects use underscore prefix for internal helpers (rarely used; most are regular functions)
- camelCase for all local and member variables
- Short names for iteration: `d` for decode, `it` for current item in lambdas
- Null-safe: postfix `?` in names when nullable: `dxCall: String?`, `dxGrid: String?`
- Private mutable state with leading underscore for backing fields in sealed types (rare in this codebase; prefer immutable data classes)
- PascalCase for all classes, interfaces, objects, enums, and sealed types
- Enum values in CONSTANT_CASE within Kotlin enums: `FIRST`, `BEST_SNR`, `FURTHEST` (for `AnswerPolicy`)
- Exception types end in `Exception`: never used in this codebase (see Error Handling below)
- Sealed interface for discriminated unions: `sealed interface QsoRx { ... }`
- Companion objects named implicitly (no explicit name): `companion object { ... }`

## Code Style

- Kotlin Official style (enforced via IDE: `JetCodeStyleSettings` in `.idea/codeStyles/Project.xml`)
- Indentation: 4 spaces (configurable; current project uses 4-space)
- Line length: no hard limit enforced; typical: 100-120 characters
- Bracket style: opening brace on same line (Kotlin convention)
- No semicolons
- No explicit linter configured (no `.ktlint` or `detekt` config files)
- Relies on IDE inspections and Kotlin compiler warnings
- All source files compile without warnings at `jvmTarget = JVM_17`
- Standard import organization by IDE (Android Studio)
- Wildcard imports not used
- No star imports in this codebase

## Import Organization

- No path aliases (gradle `include` statements) used in imports
- Fully qualified imports required: `net.ft8vc.core.QsoMachine`, `net.ft8vc.app.OperateViewModel`

## Error Handling

- **Validation before state change:** Input validated before applying: `isValidCall()`, `isValidGrid()` called before use
- **Exceptions for internal contract violations:** `throw IllegalStateException("Encoder rejected: $message")` when internal preconditions fail
- **Graceful null coalescing:** `?.let { ... }` for optional chains, early returns for validation failures
- **Unchecked exceptions (not caught explicitly):** Errors bubble up to caller (typical Kotlin style)
- `InterruptedException` caught and ignored when coroutines are cancelled: `catch (_: InterruptedException) { }`
- CAT/serial failures logged as strings: `_state.update { it.copy(catStatus = t.message ?: "CAT error") }`

## Logging

- `throw IllegalStateException("message")` for logic errors
- State mutations captured in flow-based UI state (`_state.update { ... }`)
- Error messages stored as UI strings: `catStatus: String` in `OperateUiState`
- No console logging in production code
- Error messages included in state for UI display
- Throwable messages extracted as fallback: `t.message ?: "CAT error"`

## Comments

- Function-level: Doc comments for public APIs (rare; most are self-documenting)
- High-level explanations for complex state machines
- Enum documentation for each value
- KDoc (Kotlin doc) used for public classes and functions
- Format: `/** ... */` above declarations
- Includes description, parameter docs, return value docs
- Minimal; code is self-documenting
- Brief explanations of non-obvious logic (state machine transitions)
- No comment drift (comments stay in sync with code due to small, focused functions)

## Function Design

- Small and focused: most functions 10–50 lines
- Largest function: `OperateViewModel.kt` @ 1135 lines (noted as needing refactor into sub-controllers)
- Typical: 15–30 lines for business logic
- Limited count: max 5–6 for public functions (see `onDecodes()` with 3 params + defaults)
- Default values used for optional parameters: `answerPolicy: AnswerPolicy = AnswerPolicy.FIRST`
- No varargs in this codebase; lists used instead: `decodes: List<QsoDecode>`
- Named arguments supported but not required in call sites
- Single return type (no multiple returns)
- Sealed interfaces for discriminated unions: `sealed interface QsoRx { ... }`
- Nullable returns for optional results: `fun selectCq(...): QsoDecode?`
- Boolean for success/failure: `fun onDecodes(...): Boolean`
- No void functions; side effects via coroutines/flows

## Module Design

- Top-level classes/objects exported as-is
- No barrel files (no `index.kt` that re-exports all module contents)
- Each module has focused public API surface
- `core/`: Pure FT8 logic, no Android dependencies, fully testable
- `app/`: UI, ViewModels, screen composition
- `audio/`: Audio I/O (USB/device capture/playback)
- `rig/`: CAT controller (serial, Yaesu, Digirig)
- `data/`: Database and persistence (Room, DataStore)
- `ft8-native/`: JNI bindings to native FT8 encoder/decoder
- Internal implementation classes marked `private`
- Public APIs exposed at module boundary
- No `internal` keyword used (relies on package visibility)

## Constants

- Companion objects for module-level constants (rare)
- Regex patterns as private lazy properties: 
- Enum values for options: `AnswerPolicy.FIRST`, `DecodeViewMode.OPERATE`

## Sealed Types and ADTs



## Architecture

## System Overview

```text

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
| **SerialRigBackend** | Composes serial transport + CAT protocol into PTT (RTS) and CAT control | `rig/src/main/java/net/ft8vc/rig/SerialRigBackend.kt` |
| **YaesuCat** | Yaesu new-CAT command/response parsing (freq, mode, DATA-U), parameterized by model | `rig/src/main/java/net/ft8vc/rig/YaesuCat.kt` |
| **QsoMachine** | Pure FT8 QSO state machine (CQ → grid → reports → 73) | `core/src/main/java/net/ft8vc/core/QsoMachine.kt` |
| **SlotCollector** | Buffers PCM samples, flushes on 15-second UTC slot boundary | `core/src/main/java/net/ft8vc/core/SlotCollector.kt` |
| **QsoMessages** | Parses/formats FT8 message types (CQ, grid, reports, RRR, 73) | `core/src/main/java/net/ft8vc/core/QsoMessages.kt` |
| **SettingsRepository** | Preferences (DataStore) for station, audio, rig, TX prefs | `app/src/main/java/net/ft8vc/app/settings/SettingsRepository.kt` |
| **RoomLogbook** | Room database abstraction: persist contacts, export ADIF | `data/src/main/java/net/ft8vc/data/Logbook.kt` |
| **Ft8vcDatabase** | Room DAO for QSO contacts | `data/src/main/java/net/ft8vc/data/db/Ft8vcDatabase.kt` |
| **Ft8Native** | JNI bridge to ft8_lib C/C++ for encode/decode | `ft8-native/src/main/java/net/ft8vc/ft8native/Ft8Native.kt` |

## Pattern Overview

- **Separation of concerns by domain** — Audio, Rig, Core (FT8 logic), and Data are independent modules with Kotlin interfaces; the ViewModel imports from all four
- **Pure logic isolation** — Core module classes like `QsoMachine`, `SlotCollector`, `QsoMessages` have no Android/UI dependencies; they accept injected time and callbacks for unit testing
- **Coroutine-driven concurrency** — All I/O (audio threads, CAT reads, decode) is coordinated via `viewModelScope` and StateFlow/SharedFlow
- **Single source of truth** — `OperateUiState` (single StateFlow) holds all screen and background state; derives from settings, audio level, QSO progress, and rig status
- **Android lifecycle tying** — OperateViewModel lifecycle is tied to Activity via AndroidViewModel; `onCreate`/`onDestroy` hooks manage audio capture, playback, and decode threads

## Layers

- Purpose: Compose UI, navigation, ViewModels
- Location: `app/src/main/java/net/ft8vc/app/`
- Contains: Screens (Operate, Spectrum, Log, Settings), OperateViewModel, OperateUiState, SettingsRepository
- Depends on: Jetpack Compose, Lifecycle, Navigation, all backend modules (core, audio, rig, data)
- Used by: MainActivity (entry point)
- Purpose: FT8 protocol, QSO sequencing, slot timing, decode filtering
- Location: `core/src/main/java/net/ft8vc/core/`
- Contains: QsoMachine, QsoMessages, SlotCollector, DecodeViewMode, AnswerPolicy, MaidenheadGrid, AbandonedPartners
- Depends on: Kotlin stdlib only (no Android, no external libs)
- Used by: OperateViewModel, audio decode loop, test suites
- Purpose: USB audio capture/playback, DSP (decimation, FFT, waterfall)
- Location: `audio/src/main/java/net/ft8vc/audio/`
- Contains: UsbAudioCapture, UsbAudioPlayback, SpectrumProcessor, FirDecimator, Fft, Upsampler
- Depends on: Android AudioRecord/AudioTrack APIs, core module (sample rate const), ft8-native (encode)
- Used by: OperateViewModel (callbacks for frames and spectrum)
- Purpose: USB device discovery, PTT keying, CAT for frequency/mode
- Location: `rig/src/main/java/net/ft8vc/rig/`
- Contains: RigController, SerialRigBackend, SerialTransport/UsbSerialTransport, CatProtocol/YaesuCat, PttStrategy, NoOpRigBackend
- Depends on: Android USB APIs, Kotlin stdlib
- Used by: OperateViewModel (read CAT, set PTT)
- Purpose: Room database, ADIF export
- Location: `data/src/main/java/net/ft8vc/data/`
- Contains: RoomLogbook, Ft8vcDatabase, QsoEntity, QsoDao, AdifWriter, AdifNormalizer, AdifValidator
- Depends on: Room ORM, core module (models)
- Used by: OperateViewModel (log completed QSOs)
- Purpose: JNI wrapper around kgoba/ft8_lib (C/C++) for FT8 encode/decode
- Location: `ft8-native/src/main/java/net/ft8vc/ft8native/`
- Contains: Ft8Native (JNI methods for encode, decode batch)
- Depends on: NDK, CMake, ft8_lib source (fetched at build time)
- Used by: OperateViewModel (decode pass on each slot), UsbAudioPlayback (TX encode)

## Data Flow

### Primary RX Decode Path

### Primary TX QSO Path

### CAT Frequency/Mode Read

- All state flows through `OperateUiState` (single StateFlow in OperateViewModel)
- Settings persisted via DataStore (Preferences) in SettingsRepository
- Log persisted via Room DAO (Ft8vcDatabase.qsoDao())
- QSO session state (QsoMachine) is in-memory during operate; lost on app exit (by design — recover via Continue)

## Key Abstractions

- Purpose: Pure FT8 state machine (no I/O, no Android dependencies)
- Examples: `core/src/main/java/net/ft8vc/core/QsoMachine.kt`
- Pattern: Sealed enum of QsoState (Idle, CallingCq, Answering, SendingReport, etc); stateful accumulator with methods:
- Purpose: Parse/format FT8 message types (CQ, directed, reports, endings)
- Examples: `core/src/main/java/net/ft8vc/core/QsoMessages.kt`
- Pattern: Object with sealed class hierarchy (QsoRx.Cq, GridReply, Report, RReport, Roger, RogerBye, Bye, Other)
- Purpose: Buffers mono PCM samples into 15-second UTC slot windows
- Examples: `core/src/main/java/net/ft8vc/core/SlotCollector.kt`
- Pattern: Stateful buffer with wall-clock boundary detection:
- Purpose: Abstraction for RX/TX audio, allows swapping implementations
- Examples: `audio/src/main/java/net/ft8vc/audio/AudioEngine.kt` (interface), UsbAudioCapture/UsbAudioPlayback
- Pattern: start(deviceId, callback) and stop() contract; onFrames/onEncoded callbacks carry data
- Purpose: Abstraction for PTT and CAT, allows mocking and fallback
- Examples: `rig/src/main/java/net/ft8vc/rig/RigBackend.kt` (interface), SerialRigBackend, NoOpRigBackend
- Pattern: keysender, unsendPtt(), readCat() → returns struct with freq, mode
- Purpose: Abstraction for log persistence (Room impl; could be swapped)
- Examples: `data/src/main/java/net/ft8vc/data/Logbook.kt` (interface), RoomLogbook
- Pattern: suspend fun log(contact), fun contacts() Flow<List>, suspend exportAdif(), contactCount() Flow

## Entry Points

- Location: `app/src/main/java/net/ft8vc/app/MainActivity.kt`
- Triggers: Android app launch (LAUNCHER intent-filter + USB device attach intent-filter)
- Responsibilities: Creates AndroidView context, calls setContent { Ft8vcApp() }; Compose tree bootstrap
- Location: `app/src/main/java/net/ft8vc/app/ui/nav/Ft8NavHost.kt:29`
- Triggers: Emitted by MainActivity.onCreate
- Responsibilities: Instantiates OperateViewModel/LogViewModel, sets up NavHost with 4 bottom-tab destinations (Operate, Spectrum, Log, Settings)
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

### QSO State Lost on App Exit

### Settings Sync Race in OperateViewModel

## Error Handling

- **USB device not ready** → RigController falls back to NoOpRigBackend; CAT and PTT become no-ops; state.catReady remains false; Settings screen shows USB status
- **Audio capture fails** → Exception caught in UsbAudioCapture.start() thread loop; notify() emits SnackbarEvent; isCapturing remains false
- **CAT read timeout** → `SerialRigBackend`/`YaesuCat` return null; state.rigFreqHz remains stale until next successful read
- **Decode out of samples** → SlotCollector skips slot if < 85% of expected samples; silent skip (no error)
- **ADIF export validation fails** → Export fails closed; exception propagates as SnackbarEvent with reason

## Cross-Cutting Concerns



## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, `.github/skills/`, or `.codex/skills/` with a `SKILL.md` index file.

## Primary Workflow: Superpowers

This project uses the **superpowers** skills as the working methodology.

- **New work** (features, components, behavior changes): start with
  `superpowers:brainstorming` → `superpowers:writing-plans` → execute the plan.
- **Bugs / unexpected behavior**: start with `superpowers:systematic-debugging`.
- **Implementing a feature or fix**: use `superpowers:test-driven-development`
  (write the failing test first).
- **Finishing work**: `superpowers:verification-before-completion` before
  claiming anything is done, then `superpowers:finishing-a-development-branch`.
- Planning artifacts live under `docs/superpowers/` (specs, plans, reports).
