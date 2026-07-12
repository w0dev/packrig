# Upgrading ft8_lib

Runbook for bumping the pinned [`kgoba/ft8_lib`](https://github.com/kgoba/ft8_lib)
commit that powers FT8 encode/decode. Written so the pin never becomes a
"set once, forgotten forever" fossil — follow this top to bottom when upstream
moves and you want the fixes.

## Current state

- **Pinned commit:** `9fec6ca39886edbf96f4f5e71edc76da5074e871`
- As of **2026-07-04** this *is* upstream `HEAD` — there is nothing newer to take.
  Upstream moves slowly and cuts no releases (only tags `0.1` and `2.0` exist,
  both older than the pin), so commit pinning is the only sane strategy.
- Upstream ships no CMake build; we compile a hand-picked source subset
  directly into `libpackset.so` (see below), which is why upgrades need a real
  checklist and not just a hash bump.

### Check whether upstream has moved

```bash
git ls-remote https://github.com/kgoba/ft8_lib.git HEAD
```

If the hash differs from the pin, review what changed before touching anything:

```text
https://github.com/kgoba/ft8_lib/compare/9fec6ca...<new-hash>
```

## Where the pin lives (keep these in sync)

| Location | What it holds |
|----------|---------------|
| `ft8-native/src/main/cpp/CMakeLists.txt` — `set(FT8LIB_COMMIT ...)` | **Source of truth.** Full 40-char commit hash; used as `GIT_TAG` and passed to the JNI code as the `FT8LIB_SHORT_HASH` compile definition |
| `ft8-native/src/main/cpp/ft8_jni.cpp` — `version()` | Builds the `"packset-native x.y.z (ft8_lib <short hash>)"` string shown in Settings → "Decoder library". The short hash comes from `FT8LIB_SHORT_HASH` automatically; only the `packset-native` version number is hardcoded here |
| [FT8_NATIVE.md](FT8_NATIVE.md) | Documents the pinned hash |
| `.claude/CLAUDE.md` — Key Dependencies | Documents the pinned hash |

Historical specs/plans under `docs/superpowers/` also mention the hash; those are
frozen point-in-time artifacts — do **not** update them.

## Coupling surface (what can break)

The JNI bridge (`ft8_jni.cpp`) couples to ft8_lib in two ways:

1. **Compiled sources.** `CMakeLists.txt` lists an explicit subset:
   `ft8/{constants,crc,decode,encode,ldpc,message,text}.c`,
   `fft/kiss_fft{,r}.c`, `common/monitor.c`. We deliberately omit
   `common/audio.c` (PortAudio) and `common/wave.c` (file I/O). If upstream
   adds, renames, or splits files, the list must be updated by hand.
2. **Ported demo code.** `ft8_jni.cpp` contains code *copied and adapted* from
   ft8_lib's demos, which FetchContent does **not** track:
   - callsign hash table + `ftx_callsign_hash_interface_t` glue (from
     `demo/decode_ft8.c`)
   - GFSK waveform synthesis constants and loop (from `demo/gen_ft8.c`)
   - decoder tuning constants (candidate limits, LDPC iterations, min score —
     mirrors `demo/decode_ft8.c`)

   On upgrade, diff upstream's `demo/decode_ft8.c` and `demo/gen_ft8.c` against
   the ported sections and re-sync any behavioral changes.

Also re-check `HAVE_STPCPY` (we define it so ft8_lib doesn't redefine bionic's
`stpcpy`) — confirm upstream still guards on it.

## Upgrade procedure

1. **Review the upstream diff** (compare URL above). Pay attention to:
   changes in the compiled source subset, header/API changes in
   `ft8/constants.h`, `ft8/message.h`, `ft8/encode.h`, `ft8/decode.h`,
   `common/monitor.h`, and changes to the two demo files we ported from.
2. **Bump the pin:** update `set(FT8LIB_COMMIT ...)` in
   `ft8-native/src/main/cpp/CMakeLists.txt` to the new full hash. The short
   hash in the version string follows automatically.
3. **Update the source list / JNI bridge** if the diff requires it (step 1).
4. **Bump the `packset-native` version** in `ft8_jni.cpp` `version()`
   (e.g. `0.3.0` → `0.4.0`). The version string is what Settings displays, so
   it's also your on-device proof the new build shipped.
5. **Update docs:** the pin in [FT8_NATIVE.md](FT8_NATIVE.md),
   `.claude/CLAUDE.md`, and the "Current state" section of this file.
6. **Clean native rebuild** — FetchContent and the NDK cache stale sources
   aggressively:

   ```powershell
   .\gradlew.bat clean :ft8-native:externalNativeBuildCleanDebug :app:assembleDebug
   ```

## Verification (in order, all must pass)

1. **JVM unit tests:** `.\gradlew.bat test` (ft8-native itself has none; core's
   SNR/message tests still guard the Kotlin side).
2. **Instrumented tests on device:**

   ```powershell
   .\gradlew.bat :ft8-native:connectedDebugAndroidTest
   ```

   > ⚠️ `connectedAndroidTest` **uninstalls the app and wipes its data**,
   > including the QSO logbook. On a phone with real QSOs, `adb pull` the
   > logbook database first.

   Gate specifically on:
   - `Ft8DecodeInstrumentedTest` — golden WAV decode count must not regress
     (byte-equivalent decode behavior is the milestone bar)
   - `Ft8SnrCalibrationTest` — SNR estimates stay within calibration bounds
   - `Ft8NativeLateTxTest`, `Ft8HashPersistenceTest`
3. **On-device smoke:** Settings shows
   `Decoder library: loaded v packset-native <new> (ft8_lib <new short hash>)`;
   monitor a live band and confirm decode counts look normal against a second
   receiver (or pskreporter).
4. **Field verification** on the reference rig (FT-891 + Digirig) before any
   promotion — RX decodes, TX keys, and a full QSO completes. This is the
   project's non-negotiable bar (see `.claude/CLAUDE.md`).

## Rollback

The pin is one commit's worth of change — `git revert` it, do the clean native
rebuild above, and confirm the Settings version string shows the old hash.
No data or schema is involved; ft8_lib is compiled in statically.
