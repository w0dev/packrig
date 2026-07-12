# Packset Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the project from FT8VC to Packset end-to-end: packages, application IDs, native lib, storage identifiers, CI, docs, and the GitHub repo.

**Architecture:** A staged mechanical rename on a feature branch off `unstable`. Stage 1 moves Java packages + gradle namespaces + JNI symbols in one atomic commit (they break individually). Later stages rename product identity strings, storage names, CI plumbing, and docs — each independently buildable and testable. Existing test suites are the safety harness; a repo-wide grep is the completion gate.

**Tech Stack:** Kotlin/Gradle (AGP 9.2.1), CMake/JNI, GitHub Actions, `gh` CLI.

## Global Constraints

- Name: **Packset**; tagline: **"A pocket-sized FT8 application for Android"**.
- Application IDs: `net.packset` (stable), `net.packset.unstable` (unstable), `.debug` suffix unchanged.
- FT8-the-protocol naming stays: `Ft8Native`, `Ft8NavHost.kt` filename, `ft8_lib`, FT8 message code. Only `Ft8vc*` identifiers become `Packset*`.
- GitHub secret names (`secrets.FT8VC_*`) are NOT renamed — workflows map them to new `PACKSET_*` env names.
- Do not touch: `.claude/worktrees/`, `docs/planning/`, `docs/superpowers/` historical artifacts, `.git/`, any `build/` dir.
- No behavior changes. RX/TX/CAT/QSO logic byte-identical.

---

### Task 1: Branch setup

**Files:** none (git only)

- [ ] **Step 1: Create the feature branch from unstable**

```bash
cd /Users/bsmirks/git/ft8vc
git checkout unstable && git pull --ff-only && git checkout -b rename-packset
```

Expected: `Switched to a new branch 'rename-packset'`, working tree clean.

---

### Task 2: Package, namespace, and JNI rename (atomic)

**Files:**
- Move: every `*/src/*/java/net/ft8vc/` directory → `*/src/*/java/net/packset/` (18 dirs across app, core, audio, rig, data, ft8-native, incl. test/androidTest/testFixtures)
- Modify: all `*.kt` under those trees (package/import statements), 6 module `build.gradle.kts` (namespace), `settings.gradle.kts:34` (rootProject.name), `app/proguard-rules.pro:3`, `ft8-native/src/main/cpp/ft8_jni.cpp` (4 JNI symbols), `ft8-native/src/main/cpp/CMakeLists.txt` (lib name), `ft8-native/src/main/java/.../Ft8Native.kt:16` (loadLibrary), `.github/workflows/golden-trace.yml:29` (test FQN)
- Rename: `data/.../db/Ft8vcDatabase.kt` → `PacksetDatabase.kt`; identifiers `Ft8vcDatabase` → `PacksetDatabase`, `Ft8vcApp` → `PacksetApp`, `Ft8vcTheme` → `PacksetTheme`

**Interfaces:**
- Produces: packages `net.packset.{app,core,audio,rig,data,ft8native}`; native lib `libpackset.so`; class names `PacksetDatabase`, `PacksetApp`, `PacksetTheme`. All later tasks assume these.

- [ ] **Step 1: Move package directories with git mv**

```bash
cd /Users/bsmirks/git/ft8vc
find app core audio rig data ft8-native -type d -path "*/java/net/ft8vc" -not -path "*/build/*" | while read -r d; do
  git mv "$d" "$(dirname "$d")/packset"
done
git status --short | head
```

Expected: 18 directories moved, `git status` shows renames only.

- [ ] **Step 2: Rewrite dotted, underscored, and CamelCase references**

