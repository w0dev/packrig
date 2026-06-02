# SDK setup (what to install vs skip)

Android Studio’s **SDK Manager** shows several “update available” items. Not all of them mean you should change the project pins.

## Install these (stable)

| SDK Manager item | Version you see | Action |
|------------------|-----------------|--------|
| **Android SDK Platform-Tools** | 37.0.0 | **Install/update.** This is `adb`, fastboot, etc. It does **not** set your app’s `compileSdk`. Always keep it current. |
| **CMake** | 4.1.2 | **Install/update.** Matches `cmake = "4.1.2"` in `gradle/libs.versions.toml`. Required to build `libft8vc.so`. |
| **NDK (Side by side)** | **29.0.14206865** (stable channel) | **Install.** Matches `ndk = "29.0.14206865"`. 16 KB page alignment by default. |
| **Android SDK Platform 36** | API 36 | **Install.** Matches `compileSdk` / `targetSdk` 36. |
| **Android SDK Build-Tools 36.0.0** | 36.0.0 | **Install.** Required by AGP 9.2. |

## Skip for now (pre-release)

| SDK Manager item | Why skip |
|------------------|----------|
| **NDK 30.0.14…rc1** (or beta) | **Not stable yet.** r30 is still beta/RC. For a real radio app, stay on **NDK r29** until Google ships r30 **stable** in the SDK “stable” channel. |
| **compileSdk 37** | AGP **9.2** officially supports up to **API 36.1**. Platform-Tools 37 ≠ API 37 platform. Don’t bump `compileSdk` until AGP release notes list API 37. |

## After installing

1. **File → Sync Project with Gradle Files**
2. **Fully rebuild native code** (required after NDK/linker changes — a normal Run often reuses old `.so` files):

   ```powershell
   .\gradlew.bat clean :ft8-native:externalNativeBuildCleanDebug :app:assembleDebug
   ```

3. **Uninstall** the old FT8VC app from the emulator (long-press → Uninstall), then **Run** again from Android Studio.

4. The **16 KB page size** dialog should disappear once `libft8vc.so` is rebuilt with **NDK 29** and 16 KB linker flags.

### Verify `libft8vc.so` alignment (optional)

After a debug build, on Windows PowerShell (adjust path if needed):

```powershell
$so = "app\build\intermediates\merged_native_libs\debug\mergeDebugNativeLibs\out\lib\x86_64\libft8vc.so"
& "$env:LOCALAPPDATA\Android\Sdk\ndk\29.0.14206865\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-objdump.exe" -p $so | Select-String "LOAD"
```

Look for `align 2**14` (16384). If you see `2**12` (4096), the native rebuild did not pick up NDK 29 — check SDK Manager and run `clean` again.

### `libandroidx.graphics.path.so` still listed?

That library comes from **Jetpack Compose** (AndroidX). Updating the Compose BOM usually fixes it. If it still appears after a clean rebuild, tap **Don't show again** — the app runs in compatible mode; it is not your FT8 codec. We track newer AndroidX releases as they ship 16 KB–aligned binaries.

## Quick check

```text
SDK Manager → SDK Tools (installed):
  ✓ NDK 29.0.14206865
  ✓ CMake 4.1.2
  ✓ Android SDK Platform-Tools 37.0.0
  ✓ Android SDK Build-Tools 36.0.0
```

If CMake 4.1.2 is missing, Gradle fails with `[CXX1300] CMake '4.1.2' was not found` — install it from SDK Tools, then sync again.
