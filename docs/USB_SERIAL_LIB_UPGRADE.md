# Upgrading usb-serial-for-android

Runbook for bumping the pinned
[`mik3y/usb-serial-for-android`](https://github.com/mik3y/usb-serial-for-android)
release that powers the CAT serial transport (Digirig CP210x, rig built-in USB,
generic CAT cables). Companion to [FT8_LIB_UPGRADE.md](FT8_LIB_UPGRADE.md) —
same philosophy: the pin must never become a "set once, forgotten forever"
fossil. Follow this top to bottom when upstream moves.

## Current state

- **Pinned version:** `3.9.0` (upstream tag `v3.9.0`, latest as of 2026-07-04)
- Introduced by the **multi-rig milestone** (`multi-rig` branch); the pin and
  its guard rails land in phase 1 of
  [the multi-rig spec](superpowers/specs/2026-07-04-multi-rig-support-design.md).
- Distributed **only via JitPack** (`com.github.mik3y:usb-serial-for-android`).
  JitPack builds artifacts from the GitHub tag on demand — there is no
  maintainer-signed Maven Central artifact, which is why the pin carries extra
  guard rails (scoped repo + checksum verification, below).
- Upstream releases are infrequent (roughly yearly) but meaningful: new
  bridge-chip PIDs and driver quirk fixes are exactly what a multi-rig app
  wants, so check for movement occasionally rather than never.

### Check whether upstream has moved

```bash
git ls-remote --tags https://github.com/mik3y/usb-serial-for-android.git | tail -5
```

If there is a tag newer than the pin, review before touching anything:

```text
https://github.com/mik3y/usb-serial-for-android/compare/v3.9.0...v<new>
```

and read the release notes:
`https://github.com/mik3y/usb-serial-for-android/releases`.

## Where the pin lives (keep these in sync)

| Location | What it holds |
|----------|---------------|
| `gradle/libs.versions.toml` — `usbSerial = "..."` | **Source of truth.** Exact release version; never a dynamic range |
| `settings.gradle.kts` — `exclusiveContent` block | Scopes the JitPack repo to `com.github.mik3y` only, so JitPack can never resolve any other dependency. Should not need changes on upgrade — verify it is still intact |
| `gradle/verification-metadata.xml` | Checksums for the artifact. **Must be regenerated on every bump** or CI fails (that is the point) |
| [RIG.md](RIG.md) | Documents the transport layer and pinned version |
| `.claude/CLAUDE.md` — Key Dependencies | Documents the pinned version |

Historical specs/plans under `docs/superpowers/` mention the version; those are
frozen point-in-time artifacts — do **not** update them.

## Coupling surface (what can break)

The library is consumed **only** through the `SerialTransport` wrapper in the
`rig` module — protocol drivers (`YaesuCat`, `KenwoodCat`, `IcomCiV`) never
import it. On upgrade, the coupling to re-check:

1. **Wrapper API usage.** The `SerialTransport` implementation touches
   `UsbSerialProber`, `UsbSerialDriver`, `UsbSerialPort`
   (open/read/write/close, `setParameters`, `setRTS`, `setDTR`). Upstream has
   historically kept these stable, but scan the diff for signature or
   behavior changes (especially read-timeout semantics — our CAT reply
   deadline logic depends on them).
2. **Multi-port drivers.** CP2105 (Yaesu built-in USB) exposes two ports and
   CAT must ride the **Enhanced** port. Confirm upstream's port ordering for
   `Cp21xxSerialDriver` hasn't changed.
3. **Custom prober entries.** If we registered any VID/PIDs upstream didn't
   know (check the `SerialTransport` implementation for a custom
   `ProbeTable`), see whether upstream now ships them natively and drop our
   duplicates.
4. **RTS behavior on open.** Digirig PTT is the RTS line. Verify the driver
   still leaves RTS de-asserted on open/close — a driver that toggles RTS
   during init would key the transmitter on connect. This is the single most
   safety-critical check in this runbook.

## Upgrade procedure

1. **Review the upstream diff and release notes** (links above), with the four
   coupling points in mind.
2. **Bump the pin:** `usbSerial` in `gradle/libs.versions.toml`.
3. **Regenerate dependency verification metadata:**

   ```powershell
   .\gradlew.bat --write-verification-metadata sha256 :rig:dependencies
   ```

   Diff `gradle/verification-metadata.xml` — only the usb-serial entries
   should change.
4. **Update docs:** the pin in [RIG.md](RIG.md), `.claude/CLAUDE.md`, and the
   "Current state" section of this file.
5. **Build clean:** `.\gradlew.bat clean :app:assembleDebug`.

## Verification (in order, all must pass)

1. **Unit tests:** `.\gradlew.bat :rig:test` — protocol drivers against
   `FakeSerialTransport` (these don't exercise the library, but catch wrapper
   contract drift) plus the transport wrapper's own tests.
2. **Bench check, RX side:** attach the Digirig, confirm Settings shows the
   device, CAT status reaches "Rig in sync", and frequency readback tracks the
   dial.
3. **PTT safety check:** watch the rig's TX indicator while connecting USB,
   granting permission, and starting/stopping monitor — the transmitter must
   **never** key during any of it (see coupling point 4).
4. **Field verification** on the reference rigs before any promotion:
   - FT-891 + Digirig — RX decodes, TX keys, full QSO completes
     (non-negotiable bar, see `.claude/CLAUDE.md`).
   - FTX-1 over built-in USB — CAT sync + TX via the direct-USB path.

## Rollback

The pin is one commit's worth of change — `git revert` it (version catalog +
verification metadata + docs), rebuild, and re-run the bench check. The library
is a compile-time dependency; no data or schema is involved.