```bash
cd /Users/bsmirks/git/ft8vc
# Dotted package refs in source, gradle, proguard, and workflow files
grep -rl "net\.ft8vc" app core audio rig data ft8-native settings.gradle.kts .github \
  --include="*.kt" --include="*.kts" --include="*.pro" --include="*.yml" --include="*.xml" \
  | grep -v "/build/" | xargs perl -pi -e 's/net\.ft8vc/net.packset/g'
# JNI exported symbol names (underscore form)
perl -pi -e 's/Java_net_ft8vc_/Java_net_packset_/g' ft8-native/src/main/cpp/ft8_jni.cpp
# Native library name in CMake + loadLibrary + comments
perl -pi -e 's/\bft8vc\b/packset/g' ft8-native/src/main/cpp/CMakeLists.txt
perl -pi -e 's/loadLibrary\("ft8vc"\)/loadLibrary("packset")/' ft8-native/src/main/java/net/packset/ft8native/Ft8Native.kt
# Brand-named Kotlin identifiers (Ft8vcDatabase, Ft8vcApp, Ft8vcTheme + their references/comments)
grep -rl "Ft8vc" app core audio rig data ft8-native --include="*.kt" | grep -v "/build/" \
  | xargs perl -pi -e 's/Ft8vc/Packset/g'
git mv data/src/main/java/net/packset/data/db/Ft8vcDatabase.kt data/src/main/java/net/packset/data/db/PacksetDatabase.kt
# rootProject.name
perl -pi -e 's/rootProject\.name = "ft8vc"/rootProject.name = "packset"/' settings.gradle.kts
```

Expected: no errors; `grep -rn "net\.ft8vc\|Java_net_ft8vc\|Ft8vc" app core audio rig data ft8-native --include="*.kt" --include="*.kts" --include="*.cpp" | grep -v "/build/"` returns nothing.

- [ ] **Step 3: Build and run all unit tests**

```bash
./gradlew assembleDebug test --console=plain -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`. Any `UnsatisfiedLinkError` in ft8-native tests means a JNI symbol was missed.

- [ ] **Step 4: Compile androidTest sources (no device needed)**

```bash
./gradlew compileDebugAndroidTestSources --console=plain -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor: rename packages net.ft8vc -> net.packset

Moves all six modules' source trees, gradle namespaces, JNI exported
symbols, the native lib (libft8vc.so -> libpackset.so), proguard keep
rules, rootProject.name, and Ft8vc* identifiers -> Packset*.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Product identity — applicationId, labels, storage, ADIF

**Files:**
- Modify: `app/build.gradle.kts:17` (applicationId), `app/src/main/res/values/strings.xml:2` (app_name), `core/src/main/java/net/packset/core/AppInfo.kt:15` (APP_NAME), `data/src/main/java/net/packset/data/adif/AdifExportContext.kt:5` (programId), `data/src/main/java/net/packset/data/db/PacksetDatabase.kt:38` (db filename), `app/src/main/java/net/packset/app/settings/SettingsRepository.kt:24` (datastore name), `app/src/main/java/net/packset/app/DocumentsAdifMirror.kt` (folder + mirror filename), `app/src/main/java/net/packset/app/AdifAutoBackup.kt` (snackbar copy), `app/src/main/java/net/packset/app/ui/log/LogScreen.kt:221` (empty-state copy)
- Test: `app/src/test/java/net/packset/app/AdifBackupSnackbarTextTest.kt`, plus any test asserting `"FT8VC"` (found via grep)

**Interfaces:**
- Produces: applicationId `net.packset`; ADIF `PROGRAMID` = `Packset`; durable folder `Documents/packset/`; DB file `packset_logbook.db`; DataStore `packset_settings`.

- [ ] **Step 1: Update copy-assertion tests first**

In `AdifBackupSnackbarTextTest.kt` change the expected string:

```kotlin
"ADIF backup written to Documents/packset",
```

Then find every other test asserting old branding and update it the same way:

```bash
grep -rn '"FT8VC\|Documents/ft8vc\|ft8vc_logbook\|ft8vc_settings\|ft8vc-logbook' \
  app/src/test core/src/test data/src/test audio/src/test rig/src/test --include="*.kt" | grep -v "/build/"
```

Expected after edits: the grep above still lists the files (they now assert `Packset`/`packset` values — re-run with new terms to confirm), and `./gradlew :app:testDebugUnitTest --tests "*AdifBackupSnackbarText*"` FAILS (copy not yet changed in prod code).

- [ ] **Step 2: Apply identity changes in production code**

```
app/build.gradle.kts:        applicationId = "net.packset"
strings.xml:                 <string name="app_name">Packset</string>
AppInfo.kt:                  const val APP_NAME = "Packset"
AdifExportContext.kt:        val programId: String = "Packset",
PacksetDatabase.kt:          "packset_logbook.db",
SettingsRepository.kt:       name = "packset_settings",
DocumentsAdifMirror.kt:      private const val RELATIVE_PATH = "Documents/packset/"
                             (and mirror filename ft8vc-logbook.adi -> packset-logbook.adi, incl. KDoc)
