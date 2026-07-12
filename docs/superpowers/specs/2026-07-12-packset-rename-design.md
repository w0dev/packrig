# Packset Rename — Design

**Date:** 2026-07-12
**Status:** Approved by owner (name, tagline, and full-migration scope confirmed in session)

## Decision

The project is renamed from **FT8VC** to **Packset**.

- **Name:** Packset
- **Tagline:** "A pocket-sized FT8 application for Android"
- **Application IDs:** `net.packset` (stable), `net.packset.unstable` (unstable)

### Rationale

- "FT8VC" carried the "vibe coded" origin in its name; the stable release needs a
  name that reads as finished, trustworthy software.
- A *packset* is the Commonwealth-era term for a self-contained backpack radio —
  exactly what this app makes of a phone, a rig, and a cable. The "-set" suffix
  carries radio heritage (handset, crystal set, "the set").
- The name is mode-neutral: FT4 or other modes can arrive later without a rename.
  The tagline carries "FT8" for today's discoverability.
- Collision-checked 2026-07-12: no app, software product, or indexed trademark
  named Packset. Rejected alternatives: QRV (existing iOS ham multitool),
  PocketPack/PocketPacket (existing APRS app + "packet radio" misread),
  MiniPack (crowded namespace, no radio signal), DigiSet/DigiPack (too close to
  the Digirig hardware brand). Known non-issue: DHL Germany's "Packset" shipping
  boxes (different industry and trademark class).

## Scope

Full migration — the app has no public downstream users, so there is no
compatibility constraint on identifiers.

### In scope

1. **Application identity**
   - `applicationId`: `net.ft8vc` → `net.packset`; unstable variant
     `net.ft8vc.unstable` → `net.packset.unstable`.
   - App display name: "FT8VC" → "Packset" (unstable label follows existing
     variant naming convention).
2. **Source packages and namespaces**
   - All Kotlin/Java packages `net.ft8vc.*` → `net.packset.*` across all six
     modules (`app`, `core`, `audio`, `rig`, `data`, `ft8-native`), including
     `src/test` and `src/androidTest`, with matching directory moves.
   - Gradle `namespace` values in every module's `build.gradle.kts`.
   - JNI exported symbol names in `ft8-native/src/main/cpp/ft8_jni.cpp`
     (`Java_net_ft8vc_ft8native_...` → `Java_net_packset_ft8native_...`) must be
     renamed in lockstep with the Kotlin package, or native decode/encode breaks
     at runtime with `UnsatisfiedLinkError`.
   - ProGuard rules referencing `net.ft8vc`.
   - `rootProject.name` in `settings.gradle.kts` → `packset`.
3. **Renamed types** — classes named after the old brand:
   - `Ft8vcDatabase` → `PacksetDatabase`; `Ft8vcApp` (nav host composable) and
     any other `Ft8vc*` identifiers follow the same pattern. Types named after
     the *protocol* (`Ft8Native`, `ft8_lib` references, FT8 message code) keep
     their names — FT8 the mode is not being renamed.
4. **Storage and export identifiers** (safe to rename: the new applicationId
   means every install is fresh)
   - Room database file `ft8vc_logbook.db` → `packset_logbook.db`.
   - DataStore file `ft8vc_settings` → `packset_settings`.
   - Durable export/backup folder `Documents/ft8vc` → `Documents/packset`.
   - ADIF `PROGRAMID` → `Packset`.
   - FileProvider authority and any `net.ft8vc` string literals.
5. **CI workflows** (`.github/workflows/*.yml`)
   - Artifact and APK names (`ft8vc-*` → `packset-*`).
   - Env var names `FT8VC_*` → `PACKSET_*` on both the workflow side and the
     Gradle side (`FT8VC_UNSTABLE`, `FT8VC_VERSION_CODE`,
     `FT8VC_VERSION_NAME_SUFFIX`, `FT8VC_KEYSTORE`, credentials vars).
   - GitHub **secret names** (`secrets.FT8VC_*`) stay unchanged — they live in
     repo settings and their values are not available to this migration. The
     workflow maps old secret names to new env names. Renaming the secrets
     themselves is an optional manual follow-up.
6. **Docs and branding**
   - `README.md`, `docs/*.md`, `docs/manual/`, `.claude/CLAUDE.md`: rename
     brand references, adopt the tagline.
   - Historical artifacts (`docs/superpowers/` specs/plans, `docs/planning/`)
     are records of past work and are **not** rewritten.
7. **Repo rename**
   - GitHub repo `w0dev/ft8vc` → `w0dev/packset` via `gh repo rename` (GitHub
     redirects old URLs). Local checkout directory name is left alone.

### Out of scope / unchanged

- Signing key and keystore (same key, new IDs — side-by-side installs fine).
- Any RX/TX/CAT/QSO behavior; this is a pure rename. Core Value applies: the
  rig must still key and decode on the FT-891 + Digirig reference setup.
- `.claude/worktrees/*` checkouts (other branches; renamed when they rebase).
- ft8_lib, `Ft8Native`, and all FT8-protocol naming.
- License gating, receive-only default.

## Verification

- `assembleDebug` and `assembleRelease` for both stable and unstable variants
  build clean.
- All module unit tests pass; androidTest sources compile.
- `grep -ri ft8vc` over the tree (excluding worktrees, build dirs, and
  historical planning artifacts) returns nothing.
- APK-level checks: applicationId, app label, and native lib load verified from
  the built artifact (aapt dump / unit-level JNI load test where available).
- Owner field check on the FT-891 before any stable promotion, per project
  convention. The renamed unstable APK installs alongside the old one; the old
  `net.ft8vc.unstable` install can be removed manually after the logbook is
  confirmed present in `Documents/ft8vc` (old backups are not migrated — the
  ADIF import path covers restore if wanted).

## Risks

- **JNI symbol mismatch** — highest-severity failure mode; caught by any decode
  unit/instrumented test or first manual decode, not silently.
- **Missed identifier** — a stale `net.ft8vc` literal (e.g. FileProvider
  authority) breaks a specific feature; mitigated by the exhaustive grep gate.
- **CI secret plumbing** — release workflow must keep reading the existing
  `secrets.FT8VC_*` names until the owner renames them in GitHub settings.
- **Owner's local env** — local release builds read `PACKSET_KEYSTORE` etc.
  after this change; the owner's shell profile needs the rename mirrored.
