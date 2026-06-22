# Technology Stack

**Analysis Date:** 2026-06-21

## Languages

**Primary:**
- Kotlin 2.3.21 - Main application code, Android modules
- C/C++ - FT8 encode/decode via NDK wrapper (ft8_lib binding)

**Secondary:**
- Java - JVM compatibility layer for Android framework
- CMake 4.1.2 - Native build configuration

## Runtime

**Environment:**
- Android SDK 36 (compileSdk)
- Target SDK 36 (targetSdk 34 in practice for production)
- Minimum SDK 28 (Android 9.0)
- Android NDK r29 (pinned, not r30 RC/beta)

**Java/JVM:**
- Java 17 (sourceCompatibility and targetCompatibility)
- JVM target: JVM 17

**Package Manager:**
- Gradle (Gradle Wrapper 7.x included in repo)
- Lockfile: gradle/wrapper/gradle-wrapper.properties

## Frameworks

**Core UI:**
- Android Jetpack Compose (BOM 2026.05.01) - Declarative UI framework
- Material Design 3 (`androidx.compose.material3`)
- Material Icons Extended - Icon library

**Lifecycle & Navigation:**
- AndroidX Lifecycle 2.10.0 - ViewModel, LiveData support
  - `lifecycle-runtime-ktx` - Runtime coroutine integration
  - `lifecycle-runtime-compose` - Compose integration
  - `lifecycle-viewmodel-compose` - ViewModel in Compose
- AndroidX Navigation 2.9.0 - Fragment-based navigation (not Compose Navigation)
- AndroidX Activity Compose 1.13.0 - Activity+Compose integration

**Data Persistence:**
- AndroidX Room 2.7.2 - Local SQLite database ORM
  - `room-runtime` - Core ORM
  - `room-ktx` - Kotlin coroutine support
  - `room-compiler` - KSP annotation processor
- AndroidX DataStore Preferences 1.1.7 - Encrypted key-value storage for settings

**Async/Concurrency:**
- Kotlin Coroutines 1.10.2 (`kotlinx-coroutines-android`)
- Kotlin Coroutines Test 1.10.2 - Testing library

**Build/Annotation Processing:**
- KSP (Kotlin Symbol Processing) 2.3.7 - Replacement for KAPT
- Kotlin Compose Compiler Plugin - Built into Kotlin 2.0+
- Android Gradle Plugin (AGP) 9.2.1

## Key Dependencies

**Critical (Non-Framework):**
- `ft8_lib` (kgoba GitHub) - Pinned commit 9fec6ca39886edbf96f4f5e71edc76da5074e871 via CMake FetchContent
  - MIT license
  - Compiled subset: ft8 (constants, crc, decode, encode, ldpc, message, text), fft (kiss_fft), common (monitor)
  - Omitted: common/audio.c (PortAudio), common/wave.c (file I/O) — Android audio fed directly

**Android Core:**
- AndroidX Core-KTX 1.16.0 - Core Android utilities
- Android System Libraries - USB Host, Audio Recording (via manifest permissions)

## Configuration

**Environment:**
- Version code/name injected via CI env vars:
  - `FT8VC_VERSION_CODE` - Integer version code (default 100)
  - `FT8VC_VERSION_NAME_SUFFIX` - Release suffix (default "-unstable")
- Debug and release build variants configured in `app/build.gradle.kts`
  - Debug: packageId suffixed with `.debug`, minify disabled
  - Release: R8 code shrinking enabled, conditional signing via env vars

**Build:**
- Root: `build.gradle.kts` (plugin declarations with `apply false`)
- Per-module: `**/build.gradle.kts` (Kotlin DSL)
- Version catalog: `gradle/libs.versions.toml`
  - Centralized dependency versions
  - Plugin aliases (e.g., `libs.plugins.android.application`)
- Settings: `settings.gradle.kts`
  - Plugin management (Google, Maven Central, Gradle Portal)
  - Dependency resolution (fail-on-project-repos mode)
  - Modules: `:app`, `:core`, `:audio`, `:rig`, `:data`, `:ft8-native`

**Gradle Properties (`gradle.properties`):**
- JVM args: -Xmx2048m (build memory), UTF-8 encoding
- Caching and parallel builds enabled
- AndroidX enabled; non-transitive R class
- Kotlin code style: official

**Signing:**
- Release keystore resolved from env var `FT8VC_KEYSTORE`
- Credentials via env vars: `FT8VC_KEYSTORE_PASSWORD`, `FT8VC_KEY_ALIAS`, `FT8VC_KEY_PASSWORD`
- ProGuard rules in `app/proguard-rules.pro`

## Platform Requirements

**Development:**
- Android Studio Panda (AGP 9.2 compatible)
- SDK Manager: SDK Platform 36, SDK Tools (CMake 4.1.2)
- NDK r29 (explicit requirement, r30 not used for production)
- CMake 4.1.2+ (install via SDK Manager)
- JDK 17+

**Runtime (Device/Emulator):**
- Android 9.0 (API 28) minimum
- Android 15+ (API 36) for latest features (16 KB page size support)
- USB Host hardware feature (android.hardware.usb.host required)
- Audio recording permission (RECORD_AUDIO)

**Production (Deployment):**
- Signed APK via GitHub Releases
- Stable and unstable variants (separate package IDs: `net.ft8vc` vs `net.ft8vc.unstable`)
- Side-by-side installation with stable signing key reuse

**Native Architecture Support:**
- arm64-v8a (primary for modern Android devices)
- armeabi-v7a (32-bit ARM)
- x86_64 (emulator and some tablets)
- 16 KB page size alignment (Android 15+ / Pixel emulators)

---

*Stack analysis: 2026-06-21*