AdifAutoBackup.kt:           "ADIF backup written to Documents/packset" (+ KDoc)
LogScreen.kt:221:            "Auto-exports after every QSO to Documents/packset"
```

- [ ] **Step 3: Run the full unit test suite**

```bash
./gradlew test --console=plain -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`, previously failing copy test now passes.

- [ ] **Step 4: Verify no old identity literals remain in src**

```bash
grep -rn '"FT8VC\|Documents/ft8vc\|ft8vc_logbook\|ft8vc_settings\|ft8vc-logbook\|"net\.ft8vc' \
  app/src core/src data/src audio/src rig/src ft8-native/src --include="*.kt" --include="*.xml" | grep -v "/build/"
```

Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: adopt Packset product identity

applicationId net.packset, app label, ADIF PROGRAMID, durable backup
folder Documents/packset, DB/DataStore filenames. Fresh-install safe:
the new applicationId means no existing data to migrate.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: CI and signing env plumbing

**Files:**
- Modify: `app/build.gradle.kts` (5 `System.getenv` names), `.github/workflows/unstable.yml`, `.github/workflows/release.yml`, `.github/workflows/build.yml`

**Interfaces:**
- Consumes: GitHub secrets keep their existing `FT8VC_*` names.
- Produces: env vars `PACKSET_KEYSTORE`, `PACKSET_KEYSTORE_PASSWORD`, `PACKSET_KEY_ALIAS`, `PACKSET_KEY_PASSWORD`, `PACKSET_UNSTABLE`, `PACKSET_VERSION_CODE`, `PACKSET_VERSION_NAME_SUFFIX`; artifacts `packset-debug-apk`, `packset-unstable-<run>`.

- [ ] **Step 1: Rename gradle-side env reads**

In `app/build.gradle.kts` replace every `System.getenv("FT8VC_X")` with `System.getenv("PACKSET_X")` (KEYSTORE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD, VERSION_CODE, UNSTABLE, VERSION_NAME_SUFFIX — 7 reads incl. the `resolveReleaseKeystore()` helper and its KDoc).

- [ ] **Step 2: Rename workflow env names, keep secret names**

In `unstable.yml` and `release.yml`, change only the env-var key side, e.g.:

```yaml
env:
  PACKSET_KEYSTORE_BASE64: ${{ secrets.FT8VC_KEYSTORE_BASE64 }}
  PACKSET_KEYSTORE: ${{ steps.keystore.outputs.path }}
  PACKSET_KEYSTORE_PASSWORD: ${{ secrets.FT8VC_KEYSTORE_PASSWORD }}
  PACKSET_KEY_ALIAS: ${{ secrets.FT8VC_KEY_ALIAS }}
  PACKSET_KEY_PASSWORD: ${{ secrets.FT8VC_KEY_PASSWORD }}
