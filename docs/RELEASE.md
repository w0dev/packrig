# Release process

## Branch strategy

| Branch | Purpose | CI |
|--------|---------|-----|
| **`unstable`** | Day-to-day development and field testing | Unit tests + signed **unstable** release APK (artifact only; no debug APK) |
| **`main`** | Stable, releasable code only | Unit tests, debug APK on push; **no** automatic release APK |
| **Tag `v*`** on `main` | Stable public release | Unit tests, then a **draft** GitHub Release with signed `net.packset` APK |

Merge to **`main` does not publish a release**. Stable APKs only ship when you push a version tag (e.g. `v1.0.0`) to a commit on `main`.

### Typical workflow

1. Create and push `unstable` once: `git checkout -b unstable && git push -u origin unstable`
2. Do feature work on `unstable` (or topic branches merged into `unstable`).
3. After each push to `unstable`, download the **Unstable APK** artifact from the Actions run (package id `net.packset.unstable`, version like `1.0.0-unstable+abc1234`).
4. When satisfied, merge `unstable` → `main`.
5. Bump `versionCode` / `versionName` in `app/build.gradle.kts` (and `AppInfo.VERSION_NAME` if you surface it in UI), commit on `main`, tag `v1.0.0`, push the tag → **Stable Release** workflow creates the GitHub Release.

Unstable and stable APKs can be installed side by side (`applicationIdSuffix` `.unstable` vs production `net.packset`).

## Version

- `versionName` / `AppInfo.VERSION_NAME`: semantic version (e.g. `1.0.0`)
- `versionCode` in `app/build.gradle.kts`: integer bump per **stable** release (e.g. `100`)
- Unstable CI sets `PACKSET_VERSION_CODE` to the GitHub run number so each unstable build can upgrade over the last

## Local signed release

1. Create a release keystore (once):

```bash
keytool -genkey -v -keystore ft8vc-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias ft8vc
```

2. Set environment variables and build:

```powershell
$env:PACKSET_KEYSTORE = "C:\path\to\ft8vc-release.jks"
$env:PACKSET_KEYSTORE_PASSWORD = "..."
$env:PACKSET_KEY_ALIAS = "ft8vc"
$env:PACKSET_KEY_PASSWORD = "..."
.\gradlew.bat assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

### Local unstable build (matches CI)

```powershell
$env:PACKSET_UNSTABLE = "true"
$env:PACKSET_VERSION_NAME_SUFFIX = "-unstable+local"
$env:PACKSET_VERSION_CODE = "200"
# ... same keystore vars as above ...
.\gradlew.bat assembleRelease
```

## GitHub Release (stable, CI)

Push a tag `v*` (e.g. `v1.0.0`) on a commit that is on **`main`**. The [Stable Release workflow](../.github/workflows/release.yml) runs the unit tests, builds a signed production APK, and attaches it to a **draft** GitHub Release. Tags not on `main` are rejected, and a unit-test failure aborts the release.

The draft is not public until you open it (Releases → the draft entry) and press **Publish release** — the last human gate after the field smoke checklist.

Repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|--------|-------|
| `FT8VC_KEYSTORE_BASE64` | Base64-encoded `.jks` file |
| `FT8VC_KEYSTORE_PASSWORD` | Keystore password |
| `FT8VC_KEY_ALIAS` | Key alias |
| `FT8VC_KEY_PASSWORD` | Key password |

**Stable** releases require all four secrets. **Unstable** builds use them when present (signed APK, same key as stable); if `FT8VC_KEYSTORE_BASE64` is missing, CI still produces an **unsigned** release APK for sideload testing.

> The secrets keep their historical `FT8VC_` prefix from before the Packset
> rename — the workflows map them to `PACKSET_*` env vars. Renaming the
> secrets is optional; if you do, update the `secrets.*` references in both
> workflow files.

## Unstable APK (CI)

Push to **`unstable`**. The [Unstable APK workflow](../.github/workflows/unstable.yml) builds a signed release APK and uploads it as a workflow artifact (not a GitHub Release). Open the Actions run → **Artifacts** → `packset-unstable-<run>`.

You can also trigger it manually via **Actions → Unstable APK → Run workflow**.

## Field smoke checklist (pre-stable-release)

1. **Operate** toggle → decodes appear within one 15 s slot
2. CAT read → band/mode shown in status bar
3. **Start CQ** or tap CQ → full QSO → log entry appears on **Log** tab
4. **Export ADIF** share intent opens in ADIF Master or similar
5. Callsign/grid persist after force-stop and relaunch
6. Release APK installs and runs on field phone + Digirig

Run this checklist on **unstable** builds before merging to `main` and tagging.

## Known limitations

- CAT protocol is Yaesu new-CAT only (VFO-A); unlisted rigs use the generic
  presets (CAT-less rigs are RX + RTS-keyed TX with a manual dial frequency)
- No split-frequency operation
- No contest exchange support

The current-limitations list in the [root README](../README.md#current-limitations)
is the authoritative copy — keep the two in sync.