```

Update the matching shell references (`$PACKSET_KEYSTORE_BASE64`, `PACKSET_VERSION_NAME_SUFFIX=... >> $GITHUB_ENV`, `PACKSET_UNSTABLE: true`, `PACKSET_VERSION_CODE: ${{ github.run_number }}`) and artifact names: `ft8vc-debug-apk` → `packset-debug-apk`, `ft8vc-unstable-${{ github.run_number }}` → `packset-unstable-${{ github.run_number }}`. Keep every `secrets.FT8VC_*` reference and the error message naming the secret `FT8VC_KEYSTORE_BASE64` as-is.

- [ ] **Step 3: Sanity-check the release build path locally**

```bash
./gradlew assembleRelease --console=plain -q 2>&1 | tail -5
ls app/build/outputs/apk/release/
```

Expected: `BUILD SUCCESSFUL`, unsigned `app-release.apk` (no keystore env set locally).

- [ ] **Step 4: Verify workflow grep**

```bash
grep -n "FT8VC" .github/workflows/*.yml | grep -v "secrets.FT8VC" | grep -v "FT8VC_KEYSTORE_BASE64 and related"
```

Expected: no output (only secret references and the secret-naming error message keep the old name).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "ci: rename env plumbing FT8VC_* -> PACKSET_*; keep GitHub secret names

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Docs and branding sweep

**Files:**
- Modify: `README.md`, `docs/*.md` (11 files), `docs/manual/**`, `.claude/CLAUDE.md`
- Do NOT touch: `docs/planning/`, `docs/superpowers/` (historical)

- [ ] **Step 1: README rebrand**

Title + tagline become:

```markdown
# Packset

> A pocket-sized FT8 application for Android
```

Then replace throughout README: `FT8VC` → `Packset`, `w0dev/ft8vc` → `w0dev/packset`, `net.ft8vc.unstable` → `net.packset.unstable`, `Documents/ft8vc` → `Documents/packset`, tree-diagram root `ft8vc/` → `packset/`. Keep credits ("Packset stands on the work of others") and the ft8_lib attribution intact.

- [ ] **Step 2: docs/ and manual sweep**

```bash
grep -rl "FT8VC\|ft8vc" README.md docs/*.md docs/manual .claude/CLAUDE.md \
  | xargs perl -pi -e 's/net\.ft8vc/net.packset/g; s/Documents\/ft8vc/Documents\/packset/g; s/w0dev\/ft8vc/w0dev\/packset/g; s/FT8VC_/PACKSET_/g; s/FT8VC/Packset/g; s/libft8vc/libpackset/g'
```

Then hand-review `git diff` for casualties: secret-name documentation must keep `FT8VC_*` where it refers to GitHub secret names (RELEASE.md), and any `ft8vc` path examples that refer to the *local checkout directory* may keep or change freely.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "docs: rebrand FT8VC -> Packset with tagline

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Full verification gate

**Files:** none (verification only)

- [ ] **Step 1: Repo-wide grep gate**

```bash
grep -rin "ft8vc" . \
  --exclude-dir=.git --exclude-dir=build --exclude-dir=worktrees \
  --exclude-dir=planning --exclude-dir=superpowers --exclude-dir=.gradle \
  | grep -v "secrets.FT8VC" | grep -v "FT8VC_KEYSTORE_BASE64"
```

Expected: no output. Any hit is either a miss (fix it) or a deliberate keeper (secret names) — justify each.

- [ ] **Step 2: Clean full build + all tests**

```bash
./gradlew clean assembleDebug assembleRelease test --console=plain 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`. Note: `DecodeController` level-meter test is a known pre-existing flake — re-run once before blaming the rename.

- [ ] **Step 3: APK identity check**

```bash
unzip -p app/build/outputs/apk/debug/app-debug.apk AndroidManifest.xml | strings | grep -m1 packset || \
  "$ANDROID_HOME"/build-tools/*/aapt dump badging app/build/outputs/apk/debug/app-debug.apk | head -2
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libpackset
```

Expected: package `net.packset.debug`, `lib/*/libpackset.so` present for all three ABIs.

---

### Task 7: Land, push, and rename the repo

**Files:** none (git/GitHub only)

- [ ] **Step 1: Merge to unstable and push**

```bash
git checkout unstable && git merge --no-ff rename-packset -m "merge: Packset rename (packages, identity, CI, docs)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>" && git push origin unstable
```

Expected: fast merge, push succeeds, unstable.yml CI run starts.

- [ ] **Step 2: Rename the GitHub repo**

```bash
gh repo rename packset --repo w0dev/ft8vc --yes && git remote -v
```

Expected: repo is `w0dev/packset`; git remote URL auto-updated by gh (if not: `git remote set-url origin git@github.com:w0dev/packset.git`). GitHub serves redirects from the old name.

- [ ] **Step 3: Report manual follow-ups**

Not automatable, report to owner:
1. Optionally rename GitHub secrets `FT8VC_*` → `PACKSET_*` (then update `secrets.*` refs in workflows).
2. Local shell profiles exporting `FT8VC_KEYSTORE*` need the `PACKSET_*` names for local release builds.
3. Field phone: new `net.packset.unstable` installs alongside old app; uninstall old after confirming `Documents/ft8vc` backup exists (old backups are not auto-migrated; ADIF import covers restore).
4. Consider registering `packset.app` before any public announcement.
